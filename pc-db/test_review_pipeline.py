"""Reviewed transcripts exercise extraction, controller and HTTP without model loading."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request
from http.server import ThreadingHTTPServer
from unittest.mock import patch

import app
import inference_worker
import pipeline
import processing
import store
import test_inference_worker as worker_tests
import test_pipeline as pipeline_tests
from transcript_selection import extraction_text


def review_task(selection='corrected', text='꽃게를 잡았습니다.'):
    base, dialect = '삼치 5kg 잡았다.', '삼채오 킬로그램 잡았다.'
    selected = base if selection == 'base' else dialect if selection == 'dialect' else text
    review = dict(version=1, parent_run_id='a' * 32, parent_result_sha256='b' * 64,
                  selection=selection, text=selected, text_sha256=hashlib.sha256(selected.encode()).hexdigest(),
                  reviewer='시험 검토자', reason='원음 대조 기능 시험', reviewed_at_ms=1234)
    parent = dict(run_id=review['parent_run_id'], model_sha256='c' * 64,
                  asr_text=base, dialect_text=dialect, dialect_error=None,
                  base_segments=[{'text': base, 'start': 0, 'end': 1}],
                  dialect_segments=[{'text': dialect, 'start': 0, 'end': 1}],
                  transcription_status={'base': 'COMPLETED', 'dialect': 'COMPLETED'},
                  duration_seconds=1, sample_rate=16000, audio_samples=16000)
    return {'transcript_review': review, 'transcript_parent': parent, 'phase': 'WORK', 'recording_id': 'recording'}


class ReviewedWorker(unittest.TestCase):
    def worker(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        worker, _, _ = worker_tests.WorkerContractTests.setup_worker(self, directory.name, dialect=True, extractor=True)
        worker.read_audio = lambda *args: self.fail('Reviewed text must not decode audio')
        worker.transcribe = lambda *args, **kwargs: self.fail('Reviewed text must not rerun ASR')
        return worker

    def test_selected_base_keeps_bad_dialect_but_grounds_only_selected_text(self):
        worker = self.worker()
        calls = []
        payload = {'claims': [{'field': 'catches[0].species', 'value': '삼치', 'quote': '삼치 5kg'},
                               {'field': 'catches[0].weight_kg', 'value': 5, 'quote': '5kg'}], 'uncertainties': []}
        def extract(text, phase, source, **kwargs):
            calls.append((text, source))
            return payload, json.dumps(payload), None
        worker.extract = extract
        task = review_task('base')
        before = copy.deepcopy(task)
        result = worker.process(task)
        self.assertNotIn('error', result)
        self.assertEqual(calls, [('삼치 5kg 잡았다.', 'reviewed')])
        self.assertEqual(result['dialect_text'], task['transcript_parent']['dialect_text'])
        self.assertEqual(result['segments'], task['transcript_parent']['base_segments'])
        self.assertEqual(result['extraction']['fields']['catches'][0]['weight_kg'], 5)
        self.assertEqual(result['extraction']['status'], 'DRAFT')
        self.assertEqual(task, before)

    def test_corrected_text_does_not_inherit_original_word_timestamps_or_values(self):
        worker = self.worker()
        payload = {'claims': [{'field': 'catches[0].species', 'value': '꽃게', 'quote': '꽃게를 잡았습니다'}], 'uncertainties': []}
        worker.extract = lambda *args, **kwargs: (payload, json.dumps(payload), None)
        result = worker.process(review_task())
        self.assertNotIn('error', result)
        self.assertEqual(result['segments'], [])
        self.assertTrue(result['base_segments'])
        self.assertEqual(result['extraction']['fields']['catches'][0]['species'], '꽃게')
        self.assertIsNone(result['extraction']['fields']['catches'][0]['weight_kg'])
        self.assertEqual(extraction_text(result), '꽃게를 잡았습니다.')

    def test_corrected_text_can_follow_asr_failure_without_inventing_original_transcript(self):
        worker = self.worker()
        task = review_task(text='잘 모르겠습니다.')
        task['transcript_parent'].update(asr_text=None, dialect_text=None, base_segments=[], dialect_segments=[],
                                         transcription_status=None)
        worker.extract = lambda *args, **kwargs: ({'claims': [], 'uncertainties': ['미확인']}, '{}', None)
        result = worker.process(task)
        self.assertNotIn('error', result)
        self.assertIsNone(result['asr_text'])
        self.assertIsNone(result['dialect_text'])
        self.assertEqual(result['text_source'], 'reviewed')

    def test_linked_question_uses_corrected_answer_but_never_reviewer_reason(self):
        worker = self.worker()
        worker.config['extractor'] = {'engine': 'ollama'}
        task = review_task(text='스무 개입니다.')
        task['question_context'] = worker_tests.WorkerContractTests.question_context()
        task['transcript_review']['reason'] = '비교용 999개는 모델에 전달하면 안 됨'
        with patch('ollama_extract.extract', return_value=('{"claims":[],"uncertainties":[]}', {})) as extract:
            result = worker.process(task)
        self.assertNotIn('error', result)
        self.assertEqual(result['extraction']['fields']['haul_quantity'], 20)
        data = json.loads(extract.call_args.args[1][1]['content'])
        self.assertEqual(data['transcript'], '스무 개입니다.')
        self.assertNotIn('999', json.dumps(extract.call_args.args, ensure_ascii=False))
        self.assertEqual(result['question_context'], task['question_context'])

    def test_wrong_review_link_hash_or_source_stops_before_extraction(self):
        worker = self.worker()
        worker.extract = lambda *args, **kwargs: self.fail('Invalid review must not reach model')
        tasks = [review_task('base') for _ in range(3)]
        tasks[0]['transcript_review']['text_sha256'] = '0' * 64
        tasks[1]['transcript_parent']['run_id'] = 'd' * 32
        tasks[2]['transcript_parent']['asr_text'] = '다른 원문'
        for task in tasks:
            with self.subTest(task=task), self.assertRaises(ValueError):
                worker.process(task)

    def test_invalid_extraction_preserves_review_and_originals(self):
        worker = self.worker()
        worker.extract = lambda *args, **kwargs: (None, 'invalid output', '잘못된 JSON')
        task = review_task('dialect')
        result = worker.process(task)
        self.assertIn('error', result)
        self.assertEqual(len(result['extraction_attempts']), 2)
        self.assertEqual(result['transcript_review'], task['transcript_review'])
        self.assertEqual(result['asr_text'], task['transcript_parent']['asr_text'])


REVIEW_STUB = '''import json,sys
sys.path.insert(0, ROOT)
from inference_worker import reviewed_result
for line in sys.stdin:
 task=json.loads(line)
 value=reviewed_result(task['transcript_review'],task['transcript_parent'])
 value['extraction']=json.loads(sys.argv[1])['extraction']
 if task.get('question_context') is not None: value['question_context']=task['question_context']
 TAMPER
 print(json.dumps({'id':task['id'],'result':value}),flush=True)
'''.replace('ROOT', repr(str(Path(__file__).resolve().parent)))


class ReviewController(unittest.TestCase):
    setUp = pipeline_tests.ControllerTests.setUp
    create = pipeline_tests.ControllerTests.create
    wait = pipeline_tests.ControllerTests.wait

    def parent(self):
        run = processing.enqueue(self.root, '시험기기', model_info=self.identity)['jobs'][0]
        processing.start(self.root, run['id'])
        result = {'asr_text': '기본 문장', 'dialect_text': '방언 문장', 'base_segments': [], 'dialect_segments': []}
        processing.fail(self.root, run['id'], '원래 추출 실패', result=result)
        # The fixture has a second recording; leave no unrelated queued work.
        for other in processing.pending(self.root):
            processing.fail(self.root, other['id'], '독립 시험에서 실행하지 않음')
        detail = store.detail(self.root, run['source_id'], run['work_id'])
        return dict(source=run['source_id'], work_id=run['work_id'], parent_run_id=run['id'],
                    head=detail['head'], evidence_sha=detail['fingerprint'], selection='corrected',
                    text='확인 문장 검색 시험', reviewer='회귀시험', reason='격리 DB 기능 검증')

    def test_http_reextract_search_dedup_and_original_history_preserved(self):
        controller = self.create(REVIEW_STUB.replace('TAMPER', 'pass'))
        request_data = self.parent()
        old_runs = copy.deepcopy(processing.list_runs(self.root))
        server = ThreadingHTTPServer(('127.0.0.1', 0), app.Handler)
        server.data_root, server.processor = self.root, controller
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        def stop():
            server.shutdown(); server.server_close(); thread.join()
        self.addCleanup(stop)
        url = f'http://127.0.0.1:{server.server_port}'
        def request(path, body=None, headers=None):
            data = None if body is None else json.dumps(body).encode()
            h = {'X-Local-Import': '1', 'Origin': url}; h.update(headers or {})
            with urllib.request.urlopen(urllib.request.Request(url+path, data=data, headers=h), timeout=5) as response:
                return json.load(response)
        for bad in (request_data | {'unexpected': True}, request_data | {'reviewer': ''}):
            with self.assertRaises(urllib.error.HTTPError) as failure:
                request('/api/reextract', bad)
            self.assertEqual(failure.exception.code, 400)
        with self.assertRaises(urllib.error.HTTPError) as failure:
            request('/api/reextract', request_data, {'Origin': 'http://foreign.invalid'})
        self.assertEqual(failure.exception.code, 403)
        queued = request('/api/reextract', request_data)
        self.assertEqual(len(queued['jobs']), 1)
        self.wait()
        latest = processing.list_runs(self.root)[0]
        self.assertEqual(latest['status'], 'COMPLETED', latest['error'])
        self.assertEqual(latest['result']['text_source'], 'reviewed')
        self.assertEqual(latest['result']['reviewed_text'], request_data['text'])
        self.assertEqual(latest['model_info']['execution_mode'], 'reviewed-transcript-extraction')
        repeat = request('/api/reextract', request_data)
        self.assertEqual(len(repeat['skipped']), 1); self.assertEqual(repeat['jobs'], [])
        self.wait()
        work = next(w for w in request('/api/overview')['works'] if w['id'] == request_data['work_id'])
        self.assertIn(request_data['text'], work['search_text'])
        self.assertEqual(request('/api/formal.json')['count'], 0)
        self.assertEqual(processing.list_runs(self.root)[1:], old_runs)
        self.assertEqual(self.original_db, (self.root/'records.sqlite3').read_bytes())

    def test_worker_cannot_change_preserved_originals(self):
        controller = self.create(REVIEW_STUB.replace('TAMPER', "value['asr_text']='원문 변조'"))
        queued = controller.enqueue_review(**self.parent())
        self.wait()
        run = next(r for r in processing.list_runs(self.root) if r['id'] == queued['jobs'][0]['id'])
        self.assertEqual(run['status'], 'FAILED')
        self.assertIn('원래 전사', run['error'])
        self.assertEqual(run['input']['transcript_parent']['asr_text'], '기본 문장')
        indexed = app.transcript_search_text(run)
        self.assertIn('기본 문장', indexed)
        self.assertIn('확인 문장 검색 시험', indexed)
        self.assertNotIn('원문 변조', indexed)

    def test_source_changed_before_execution_stops_without_model(self):
        controller = self.create()
        with patch.object(controller, '_start_pending'):
            queued = controller.enqueue_review(**self.parent())
        with patch.object(processing, 'review_is_current', return_value=False), patch.object(controller, '_launch') as launch:
            controller._run()
        launch.assert_not_called()
        run = next(r for r in processing.list_runs(self.root) if r['id'] == queued['jobs'][0]['id'])
        self.assertEqual(run['status'], 'FAILED')
        self.assertIn('원자료', run['error'])

    def test_source_changed_during_execution_retains_unconfirmed_output(self):
        controller = self.create(REVIEW_STUB.replace('TAMPER', 'pass'))
        with patch.object(processing, 'review_is_current', side_effect=[True, False]):
            queued = controller.enqueue_review(**self.parent())
            self.wait()
        run = next(r for r in processing.list_runs(self.root) if r['id'] == queued['jobs'][0]['id'])
        self.assertEqual(run['status'], 'FAILED')
        self.assertIn('추출 도중 원자료', run['error'])
        self.assertEqual(run['extraction']['status'], 'DRAFT')
        self.assertEqual(store.formal_export(self.root)['count'], 0)


if __name__ == '__main__':
    unittest.main()
