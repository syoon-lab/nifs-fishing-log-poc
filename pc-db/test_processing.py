"""Processing storage tests use temporary DBs and no model/GPU dependencies."""
import concurrent.futures
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

import processing
import store


class Processing(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        # Two legitimate recording identifiers intentionally have identical bytes.
        audio = b'identical bytes, independently recorded identifiers'
        import hashlib
        self.audio_sha = hashlib.sha256(audio).hexdigest()
        (self.root / 'audio').mkdir()
        (self.root / 'audio' / (self.audio_sha + '.m4a')).write_bytes(audio)
        d = {
            'schema_version': 1, 'exported_at_ms': 2,
            'time_basis': 'test', 'coordinate_system': 'WGS84',
            'sessions': [{'id': 'session', 'started_ms': 0, 'ended_ms': 1}],
            'recordings': [dict(id=rid, session_id='session', started_ms=0,
                                ended_ms=1, label=rid, status='COMPLETED',
                                start_location_id=None, end_location_id=None)
                           for rid in ('0001', '0002')],
            'locations': [], 'events': [],
        }
        self.head = store.fingerprint(d)
        c = store.connect(self.root)
        try:
            c.execute('INSERT INTO sources VALUES(?,?,?)', ('source', self.head, 1))
            metadata = {k: d[k] for k in store.META}
            metadata['tables'] = [table for table in store.TABLES if table in d]
            c.execute('INSERT INTO snapshots VALUES(?,?,?,?,?,?,?,?,?,?,?)', (
                self.head, 'source', None, 1, 'fixture.zip', 1, 1,
                store.canonical(metadata), '{}', '{}', 'temporary fixture'))
            for table in metadata['tables']:
                for i, row in enumerate(d[table]):
                    c.execute('INSERT INTO raw_rows VALUES(?,?,?,?,?)',
                              (self.head, table, row['id'], i, store.canonical(row)))
            for rid in ('0001', '0002'):
                c.execute('INSERT INTO audio VALUES(?,?,?,?,?)',
                          (self.head, rid, self.audio_sha, len(audio), 'audio/' + rid + '.m4a'))
            for wid, w in store.works(d).items():
                c.execute('INSERT INTO work_index VALUES(?,?,?)', (self.head, wid, w['fingerprint']))
            c.commit()
        finally:
            c.close()
        self.original_db = (self.root / 'records.sqlite3').read_bytes()
        self.model = {'engine': 'test-engine', 'model_sha256': 'abc', 'version': 1}

    def queue(self):
        return processing.enqueue(self.root, 'source', model_info=self.model)

    def next_snapshot(self, change=lambda data: None):
        """Append a source revision; never UPDATE source rows in earlier snapshots."""
        c = store.connect(self.root)
        try:
            d = store.snapshot(c, self.head)
            d['exported_at_ms'] += 1
            change(d)
            new_head = store.fingerprint(d)
            receipt = c.execute('SELECT receipt_no FROM sources WHERE id=?', ('source',)).fetchone()[0] + 1
            metadata = {k: d[k] for k in store.META}
            metadata['tables'] = [table for table in store.TABLES if table in d]
            c.execute('INSERT INTO snapshots VALUES(?,?,?,?,?,?,?,?,?,?,?)', (
                new_head, 'source', self.head, receipt, 'fixture.zip', receipt, 1,
                store.canonical(metadata), '{}', '{}', 'temporary source revision'))
            for table in metadata['tables']:
                for i, row in enumerate(d[table]):
                    c.execute('INSERT INTO raw_rows VALUES(?,?,?,?,?)',
                              (new_head, table, row['id'], i, store.canonical(row)))
            c.execute('INSERT INTO audio SELECT ?,recording_id,sha,bytes,path '
                      'FROM audio WHERE snapshot_sha=?', (new_head, self.head))
            for wid, w in store.works(d).items():
                c.execute('INSERT INTO work_index VALUES(?,?,?)', (new_head, wid, w['fingerprint']))
            c.execute('UPDATE sources SET head=?,receipt_no=? WHERE id=?', (new_head, receipt, 'source'))
            c.commit()
            self.head = new_head
        finally:
            c.close()

    def test_phase_change_reprocesses_but_unchanged_export_and_unused_metadata_deduplicate(self):
        self.next_snapshot(lambda d: d['recordings'][0].update(phase='START'))
        first = self.queue()['jobs']
        for job in first:
            processing.start(self.root, job['id'])
            processing.finish(self.root, job['id'], {'asr_text': '전사'})
        self.next_snapshot()
        self.assertEqual(self.queue()['jobs'], [])
        self.next_snapshot(lambda d: d['recordings'][0].update(label='unused metadata changed'))
        self.assertEqual(self.queue()['jobs'], [])
        self.next_snapshot(lambda d: d['recordings'][0].update(phase='END'))
        before = (self.root / 'records.sqlite3').read_bytes()
        changed = self.queue()
        self.assertEqual(len(changed['jobs']), 1)
        self.assertEqual(len(changed['skipped']), 1)
        new = changed['jobs'][0]
        self.assertEqual(new['recording_id'], '0001')
        self.assertEqual(new['input']['recording']['phase'], 'END')
        old = next(r for r in processing.list_runs(self.root) if r['id'] == first[0]['id'])
        self.assertEqual(old['input']['recording']['phase'], 'START')
        self.assertFalse(old['current'])
        processing.start(self.root, new['id'])
        processing.finish(self.root, new['id'], {'asr_text': '다시 전사'})
        self.assertEqual(self.queue()['jobs'], [])
        self.assertEqual(before, (self.root / 'records.sqlite3').read_bytes())

    def test_missing_and_explicit_legacy_phase_deduplicate(self):
        jobs = self.queue()['jobs']
        for job in jobs:
            processing.start(self.root, job['id'])
            processing.finish(self.root, job['id'], {'asr_text': '전사 결과'})
        self.assertEqual(len(self.queue()['skipped']), 2)
        # An absent phase and explicit LEGACY are identical worker inputs.
        self.next_snapshot(lambda d: d['recordings'][0].update(phase='LEGACY'))
        self.assertEqual(self.queue()['jobs'], [])
        self.next_snapshot(lambda d: d['recordings'][0].update(phase='START'))
        self.assertEqual(len(self.queue()['jobs']), 1)

    def question_fixture(self, d):
        d['operations'] = [{'id': 'operation', 'session_id': 'session', 'started_ms': 0,
                             'ended_ms': 1, 'status': 'COMPLETED'}]
        for r in d['recordings']:
            r.update(operation_id='operation', phase='WORK')
        detail = {'version': 1, 'phase': 'WORK', 'key': 'haul_quantity',
                  'text': '이번에 걷은 통발은 몇 개입니까?', 'audio': 'haul_pot', 'species': None,
                  'field_fingerprint': '["haul_quantity",null]', 'sources': []}
        d['events'] = [{'id': 'question', 'recording_id': '0001', 'session_id': 'session',
                        'operation_id': 'operation', 'type': 'FIELD_ANSWER_CONTEXT',
                        'time_ms': 0, 'detail': store.canonical(detail)}]

    def complete(self, jobs):
        for job in jobs:
            processing.start(self.root, job['id'])
            processing.finish(self.root, job['id'], {'asr_text': '서른 개입니다.'})

    def test_question_context_changes_identity_and_currentness_only_when_consumed(self):
        self.next_snapshot(self.question_fixture)
        first = self.queue()['jobs']
        self.complete(first)
        answer = next(j for j in first if j['recording_id'] == '0001')
        self.assertEqual(answer['input']['question_context']['target_field'], 'haul_quantity')
        self.assertNotIn('question_context', next(j for j in first if j['recording_id'] == '0002')['input'])
        # Field fingerprints may contain phone values; they are never worker input.
        def unused_change(d):
            detail = json.loads(d['events'][0]['detail'])
            detail['field_fingerprint'] = '["haul_quantity",999]'
            detail['sources'] = [{'recording_id': '0002', 'old_answer': 999}]
            d['events'][0].update(time_ms=999, detail=store.canonical(detail))
        self.next_snapshot(unused_change)
        self.assertEqual(self.queue()['jobs'], [])
        old = next(r for r in processing.list_runs(self.root) if r['id'] == answer['id'])
        self.assertTrue(old['current'])
        self.assertFalse(old['evidence_current'])
        self.assertNotIn('999', store.canonical(old['input']['question_context']))
        def changed_question(d):
            detail = json.loads(d['events'][0]['detail'])
            detail.update(key='redeploy_quantity', audio='reset_pot', text='다시 놓은 통발은 몇 개입니까?')
            d['events'][0]['detail'] = store.canonical(detail)
        self.next_snapshot(changed_question)
        before = (self.root / 'records.sqlite3').read_bytes()
        changed = self.queue()
        self.assertEqual(len(changed['jobs']), 1)
        self.assertEqual(len(changed['skipped']), 1)
        new = changed['jobs'][0]
        self.assertEqual(new['recording_id'], '0001')
        self.assertEqual(new['input']['question_context']['target_field'], 'redeploy_quantity')
        old = next(r for r in processing.list_runs(self.root) if r['id'] == answer['id'])
        self.assertFalse(old['current'])
        self.assertEqual(old['input']['question_context']['target_field'], 'haul_quantity')
        self.complete(changed['jobs'])
        self.assertEqual(self.queue()['jobs'], [])
        self.assertEqual(before, (self.root / 'records.sqlite3').read_bytes())

    def test_adding_and_removing_consumed_link_does_not_modify_prior_runs(self):
        def operation_only(d):
            self.question_fixture(d)
            d['events'] = []
        self.next_snapshot(operation_only)
        initial = self.queue()['jobs']
        self.complete(initial)
        self.next_snapshot(self.question_fixture)
        second = self.queue()['jobs']
        self.assertEqual(len(second), 1)
        self.complete(second)
        self.assertFalse(next(r for r in processing.list_runs(self.root)
                              if r['id'] == next(j['id'] for j in initial if j['recording_id'] == '0001'))['current'])
        self.next_snapshot(lambda d: d['events'].clear())
        # No-context input is again identical to its original completed run.
        self.assertEqual(self.queue()['jobs'], [])
        self.assertFalse(next(r for r in processing.list_runs(self.root) if r['id'] == second[0]['id'])['current'])
        self.assertEqual(len(processing.list_runs(self.root)), 3)

    def test_ambiguous_link_is_not_consumed_and_preserves_no_context_dedup(self):
        def ambiguous(d):
            self.question_fixture(d)
            d['events'].append(d['events'][0] | {'id': 'second-question'})
        self.next_snapshot(ambiguous)
        jobs = self.queue()['jobs']
        self.assertTrue(all('question_context' not in job['input'] for job in jobs))
        self.complete(jobs)
        self.next_snapshot(lambda d: d['events'][1].update(time_ms=999))
        self.assertEqual(self.queue()['jobs'], [])

    def test_distinct_recordings_same_bytes_and_repeat_queue(self):
        first = self.queue()
        self.assertEqual(len(first['jobs']), 2)
        self.assertEqual({j['recording_id'] for j in first['jobs']}, {'0001', '0002'})
        second = self.queue()
        self.assertEqual(second['jobs'], [])
        self.assertEqual(len(second['skipped']), 2)
        self.assertEqual(len(processing.pending(self.root)), 2)
        self.assertEqual(self.original_db, (self.root / 'records.sqlite3').read_bytes())

    def test_completed_run_skips_but_changed_model_creates_attempt(self):
        first = self.queue()['jobs'][0]
        processing.start(self.root, first['id'])
        processing.finish(self.root, first['id'], {'asr_text': '원문', 'dialect_text': None,
                                                  'extraction': {'status': 'DRAFT', 'quantity': None}})
        self.assertFalse(self.queue()['jobs'])
        self.model['version'] = 2
        self.assertEqual(len(self.queue()['jobs']), 2)
        completed = next(r for r in processing.list_runs(self.root) if r['id'] == first['id'])
        self.assertEqual(completed['asr_text'], '원문')
        self.assertIsNone(completed['extraction']['quantity'])
        self.assertEqual(completed['review_status'], 'DRAFT')
        self.assertEqual(completed['snapshot_sha'], self.head)
        self.assertTrue(completed['current'])

    def test_failed_partial_results_and_retry_preserve_history(self):
        first = self.queue()['jobs'][0]
        processing.start(self.root, first['id'])
        processing.fail(self.root, first['id'], 'invalid extraction JSON',
                        result={'asr_text': '보존한 전사', 'extractor_raw': 'invalid'})
        retry = self.queue()['jobs']
        self.assertEqual(len(retry), 1)
        self.assertEqual(retry[0]['attempt_no'], 2)
        self.assertNotEqual(retry[0]['id'], first['id'])
        failed = next(r for r in processing.list_runs(self.root) if r['id'] == first['id'])
        self.assertEqual(failed['asr_text'], '보존한 전사')
        self.assertEqual(failed['result']['extractor_raw'], 'invalid')
        self.assertEqual(failed['status'], 'FAILED')
        self.assertEqual(failed['error'], 'invalid extraction JSON')
        self.assertEqual(self.original_db, (self.root / 'records.sqlite3').read_bytes())

    def test_input_hash_failure_can_be_recorded_before_model_start(self):
        first = self.queue()['jobs'][0]
        processing.fail(self.root, first['id'], '원음 해시 불일치')
        self.assertIsNone(processing.start(self.root, first['id']))
        self.assertEqual(len(self.queue()['jobs']), 1)

    def test_atomic_claim_and_duplicate_enqueue_across_connections(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            batches = list(pool.map(lambda _: self.queue(), range(4)))
        self.assertEqual(sum(len(b['jobs']) for b in batches), 2)
        run_id = processing.pending(self.root)[0]['id']
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            claims = list(pool.map(lambda _: processing.start(self.root, run_id), range(4)))
        self.assertEqual(sum(c is not None for c in claims), 1)

    def test_immutable_events_and_terminal_results(self):
        first = self.queue()['jobs'][0]
        processing.start(self.root, first['id'])
        processing.finish(self.root, first['id'], {'asr_text': ''})
        with self.assertRaises(ValueError):
            processing.finish(self.root, first['id'], {'asr_text': 'overwrite'})
        with self.assertRaises(ValueError):
            processing.fail(self.root, first['id'], 'overwrite')
        c = processing.connect(self.root)
        try:
            for sql in ('DELETE FROM runs', 'DELETE FROM run_events',
                        "UPDATE runs SET recording_id='other'",
                        "UPDATE run_events SET result_json='{}'"):
                with self.assertRaises(sqlite3.IntegrityError):
                    c.execute(sql)
            self.assertEqual(c.execute('SELECT COUNT(*) FROM run_events WHERE run_id=?',
                                       (first['id'],)).fetchone()[0], 3)
        finally:
            c.close()

    def test_recovery_retains_interrupted_attempts_and_does_not_touch_completed(self):
        jobs = self.queue()['jobs']
        processing.start(self.root, jobs[0]['id'])
        recovered = processing.recover(self.root)
        self.assertEqual(len(recovered), 2)
        self.assertTrue(all(r['status'] == 'INTERRUPTED' for r in recovered))
        self.assertEqual(processing.recover(self.root), [])
        self.assertEqual(len(self.queue()['jobs']), 2)
        self.assertEqual(processing.counts(self.root)['INTERRUPTED'], 2)

    def test_work_filter_and_missing_source_do_not_invent_records(self):
        q = processing.enqueue(self.root, 'source', 'legacy:0001', self.model)
        self.assertEqual(len(q['jobs']), 1)
        self.assertEqual(processing.list_runs(self.root, 'source', 'legacy:0002'), [])
        self.assertEqual(len(processing.list_runs(self.root, 'source', 'legacy:0001')), 1)
        with self.assertRaises(ValueError):
            processing.enqueue(self.root, 'missing', model_info=self.model)
        with self.assertRaises(ValueError):
            processing.enqueue(self.root, 'source', 'missing', self.model)
        self.assertEqual(self.original_db, (self.root / 'records.sqlite3').read_bytes())


if __name__ == '__main__':
    unittest.main()
