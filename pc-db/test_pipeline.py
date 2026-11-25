"""Offline pipeline/controller/HTTP tests; model subprocesses are deterministic stubs."""
import concurrent.futures
import json
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

import app
import inference_worker
import pipeline
import processing
import store
from test_pc import BASE


def result(text='전사 시험'):
    return {'asr_text': text, 'dialect_text': None,
            'extraction': {'status': 'DRAFT', 'fields': store.blank_fields(),
                           'evidence': [], 'uncertainties': ['시험용 자동 추출']}}


class ExtractionValidation(unittest.TestCase):
    def test_fallback_evidence_uses_base_and_retains_dialect_output(self):
        value = result('통발을 양망했습니다.')
        value.update(dialect_text='-', text_source='base_fallback', fallback_reason='dialect_empty')
        value['extraction']['fields']['work_type'] = '통발을 양망했습니다'
        value['extraction']['evidence'] = [{'field': 'work_type', 'quote': '통발을 양망했습니다'}]
        pipeline.validate_extraction(value)
        self.assertEqual(value['dialect_text'], '-')
        value['extraction']['evidence'][0]['quote'] = '-'
        with self.assertRaises(ValueError):
            pipeline.validate_extraction(value)
        value['dialect_text'] = '유효한 다른 문장'
        with self.assertRaises(ValueError):
            pipeline.validate_extraction(value)

    def test_missing_values_remain_null_and_never_confirmed(self):
        value = pipeline.validate_extraction(result())
        self.assertIsNone(value['extraction']['fields']['haul_quantity'])
        self.assertEqual(value['extraction']['status'], 'DRAFT')
        self.assertIn('outcome', value['missing_fields'])
        for change in ({'status': 'CONFIRMED'}, {'extra': 'unexpected'}):
            invalid = result()
            invalid['extraction'].update(change)
            with self.assertRaises(ValueError):
                pipeline.validate_extraction(invalid)

    def test_date_and_field_classification_need_human_confirmation(self):
        for field, value in [('deployment_date', '2026-11-08'), ('record_class', 'FIELD')]:
            invalid = result()
            invalid['extraction']['fields'][field] = value
            with self.assertRaises(ValueError):
                pipeline.validate_extraction(invalid)

    def test_nonempty_extraction_requires_actual_selected_transcript_quotes(self):
        value = result('통발 서른 개를 양망했습니다.')
        value['extraction']['fields']['work_type'] = '통발 양망'
        with self.assertRaises(ValueError):
            pipeline.validate_extraction(value)
        value['extraction']['evidence'] = [{'field': 'work_type', 'quote': '통발 서른 개'}]
        pipeline.validate_extraction(value)
        value['dialect_text'] = '다른 문장'
        with self.assertRaises(ValueError):
            pipeline.validate_extraction(value)

    def test_conflicting_numbers_and_outcomes_rejected(self):
        for field, value in [('haul_quantity', -1), ('workers', True), ('outcome', 'invented')]:
            invalid = result()
            invalid['extraction']['fields'][field] = value
            with self.assertRaises(ValueError):
                pipeline.validate_extraction(invalid)

    def test_worker_parser_rejects_partial_or_wrapped_non_json(self):
        self.assertEqual(inference_worker.parse_extraction('```json\n{"status":"DRAFT"}\n```'),
                         {'status': 'DRAFT'})
        for raw in ('{"status":', '[]', 'Here is your JSON: {}'):
            with self.assertRaises((ValueError, TypeError)):
                inference_worker.parse_extraction(raw)

    def test_draft_keeps_known_gear_with_unspoken_unit_but_formal_store_is_strict(self):
        value = result('통발을 양망했습니다.')
        value['extraction']['fields']['gear_type'] = 'POT'
        value['extraction']['evidence'] = [{'field': 'gear_type', 'quote': '통발'}]
        checked = pipeline.validate_extraction(value)
        self.assertEqual(checked['extraction']['fields']['gear_type'], 'POT')
        self.assertIsNone(checked['extraction']['fields']['gear_unit'])
        self.assertIn('gear_unit', checked['missing_fields'])
        self.assertEqual(checked['extraction']['status'], 'DRAFT')
        with self.assertRaisesRegex(ValueError, '단위'):
            store.normalize_fields(checked['extraction']['fields'])
        checked['extraction']['status'] = 'CONFIRMED'
        with self.assertRaises(ValueError):
            pipeline.validate_extraction(checked)


class ControllerTests(unittest.TestCase):
    def setUp(self):
        if not BASE.exists():
            self.skipTest('시험 기기 내보내기 자료가 없어 건너뜀(공개 저장소 미포함)')
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        store.import_zip(BASE, self.root, '시험기기')
        self.original_db = (self.root / 'records.sqlite3').read_bytes()
        self.model_dir = self.root / 'model'
        self.model_dir.mkdir()
        self.config = {'python': sys.executable, 'ffmpeg': sys.executable,
                       'ffprobe': sys.executable,
                       'asr': {'base': {'path': str(self.model_dir)}, 'dialect': None},
                       'extractor': {'path': str(self.model_dir)}}
        self.config_path = self.root / 'models.json'
        self.config_path.write_text(json.dumps(self.config))
        self.identity = {'config': self.config, 'version': 'test-only'}
        identity = patch.object(pipeline, 'model_identity', return_value=self.identity)
        identity.start()
        self.addCleanup(identity.stop)
        self.controller = None

    def create(self, script=None):
        self.controller = pipeline.Controller(self.root, self.config_path)
        self.addCleanup(self.controller.close)
        if script is None:
            script = '''import json,sys
reply = json.loads(sys.argv[1])
for line in sys.stdin:
 task = json.loads(line)
 value = dict(reply, received_phase=task.get('phase'))
 if task.get('question_context') is not None: value['question_context']=task['question_context']
 print(json.dumps({'id':task['id'],'result':value}),flush=True)
'''
        def launch(model_info):
            self.controller.child = subprocess.Popen(
                [sys.executable, '-u', '-c', script, json.dumps(result())],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                text=True, bufsize=1)
        self.controller._launch = launch
        return self.controller

    def wait(self):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            thread = self.controller.thread
            if thread is None or not thread.is_alive():
                return
            thread.join(timeout=.05)
        self.fail('Stub processing worker did not finish within 5 seconds')

    def test_full_pipeline_records_draft_and_deduplicates_concurrent_requests(self):
        c = self.create()
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            batches = list(pool.map(lambda _: c.enqueue('시험기기'), range(4)))
        self.assertEqual(sum(len(b['jobs']) for b in batches), 2)
        self.wait()
        runs = processing.list_runs(self.root)
        self.assertEqual(len(runs), 2)
        self.assertTrue(all(r['status'] == 'COMPLETED' for r in runs))
        for run in runs:
            self.assertEqual(run['result']['received_phase'], run['input']['recording'].get('phase', 'LEGACY'))
            self.assertEqual(run['result'].get('question_context'), run['input'].get('question_context'))
            self.assertEqual(run['review_status'], 'DRAFT')
        self.assertFalse(c.enqueue('시험기기')['jobs'])
        self.wait()
        self.assertEqual(store.formal_export(self.root)['count'], 0)
        self.assertEqual(self.original_db, (self.root / 'records.sqlite3').read_bytes())

    def test_exclusive_controller_lock_and_recovery(self):
        processing.enqueue(self.root, '시험기기', model_info=self.identity)
        c = self.create()
        self.assertEqual(processing.counts(self.root)['INTERRUPTED'], 2)
        with self.assertRaises(RuntimeError):
            pipeline.Controller(self.root, self.config_path)
        c.enqueue('시험기기')
        self.wait()
        self.assertEqual(processing.counts(self.root)['COMPLETED'], 2)
        self.assertEqual(processing.counts(self.root)['INTERRUPTED'], 2)

    def test_models_missing_report_unavailable_without_queueing(self):
        self.config_path.write_text('{}')
        c = self.create()
        self.assertFalse(c.status()['models']['ready'])
        with self.assertRaises((ValueError, KeyError)):
            c.enqueue('시험기기')
        self.assertEqual(processing.list_runs(self.root), [])
        self.assertEqual(self.original_db, (self.root / 'records.sqlite3').read_bytes())

    def test_dialect_selection_accepts_only_registered_existing_models(self):
        self.config['available_dialects'] = {
            'present': {'path': str(self.model_dir), 'name': '시험 방언'},
            'missing': {'path': str(self.root / 'absent'), 'name': '없는 모델'},
        }
        self.config_path.write_text(json.dumps(self.config))
        c = self.create()
        for invalid in ('unregistered', '../model', str(self.model_dir)):
            with self.subTest(dialect=invalid), self.assertRaisesRegex(ValueError, '등록되지 않은'):
                c.enqueue('시험기기', dialect=invalid)
        with self.assertRaisesRegex(ValueError, '파일을 찾을 수 없습니다'):
            c.enqueue('시험기기', dialect='missing')
        self.assertEqual(processing.list_runs(self.root), [])
        self.assertEqual(c.status()['models']['available_dialects'], self.config['available_dialects'])
        self.assertEqual(self.original_db, (self.root / 'records.sqlite3').read_bytes())

    def test_base_and_dialect_runs_have_distinct_identity_but_each_deduplicates(self):
        self.config['available_dialects'] = {
            'regional': {'path': str(self.model_dir), 'name': '시험 방언', 'engine': 'peft-whisper'},
        }
        self.config_path.write_text(json.dumps(self.config))
        c = self.create()
        def identity(config):
            return {'config': json.loads(json.dumps(config)), 'version': 'test-only'}
        with patch.object(pipeline, 'model_identity', side_effect=identity):
            base = c.enqueue('시험기기', dialect='base')
            self.assertEqual(len(base['jobs']), 2)
            self.wait()
            same_base = c.enqueue('시험기기', dialect='base')
            self.assertEqual(len(same_base['jobs']), 0)
            self.assertEqual(len(same_base['skipped']), 2)
            self.wait()
            regional = c.enqueue('시험기기', dialect='regional')
            self.assertEqual(len(regional['jobs']), 2)
            self.wait()
            self.assertTrue(all(r['model_info']['config']['asr']['dialect']['name'] == '시험 방언'
                                for r in regional['jobs']))
            self.assertNotEqual(base['jobs'][0]['model_sha256'], regional['jobs'][0]['model_sha256'])
            self.assertEqual(len(c.enqueue('시험기기', dialect='regional')['jobs']), 0)
            self.wait()
            self.assertEqual(len(c.enqueue('시험기기', dialect='base')['jobs']), 0)
            self.wait()
        self.assertEqual(len(processing.list_runs(self.root)), 4)
        self.assertEqual(processing.counts(self.root)['COMPLETED'], 4)
        self.assertEqual(self.original_db, (self.root / 'records.sqlite3').read_bytes())

    def test_launch_uses_immutable_prompt_snapshot_in_worker_config(self):
        c = self.create()
        editable_prompt = self.root / 'editable-prompt.txt'
        editable_prompt.write_text('현재 편집 중인 프롬프트')
        info = {'config': self.config | {'extraction_prompt': str(editable_prompt)},
                'prompt_text': '이 작업을 등록할 때의 프롬프트', 'version': 'test-only'}
        with patch.object(pipeline.subprocess, 'Popen') as launch:
            launch.return_value.poll.return_value = 0
            pipeline.Controller._launch(c, info)
            command = launch.call_args.args[0]
            worker_config = Path(command[command.index('--config') + 1])
            saved_config = json.loads(worker_config.read_text())
            snapshot = Path(saved_config['extraction_prompt'])
            self.assertNotEqual(snapshot, editable_prompt)
            self.assertEqual(snapshot.read_text(), info['prompt_text'])
            self.assertEqual(saved_config['asr'], self.config['asr'])
            self.assertEqual(launch.call_args.kwargs['env']['HF_HUB_OFFLINE'], '1')
            first_bytes = worker_config.read_bytes()
            c._stop_child()
            editable_prompt.write_text('등록 후 변경한 프롬프트')
            pipeline.Controller._launch(c, info)
            self.assertEqual(worker_config.read_bytes(), first_bytes)
            self.assertEqual(snapshot.read_text(), info['prompt_text'])
            c._stop_child()
            changed = info | {'prompt_text': '새 작업의 다른 프롬프트'}
            pipeline.Controller._launch(c, changed)
            command = launch.call_args.args[0]
            second_config = Path(command[command.index('--config') + 1])
            self.assertNotEqual(second_config, worker_config)
            second_prompt = Path(json.loads(second_config.read_text())['extraction_prompt'])
            self.assertEqual(second_prompt.read_text(), changed['prompt_text'])
            self.assertEqual(snapshot.read_text(), info['prompt_text'])

    def test_corrupt_audio_is_recorded_as_failure_before_model_execution(self):
        c = self.create()
        first = processing.source_recordings(self.root, '시험기기')[0]
        (self.root / 'audio' / (first['audio_sha256'] + '.m4a')).write_bytes(b'corrupted')
        c.enqueue('시험기기')
        self.wait()
        failed = next(r for r in processing.list_runs(self.root)
                      if r['recording_id'] == first['recording_id'])
        self.assertEqual(failed['status'], 'FAILED')
        self.assertIn('해시', failed['error'])
        self.assertEqual(self.original_db, (self.root / 'records.sqlite3').read_bytes())

    def test_invalid_worker_result_keeps_evidence_and_continues_queue(self):
        c = self.create('''import json,sys
reply=json.loads(sys.argv[1]); count=0
for line in sys.stdin:
 task=json.loads(line); count+=1
 value=dict(reply,extraction=[]) if count==1 else dict(reply)
 if task.get('question_context') is not None: value['question_context']=task['question_context']
 print(json.dumps({'id':task['id'],'result':value}),flush=True)
''')
        c.enqueue('시험기기')
        self.wait()
        runs = processing.list_runs(self.root)
        self.assertEqual({r['status'] for r in runs}, {'FAILED', 'COMPLETED'})
        failed = next(r for r in runs if r['status'] == 'FAILED')
        self.assertEqual(failed['asr_text'], '전사 시험')
        retained = failed['result'].get('raw_result', failed['result'].get('invalid_fields', {}))
        self.assertEqual(retained['extraction'], [])

    def test_worker_cannot_omit_registered_question_context(self):
        c = self.create('''import json,sys
reply=json.loads(sys.argv[1])
for line in sys.stdin:
 task=json.loads(line)
 print(json.dumps({'id':task['id'],'result':reply}),flush=True)
''')
        c.enqueue('시험기기')
        self.wait()
        runs = processing.list_runs(self.root)
        linked = [r for r in runs if r['input'].get('question_context')]
        self.assertEqual(len(linked), 1)
        self.assertEqual(linked[0]['status'], 'FAILED')
        self.assertIn('질문 연결', linked[0]['error'])
        self.assertEqual(linked[0]['asr_text'], '전사 시험')
        self.assertTrue(all(r['status'] == 'COMPLETED' for r in runs if not r['input'].get('question_context')))

    def test_close_terminates_worker_and_allows_new_exclusive_owner(self):
        c = self.create('''import json,sys,time
for line in sys.stdin:
 time.sleep(30)
''')
        c.enqueue('시험기기')
        deadline = time.monotonic() + 2
        while c.child is None and time.monotonic() < deadline:
            time.sleep(.01)
        old_thread = c.thread
        c.close()
        self.assertFalse(old_thread.is_alive())
        replacement = pipeline.Controller(self.root, self.config_path)
        replacement.close()
        self.assertFalse(any(r['status'] in processing.ACTIVE for r in processing.list_runs(self.root)))

    def test_http_queue_status_detail_and_search(self):
        c = self.create()
        server = ThreadingHTTPServer(('127.0.0.1', 0), app.Handler)
        server.data_root = self.root
        server.processor = c
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        def stop():
            server.shutdown()
            server.server_close()
            thread.join()
        self.addCleanup(stop)
        url = 'http://127.0.0.1:' + str(server.server_port)
        def request(path, body=None):
            headers = {'X-Local-Import': '1', 'Origin': url}
            data = None if body is None else json.dumps(body).encode()
            with urllib.request.urlopen(urllib.request.Request(url + path, data=data, headers=headers), timeout=5) as r:
                return json.load(r)
        self.assertTrue(request('/api/processing')['models']['ready'])
        queued = request('/api/process', {'source': '시험기기'})
        self.assertEqual(len(queued['jobs']), 2)
        self.wait()
        status = request('/api/processing')
        self.assertEqual(status['counts']['COMPLETED'], 2)
        work = request('/api/overview')['works'][0]
        self.assertEqual(work['pc_status'], 'COMPLETED')
        self.assertIn('전사 시험', work['search_text'])
        detail = request('/api/work?' + urllib.parse.urlencode({'source': '시험기기', 'work': work['id']}))
        self.assertEqual(len(detail['pc_runs']), 2)
        self.assertEqual(request('/api/formal.json')['count'], 0)
        self.assertEqual(self.original_db, (self.root / 'records.sqlite3').read_bytes())
        self.config_path.write_text('{}')
        self.assertFalse(request('/api/processing')['models']['ready'])
        with self.assertRaises(urllib.error.HTTPError) as failure:
            request('/api/process', {'source': '시험기기'})
        self.assertEqual(failure.exception.code, 400)
        self.assertEqual(len(processing.list_runs(self.root)), 2)


if __name__ == '__main__':
    unittest.main()
