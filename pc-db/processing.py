"""Append-only PC inference attempts, separate from the immutable source database.

The worker owns model execution. This module only records inputs and state changes;
it never writes records.sqlite3, original audio, or human confirmation history.
"""
import json
import sqlite3
import uuid
from pathlib import Path

import store
from answer_context import build_question_context, consumed_context
from transcript_review import reviewed_text, text_sha256, validate_review

ACTIVE = ('QUEUED', 'RUNNING')
TERMINAL = ('COMPLETED', 'FAILED', 'INTERRUPTED')


def connect(root):
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    c = sqlite3.connect(root / 'processing.sqlite3', timeout=30)
    c.row_factory = sqlite3.Row
    c.execute('PRAGMA foreign_keys=ON')
    c.executescript('''
    CREATE TABLE IF NOT EXISTS runs(
        seq INTEGER PRIMARY KEY AUTOINCREMENT,
        id TEXT NOT NULL UNIQUE,
        dedup_key TEXT NOT NULL,
        attempt_no INTEGER NOT NULL,
        source_id TEXT NOT NULL,
        work_id TEXT NOT NULL,
        recording_id TEXT NOT NULL,
        snapshot_sha TEXT NOT NULL,
        evidence_sha TEXT NOT NULL,
        audio_sha256 TEXT NOT NULL,
        audio_bytes INTEGER NOT NULL,
        model_sha256 TEXT NOT NULL,
        model_info TEXT NOT NULL,
        input_json TEXT NOT NULL,
        created_ms INTEGER NOT NULL,
        UNIQUE(dedup_key, attempt_no)
    );
    CREATE TABLE IF NOT EXISTS run_events(
        seq INTEGER PRIMARY KEY AUTOINCREMENT,
        run_id TEXT NOT NULL REFERENCES runs(id),
        status TEXT NOT NULL CHECK(status IN
          ('QUEUED','RUNNING','COMPLETED','FAILED','INTERRUPTED')),
        at_ms INTEGER NOT NULL,
        result_json TEXT NOT NULL,
        error TEXT
    );
    CREATE INDEX IF NOT EXISTS run_events_lookup ON run_events(run_id, seq);
    CREATE INDEX IF NOT EXISTS run_recording_lookup ON runs(source_id, recording_id);
    CREATE VIEW IF NOT EXISTS current_runs AS
      SELECT r.*, e.status, e.at_ms AS updated_ms, e.result_json, e.error
      FROM runs r JOIN run_events e ON e.run_id=r.id
      WHERE e.seq=(SELECT MAX(x.seq) FROM run_events x WHERE x.run_id=r.id);
    ''')
    for table in ('runs', 'run_events'):
        for action in ('UPDATE', 'DELETE'):
            c.execute(f"CREATE TRIGGER IF NOT EXISTS immutable_{table}_{action} "
                      f"BEFORE {action} ON {table} BEGIN "
                      "SELECT RAISE(ABORT,'append-only processing history'); END")
    return c


def source_recordings(root, source=None, work_id=None):
    """Read a consistent source snapshot using SQLite's read-only connection mode."""
    p = (Path(root) / 'records.sqlite3').resolve()
    if not p.is_file():
        if source is not None:
            raise ValueError('가져온 원자료가 없습니다.')
        return []
    c = sqlite3.connect(p.as_uri() + '?mode=ro', uri=True, timeout=30)
    c.row_factory = sqlite3.Row
    try:
        c.execute('BEGIN')
        sources = list(c.execute('SELECT * FROM sources' +
                                (' WHERE id=?' if source is not None else ''),
                                (source,) if source is not None else ()))
        if source is not None and not sources:
            raise ValueError('자료군 없음')
        rows = []
        found_work = False
        for s in sources:
            if not s['head']:
                continue
            works = store.works(store.snapshot(c, s['head']))
            audio = {r['recording_id']: r for r in c.execute(
                'SELECT * FROM audio WHERE snapshot_sha=?', (s['head'],))}
            for wid, w in works.items():
                if work_id is not None and wid != work_id:
                    continue
                found_work = True
                for r in w['evidence']['recordings']:
                    a = audio.get(r['id'])
                    if a is None:
                        raise ValueError('원음 연결 없음: ' + r['id'])
                    question_context = build_question_context(
                        s['id'], wid, r, w['evidence']['operation'], w['evidence']['events'])
                    rows.append({
                        'source_id': s['id'], 'work_id': wid,
                        'recording_id': r['id'], 'snapshot_sha': s['head'],
                        'evidence_sha': w['fingerprint'],
                        'audio_sha256': a['sha'], 'audio_bytes': a['bytes'],
                        'input': {
                            'recording': r,
                            'operation': w['evidence']['operation'],
                            'session': w['evidence']['session'],
                            'events': [e for e in w['evidence']['events']
                                       if e.get('recording_id') == r['id']],
                            **({'question_context': question_context} if question_context is not None else {}),
                        },
                    })
        if work_id is not None and not found_work:
            raise ValueError('현재 판본의 작업이 아닙니다.')
        return rows
    finally:
        c.close()


def _decode(row):
    d = dict(row)
    d['model_info'] = json.loads(d['model_info'])
    d['input'] = json.loads(d.pop('input_json'))
    result = json.loads(d.pop('result_json'))
    d['result'] = result
    for field in ('asr_text', 'dialect_text', 'extraction', 'segments'):
        d[field] = result.get(field)
    d['review_status'] = 'DRAFT'
    d['finished_ms'] = d['updated_ms'] if d['status'] in TERMINAL else None
    d['audio_path'] = 'audio/' + d['audio_sha256'] + '.m4a'
    return d


def _one(c, run_id):
    row = c.execute('SELECT * FROM current_runs WHERE id=?', (run_id,)).fetchone()
    if row is None:
        raise ValueError('PC 처리 이력이 없습니다.')
    return _decode(row)


def enqueue(root, source, work_id=None, model_info=None):
    """Queue each recording once per audio/phase/model identity; failed attempts can retry.

    Returns newly queued ``jobs`` and already queued/running/completed ``skipped``.
    Different recording identifiers are never collapsed because their bytes match.
    """
    if not isinstance(model_info, dict) or not model_info:
        raise ValueError('모델 실행 정보를 먼저 확인하세요.')
    model_json = store.canonical(model_info)
    model_sha = store.fingerprint(model_info)
    inputs = source_recordings(root, source, work_id)
    c = connect(root)
    try:
        c.execute('BEGIN IMMEDIATE')
        jobs, skipped = [], []
        for item in inputs:
            identity = [item['source_id'], item['recording_id'], item['audio_sha256'], model_sha]
            phase = item['input']['recording'].get('phase', 'LEGACY')
            context = consumed_context(item['input'])
            key = store.fingerprint(identity + [phase] + ([context] if context is not None else []))
            previous = c.execute('SELECT * FROM current_runs WHERE dedup_key=? '
                                 'ORDER BY attempt_no DESC LIMIT 1', (key,)).fetchone()
            if previous is not None and previous['status'] in (*ACTIVE, 'COMPLETED'):
                skipped.append(_decode(previous))
                continue
            rid = uuid.uuid4().hex
            created = store.now()
            c.execute('''INSERT INTO runs(
                id,dedup_key,attempt_no,source_id,work_id,recording_id,snapshot_sha,
                evidence_sha,audio_sha256,audio_bytes,model_sha256,model_info,input_json,created_ms)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)''', (
                rid, key, previous['attempt_no'] + 1 if previous else 1,
                item['source_id'], item['work_id'], item['recording_id'],
                item['snapshot_sha'], item['evidence_sha'], item['audio_sha256'],
                item['audio_bytes'], model_sha, model_json,
                store.canonical(item['input']), created))
            c.execute('INSERT INTO run_events(run_id,status,at_ms,result_json) '
                      "VALUES(?,'QUEUED',?,'{}')", (rid, created))
            jobs.append(_one(c, rid))
        c.commit()
        return {'jobs': jobs, 'skipped': skipped}
    except Exception:
        c.rollback()
        raise
    finally:
        c.close()


def pending(root):
    if not (Path(root) / 'processing.sqlite3').is_file():
        return []
    c = connect(root)
    try:
        return [_decode(r) for r in c.execute(
            "SELECT * FROM current_runs WHERE status='QUEUED' ORDER BY seq")]
    finally:
        c.close()


def _same_recording_input(run, item):
    """Match the recording and consumed context, allowing a later equivalent export."""
    return (all(run.get(key) == item.get(key) for key in
                ('source_id', 'work_id', 'recording_id', 'audio_sha256', 'audio_bytes'))
            and run['input']['recording'].get('phase', 'LEGACY') ==
                item['input']['recording'].get('phase', 'LEGACY')
            and consumed_context(run['input']) == consumed_context(item['input']))


def review_is_current(root, job):
    """A review belongs to its exact source head and evidence, before and after execution."""
    try:
        validate_review(job['input']['transcript_review'])
        items = source_recordings(root, job['source_id'], job['work_id'])
        item = next((row for row in items if row['recording_id'] == job['recording_id']), None)
        return (item is not None and _same_recording_input(job, item)
                and job['snapshot_sha'] == item['snapshot_sha']
                and job['evidence_sha'] == item['evidence_sha'])
    except (AttributeError, KeyError, TypeError, ValueError):
        return False


def enqueue_review(root, source, work_id, parent_run_id, head, evidence_sha,
                   selection, text, reviewer, reason, model_info):
    """Append one explicitly reviewed extraction attempt without replacing ASR or source rows."""
    if not isinstance(model_info, dict) or not model_info:
        raise ValueError('모델 실행 정보를 먼저 확인하세요.')
    if any(not isinstance(value, str) or not value.strip()
           for value in (source, work_id, parent_run_id, head, evidence_sha)):
        raise ValueError('검토할 자료군·작업·처리 번호·원자료 판본이 필요합니다.')
    model_json = store.canonical(model_info)
    model_sha = store.fingerprint(model_info)
    c = connect(root)
    try:
        c.execute('BEGIN IMMEDIATE')
        parent = _one(c, parent_run_id)
        if parent['status'] not in TERMINAL:
            raise ValueError('진행 중인 전사는 검토할 수 없습니다.')
        if parent['source_id'] != source or parent['work_id'] != work_id:
            raise ValueError('다른 자료군이나 작업의 전사입니다.')
        items = source_recordings(root, source, work_id)
        item = next((row for row in items if row['recording_id'] == parent['recording_id']), None)
        if item is None or item['snapshot_sha'] != head or item['evidence_sha'] != evidence_sha:
            raise ValueError('원자료 판본이나 작업 근거가 갱신되었습니다. 작업을 다시 열어 확인하세요.')
        if not _same_recording_input(parent, item) or parent['evidence_sha'] != item['evidence_sha']:
            raise ValueError('현재 원음·질문 연결·작업 근거와 일치하는 전사만 검토할 수 있습니다.')
        original = parent['result']
        original_model_sha = parent['model_sha256']
        if 'transcript_review' in parent['input']:
            prior_review = validate_review(parent['input']['transcript_review'])
            frozen = parent['input'].get('transcript_parent')
            expected_fields = {'run_id', 'model_sha256', 'asr_text', 'dialect_text', 'dialect_error',
                               'base_segments', 'dialect_segments', 'transcription_status',
                               'duration_seconds', 'sample_rate', 'audio_samples'}
            if (not isinstance(frozen, dict) or set(frozen) != expected_fields
                    or frozen['run_id'] != prior_review['parent_run_id']
                    or not isinstance(frozen['model_sha256'], str) or not frozen['model_sha256']):
                raise ValueError('이전 검토에 보관한 원래 전사 연결이 올바르지 않습니다.')
            if (any(frozen[name] is not None and not isinstance(frozen[name], str)
                    for name in ('asr_text', 'dialect_text', 'dialect_error'))
                    or any(not isinstance(frozen[name], list) for name in ('base_segments', 'dialect_segments'))):
                raise ValueError('이전 검토에 보관한 원래 전사 형식이 올바르지 않습니다.')
            reviewed_text(frozen | {'text_source': 'reviewed', 'transcript_review': prior_review,
                                    'reviewed_text': prior_review['text']})
            # A reviewed run does not execute ASR. Its result may be absent after
            # interruption or may contain rejected worker output. Only its frozen
            # input can supply original transcripts to another review.
            original = frozen
            original_model_sha = frozen['model_sha256']
        if selection in ('base', 'dialect'):
            selected = original.get('asr_text' if selection == 'base' else 'dialect_text')
            if text is not None and text != selected:
                raise ValueError('선택한 전사를 바꾸려면 직접 수정을 선택하세요.')
            if selection == 'dialect' and original.get('dialect_error'):
                raise ValueError('실패한 방언 전사를 선택할 수 없습니다.')
            text = selected
        created = store.now()
        review = {
            'version': 1, 'parent_run_id': parent_run_id,
            'parent_result_sha256': store.fingerprint(parent['result']), 'selection': selection,
            'text': text, 'text_sha256': text_sha256(text) if isinstance(text, str) else '',
            'reviewer': reviewer, 'reason': reason, 'reviewed_at_ms': created,
        }
        validate_review(review)
        # Exact head is intentional: a review made before a new export is not reused
        # as though it had been submitted against the new export.
        identity = {key: item[key] for key in
                    ('source_id', 'work_id', 'recording_id', 'snapshot_sha', 'evidence_sha', 'audio_sha256')}
        identity.update(model_sha256=model_sha,
                        phase=item['input']['recording'].get('phase', 'LEGACY'),
                        question_context=consumed_context(item['input']),
                        transcript_review={key: value for key, value in review.items() if key != 'reviewed_at_ms'})
        key = store.fingerprint(identity)
        previous_row = c.execute('SELECT * FROM current_runs WHERE dedup_key=? '
                                 'ORDER BY attempt_no DESC LIMIT 1', (key,)).fetchone()
        previous = _decode(previous_row) if previous_row is not None else None
        latest = None
        for row in c.execute(
                'SELECT * FROM current_runs WHERE source_id=? AND recording_id=? ORDER BY seq DESC',
                (source, parent['recording_id'])):
            candidate = _decode(row)
            if _same_recording_input(candidate, item):
                latest = candidate
                break
        allowed_latest = {parent_run_id}
        if previous is not None:
            allowed_latest.add(previous['id'])
        if latest is None or latest['id'] not in allowed_latest:
            raise ValueError('이 녹음의 새 처리 이력이 있습니다. 최신 처리를 다시 열어 확인하세요.')
        if previous is not None and previous['status'] in (*ACTIVE, 'COMPLETED'):
            c.commit()
            return {'jobs': [], 'skipped': [previous]}
        transcript_parent = {
            'run_id': parent_run_id, 'model_sha256': original_model_sha,
            **{name: original.get(name) for name in ('asr_text', 'dialect_text', 'dialect_error',
                'transcription_status', 'duration_seconds', 'sample_rate', 'audio_samples')},
            **{name: original.get(name, []) for name in ('base_segments', 'dialect_segments')},
        }
        input_data = item['input'] | {'transcript_review': review, 'transcript_parent': transcript_parent}
        rid = uuid.uuid4().hex
        c.execute('''INSERT INTO runs(
            id,dedup_key,attempt_no,source_id,work_id,recording_id,snapshot_sha,
            evidence_sha,audio_sha256,audio_bytes,model_sha256,model_info,input_json,created_ms)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)''', (
                rid, key, previous['attempt_no'] + 1 if previous else 1,
                item['source_id'], item['work_id'], item['recording_id'],
                item['snapshot_sha'], item['evidence_sha'], item['audio_sha256'],
                item['audio_bytes'], model_sha, model_json, store.canonical(input_data), created))
        c.execute('INSERT INTO run_events(run_id,status,at_ms,result_json) '
                  "VALUES(?,'QUEUED',?,'{}')", (rid, created))
        job = _one(c, rid)
        c.commit()
        return {'jobs': [job], 'skipped': []}
    except Exception:
        c.rollback()
        raise
    finally:
        c.close()


def _transition(root, run_id, status, result=None, error=None):
    c = connect(root)
    try:
        c.execute('BEGIN IMMEDIATE')
        before = _one(c, run_id)
        if status == 'RUNNING':
            if before['status'] != 'QUEUED':
                c.rollback()
                return None
        elif before['status'] != 'RUNNING' and not (
                status == 'FAILED' and before['status'] == 'QUEUED'):
            raise ValueError('대기·실행 중인 처리만 실패로 기록하며 완료는 실행 중에만 가능합니다.')
        if result is None:
            result = before['result']
        if not isinstance(result, dict):
            raise ValueError('PC 처리 결과는 객체여야 합니다.')
        for key in ('asr_text', 'dialect_text'):
            if result.get(key) is not None and not isinstance(result[key], str):
                raise ValueError(key + ': 문자열 필요')
        if result.get('extraction') is not None and not isinstance(result['extraction'], dict):
            raise ValueError('항목 추출 결과는 객체여야 합니다.')
        if status == 'COMPLETED':
            review = before['input'].get('transcript_review')
            if review is not None:
                if result.get('transcript_review') != review:
                    raise ValueError('등록한 검토 전사와 처리 결과의 검토 이력이 다릅니다.')
                reviewed_text(result)
                original = before['input']['transcript_parent']
                if any(result.get(key) != original[key] for key in ('asr_text', 'dialect_text', 'dialect_error')):
                    raise ValueError('재추출 과정에서 원래 전사 또는 오류가 변경되었습니다.')
            elif (result.get('transcript_review') is not None or result.get('reviewed_text') is not None
                  or result.get('text_source') == 'reviewed'):
                raise ValueError('일반 전사 결과에 등록하지 않은 검토 이력을 넣을 수 없습니다.')
            elif not isinstance(result.get('asr_text'), str):
                raise ValueError('완료 결과에 원문 전사가 필요합니다.')
        c.execute('INSERT INTO run_events(run_id,status,at_ms,result_json,error) '
                  'VALUES(?,?,?,?,?)',
                  (run_id, status, store.now(), store.canonical(result), error))
        after = _one(c, run_id)
        c.commit()
        return after
    except Exception:
        c.rollback()
        raise
    finally:
        c.close()


def start(root, run_id):
    """Atomic claim; returns None if another worker has already claimed the job."""
    return _transition(root, run_id, 'RUNNING')


def finish(root, run_id, result):
    return _transition(root, run_id, 'COMPLETED', result=result)


def fail(root, run_id, error, result=None):
    """Preserve successful earlier stages when a later model stage fails."""
    if not isinstance(error, str) or not error.strip():
        raise ValueError('오류 원인을 남겨야 합니다.')
    return _transition(root, run_id, 'FAILED', result=result, error=error)


def recover(root):
    """Called once after obtaining the exclusive worker lock, never during polling."""
    if not (Path(root) / 'processing.sqlite3').is_file():
        return []
    c = connect(root)
    try:
        c.execute('BEGIN IMMEDIATE')
        stale = list(c.execute("SELECT * FROM current_runs WHERE status IN ('QUEUED','RUNNING')"))
        recovered = []
        for row in stale:
            c.execute('INSERT INTO run_events(run_id,status,at_ms,result_json,error) '
                      "VALUES(?,'INTERRUPTED',?,?,?)", (
                          row['id'], store.now(), row['result_json'],
                          '이전 PC 처리 실행이 종료되었습니다. 원음을 보존했으며 다시 시도할 수 있습니다.'))
            recovered.append(_one(c, row['id']))
        c.commit()
        return recovered
    except Exception:
        c.rollback()
        raise
    finally:
        c.close()


def list_runs(root, source=None, work_id=None):
    if not (Path(root) / 'processing.sqlite3').is_file():
        return []
    current = {(r['source_id'], r['recording_id']): r
               for r in source_recordings(root, source)}
    c = connect(root)
    try:
        query = 'SELECT * FROM current_runs'
        args = ()
        if source is not None:
            query += ' WHERE source_id=?'
            args = (source,)
        result = []
        for row in c.execute(query + ' ORDER BY seq DESC', args):
            d = _decode(row)
            link = current.get((d['source_id'], d['recording_id']))
            d['current'] = (link is not None and link['audio_sha256'] == d['audio_sha256']
                            and link['input']['recording'].get('phase', 'LEGACY') ==
                            d['input']['recording'].get('phase', 'LEGACY')
                            and consumed_context(link['input']) == consumed_context(d['input']))
            d['evidence_current'] = d['current'] and link['evidence_sha'] == d['evidence_sha']
            d['original_work_id'] = d['work_id']
            if link:
                d['work_id'] = link['work_id']
            if work_id is None or d['work_id'] == work_id:
                result.append(d)
        return result
    finally:
        c.close()


def counts(root):
    result = dict.fromkeys((*ACTIVE, *TERMINAL), 0)
    if not (Path(root) / 'processing.sqlite3').is_file():
        return result
    c = connect(root)
    try:
        for row in c.execute('SELECT status,COUNT(*) FROM current_runs GROUP BY status'):
            result[row[0]] = row[1]
        return result
    finally:
        c.close()
