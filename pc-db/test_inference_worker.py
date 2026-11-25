"""Worker protocol and preservation checks without loading ML libraries."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from inference_worker import Worker, parse_extraction, strict_json
import store


class WorkerContractTests(unittest.TestCase):
    @staticmethod
    def question_context():
        return {'version': 1, 'source_id': 'source', 'work_id': 'op:operation',
                'recording_id': 'recording', 'session_id': 'session', 'operation_id': 'operation',
                'event_id': 'event', 'target_field': 'haul_quantity', 'expected_unit': '개',
                'question': '이번에 걷은 통발은 몇 개입니까?'}

    def test_question_context_routes_only_explicit_answer_and_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            worker, task, _ = self.setup_worker(directory, extractor=True)
            worker.transcribe = lambda *args, **kwargs: {'text': '서른 개입니다.', 'segments': []}
            worker.extract = lambda *args, **kwargs: ({'claims': [], 'uncertainties': []}, '{}', None)
            task.update(recording_id='recording', question_context=self.question_context())
            linked = worker.process(task)
            self.assertNotIn('error', linked)
            self.assertEqual(linked['extraction']['fields']['haul_quantity'], 30)
            self.assertIsNone(linked['extraction']['fields']['gear_type'])
            self.assertEqual(linked['question_context'], task['question_context'])
            del task['question_context']
            unlinked = worker.process(task)
            self.assertIsNone(unlinked['extraction']['fields']['haul_quantity'])

    def test_model_receives_only_projected_question_and_current_answer(self):
        with tempfile.TemporaryDirectory() as directory:
            worker, _, _ = self.setup_worker(directory, extractor=True)
            worker.config['extractor'] = {'engine': 'ollama'}
            with patch('ollama_extract.extract', return_value=('{"claims":[],"uncertainties":[]}', {})) as extract:
                worker.extract('서른 개', 'WORK', 'base', question_context=self.question_context())
            messages = extract.call_args.args[1]
            data = json.loads(messages[1]['content'])
            self.assertEqual(data, {'recording_phase': 'WORK', 'transcript_source': 'base',
                'transcript': '서른 개', 'linked_question': {'target_field': 'haul_quantity',
                'expected_unit': '개', 'question': '이번에 걷은 통발은 몇 개입니까?'}})
            with self.assertRaises(ValueError):
                worker.extract('서른 개', 'WORK', 'base', question_context=self.question_context() | {'prior_value': 999})

    def test_question_from_another_recording_cannot_be_used(self):
        with tempfile.TemporaryDirectory() as directory:
            worker, task, _ = self.setup_worker(directory, extractor=True)
            worker.transcribe = lambda *args, **kwargs: {'text': '서른 개', 'segments': []}
            worker.extract = lambda *args, **kwargs: self.fail('Wrong recording link must be rejected')
            task.update(recording_id='another', question_context=self.question_context())
            with self.assertRaises(ValueError):
                worker.process(task)

    def test_ollama_partial_response_preserves_raw_and_runtime(self):
        from ollama_extract import OllamaExtractionError
        with tempfile.TemporaryDirectory() as directory:
            worker, _, _ = self.setup_worker(directory, extractor=True)
            worker.config['extractor'] = {'engine': 'ollama'}
            error = OllamaExtractionError('token limit', raw='{"claims":', metadata={'ollama_version': 'test'})
            with patch('ollama_extract.extract', side_effect=error):
                payload, raw, warning = worker.extract('시험', 'WORK', 'base')
            self.assertIsNone(payload)
            self.assertEqual(raw, '{"claims":')
            self.assertIn('token limit', warning)
            self.assertEqual(worker.versions['ollama_version'], 'test')

    def test_punctuation_only_dialect_uses_base_without_relabeling_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            worker, task, _ = self.setup_worker(directory, dialect=True, extractor=True)
            worker.transcribe = lambda waveform, base=False: {'text': '기본 원문' if base else '-', 'segments': []}
            called = []
            def extract(text, phase, source, **kwargs):
                called.append((text, source))
                return {'claims': [], 'uncertainties': []}, '{}', None
            worker.extract = extract
            result = worker.process(task)
            self.assertNotIn('error', result)
            self.assertEqual(called, [('기본 원문', 'base_fallback')])
            self.assertEqual(result['asr_text'], '기본 원문')
            self.assertEqual(result['dialect_text'], '-')
            self.assertEqual(result['transcription_status']['dialect'], 'EMPTY')
            self.assertTrue(result['warnings'])

    def test_both_transcripts_empty_do_not_invoke_extractor(self):
        with tempfile.TemporaryDirectory() as directory:
            worker, task, _ = self.setup_worker(directory, dialect=True, extractor=True)
            worker.transcribe = lambda *args, **kwargs: {'text': '-', 'segments': []}
            worker.extract = lambda *args, **kwargs: self.fail('No usable source')
            result = worker.process(task)
            self.assertIn('유효한 전사', result['error'])
            self.assertIsNone(result['extraction'])

    def test_extraction_failure_does_not_switch_usable_dialect_to_base(self):
        with tempfile.TemporaryDirectory() as directory:
            worker, task, _ = self.setup_worker(directory, dialect=True, extractor=True)
            worker.transcribe = lambda waveform, base=False: {'text': '기본 원문' if base else '불명확한 방언', 'segments': []}
            called = []
            def extract(text, phase, source, **kwargs):
                called.append((text, source))
                return None, 'bad JSON', 'invalid JSON'
            worker.extract = extract
            result = worker.process(task)
            self.assertEqual(called, [('불명확한 방언', 'dialect')] * 2)
            self.assertIn('error', result)
            self.assertEqual(result['text_source'], 'dialect')
            self.assertIsNone(result['fallback_reason'])

    def test_bad_number_is_never_saved_as_valid_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            worker, task, _ = self.setup_worker(directory, extractor=True)
            worker.transcribe = lambda *args, **kwargs: {'text': '통발 마흔 개', 'segments': []}
            claims = {'claims': [{'field': 'gear_quantity', 'value': 4, 'quote': '통발 마흔 개'}], 'uncertainties': []}
            worker.extract = lambda *args, **kwargs: (claims, json.dumps(claims), None)
            result = worker.process(task)
            self.assertIn('error', result)
            self.assertIsNone(result['extraction'])
            self.assertEqual(len(result['extraction_attempts']), 2)
            self.assertEqual(result['claims'], claims)

    def test_blank_output_cannot_hide_explicit_missing_weight(self):
        with tempfile.TemporaryDirectory() as directory:
            worker, task, _ = self.setup_worker(directory, extractor=True)
            worker.transcribe = lambda *args, **kwargs: {'text': '농어 칠 킬로그램 잡았습니다.', 'segments': []}
            payload = {'claims': [], 'uncertainties': []}
            worker.extract = lambda *args, **kwargs: (payload, json.dumps(payload), None)
            result = worker.process(task)
            self.assertIn('error', result)
            self.assertTrue(result['grounding']['coverage_warnings'])

    def test_strict_json_rejects_nonfinite_duplicate_and_extra_text(self):
        for text in ('{"x":NaN}', '{"x":Infinity}', '{"x":-Infinity}', '{"x":1e999}',
                     '{"x":1,"x":2}', '{"nested":{"x":1,"x":2}}', '{} trailing'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                strict_json(text)

    def test_extraction_accepts_only_whole_object_or_json_fence(self):
        self.assertEqual(parse_extraction('```json\n{"status":"DRAFT"}\n```'), {'status': 'DRAFT'})
        for text in ('[]', 'prefix {"status":"DRAFT"}', '```python\n{}\n```'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                parse_extraction(text)

    def setup_worker(self, directory, dialect=False, extractor=False):
        root = Path(directory)
        prompt = root / 'prompt.txt'
        prompt.write_text('original prompt')
        config = root / 'config.json'
        config.write_text(json.dumps({'asr': {'base': {}, 'dialect': {'path': 'adapter'} if dialect else None},
                                      'extractor': {} if not extractor else {'path': 'model'},
                                      'extraction_prompt': str(prompt)}))
        audio = root / 'audio.m4a'
        audio.write_bytes(b'placeholder')
        worker = Worker(config)
        worker.read_audio = lambda _: ([0] * 16000, {})
        return worker, {'audio_path': str(audio), 'phase': 'WORK'}, prompt

    def test_prompt_is_frozen_at_worker_start(self):
        with tempfile.TemporaryDirectory() as directory:
            worker, _, prompt = self.setup_worker(directory, extractor=True)
            prompt.write_text('changed prompt')
            self.assertEqual(worker.prompt, 'original prompt')

    def test_dialect_exception_preserves_error_but_extracts_from_base(self):
        with tempfile.TemporaryDirectory() as directory:
            worker, task, _ = self.setup_worker(directory, dialect=True, extractor=True)
            def transcribe(waveform, base=False):
                if base:
                    return {'text': '원래 전사', 'segments': [{'text': '원래 전사'}]}
                raise RuntimeError('adapter failure')
            worker.transcribe = transcribe
            worker.extract = lambda *args, **kwargs: ({'claims': [], 'uncertainties': []}, '{}', None)
            with contextlib.redirect_stderr(io.StringIO()):
                result = worker.process(task)
            self.assertEqual(result['asr_text'], '원래 전사')
            self.assertIsNone(result['dialect_text'])
            self.assertNotIn('error', result)
            self.assertIn('adapter failure', result['dialect_error'])
            self.assertEqual(result['transcription_status']['dialect'], 'FAILED')
            self.assertEqual(result['text_source'], 'base_fallback')
            self.assertEqual(result['extraction']['status'], 'DRAFT')
            self.assertFalse(worker.dialect_loaded)

    def test_failed_extraction_retry_preserves_both_attempts(self):
        with tempfile.TemporaryDirectory() as directory:
            worker, task, _ = self.setup_worker(directory, extractor=True)
            worker.transcribe = lambda *a, **kw: {'text': '모릅니다.', 'segments': []}
            valid = {'claims': [], 'uncertainties': ['알 수 없다고 답변함']}
            calls = []
            def extract(text, phase, source, **kwargs):
                calls.append(kwargs)
                return (None, 'bad JSON', 'invalid JSON') if len(calls) == 1 else (valid, json.dumps(valid), None)
            worker.extract = extract
            result = worker.process(task)
            self.assertEqual(len(result['extraction_attempts']), 2)
            self.assertEqual(result['extraction_attempts'][0]['raw'], 'bad JSON')
            self.assertEqual(calls[1]['previous_raw'], 'bad JSON')
            self.assertEqual(calls[1]['validation_error'], 'invalid JSON')
            self.assertEqual(result['extraction']['status'], 'DRAFT')
            self.assertEqual(result['warnings'], [])


if __name__ == '__main__':
    unittest.main()
