"""Local GPU jobs; original survey and review databases are never edited here."""
import fcntl
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import selectors
import subprocess
import threading
import time

import processing
import store
from transcript_selection import extraction_text

ROOT = Path(__file__).resolve().parent
IMPLEMENTATION = ('inference_worker.py', 'extraction_prompt.txt', 'pipeline.py',
                  'store.py', 'processing.py', 'verify_export.py', 'extraction.py', 'ollama_extract.py',
                  'answer_context.py', 'transcript_selection.py', 'transcript_review.py')


def implementation_identity():
    return {name: store.sha_file(ROOT / name) for name in IMPLEMENTATION}


def code_identity(identity):
    return {key: value for key, value in identity.items() if key != 'extraction_prompt.txt'}


def model_files(model):
    if model.get('engine') == 'ollama':
        root = Path(model['path'])
        manifest = Path(model['manifest'])
        data = json.loads(manifest.read_text())
        digests = [data['config']['digest'], *[item['digest'] for item in data['layers']]]
        import re
        if any(not re.fullmatch(r'sha256:[0-9a-f]{64}', value) for value in digests):
            raise ValueError('올바르지 않은 로컬 모델 manifest')
        if store.sha_file(manifest) != model['digest']:
            raise ValueError('등록된 모델 digest와 로컬 manifest가 다릅니다.')
        return [manifest, *[root / 'blobs' / value.replace(':', '-') for value in digests]]
    return sorted(p for p in Path(model['path']).iterdir()
                  if p.is_file() and p.suffix in ('.json', '.safetensors', '.bin', '.model', '.txt'))


def artifact_state(config):
    state = {}
    for role, model in (('asr', config['asr']['base']), ('dialect', config['asr'].get('dialect')),
                        ('extractor', config['extractor'])):
        if model:
            state[role] = {}
            for path in model_files(model):
                stat = path.stat()
                state[role][path.name] = [stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns]
    return state


def read_config(path=ROOT / 'models.json'):
    config = json.loads(Path(path).read_text())
    for key in ('python', 'ffmpeg', 'ffprobe'):
        if not Path(config[key]).is_file():
            raise ValueError(f'{key} 경로를 확인하세요: {config[key]}')
    for model in (config['asr']['base'], config['asr'].get('dialect'), config['extractor']):
        if model and not Path(model['path']).is_dir():
            raise ValueError('모델 경로를 확인하세요: ' + model['path'])
    return config


def describe_models(config):
    return {'ready': True, 'asr': config['asr']['base'],
            'dialect': config['asr'].get('dialect'), 'extractor': config['extractor'],
            'available_dialects': config.get('available_dialects', {}),
            'selected_dialect': next((key for key, value in config.get('available_dialects', {}).items()
                                      if value == config['asr'].get('dialect')), 'base')}


def model_identity(config):
    """Hash actual local weights, tokenizer, code and prompt once per controller."""
    info = describe_models(config)
    before = artifact_state(config)
    artifacts = {}
    for role in ('asr', 'dialect', 'extractor'):
        if not info[role]:
            continue
        directory = Path(info[role]['path'])
        files = model_files(info[role])
        if info[role].get('engine') != 'ollama' and not any(p.suffix in ('.safetensors', '.bin') for p in files):
            raise ValueError('모델 가중치 없음: ' + str(directory))
        artifacts[role] = {p.name: store.sha_file(p) for p in files}
    versions = subprocess.check_output([config['python'], '-c',
        'import importlib.metadata,json; print(json.dumps({n:importlib.metadata.version(n) '
        'for n in ["torch","transformers","peft","numpy"]}))'], text=True, timeout=30)
    info['versions'] = json.loads(versions)
    info['artifacts'] = artifacts
    info['implementation'] = implementation_identity()
    info['prompt_text'] = (ROOT / 'extraction_prompt.txt').read_text()
    if artifact_state(config) != before:
        raise ValueError('모델 파일이 검사 도중 변경됐습니다. 다시 실행하세요.')
    info['artifact_state'] = before
    info['config'] = config
    return info


def validate_extraction(result):
    """Keep unverifiable model output as failed-attempt evidence, never formal data."""
    extraction = result.get('extraction')
    if not isinstance(extraction, dict) or set(extraction) != {'status', 'fields', 'evidence', 'uncertainties'}:
        raise ValueError('항목 추출 JSON 구조가 올바르지 않습니다.')
    if extraction['status'] != 'DRAFT':
        raise ValueError('자동 추출은 DRAFT여야 합니다.')
    original_fields = extraction['fields']
    check_fields = dict(original_fields) if isinstance(original_fields, dict) else original_fields
    # A draft may name a gear without speaking its unit. Formal review remains stricter.
    unknown_unit = (isinstance(check_fields, dict) and check_fields.get('gear_type') in ('POT', 'GILLNET')
                    and check_fields.get('gear_unit') is None)
    if unknown_unit:
        check_fields['gear_type'] = None
    fields, missing = store.normalize_fields(check_fields)
    if unknown_unit:
        fields['gear_type'] = original_fields['gear_type']
        missing.append('gear_unit')
        missing.extend(key for key in ('deployment_date', 'haul_quantity') if fields[key] is None)
        if fields['disposition'] == 'UNCONFIRMED':
            missing.append('disposition')
        if fields['disposition'] == 'REDEPLOYED' and fields['redeploy_quantity'] is None:
            missing.append('redeploy_quantity')
    if fields['record_class'] != 'UNCLASSIFIED' or fields['deployment_date'] is not None:
        raise ValueError('자료 구분·투망일은 자동 확정할 수 없습니다.')
    if not isinstance(extraction['uncertainties'], list) or any(not isinstance(x, str) for x in extraction['uncertainties']):
        raise ValueError('불확실 항목 형식 오류')
    text = extraction_text(result)
    if not isinstance(extraction['evidence'], list):
        raise ValueError('추출 근거 형식 오류')
    evidence = {}
    for item in extraction['evidence']:
        if (not isinstance(item, dict) or set(item) != {'field', 'quote'} or
                not isinstance(item['field'], str) or not isinstance(item['quote'], str) or
                not item['quote'].strip() or item['quote'] not in text):
            raise ValueError('전사 원문과 일치하지 않는 추출 근거')
        evidence[item['field']] = item['quote']
    defaults = store.blank_fields()
    required = [key for key, value in fields.items()
                if key != 'catches' and value is not None and value != defaults[key]]
    for i, catch in enumerate(fields['catches']):
        required.extend(f'catches[{i}].{key}' for key, value in catch.items()
                        if value is not None and not (key == 'approximate' and value is False)
                        and not (key == 'outcome' and value == 'UNCONFIRMED'))
    if any(key not in evidence for key in required):
        raise ValueError('값의 전사 근거 부족: ' + ', '.join(k for k in required if k not in evidence))
    extraction['fields'] = fields
    # Missing fields are legitimate drafts; do not turn null into zero or confirm.
    result['missing_fields'] = missing
    return result


def failure_result(result):
    if not isinstance(result, dict):
        return {'raw_result': result}
    safe = dict(result)
    for key, expected in (('asr_text', str), ('dialect_text', str), ('extraction', dict)):
        if safe.get(key) is not None and not isinstance(safe[key], expected):
            safe.setdefault('invalid_fields', {})[key] = safe.pop(key)
    return safe


class Controller:
    def __init__(self, root, config_path=ROOT / 'models.json'):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.config_path = Path(config_path).resolve()
        self.lockfile = (self.root / 'processing.lock').open('a')
        try:
            fcntl.flock(self.lockfile, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            self.lockfile.close()
            raise RuntimeError('이 자료 폴더의 PC 전사 프로그램이 이미 실행 중입니다.')
        processing.recover(self.root)
        self.lock = threading.RLock()
        self.thread = None
        self.child = None
        self.stopping = False
        self.identity = None
        self.config = None
        self.startup_code = code_identity(implementation_identity())

    def status(self):
        try:
            models = describe_models(read_config(self.config_path))
        except Exception as e:
            models = {'ready': False, 'error': str(e)}
        return {'running': bool(self.thread and self.thread.is_alive()),
                'counts': processing.counts(self.root), 'models': models,
                'runs': processing.list_runs(self.root)}

    def _prepare(self, dialect=None):
        """Called under self.lock for both audio and reviewed-transcript requests."""
        if self.stopping:
            raise RuntimeError('전사 프로그램을 종료 중입니다.')
        config = read_config(self.config_path)
        current_impl = implementation_identity()
        if code_identity(current_impl) != self.startup_code:
            raise RuntimeError('실행 코드가 변경됐습니다. PC 서버를 재시작하세요.')
        if dialect is not None:
            if dialect != 'base' and dialect not in config.get('available_dialects', {}):
                raise ValueError('등록되지 않은 방언 모델입니다.')
            config['asr']['dialect'] = None if dialect == 'base' else config['available_dialects'][dialect]
            if config['asr']['dialect'] and not Path(config['asr']['dialect']['path']).is_dir():
                raise ValueError('방언 모델 파일을 찾을 수 없습니다.')
        current_artifacts = artifact_state(config)
        if (self.config != config or self.identity is None or
                self.identity.get('implementation', current_impl) != current_impl or
                self.identity.get('artifact_state', current_artifacts) != current_artifacts):
            if self.thread and self.thread.is_alive():
                raise ValueError('진행 중 작업이 끝난 뒤 모델 설정을 변경하세요.')
            self.identity = model_identity(config)
            self.config = config

    def _start_pending(self):
        if not self.thread or not self.thread.is_alive():
            self.thread = threading.Thread(target=self._run, daemon=True)
            self.thread.start()

    def enqueue(self, source, work_id=None, dialect=None):
        with self.lock:
            self._prepare(dialect)
            result = processing.enqueue(self.root, source, work_id, model_info=self.identity)
            self._start_pending()
            return result

    def enqueue_review(self, source, work_id, parent_run_id, head, evidence_sha,
                       selection, text, reviewer, reason):
        with self.lock:
            self._prepare()
            identity = dict(self.identity, execution_mode='reviewed-transcript-extraction')
            result = processing.enqueue_review(self.root, source, work_id, parent_run_id,
                head, evidence_sha, selection, text, reviewer, reason, model_info=identity)
            self._start_pending()
            return result

    def _launch(self, model_info):
        if ('implementation' in model_info and
                code_identity(model_info['implementation']) != code_identity(implementation_identity())):
            raise RuntimeError('등록 이후 실행 코드가 변경됐습니다. 서버 재시작 후 다시 시도하세요.')
        if ('artifact_state' in model_info and
                model_info['artifact_state'] != artifact_state(model_info['config'])):
            raise RuntimeError('등록 이후 모델 파일이 변경됐습니다.')
        # Snapshot exact settings so a changed models.json cannot relabel a running job.
        config = self.root / ('worker-config-' + store.fingerprint(model_info) + '.json')
        prompt = config.with_suffix('.prompt.txt')
        if not prompt.exists():
            prompt.write_text(model_info['prompt_text'])
        if prompt.read_text() != model_info['prompt_text']:
            raise ValueError('고정된 추출 지시문이 변경됐습니다.')
        expected_config = model_info['config'] | {'extraction_prompt': str(prompt)}
        if not config.exists():
            config.write_text(store.canonical(expected_config))
        if json.loads(config.read_text()) != expected_config:
            raise ValueError('고정된 모델 실행 설정이 변경됐습니다.')
        env = dict(os.environ, HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', TOKENIZERS_PARALLELISM='false')
        env['PATH'] = str(Path(model_info['config']['ffmpeg']).parent) + os.pathsep + env.get('PATH', '')
        log = (self.root / 'processing-worker.log').open('ab')
        try:
            self.child = subprocess.Popen([model_info['config']['python'], '-u',
                str(ROOT / 'inference_worker.py'), '--config', str(config)], stdin=subprocess.PIPE,
                stdout=subprocess.PIPE, stderr=log, env=env, text=True, bufsize=1)
        finally:
            log.close()

    def _stop_child(self):
        child = self.child
        self.child = None
        if child:
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()
            child.stdin.close()
            child.stdout.close()

    def _run(self):
        try:
            while not self.stopping:
                with self.lock:
                    jobs = processing.pending(self.root)
                    if not jobs:
                        return
                    job = processing.start(self.root, jobs[0]['id'])
                if job is None:
                    continue
                result = None
                try:
                    review = job['input'].get('transcript_review')
                    if review is not None and not processing.review_is_current(self.root, job):
                        raise ValueError('전사 검토 이후 원자료가 갱신되었습니다. 최신 작업을 다시 확인하세요.')
                    audio = self.root / 'audio' / (job['audio_sha256'] + '.m4a')
                    if store.sha_file(audio) != job['audio_sha256']:
                        raise ValueError('원음 해시 불일치: 전사 중단')
                    with self.lock:
                        if self.stopping:
                            return
                        if not self.child or self.child.poll() is not None:
                            self._launch(job['model_info'])
                    message = {'id': job['id'], 'audio_path': str(audio),
                               'recording_id': job['recording_id'], 'phase': job['input']['recording'].get('phase', 'LEGACY')}
                    if job['input'].get('question_context') is not None:
                        message['question_context'] = job['input']['question_context']
                    if review is not None:
                        message['transcript_review'] = review
                        message['transcript_parent'] = job['input']['transcript_parent']
                    self.child.stdin.write(store.canonical(message) + '\n')
                    self.child.stdin.flush()
                    with selectors.DefaultSelector() as selector:
                        selector.register(self.child.stdout, selectors.EVENT_READ)
                        if not selector.select(timeout=3600):
                            self._stop_child()
                            raise TimeoutError('녹음 1건의 처리 제한 60분 초과')
                    line = self.child.stdout.readline()
                    if not line:
                        raise RuntimeError('전사 프로세스 종료. processing-worker.log를 확인하세요.')
                    response = store.strict_json(line)
                    result = response.get('result')
                    if response.get('id') != job['id']:
                        raise ValueError('전사 응답 식별자 불일치')
                    if response.get('error'):
                        raise ValueError(response['error'])
                    if result.get('question_context') != job['input'].get('question_context'):
                        raise ValueError('등록한 질문 연결과 처리 결과의 근거가 다릅니다.')
                    if result.get('transcript_review') != review:
                        raise ValueError('등록한 전사 검토와 처리 결과의 근거가 다릅니다.')
                    if review is not None:
                        from inference_worker import reviewed_result
                        expected = reviewed_result(review, job['input']['transcript_parent'])
                        if any(result.get(key) != value for key, value in expected.items()):
                            raise ValueError('검토 전사 또는 재사용한 원래 전사 정보가 변경됐습니다.')
                    validate_extraction(result)
                    if store.sha_file(audio) != job['audio_sha256']:
                        raise ValueError('처리 도중 원음 변경')
                    if review is not None and not processing.review_is_current(self.root, job):
                        raise ValueError('추출 도중 원자료가 갱신되었습니다. 검토 전사를 최신 근거와 대조하세요.')
                    processing.finish(self.root, job['id'], result)
                except Exception as e:
                    processing.fail(self.root, job['id'], str(e), result=failure_result(result))
        finally:
            with self.lock:
                self._stop_child()
                self.thread = None
                # A request may have queued work after the last empty-queue check.
                if not self.stopping and processing.pending(self.root):
                    self.thread = threading.Thread(target=self._run, daemon=True)
                    self.thread.start()

    def close(self):
        if self.lockfile.closed:
            return
        with self.lock:
            self.stopping = True
            child = self.child
            thread = self.thread
            if child and child.poll() is None:
                child.terminate()
        if thread:
            thread.join(timeout=15)
            if thread.is_alive():
                if child and child.poll() is None:
                    child.kill()
                thread.join(timeout=10)
            if thread.is_alive():
                raise RuntimeError('전사 종료를 기다리는 중입니다. 실행 잠금을 유지합니다.')
        processing.recover(self.root)
        self.lockfile.close()
