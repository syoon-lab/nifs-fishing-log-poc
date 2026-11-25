#!/usr/bin/env python3
"""Independent source TAR / original viewer SQLite -> actual PC DB reconciliation.
Does not call importer/validator. Read-only to all databases.
"""
import hashlib,json,sqlite3,tarfile,tempfile,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parent
PROJECT=ROOT.parent

def sha(path):
 h=hashlib.sha256()
 with Path(path).open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
def db(path):
 c=sqlite3.connect(f'file:{Path(path).resolve()}?mode=ro',uri=True);c.row_factory=sqlite3.Row;return c

def verify():
 c=db(ROOT/'data/records.sqlite3');sources={r['id']:r['head'] for r in c.execute('SELECT * FROM sources')};report={}
 tarpath=PROJECT/'android-recorder/검증자료/2026-11-09_S24_0.8.1/시험후.tar'
 with tarfile.open(tarpath) as t,tempfile.TemporaryDirectory() as temp:
  files={m.name.removeprefix('./'):m for m in t.getmembers() if m.isfile()}
  p=Path(temp)/'source.db';p.write_bytes(t.extractfile(files['databases/field-records.db']).read());original=db(p);head=sources['윤석진 S24'];counts={};colmap={}
  table_names=[r[0] for r in original.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
  for table in table_names:
   if table=='android_metadata':continue
   rows=[dict(r) for r in original.execute('SELECT * FROM '+table+' ORDER BY rowid')]
   actual=[json.loads(r[0]) for r in c.execute('SELECT payload FROM raw_rows WHERE snapshot_sha=? AND table_name=? ORDER BY ordinal',(head,table))]
   assert rows==actual,table
   counts[table]=len(rows);colmap[table]=[r[1] for r in original.execute('PRAGMA table_info('+table+')')]
  assert set(counts)=={r[0] for r in c.execute('SELECT DISTINCT table_name FROM raw_rows WHERE snapshot_sha=?',(head,))}
  recording_rows=[dict(r) for r in original.execute('SELECT * FROM recordings')]
  mapping={r['recording_id']:dict(r) for r in c.execute('SELECT * FROM audio WHERE snapshot_sha=?',(head,))}
  assert set(mapping)=={r['id'] for r in recording_rows}
  for r in recording_rows:
   member=files['files/records/'+r['path']];h=hashlib.sha256();size=0
   with t.extractfile(member) as f:
    for b in iter(lambda:f.read(1024*1024),b''):h.update(b);size+=len(b)
   a=mapping[r['id']];assert a['sha']==h.hexdigest() and a['bytes']==size and a['path']==r['path'];assert sha(ROOT/'data/audio'/(a['sha']+'.m4a'))==h.hexdigest()
  report['S24']={'source_tar_sha256':sha(tarpath),'counts':counts,'columns':colmap,'rows':sum(counts.values()),'audio':len(mapping),'all_values_and_order_match':True,'all_original_audio_match':True,'excluded_survey_rows':0,'metadata_table_excluded':'android_metadata: Android locale metadata, retained in original TAR'};original.close()
 oldpath=PROJECT/'보관/legacy/db-viewer/data/records.sqlite3';old=db(oldpath);head=sources['2차 컨설팅 가상기기'];counts={}
 for table in ('sessions','recordings','locations','events'):
  before={r['id']:json.loads(r['payload']) for r in old.execute('SELECT * FROM '+table)}
  after={r['row_id']:json.loads(r['payload']) for r in c.execute('SELECT row_id,payload FROM raw_rows WHERE snapshot_sha=? AND table_name=?',(head,table))};assert before==after;counts[table]=len(before)
 for r in old.execute('SELECT * FROM recordings'):
  a=c.execute('SELECT * FROM audio WHERE snapshot_sha=? AND recording_id=?',(head,r['id'])).fetchone();assert a['sha']==r['audio_sha'];assert sha(oldpath.parent/'audio'/(a['sha']+'.m4a'))==sha(ROOT/'data/audio'/(a['sha']+'.m4a'))
 initial=json.loads((ROOT/'검증자료/초기자료_가져오기검증.json').read_text());assert sha(oldpath)==initial['old_db_sha256_before'];old.close()
 report['old_viewer']={'counts':counts,'rows':sum(counts.values()),'audio':counts['recordings'],'original_sqlite_sha256':sha(oldpath),'unchanged':True,'all_rows_and_audio_match':True}
 for s in c.execute('SELECT * FROM snapshots'):
  archive=ROOT/'data/archives'/(s['sha']+'.zip');assert sha(archive)==s['sha']
  with zipfile.ZipFile(archive) as z:
   d=json.loads(z.read('records.json'));m=json.loads(z.read('manifest.json'))
   for table in m['counts']:
    rows=[json.loads(r[0]) for r in c.execute('SELECT payload FROM raw_rows WHERE snapshot_sha=? AND table_name=? ORDER BY ordinal',(s['sha'],table))];assert rows==d[table]
 assert c.execute('PRAGMA integrity_check').fetchone()[0]=='ok';assert not c.execute('PRAGMA foreign_key_check').fetchall()
 report['current_rows']=c.execute('SELECT COUNT(*) FROM current_raw').fetchone()[0];report['pc_decisions']=c.execute('SELECT COUNT(*) FROM decisions').fetchone()[0];report['formal_records']=c.execute('SELECT COUNT(*) FROM current_formal').fetchone()[0];report['source_count']=len(sources);report['integrity_check']='ok';c.close();return report
if __name__=='__main__':print(json.dumps(verify(),ensure_ascii=False,indent=2))
