"""Reviewed-transcript provenance/storage checks using only temporary databases."""
import concurrent.futures
import copy
import json
import sqlite3
import unittest

import processing
import store
import test_processing as fixtures
from transcript_review import reviewed_text, text_sha256, validate_review


class ReviewProvenance(unittest.TestCase):
    def review(self, **changes):
        text = '삼치 5kg 잡았다.'
        result = {'version': 1, 'parent_run_id': 'parent-run',
                  'parent_result_sha256': 'a' * 64, 'selection': 'base',
                  'text': text, 'text_sha256': text_sha256(text),
                  'reviewer': '검토자', 'reason': '원음 대조', 'reviewed_at_ms': 1}
        result.update(changes)
        return result

    def test_review_preserves_exact_text_and_identity(self):
        text = '  삼치 5kg\n잡았다.  '
        review = self.review(text=text, text_sha256=text_sha256(text), reviewer=' 검토자 ')
        self.assertIs(validate_review(review), review)
        self.assertEqual(review['text'], text)
        self.assertEqual(review['reviewer'], ' 검토자 ')

    def test_invalid_hash_empty_identity_missing_and_unknown_fields_rejected(self):
        for changes in ({'text_sha256': 'b' * 64}, {'text': '-'}, {'text': None},
                        {'reviewer': ''}, {'reviewer': '  '}, {'reason': ''},
                        {'reviewer': 'x' * 121}, {'reason': 'x' * 2001},
                        {'selection': 'automatic'}, {'version': True},
                        {'reviewed_at_ms': True}, {'parent_result_sha256': 'not a hash'},
                        {'parent_run_id': ''}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate_review(self.review(**changes))
        for changed in (self.review(extra='forged'), {k: v for k, v in self.review().items() if k != 'reason'}):
            with self.assertRaises(ValueError):
                validate_review(changed)

    def test_utf8_limit_is_bytes_and_rejected_without_truncation(self):
        text = '가' * 33334
        with self.assertRaisesRegex(ValueError, '100,000'):
            validate_review(self.review(text=text, text_sha256=text_sha256(text)))
        allowed = '가' * 33333
        self.assertEqual(validate_review(self.review(text=allowed, text_sha256=text_sha256(allowed)))['text'], allowed)

    def test_selected_base_and_dialect_must_equal_retained_transcript(self):
        review = self.review()
        result = {'text_source': 'reviewed', 'transcript_review': review,
                  'reviewed_text': review['text'], 'asr_text': review['text'], 'dialect_text': '삼채오 킬로그램'}
        self.assertEqual(reviewed_text(result), review['text'])
        with self.assertRaises(ValueError):
            reviewed_text(result | {'asr_text': '삼치 3.5kg 잡았다.'})
        with self.assertRaises(ValueError):
            reviewed_text(result | {'reviewed_text': '다른 문장'})
        with self.assertRaises(ValueError):
            reviewed_text(result | {'text_source': 'base'})
        result['transcript_review'] = review | {'selection': 'dialect'}
        result['dialect_text'] = review['text']
        self.assertEqual(reviewed_text(result), review['text'])
        with self.assertRaises(ValueError):
            reviewed_text(result | {'dialect_error': 'ASR failed'})

    def test_corrected_text_is_independent_but_hash_and_provenance_remain_required(self):
        review = self.review(selection='corrected')
        result = {'text_source': 'reviewed', 'transcript_review': review,
                  'reviewed_text': review['text'], 'asr_text': None, 'dialect_text': '-'}
        self.assertEqual(reviewed_text(result), review['text'])
        with self.assertRaises(ValueError):
            reviewed_text(result | {'transcript_review': review | {'text_sha256': '0' * 64}})


class ReviewStorage(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.Processing()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root
        self.model = {'engine': 'test-extractor', 'version': 2, 'execution_mode': 'reviewed-transcript-extraction'}
        self.original = {
            'asr_text': '삼치 5kg 잡았다.', 'dialect_text': '삼채오 킬로그램 잡았다.',
            'base_segments': [{'start': 0, 'end': 1, 'text': '삼치 5kg 잡았다.'}],
            'dialect_segments': [{'start': 0, 'end': 1, 'text': '삼채오 킬로그램 잡았다.'}],
            'transcription_status': {'base': 'COMPLETED', 'dialect': 'COMPLETED'},
            'error': 'unsupported amount', 'duration_seconds': 1, 'sample_rate': 16000, 'audio_samples': 16000,
        }
        self.parent = self.fixture.queue()['jobs'][0]
        processing.start(self.root, self.parent['id'])
        self.parent = processing.fail(self.root, self.parent['id'], 'unsupported amount', self.original)
        self.original_history = self.history()

    def history(self):
        c = sqlite3.connect(self.root / 'processing.sqlite3')
        try:
            return {table: c.execute('SELECT * FROM ' + table + ' ORDER BY seq').fetchall()
                    for table in ('runs', 'run_events')}
        finally:
            c.close()

    def args(self, **changes):
        args = dict(source='source', work_id=self.parent['work_id'], parent_run_id=self.parent['id'],
                    head=self.fixture.head, evidence_sha=self.parent['evidence_sha'], selection='base', text=None,
                    reviewer='시험 검토자', reason='원음과 기본 전사를 대조한 시험', model_info=self.model)
        args.update(changes)
        return args

    def queue_review(self, **changes):
        return processing.enqueue_review(self.root, **self.args(**changes))

    def completed_result(self, job):
        review, original = job['input']['transcript_review'], job['input']['transcript_parent']
        return {**original, 'text_source': 'reviewed', 'reviewed_text': review['text'],
                'transcript_review': review, 'processing_mode': 'transcript_review',
                'asr_reused_from_run_id': review['parent_run_id'],
                'asr_parent_model_sha256': original['model_sha256']}

    def test_base_selection_appends_audit_and_preserves_source_audio_parent_and_defaults(self):
        before_audio = (self.root / 'audio' / (self.parent['audio_sha256'] + '.m4a')).read_bytes()
        job = self.queue_review()['jobs'][0]
        review, original = job['input']['transcript_review'], job['input']['transcript_parent']
        self.assertEqual(review['text'], self.original['asr_text'])
        self.assertEqual(review['parent_result_sha256'], store.fingerprint(self.original))
        self.assertEqual(review['selection'], 'base')
        self.assertEqual(original['dialect_text'], self.original['dialect_text'])
        self.assertEqual(original['base_segments'], self.original['base_segments'])
        self.assertIsNone(original['dialect_error'])
        self.assertEqual(job['review_status'], 'DRAFT')
        self.assertTrue(processing.review_is_current(self.root, job))
        self.assertEqual((self.root / 'records.sqlite3').read_bytes(), self.fixture.original_db)
        self.assertEqual((self.root / 'audio' / (self.parent['audio_sha256'] + '.m4a')).read_bytes(), before_audio)
        after = self.history()
        for table, rows in self.original_history.items():
            self.assertEqual(after[table][:len(rows)], rows)
        self.assertEqual(len(after['runs']), len(self.original_history['runs']) + 1)
        self.assertEqual(len(after['run_events']), len(self.original_history['run_events']) + 1)

    def test_dialect_and_corrected_choices_do_not_silently_change_source_text(self):
        with self.assertRaisesRegex(ValueError, '직접 수정'):
            self.queue_review(text='삼치 3.5kg 잡았다.')
        dialect = self.queue_review(selection='dialect')['jobs'][0]
        self.assertEqual(dialect['input']['transcript_review']['text'], self.original['dialect_text'])
        processing.start(self.root, dialect['id'])
        processing.finish(self.root, dialect['id'], self.completed_result(dialect))
        changed = '  삼치 5킬로그램 잡았다.\n'
        corrected = self.queue_review(parent_run_id=dialect['id'], selection='corrected', text=changed)['jobs'][0]
        self.assertEqual(corrected['input']['transcript_review']['text'], changed)
        self.assertEqual(corrected['input']['transcript_parent']['asr_text'], self.original['asr_text'])
        self.assertEqual(corrected['input']['transcript_review']['parent_run_id'], dialect['id'])

    def test_repeat_requests_skip_pending_running_and_completed_without_new_audit(self):
        job = self.queue_review()['jobs'][0]
        for phase in ('QUEUED', 'RUNNING', 'COMPLETED'):
            if phase == 'RUNNING':
                processing.start(self.root, job['id'])
            elif phase == 'COMPLETED':
                processing.finish(self.root, job['id'], self.completed_result(job))
            before = self.history()
            result = self.queue_review()
            self.assertEqual(result['jobs'], [])
            self.assertEqual(result['skipped'][0]['id'], job['id'])
            self.assertEqual(result['skipped'][0]['status'], phase)
            self.assertEqual(self.history(), before)

    def test_failed_same_request_retries_without_replacing_prior_attempt(self):
        first = self.queue_review()['jobs'][0]
        processing.start(self.root, first['id'])
        processing.fail(self.root, first['id'], 'extractor unavailable', self.completed_result(first))
        retry = self.queue_review()['jobs'][0]
        self.assertNotEqual(first['id'], retry['id'])
        self.assertEqual(retry['dedup_key'], first['dedup_key'])
        self.assertEqual(retry['attempt_no'], 2)
        old = next(run for run in processing.list_runs(self.root) if run['id'] == first['id'])
        self.assertEqual(old['status'], 'FAILED')
        self.assertEqual(old['error'], 'extractor unavailable')

    def test_recovered_queued_review_retains_originals_for_next_review(self):
        first = self.queue_review()['jobs'][0]
        recovered = processing.recover(self.root)
        self.assertEqual(next(run for run in recovered if run['id'] == first['id'])['result'], {})
        second = self.queue_review(parent_run_id=first['id'])['jobs'][0]
        self.assertEqual(second['input']['transcript_parent'],
                         first['input']['transcript_parent'] | {'run_id': first['id']})
        self.assertEqual(second['input']['transcript_review']['text'], self.original['asr_text'])
        self.assertEqual(second['input']['transcript_review']['parent_result_sha256'], store.fingerprint({}))

    def test_failed_empty_response_and_repeated_edits_keep_original_asr_model_identity(self):
        first = self.queue_review()['jobs'][0]
        processing.start(self.root, first['id'])
        processing.fail(self.root, first['id'], 'worker exited', {})
        second = self.queue_review(parent_run_id=first['id'], selection='corrected', text='직접 확인한 문장',
                                   model_info=self.model | {'version': 3})['jobs'][0]
        processing.start(self.root, second['id'])
        processing.finish(self.root, second['id'], self.completed_result(second))
        third = self.queue_review(parent_run_id=second['id'], selection='base',
                                  model_info=self.model | {'version': 4})['jobs'][0]
        for job in (first, second, third):
            self.assertEqual(job['input']['transcript_parent']['model_sha256'], self.parent['model_sha256'])
            self.assertEqual(job['input']['transcript_parent']['asr_text'], self.original['asr_text'])
            self.assertEqual(job['input']['transcript_parent']['dialect_text'], self.original['dialect_text'])
        self.assertEqual(third['input']['transcript_parent']['run_id'], second['id'])
        self.assertEqual(third['input']['transcript_review']['text'], self.original['asr_text'])

    def test_rejected_tampered_worker_output_cannot_poison_next_review_originals(self):
        first = self.queue_review()['jobs'][0]
        processing.start(self.root, first['id'])
        forged = self.completed_result(first) | {
            'asr_text': '위조한 기본 전사', 'dialect_text': '위조한 방언 전사',
            'dialect_error': 'forged error', 'base_segments': [],
            'dialect_segments': [], 'transcription_status': {}, 'duration_seconds': 99,
            'sample_rate': 1, 'audio_samples': 1,
        }
        with self.assertRaises(ValueError):
            processing.finish(self.root, first['id'], forged)
        processing.fail(self.root, first['id'], 'parent mismatch', forged)
        with self.assertRaisesRegex(ValueError, '직접 수정'):
            self.queue_review(parent_run_id=first['id'], text=forged['asr_text'])
        second = self.queue_review(parent_run_id=first['id'], selection='base')['jobs'][0]
        expected = first['input']['transcript_parent'] | {'run_id': first['id']}
        self.assertEqual(second['input']['transcript_parent'], expected)
        self.assertEqual(second['input']['transcript_review']['parent_result_sha256'], store.fingerprint(forged))
        self.assertEqual(second['input']['transcript_review']['text'], self.original['asr_text'])
        processing.start(self.root, second['id'])
        processing.fail(self.root, second['id'], 'worker exited', {})
        corrected = self.queue_review(parent_run_id=second['id'], selection='corrected', text='검토해 수정한 문장')['jobs'][0]
        self.assertEqual(corrected['input']['transcript_parent'], expected | {'run_id': second['id']})
        self.assertEqual(corrected['input']['transcript_review']['selection'], 'corrected')

    def test_concurrent_identical_review_queues_only_one_attempt(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            results = list(pool.map(lambda _: self.queue_review(), range(3)))
        self.assertEqual(sum(len(result['jobs']) for result in results), 1)
        self.assertEqual(sum(len(result['skipped']) for result in results), 2)

    def test_identical_audio_under_distinct_recordings_keeps_separate_reviews(self):
        other = next(run for run in processing.list_runs(self.root) if run['recording_id'] != self.parent['recording_id'])
        processing.start(self.root, other['id'])
        processing.fail(self.root, other['id'], 'unsupported amount', self.original)
        first = self.queue_review()['jobs'][0]
        second = self.queue_review(parent_run_id=other['id'], work_id=other['work_id'],
                                   evidence_sha=other['evidence_sha'])['jobs'][0]
        self.assertEqual(first['audio_sha256'], second['audio_sha256'])
        self.assertNotEqual(first['recording_id'], second['recording_id'])
        self.assertNotEqual(first['dedup_key'], second['dedup_key'])

    def test_linked_question_is_preserved_and_task_phase_audio_context_are_rechecked(self):
        self.fixture.next_snapshot(self.fixture.question_fixture)
        parents = self.fixture.queue()['jobs']
        self.fixture.complete(parents)
        self.parent = next(run for run in processing.list_runs(self.root)
                           if run['id'] == next(job['id'] for job in parents if job['recording_id'] == '0001'))
        job = self.queue_review()['jobs'][0]
        context = job['input']['question_context']
        self.assertEqual(context['target_field'], 'haul_quantity')
        self.assertEqual(context, self.parent['input']['question_context'])
        self.assertNotIn('field_fingerprint', context)
        self.assertTrue(processing.review_is_current(self.root, job))
        changed = copy.deepcopy(job)
        changed['input']['question_context'].update(target_field='redeploy_quantity', question='다시 놓은 통발은 몇 개입니까?')
        self.assertFalse(processing.review_is_current(self.root, changed))
        changed = copy.deepcopy(job)
        changed['input']['recording']['phase'] = 'END'
        self.assertFalse(processing.review_is_current(self.root, changed))
        self.assertFalse(processing.review_is_current(self.root, job | {'audio_sha256': '0' * 64}))
        self.assertFalse(processing.review_is_current(self.root, job | {'work_id': 'other-work'}))
        self.assertFalse(processing.review_is_current(self.root, {'input': {'transcript_review': {}}}))

    def test_wrong_parent_source_work_head_evidence_and_missing_identity_do_not_append(self):
        other = next(run for run in processing.list_runs(self.root) if run['recording_id'] != self.parent['recording_id'])
        cases = ({'parent_run_id': 'absent'}, {'source': 'another source'}, {'work_id': 'other work'},
                 {'head': 'wrong head'}, {'evidence_sha': 'wrong evidence'}, {'reviewer': ''}, {'reason': ' '},
                 {'selection': 'corrected', 'text': '-'}, {'selection': 'corrected', 'text': None},
                 {'parent_run_id': other['id'], 'work_id': other['work_id']})
        for changes in cases:
            before = self.history()
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.queue_review(**changes)
            self.assertEqual(self.history(), before)

    def test_newer_processing_prevents_applying_old_parent_or_reusing_old_review(self):
        review = self.queue_review()['jobs'][0]
        processing.start(self.root, review['id'])
        processing.finish(self.root, review['id'], self.completed_result(review))
        newer = processing.enqueue(self.root, 'source', self.parent['work_id'], {'engine': 'new-ASR'})['jobs'][0]
        before = self.history()
        with self.assertRaisesRegex(ValueError, '최신'):
            self.queue_review()
        self.assertEqual(self.history(), before)
        self.assertEqual(newer['recording_id'], self.parent['recording_id'])

    def test_new_source_head_and_changed_evidence_invalidate_review(self):
        job = self.queue_review()['jobs'][0]
        self.fixture.next_snapshot()
        self.assertFalse(processing.review_is_current(self.root, job))
        with self.assertRaisesRegex(ValueError, '갱신'):
            self.queue_review(head=self.parent['snapshot_sha'])
        self.fixture.next_snapshot(lambda data: data['recordings'][0].update(label='changed evidence'))
        with self.assertRaisesRegex(ValueError, '갱신|일치'):
            self.queue_review()
        self.assertFalse(processing.review_is_current(self.root, job))

    def test_completion_rejects_modified_review_or_originals_and_normal_job_forgery(self):
        job = self.queue_review()['jobs'][0]
        processing.start(self.root, job['id'])
        correct = self.completed_result(job)
        bad_review = copy.deepcopy(correct)
        bad_review['transcript_review']['reason'] = 'changed after enqueue'
        for bad in (bad_review, correct | {'reviewed_text': 'invented'},
                    correct | {'dialect_text': 'erased original error'},
                    correct | {'text_source': 'base'}):
            with self.assertRaises(ValueError):
                processing.finish(self.root, job['id'], bad)
        self.assertEqual(processing.finish(self.root, job['id'], correct)['status'], 'COMPLETED')
        ordinary = next(run for run in processing.list_runs(self.root) if run['status'] == 'QUEUED')
        processing.start(self.root, ordinary['id'])
        with self.assertRaisesRegex(ValueError, '일반 전사'):
            processing.finish(self.root, ordinary['id'], correct)

    def test_corrected_review_can_complete_after_asr_itself_failed(self):
        parent = processing.enqueue(self.root, 'source', self.parent['work_id'], {'engine': 'failed-ASR'})['jobs'][0]
        processing.start(self.root, parent['id'])
        processing.fail(self.root, parent['id'], 'audio decoding failed', {})
        with self.assertRaises(ValueError):
            self.queue_review(parent_run_id=parent['id'])
        job = self.queue_review(parent_run_id=parent['id'], selection='corrected', text='원음을 듣고 적은 문장')['jobs'][0]
        original = job['input']['transcript_parent']
        self.assertIsNone(original['asr_text'])
        self.assertEqual(original['base_segments'], [])
        processing.start(self.root, job['id'])
        result = processing.finish(self.root, job['id'], self.completed_result(job))
        self.assertEqual(result['status'], 'COMPLETED')
        self.assertIsNone(result['asr_text'])
        self.assertEqual(result['result']['reviewed_text'], '원음을 듣고 적은 문장')

    def test_failed_dialect_cannot_be_selected_but_manual_correction_is_explicit(self):
        parent = processing.enqueue(self.root, 'source', self.parent['work_id'], {'engine': 'failed-dialect'})['jobs'][0]
        processing.start(self.root, parent['id'])
        original = self.original | {'dialect_error': 'adapter failed'}
        processing.fail(self.root, parent['id'], 'adapter failed', original)
        with self.assertRaisesRegex(ValueError, '실패한 방언'):
            self.queue_review(parent_run_id=parent['id'], selection='dialect')
        job = self.queue_review(parent_run_id=parent['id'], selection='corrected', text=self.original['dialect_text'])['jobs'][0]
        self.assertEqual(job['input']['transcript_review']['selection'], 'corrected')
        self.assertEqual(job['input']['transcript_parent']['dialect_error'], 'adapter failed')


if __name__ == '__main__':
    unittest.main(verbosity=2)
