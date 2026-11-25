"""Model provenance regression tests; only temporary files and stub processes.

Contracts: implementation_identity() hashes the implementation/prompt files;
artifact_state(config) records selected model files' size, mtime_ns and ctime_ns.
Controller must require restart after code changes, refresh idle prompt/model
changes, and reject tampered frozen launch inputs before starting a worker.
"""
import copy
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

import pipeline
import store


IMPLEMENTATION = ('inference_worker.py', 'extraction_prompt.txt', 'pipeline.py',
                  'store.py', 'processing.py', 'verify_export.py', 'extraction.py', 'ollama_extract.py',
                  'answer_context.py', 'transcript_selection.py', 'transcript_review.py')


class IdentityTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.code = self.root / 'code'
        self.code.mkdir()
        for name in IMPLEMENTATION:
            (self.code / name).write_text('original ' + name)
        self.models = self.root / 'model'
        self.models.mkdir()
        self.weight = self.models / 'model.safetensors'
        self.weight.write_bytes(b'abcd')
        (self.models / 'config.json').write_text('{}')
        self.config = {
            'python': sys.executable, 'ffmpeg': sys.executable, 'ffprobe': sys.executable,
            'asr': {'base': {'path': str(self.models), 'engine': 'transformers-whisper'}, 'dialect': None},
            'extractor': {'path': str(self.models), 'engine': 'transformers'},
            'extraction_prompt': str(self.code / 'extraction_prompt.txt'),
        }
        self.config_path = self.root / 'models.json'
        self.config_path.write_text(json.dumps(self.config))
        for target, kwargs in (
            ('ROOT', {'new': self.code}),
            ('model_identity', {'side_effect': self.identity}),
        ):
            p = patch.object(pipeline, target, **kwargs)
            mock = p.start()
            self.addCleanup(p.stop)
            if target == 'model_identity':
                self.identity_mock = mock
        self.queue_patch = patch.object(pipeline.processing, 'enqueue',
                                       return_value={'jobs': [], 'skipped': []})
        self.queue = self.queue_patch.start()
        self.addCleanup(self.queue_patch.stop)
        thread = Mock()
        thread.is_alive.return_value = False
        p = patch.object(pipeline.threading, 'Thread', return_value=thread)
        p.start()
        self.addCleanup(p.stop)
        self.controller = pipeline.Controller(self.root / 'data', self.config_path)
        self.addCleanup(self.cleanup_controller)

    def cleanup_controller(self):
        self.controller.thread = None
        self.controller.close()

    def identity(self, config):
        return {
            'config': copy.deepcopy(config),
            'implementation': pipeline.implementation_identity(),
            'artifact_state': pipeline.artifact_state(config),
            'prompt_text': (self.code / 'extraction_prompt.txt').read_text(),
        }

    def test_implementation_identity_contains_content_hashes(self):
        actual = pipeline.implementation_identity()
        self.assertEqual(set(actual), set(IMPLEMENTATION))
        self.assertEqual(actual, {name: store.sha_file(self.code / name) for name in IMPLEMENTATION})
        (self.code / 'inference_worker.py').write_text('changed worker')
        changed = pipeline.implementation_identity()
        self.assertNotEqual(actual['inference_worker.py'], changed['inference_worker.py'])
        self.assertEqual(actual['pipeline.py'], changed['pipeline.py'])

    def test_artifact_state_detects_same_size_replacement_even_with_restored_mtime(self):
        before = pipeline.artifact_state(self.config)
        original = self.weight.stat()
        time.sleep(.002)
        self.weight.write_bytes(b'efgh')
        os.utime(self.weight, ns=(original.st_atime_ns, original.st_mtime_ns))
        after = pipeline.artifact_state(self.config)
        self.assertNotEqual(before, after, 'ctime must distinguish replacement with restored size/mtime')

    def test_code_change_since_start_requires_restart_before_queueing(self):
        (self.code / 'inference_worker.py').write_text('new worker implementation')
        with self.assertRaises((ValueError, RuntimeError)):
            self.controller.enqueue('source')
        self.queue.assert_not_called()
        self.identity_mock.assert_not_called()

    def test_idle_prompt_change_recomputes_identity_without_restart(self):
        self.controller.enqueue('source')
        first = copy.deepcopy(self.queue.call_args.kwargs['model_info'])
        (self.code / 'extraction_prompt.txt').write_text('new extraction instructions')
        self.controller.enqueue('source')
        second = self.queue.call_args.kwargs['model_info']
        self.assertEqual(self.identity_mock.call_count, 2)
        self.assertNotEqual(first['prompt_text'], second['prompt_text'])
        self.assertNotEqual(store.fingerprint(first), store.fingerprint(second))

    def test_idle_model_change_recomputes_identity(self):
        self.controller.enqueue('source')
        first = copy.deepcopy(self.queue.call_args.kwargs['model_info'])
        self.weight.write_bytes(b'new model bytes')
        self.controller.enqueue('source')
        self.assertEqual(self.identity_mock.call_count, 2)
        self.assertNotEqual(first['artifact_state'], self.queue.call_args.kwargs['model_info']['artifact_state'])

    def test_active_prompt_change_rejects_without_regenerating_identity(self):
        self.controller.enqueue('source')
        active = Mock()
        active.is_alive.return_value = True
        self.controller.thread = active
        (self.code / 'extraction_prompt.txt').write_text('new prompt during a batch')
        before_identity = copy.deepcopy(self.controller.identity)
        with self.assertRaises((ValueError, RuntimeError)):
            self.controller.enqueue('source')
        self.assertEqual(self.identity_mock.call_count, 1)
        self.assertEqual(self.queue.call_count, 1)
        self.assertEqual(self.controller.identity, before_identity)

    def test_launch_rejects_overwritten_frozen_prompt_before_starting_worker(self):
        info = self.identity(self.config)
        with patch.object(pipeline.subprocess, 'Popen') as launch:
            launch.return_value.poll.return_value = 0
            self.controller._launch(info)
            argv = launch.call_args.args[0]
            frozen_config = Path(argv[argv.index('--config') + 1])
            frozen_prompt = Path(json.loads(frozen_config.read_text())['extraction_prompt'])
            self.controller._stop_child()
            frozen_prompt.write_text('tampered pinned prompt')
            launch.reset_mock()
            with self.assertRaises((ValueError, RuntimeError)):
                self.controller._launch(info)
            launch.assert_not_called()

    def test_launch_rejects_changed_implementation_before_starting_worker(self):
        info = self.identity(self.config)
        (self.code / 'inference_worker.py').write_text('changed after queueing')
        with patch.object(pipeline.subprocess, 'Popen') as launch:
            with self.assertRaises((ValueError, RuntimeError)):
                self.controller._launch(info)
            launch.assert_not_called()

    def test_launch_rejects_changed_model_files_before_starting_worker(self):
        info = self.identity(self.config)
        self.weight.write_bytes(b'changed after queueing')
        with patch.object(pipeline.subprocess, 'Popen') as launch:
            with self.assertRaises((ValueError, RuntimeError)):
                self.controller._launch(info)
            launch.assert_not_called()


if __name__ == '__main__':
    unittest.main()
