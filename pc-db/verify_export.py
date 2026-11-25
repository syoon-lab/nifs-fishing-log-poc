#!/usr/bin/env python3
"""Check a 바다기록 export without installing additional Python packages.
This validates archive completeness/relationships, not audio intelligibility or GPS truth.
"""
from decimal import Decimal
import csv
import hashlib
import io
import json
import sys
from pathlib import PurePosixPath
from zipfile import ZipFile


def strict_json(value):
    def pairs(items):
        result = {}
        for k, v in items:
            if k in result: raise ValueError('Duplicate JSON key: ' + k)
            result[k] = v
        return result
    def invalid(value): raise ValueError('Nonfinite JSON number: ' + value)
    return json.loads(value, object_pairs_hook=pairs, parse_constant=invalid)

GEAR_FIELDS = ['gear_type','deployment_date','deployment_date_candidate','date_status','haul_quantity','redeploy_quantity','gear_unit','disposition','recorded_date']
GEAR_COLUMNS = ['id','structured_log_id'] + GEAR_FIELDS


def check_basics(tables):
    import math
    for table, rows in tables.items():
        for row in rows.values():
            for k,v in row.items():
                if isinstance(v,float) and not math.isfinite(v): raise ValueError('Nonfinite value')
                if (k.endswith('_id') or k=='id') and v is not None and (not isinstance(v,str) or not v): raise ValueError('Invalid identifier')
    for r in tables['recordings'].values():
        if not isinstance(r['started_ms'],int) or isinstance(r['started_ms'],bool): raise ValueError('Invalid recording time')
        if r['ended_ms'] is not None and r['ended_ms'] < r['started_ms']: raise ValueError('Reversed recording time')
    for r in tables['locations'].values():
        if not all(isinstance(r[k],(int,float)) and not isinstance(r[k],bool) for k in ('latitude','longitude')): raise ValueError('Invalid coordinate')
        if not -90<=r['latitude']<=90 or not -180<=r['longitude']<=180: raise ValueError('Coordinate range')


def check_gear(tables):
    import datetime
    gear={}
    for r in tables['gear_details'].values():
        if r['structured_log_id'] not in tables['structured_logs'] or r['structured_log_id'] in gear: raise ValueError('Gear reference/duplicate')
        gear[r['structured_log_id']]=r
    for log in tables['structured_logs'].values():
        d=strict_json(log['data_json']); g=gear.get(log['id'])
        if log['phase']!='WORK':
            if g: raise ValueError('Unexpected legacy gear detail')
            continue
        if not g or any(d.get(k)!=g[k] for k in GEAR_FIELDS): raise ValueError('Gear details missing/mismatch')
        if d['gear_type'] not in ('POT','GILLNET') or d['gear_unit'] != ('개' if d['gear_type']=='POT' else '폭'): raise ValueError('Gear type/unit mismatch')
        for k in ('haul_quantity','redeploy_quantity'):
            if d[k] is not None and (type(d[k]) not in (int,float) or d[k]<=0 or d[k]>1e9 or int(d[k])!=d[k]): raise ValueError('Gear quantity')
        ref=datetime.date.fromisoformat(d['recorded_date'])
        for k in ('deployment_date','deployment_date_candidate'):
            if d[k] is not None and datetime.date.fromisoformat(d[k])>ref: raise ValueError('Future deployment date')
        if d['date_status'] not in ('MISSING','CANDIDATE','UNCONFIRMED','CONFIRMED') or d['disposition'] not in ('UNCONFIRMED','REMOVED','REDEPLOYED'): raise ValueError('Gear state')
        if d['deployment_date'] is not None and d['date_status']!='CONFIRMED': raise ValueError('Unconfirmed deployment date')


def check_events(tables):
    for e in tables['events'].values():
        sid,rid=e['session_id'],e['recording_id']
        if sid is not None and sid not in tables['sessions']: raise ValueError('Event session reference')
        if rid is not None and (rid not in tables['recordings'] or tables['recordings'][rid]['session_id']!=sid): raise ValueError('Event recording reference')
        if not e['type'].startswith('FIELD_') and e['type']!='GEAR_WORK_CONTEXT': continue
        d=strict_json(e['detail'])
        if not isinstance(d,dict) or d.get('version')!=1: raise ValueError('Unknown field event version')
        op=e.get('operation_id')
        if op not in tables.get('operations',{}): raise ValueError('Field event operation reference')
        for src in d.get('sources',[]):
            r=tables['recordings'].get(src.get('recording_id'))
            if not r or r.get('operation_id')!=op: raise ValueError('Field source recording reference')
            for k in ('attempt_id','source_attempt_id'):
                if src.get(k) is not None:
                    t=tables.get('transcriptions',{}).get(src[k])
                    if not t or t['recording_id']!=r['id']: raise ValueError('Field source transcription reference')
            if src.get('review_id') is not None:
                review=tables.get('transcript_reviews',{}).get(src['review_id'])
                if not review or tables['transcriptions'][review['transcription_id']]['recording_id']!=r['id']: raise ValueError('Field review reference')
            if src.get('structured_id') is not None:
                log=tables.get('structured_logs',{}).get(src['structured_id'])
                if not log or tables['transcriptions'][log['transcription_id']]['recording_id']!=r['id']: raise ValueError('Field structured reference')
        if e['type'] in ('FIELD_SUPPLEMENT','FIELD_ANSWER_CONTEXT') and rid is None: raise ValueError('Missing answer/supplement recording')
        if e['type']=='FIELD_REVIEW' and not isinstance(d.get('data'),dict): raise ValueError('Missing field review data')


COLUMNS = {
    'sessions': ['id','started_ms','ended_ms','interval_ms','status'],
    'recordings': ['id','session_id','started_ms','ended_ms','label','path','status','error','start_location_id','end_location_id'],
    'locations': ['id','session_id','recording_id','measured_ms','received_ms','elapsed_ns','latitude','longitude','accuracy_m','provider','is_mock'],
    'events': ['id','session_id','recording_id','time_ms','type','detail'],
}
OPERATIONS = ['id','session_id','sequence_no','started_ms','ended_ms','status','stage']



TRANSCRIPTION_COLUMNS = {
    'transcriptions': ['id','recording_id','status','created_ms','finished_ms','engine','model_sha256','audio_sha256','text','segments_json','duration_ms','error'],
    'transcript_reviews': ['id','transcription_id','created_ms','text'],
    'diary_entries': ['id','review_id','created_ms','data_json'],
}

def check_transcriptions(tables, audio_files):
    import re
    audio = {f['recording_id']: f for f in audio_files}
    attempts = tables['transcriptions']
    reviews = tables['transcript_reviews']
    for t in attempts.values():
        r = tables['recordings'].get(t['recording_id'])
        if not r or r['status'] != 'COMPLETED':
            raise ValueError('Transcription recording reference')
        if t['status'] not in ('COMPLETED','FAILED','CANCELLED','INTERRUPTED'):
            raise ValueError('Unfinished/unknown transcription')
        if t['audio_sha256'] != audio[r['id']]['sha256'] or not re.fullmatch('[0-9a-f]{64}',t['model_sha256']):
            raise ValueError('Transcription source fingerprint')
        segments = strict_json(t['segments_json'])
        if t['status'] == 'COMPLETED':
            if t['finished_ms'] is None or t['duration_ms'] is None or t['duration_ms'] <= 0:
                raise ValueError('Incomplete transcription metadata')
            if ''.join(s['text'] for s in segments) != t['text']:
                raise ValueError('Transcript segments/text mismatch')
            previous = 0
            for s in segments:
                if s['start_ms'] < previous or s['end_ms'] < s['start_ms']:
                    raise ValueError('Transcript segment time')
                previous = s['start_ms']
        elif t['text'] or segments or t['duration_ms'] is not None:
            raise ValueError('Failed transcription has completed text')
    for r in reviews.values():
        t = attempts.get(r['transcription_id'])
        if not t or t['status'] != 'COMPLETED' or not r['text'].strip():
            raise ValueError('Review reference/status/text')
    linked = set()
    for e in tables['diary_entries'].values():
        review = reviews.get(e['review_id'])
        if not review or e['review_id'] in linked:
            raise ValueError('Diary review reference/duplicate')
        linked.add(e['review_id'])
        t = attempts[review['transcription_id']]
        r = tables['recordings'][t['recording_id']]
        d = strict_json(e['data_json'])
        expected = dict(schema_version=1, recording_id=r['id'], session_id=r['session_id'], operation_id=r['operation_id'], phase=r['phase'], recorded_at_ms=r['started_ms'], time_source='recording.started_ms', text=review['text'], review_status='TRANSCRIPT_CONFIRMED')
        if d != expected:
            raise ValueError('Diary source/time/text mismatch')
    if linked != set(reviews):
        raise ValueError('Review missing diary entry')



STRUCTURED_COLUMNS = {
 'structured_logs':['id','transcription_id','review_id','created_ms','extractor_version','source_text','phase','status','work_type','target_species','gear_quantity','gear_unit','workers','outcome','notes_state','notes_text','data_json'],
 'catch_items':['id','structured_log_id','row_no','species','quantity','unit','weight_kg','outcome','approximate','evidence_json','data_json'],
}

def check_structured(tables):
    import math
    logs=tables['structured_logs']; grouped={};used_reviews=set()
    for c in tables['catch_items'].values():
        if c['structured_log_id'] not in logs: raise ValueError('Orphan catch item')
        group=grouped.setdefault(c['structured_log_id'],{})
        if c['row_no'] in group: raise ValueError('Duplicate catch sequence')
        group[c['row_no']]=c
    for l in logs.values():
        t=tables['transcriptions'].get(l['transcription_id'])
        if not t or t['status']!='COMPLETED': raise ValueError('Structured transcript reference')
        r=tables['recordings'][t['recording_id']]
        source=t['text']
        if l['review_id'] is not None:
            review=tables['transcript_reviews'].get(l['review_id'])
            if not review or review['transcription_id']!=t['id'] or review['id'] in used_reviews: raise ValueError('Structured review reference')
            source=review['text'];used_reviews.add(review['id'])
        if source!=l['source_text'] or l['phase']!=r['phase']: raise ValueError('Structured source/phase mismatch')
        if l['status'] not in ('DRAFT','CONFIRMED') or l['status']=='CONFIRMED' and l['review_id'] is None: raise ValueError('Structured review status')
        d=strict_json(l['data_json'])
        if d['version']!=l['extractor_version'] or d['version'] not in ('common-fields-ko/1.0','common-fields-ko/1.1','common-fields-ko/1.2','gillnet-pot/1.0','gillnet-pot/1.1','gillnet-pot/1.2') or d['phase']!=l['phase']: raise ValueError('Extractor metadata')
        for k in ('work_type','target_species','gear_quantity','gear_unit','workers','outcome','notes_state','notes_text'):
            if d[k]!=l[k]: raise ValueError('Structured typed/JSON mismatch: '+k)
        for k in ('gear_quantity','workers'):
            if d[k] is not None and (not isinstance(d[k],(int,float)) or not math.isfinite(d[k]) or d[k]<0 or d[k]>1e9 or int(d[k])!=d[k]): raise ValueError('Invalid common quantity')
        if d['outcome'] not in ('UNCONFIRMED','CAUGHT','NO_CATCH','NOT_FISHED') or d['notes_state'] not in ('NOT_MENTIONED','EXPLICIT_NONE','REPORTED'): raise ValueError('Structured outcome')
        group=grouped.get(l['id'],{})
        if len(group)!=len(d['catches']): raise ValueError('Missing catch item')
        positive=False
        for i,expected in enumerate(d['catches'],1):
            c=group.get(i)
            if not c or strict_json(c['data_json'])!=expected: raise ValueError('Catch item JSON/sequence')
            for k in ('species','quantity','unit','weight_kg','outcome'):
                if c[k]!=expected[k]: raise ValueError('Catch typed/JSON mismatch: '+k)
            if c['approximate']!=int(expected['approximate']) or strict_json(c['evidence_json'])!=expected['evidence']: raise ValueError('Catch evidence mismatch')
            for k in ('quantity','weight_kg'):
                n=c[k]
                if n is not None and (not isinstance(n,(int,float)) or not math.isfinite(n) or n<0 or n>1e9): raise ValueError('Invalid catch number')
            if c['quantity'] is not None and c['unit'] is None: raise ValueError('Missing quantity unit')
            if c['unit'] is not None and c['unit'] not in ('마리','개','상자','통','포대','바구니'): raise ValueError('Unknown catch unit')
            if c['unit']=='마리' and c['quantity'] is not None and int(c['quantity'])!=c['quantity']: raise ValueError('Fractional fish count')
            has_positive=(c['quantity'] or 0)>0 or (c['weight_kg'] or 0)>0
            if c['outcome'] not in ('UNCONFIRMED','CAUGHT','NO_CATCH') or c['outcome']=='NO_CATCH' and has_positive: raise ValueError('Catch outcome')
            positive |= has_positive
        if positive and d['outcome'] in ('NO_CATCH','NOT_FISHED'): raise ValueError('Contradictory overall catch state')


def check_workflow(tables):
    sessions, operations, voices = (tables[t] for t in ('sessions', 'operations', 'recordings'))
    gear_ops = {}
    for e in tables['events'].values():
        if e['type'] == 'GEAR_WORK_CONTEXT':
            d = strict_json(e['detail'])
            if d.get('version') != 1 or d.get('gear_type') not in ('POT','GILLNET') or e['operation_id'] not in operations or e['operation_id'] in gear_ops:
                raise ValueError('Invalid gear context')
            gear_ops[e['operation_id']] = d
    sequences = {}
    counts = {}
    for op in operations.values():
        parent = sessions.get(op['session_id'])
        if not parent or parent['workflow'] != 'SEQUENTIAL':
            raise ValueError('Operation references wrong session')
        seq = op['sequence_no']
        group = sequences.setdefault(op['session_id'], set())
        if not isinstance(seq, int) or seq < 1 or seq in group:
            raise ValueError('Duplicate or invalid operation sequence')
        group.add(seq)
        if op['status'] not in ('COMPLETED', 'INTERRUPTED') or op['stage'] != op['status']:
            raise ValueError('Unfinished or invalid operation state')
        if (op['ended_ms'] is None) != (op['status'] != 'COMPLETED'):
            raise ValueError('Operation end time inconsistent with state')
        if parent['status'] == 'COMPLETED' and op['status'] != 'COMPLETED':
            raise ValueError('Arrival before operation completion')
    for group in sequences.values():
        if group != set(range(1, len(group) + 1)):
            raise ValueError('Missing operation sequence')
    for r in voices.values():
        if r['operation_id'] is None:
            if r['phase'] != 'LEGACY' or sessions[r['session_id']]['workflow'] != 'LEGACY':
                raise ValueError('Missing recording operation')
            continue
        op = operations.get(r['operation_id'])
        if not op or op['session_id'] != r['session_id'] or r['phase'] not in ('START','NOTE','END','WORK'):
            raise ValueError('Recording operation/phase mismatch')
        if (op['id'] in gear_ops) != (r['phase'] == 'WORK'):
            raise ValueError('Mixed work/legacy phases')
        if r['status'] == 'COMPLETED':
            phases = counts.setdefault(r['operation_id'], {'START': 0, 'END': 0, 'WORK': 0})
            if r['phase'] in phases:
                phases[r['phase']] += 1
    for op in operations.values():
        phases = counts.get(op['id'], {'START': 0, 'END': 0, 'WORK': 0})
        if op['id'] in gear_ops:
            if op['status']=='COMPLETED' and phases['WORK'] < 1: raise ValueError('Missing work audio')
            continue
        if max(phases.values()) > 1:
            raise ValueError('Duplicate successful start/end recordings')
        if op['status'] == 'COMPLETED' and phases != {'START': 1, 'END': 1, 'WORK': 0}:
            raise ValueError('Completed operation lacks required recording')
    for table in ('locations', 'events'):
        for row in tables[table].values():
            if row['session_id'] is not None and row['session_id'] not in sessions:
                raise ValueError(table + ': missing session')
            if row['operation_id'] is not None:
                op = operations.get(row['operation_id'])
                if not op or op['session_id'] != row['session_id']:
                    raise ValueError(table + ': wrong operation')
            if row['recording_id'] is not None:
                voice = voices.get(row['recording_id'])
                if not voice or voice['session_id'] != row['session_id'] or voice['operation_id'] != row['operation_id']:
                    raise ValueError(table + ': wrong recording relation')


def verify(path):
    with ZipFile(path) as z:
        names=z.namelist()
        if len(names)!=len(set(names)):
            raise ValueError('ZIP contains duplicate entry names')
        if any(PurePosixPath(n).is_absolute() or '..' in PurePosixPath(n).parts for n in names):
            raise ValueError('Unsafe archive path')
        bad=z.testzip()
        if bad:
            raise ValueError('CRC mismatch: '+bad)
        data=strict_json(z.read('records.json'))
        manifest=strict_json(z.read('manifest.json'))
        version = data['schema_version']
        if version not in (1,2,3,4,5) or manifest['schema_version']!=version:
            raise ValueError('Unknown schema version')
        columns = {t: list(v) for t, v in COLUMNS.items()}
        if version >= 2:
            columns['sessions'] += ['workflow']
            columns['recordings'] += ['operation_id', 'phase']
            columns['locations'] += ['operation_id']
            columns['events'] += ['operation_id']
            columns['operations'] = OPERATIONS
        if version >= 3:
            columns.update(TRANSCRIPTION_COLUMNS)
        if version >= 4:
            columns.update(STRUCTURED_COLUMNS)
        if version >= 5:
            columns['gear_details'] = GEAR_COLUMNS
        if set(manifest['counts']) != set(columns):
            raise ValueError('Missing or unexpected table counts')
        expected_keys = set(columns) | {'schema_version','exported_at_ms','time_basis','coordinate_system'}
        if set(data) != expected_keys:
            raise ValueError('Missing or unexpected data sections')
        tables={}
        for table in columns:
            rows=data[table]
            if not isinstance(rows,list) or any(not isinstance(r,dict) or not isinstance(r.get('id'),str) or not r['id'] for r in rows):
                raise ValueError(table + ': invalid row/identifier')
            if any(set(r) != set(columns[table]) for r in rows):
                raise ValueError(table + ': JSON column mismatch')
            if type(manifest['counts'][table]) is not int or len(rows)!=manifest['counts'][table]:
                raise ValueError(table+': count mismatch')
            index={r['id']:r for r in rows}
            if len(index)!=len(rows):
                raise ValueError(table+': duplicate identifier')
            tables[table]=index
            reader=csv.DictReader(io.StringIO(z.read(table+'.csv').decode('utf-8-sig'),newline=''))
            csv_rows=list(reader)
            if reader.fieldnames != columns[table]:
                raise ValueError(table + ': CSV column mismatch')
            if len(csv_rows)!=len(rows):
                raise ValueError(table+': CSV count mismatch')
            for source,converted in zip(rows,csv_rows):
                if set(converted)!=set(columns[table]) or any(v is None for v in converted.values()):raise ValueError(table + ': malformed CSV row')
                for key in reader.fieldnames:
                    value=source[key]
                    expected='' if value is None else str(value)
                    if isinstance(value,str) and value[:1] in ('=','+','@','-','\t','\r'):
                        expected="'"+value
                    # JSON/SQLite can render integral REAL values with a trailing .0.
                    actual=converted[key]
                    if isinstance(value,(int,float)) and not isinstance(value,bool):
                        if actual=='' or Decimal(actual)!=Decimal(str(value)):
                            raise ValueError(f'{table}: CSV numeric mismatch {key}')
                    elif actual!=expected:
                        raise ValueError(f'{table}: CSV value mismatch {key}')
        refs={r['path']:r for r in data['recordings']}
        if len(refs)!=len(data['recordings']):
            raise ValueError('Multiple recordings reference the same audio')
        if len(manifest['audio_files'])!=len(refs):
            raise ValueError('Audio count mismatch')
        seen=set()
        for entry in manifest['audio_files']:
            name=entry['path']
            if name in seen or name not in refs or refs[name]['id']!=entry['recording_id']:
                raise ValueError('Audio identity mismatch')
            seen.add(name)
            h=hashlib.sha256();length=0
            with z.open(name) as audio:
                while chunk:=audio.read(1024*1024):
                    h.update(chunk);length+=len(chunk)
            if length!=entry['bytes'] or h.hexdigest()!=entry['sha256']:
                raise ValueError('Audio checksum mismatch: '+name)
        expected=set(refs)|{'records.json','manifest.json','읽어주세요.txt'}|{table+'.csv' for table in columns}
        if set(names)!=expected:
            raise ValueError('Unexpected or missing archive files')
        for r in data['recordings']:
            if r['session_id'] not in tables['sessions']:
                raise ValueError('Recording references a missing session')
            for key in ('start_location_id','end_location_id'):
                if r[key] is not None:
                    point=tables['locations'].get(r[key])
                    if point is None or point['session_id']!=r['session_id']:
                        raise ValueError('Recording location reference mismatch')
        for point in data['locations']:
            if point['session_id'] not in tables['sessions']:
                raise ValueError('Location references a missing session')
            if point['recording_id'] is not None:
                recording=tables['recordings'].get(point['recording_id'])
                if recording is None or recording['session_id']!=point['session_id']:
                    raise ValueError('Location references the wrong recording')
        if any(r['status'] == 'ACTIVE' for r in data['sessions']) or any(r['status'] in ('PREPARING','RECORDING') for r in data['recordings']):
            raise ValueError('Export contains active capture')
        if version >= 2:
            check_workflow(tables)
        if version >= 3:
            check_transcriptions(tables, manifest['audio_files'])
        if version >= 4:
            check_structured(tables)
        if version >= 5:
            check_gear(tables)
        check_events(tables)
        check_basics(tables)
        return {'verified':True,'schema_version':version,'counts':manifest['counts'],
                'recordings_without_start_location':sum(r['start_location_id'] is None for r in data['recordings']),
                'incomplete_recordings':sum(r['status']!='COMPLETED' for r in data['recordings'])}


if __name__=='__main__':
    if len(sys.argv)!=2:
        sys.exit('Usage: python3 tools/verify_export.py exported.zip')
    try:
        print(json.dumps(verify(sys.argv[1]),ensure_ascii=False,indent=2))
    except Exception as error:
        sys.exit('Export validation failed: '+str(error))
