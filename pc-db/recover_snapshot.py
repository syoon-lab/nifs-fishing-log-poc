#!/usr/bin/env python3
"""Create a clearly identified PC reconstruction from a complete, idle ADB TAR.
The input TAR is never edited. This is NOT an Android UI-exported ZIP.
"""
import argparse,csv,hashlib,io,json,sqlite3,tarfile,tempfile,zipfile
from pathlib import Path
import store
from verify_export import COLUMNS,OPERATIONS,TRANSCRIPTION_COLUMNS,STRUCTURED_COLUMNS,GEAR_COLUMNS

def recover(source,destination,report_path):
 source=Path(source);destination=Path(destination);report_path=Path(report_path)
 if destination.exists() or report_path.exists():raise ValueError('기존 산출물을 덮어쓰지 않습니다.')
 before_sha=store.sha_file(source)
 with tarfile.open(source) as t:
  members=[m for m in t.getmembers() if m.isfile()];names=[m.name.removeprefix('./') for m in members]
  if len(names)!=len(set(names)):raise ValueError('원자료 TAR의 중복 경로')
  allfiles=dict(zip(names,members));db_name='databases/field-records.db'
  for suffix in ('-wal','-journal'):
   item=allfiles.get(db_name+suffix)
   if item and item.size:raise ValueError('미반영 journal/WAL이 있습니다. 원자료 복구 상태를 먼저 확인해야 합니다.')
  db_bytes=t.extractfile(allfiles[db_name]).read()
  with tempfile.TemporaryDirectory() as td:
   p=Path(td)/'source.sqlite3';p.write_bytes(db_bytes);db=sqlite3.connect(f'file:{p}?mode=ro',uri=True);db.row_factory=sqlite3.Row
   try:
    if db.execute('PRAGMA integrity_check').fetchone()[0]!='ok' or db.execute('PRAGMA foreign_key_check').fetchall():raise ValueError('원본 DB 무결성 오류')
    version=db.execute('PRAGMA user_version').fetchone()[0]
    if version!=5:raise ValueError('이 재구성 도구는 확인된 schema 5 사본에만 사용합니다.')
    columns={k:list(v) for k,v in COLUMNS.items()};columns['sessions']+=['workflow'];columns['recordings']+=['operation_id','phase'];columns['locations']+=['operation_id'];columns['events']+=['operation_id'];columns['operations']=OPERATIONS;columns.update(TRANSCRIPTION_COLUMNS);columns.update(STRUCTURED_COLUMNS);columns['gear_details']=GEAR_COLUMNS
    actual=[r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
    if set(actual)!=set(columns)|{'android_metadata'}:raise ValueError('미확인 원본 DB 표: '+str(actual))
    d={'schema_version':5,'exported_at_ms':store.now(),'time_basis':'Unix epoch milliseconds (UTC); elapsed_ns is device monotonic clock','coordinate_system':'WGS84'}
    for name,keys in columns.items():
     if [r[1] for r in db.execute('PRAGMA table_info('+name+')')]!=keys:raise ValueError(name+': 원본 열 구조 불일치')
     d[name]=[dict(r) for r in db.execute('SELECT * FROM '+name+' ORDER BY rowid')]
    metadata_rows=[dict(r) for r in db.execute('SELECT * FROM android_metadata')]
   finally:db.close()
  audio={n for n in names if n.startswith('files/records/audio/')};expected={'files/records/'+r['path'] for r in d['recordings']}
  if audio!=expected:raise ValueError('원본 TAR 원음과 DB 연결의 누락/고아 파일')
  manifest={'schema_version':5,'counts':{name:len(d[name]) for name in columns},'audio_files':[]}
  destination.parent.mkdir(parents=True,exist_ok=True)
  try:
   with zipfile.ZipFile(destination,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=1) as z:
    for r in d['recordings']:
     member=allfiles['files/records/'+r['path']];h=hashlib.sha256();length=0
     with t.extractfile(member) as src,z.open(r['path'],'w') as out:
      for b in iter(lambda:src.read(1024*1024),b''):h.update(b);length+=len(b);out.write(b)
     manifest['audio_files'].append({'recording_id':r['id'],'path':r['path'],'bytes':length,'sha256':h.hexdigest()})
    z.writestr('records.json',json.dumps(d,ensure_ascii=False,indent=2))
    for name,keys in columns.items():
     out=io.StringIO(newline='');writer=csv.writer(out);writer.writerow(keys)
     for r in d[name]:
      vals=[]
      for key in keys:
       v=r[key]
       if isinstance(v,str) and v[:1] in ('=','+','@','-','\t','\r'):v="'"+v
       vals.append(v)
      writer.writerow(vals)
     z.writestr(name+'.csv',out.getvalue().encode('utf-8-sig'))
    z.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2))
    z.writestr('읽어주세요.txt','PC 재구성 ZIP — 휴대전화에서 직접 내보낸 ZIP이 아닙니다.\n원본: '+str(source.resolve())+'\n원본 TAR SHA-256: '+before_sha+'\n11개 표의 모든 행·열·순서·원음 보존. exported_at_ms는 PC 재구성 시각이며 녹음/수신 시각이 아닙니다.\n원본 TAR의 모델·설정·시험 DB·android_metadata는 조사 표로 중복 집계하지 않으며 원본 TAR에 보존합니다.\nPC 최종 확인 전 자동 확정하지 마세요. null은 미확인, 수량 단위는 환산하지 않습니다.\n')
   check=store.validate(destination)[2]
   with zipfile.ZipFile(destination) as z:
    if json.loads(z.read('records.json'))!=d:raise ValueError('재구성 ZIP 전체 값 불일치')
    for a in manifest['audio_files']:
     raw=t.extractfile(allfiles['files/records/'+a['path']]);h=hashlib.sha256()
     for b in iter(lambda:raw.read(1024*1024),b''):h.update(b)
     if h.hexdigest()!=a['sha256']:raise ValueError('원본 TAR 원음 독립 대조 실패')
   if store.sha_file(source)!=before_sha:raise ValueError('원본 TAR이 변경됐습니다.')
   inventory=[]
   for n,m in allfiles.items():inventory.append({'path':n,'bytes':m.size,'handling':'DB 전체 조사표 반영; android_metadata는 별도 보존' if n==db_name else '원음 반영' if n in audio else '조사 표에 포함하지 않음; 원본 TAR에 그대로 보존'})
   report={'source_kind':'S24_PRIVATE_SNAPSHOT_PC_RECONSTRUCTION_NOT_PHONE_EXPORT','source_tar':str(source.resolve()),'source_sha256':before_sha,'source_unchanged':True,'source_db_sha256':hashlib.sha256(db_bytes).hexdigest(),'zip_sha256':store.sha_file(destination),'created_ms':d['exported_at_ms'],'columns':columns,'counts':manifest['counts'],'total_rows':sum(manifest['counts'].values()),'excluded_data_rows':0,'android_metadata_not_survey_rows':metadata_rows,'audio':len(audio),'all_sqlite_rows_values_order_match':True,'all_tar_audio_hashes_match':True,'source_file_inventory':inventory,'verification':check}
   report_path.write_text(json.dumps(report,ensure_ascii=False,indent=2));return report
  except Exception:
   # A partial new artifact is removed; existing outputs were rejected before opening.
   destination.unlink(missing_ok=True);raise

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('destination',type=Path);p.add_argument('report',type=Path);a=p.parse_args();r=recover(a.source,a.destination,a.report);print(json.dumps({k:v for k,v in r.items() if k not in ('source_file_inventory','columns')},ensure_ascii=False,indent=2))
