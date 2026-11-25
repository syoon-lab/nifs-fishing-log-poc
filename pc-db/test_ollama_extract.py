import copy
import json
import unittest
from unittest.mock import patch

import ollama_extract as oe


class OllamaExtractTests(unittest.TestCase):
    def setUp(self):
        self.spec = {'model': 'qwen3.6:35b', 'digest': '07d35212591fc27746f0a317c975a6d68754fb38e9053d82e25f06057af28522'}
        self.messages = [{'role': 'system', 'content': 'Return grounded claims.'},
                         {'role': 'user', 'content': '통발 아홉 개를 걷었습니다.'}]
        self.reply = {'model': self.spec['model'], 'done': True, 'done_reason': 'stop',
                      'message': {'role': 'assistant', 'content': '{"claims":[],"uncertainties":[]}'},
                      'prompt_eval_count': 100, 'eval_count': 20, 'total_duration': 1000}
        self.calls = []

    def fake_http(self, path, payload=None):
        self.calls.append((path, payload))
        if path == '/api/tags':
            return {'models': [{'name': self.spec['model'], 'digest': 'sha256:' + self.spec['digest']}]}
        if path == '/api/version':
            return {'version': 'test-version'}
        if path == '/api/chat':
            return copy.deepcopy(self.reply)
        self.fail('Unexpected endpoint: ' + path)

    def test_pinned_installed_model_and_structured_deterministic_request(self):
        with patch.object(oe, '_http_json', side_effect=self.fake_http):
            raw, metadata = oe.extract(self.spec, self.messages)
        self.assertEqual(json.loads(raw), {'claims': [], 'uncertainties': []})
        self.assertEqual([x[0] for x in self.calls], ['/api/tags', '/api/version', '/api/chat', '/api/tags'])
        request = self.calls[2][1]
        self.assertEqual(request['messages'], self.messages)
        self.assertEqual(request['format'], oe.CLAIM_SCHEMA)
        self.assertEqual(request['options'], {'temperature': 0, 'seed': 0, 'num_ctx': 16384, 'num_predict': 2048})
        self.assertIs(request['stream'], False)
        self.assertIs(request['think'], False)
        self.assertEqual(request['keep_alive'], '5m')
        self.assertEqual(metadata['digest'], self.spec['digest'])
        self.assertEqual(metadata['api_version'], 'test-version')
        self.assertFalse(metadata['input_truncated'])

    def test_no_remote_urls_or_model_pulls(self):
        for key in ('url', 'host', 'base_url', 'endpoint'):
            with self.subTest(key=key), patch.object(oe, '_http_json') as http:
                with self.assertRaises(oe.OllamaExtractionError):
                    oe.extract(self.spec | {key: 'https://remote.example'}, self.messages)
                http.assert_not_called()
        with patch.object(oe, '_http_json', return_value={'models': []}) as http:
            with self.assertRaises(oe.OllamaExtractionError):
                oe.extract(self.spec, self.messages)
            self.assertEqual(http.call_count, 1)

    def test_wrong_digest_fails_before_inference(self):
        with patch.object(oe, '_http_json', return_value={'models': [
                {'name': self.spec['model'], 'digest': 'f' * 64}]}) as http:
            with self.assertRaises(oe.OllamaExtractionError):
                oe.extract(self.spec, self.messages)
            self.assertEqual(http.call_count, 1)

    def test_full_korean_input_budget_no_truncation(self):
        messages = [{'role': 'user', 'content': '가' * 6000}]
        with patch.object(oe, '_http_json') as http:
            with self.assertRaises(oe.OllamaExtractionError):
                oe.extract(self.spec, messages)
            http.assert_not_called()
        with patch.object(oe, '_http_json', side_effect=self.fake_http):
            oe.extract(self.spec | {'num_ctx': 32768}, messages)
        self.assertEqual(self.calls[2][1]['messages'], messages)

    def test_partial_and_empty_responses_are_not_success(self):
        for change in ({'done_reason': 'length'}, {'done': False},
                       {'message': {'role': 'assistant', 'content': ''}},
                       {'model': 'different-model'}, {'prompt_eval_count': None}):
            with self.subTest(change=change):
                before = self.reply
                self.reply = before | change
                with patch.object(oe, '_http_json', side_effect=self.fake_http):
                    with self.assertRaises(oe.OllamaExtractionError):
                        oe.extract(self.spec, self.messages)
                self.reply = before

    def test_token_limit_failure_retains_raw_and_provenance(self):
        self.reply['done_reason'] = 'length'
        self.reply['message']['content'] = '{"claims":['
        with patch.object(oe, '_http_json', side_effect=self.fake_http):
            with self.assertRaises(oe.OllamaExtractionError) as caught:
                oe.extract(self.spec, self.messages)
        self.assertEqual(caught.exception.raw, '{"claims":[')
        self.assertEqual(caught.exception.metadata['done_reason'], 'length')

    def test_invalid_message_roles_and_options_rejected_before_http(self):
        with patch.object(oe, '_http_json') as http:
            for messages in ([], [{'role': 'tool', 'content': 'x'}], [{'role': 'user', 'content': 4}]):
                with self.assertRaises(oe.OllamaExtractionError):
                    oe.extract(self.spec, messages)
            for num_ctx in (True, 100, 65536):
                with self.assertRaises(oe.OllamaExtractionError):
                    oe.extract(self.spec | {'num_ctx': num_ctx}, self.messages)
            http.assert_not_called()

    def test_strict_protocol_rejects_duplicate_and_nonfinite_json(self):
        for text in ('{"x":1,"x":2}', '{"x":NaN}', '{"x":1e999}'):
            with self.assertRaises(oe.OllamaExtractionError):
                oe._strict_json(text)

    def test_tag_change_after_generation_preserves_rejected_candidate(self):
        tags_calls = 0
        def changing(path, payload=None):
            nonlocal tags_calls
            if path == '/api/tags':
                tags_calls += 1
                if tags_calls > 1:
                    return {'models': [{'name': self.spec['model'], 'digest': 'f' * 64}]}
            return self.fake_http(path, payload)
        with patch.object(oe, '_http_json', side_effect=changing):
            with self.assertRaises(oe.OllamaExtractionError) as caught:
                oe.extract(self.spec, self.messages)
        self.assertEqual(caught.exception.raw, self.reply['message']['content'])
        self.assertEqual(caught.exception.metadata['digest'], self.spec['digest'])


if __name__ == '__main__':
    unittest.main()
