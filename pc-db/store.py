"""Immutable source snapshots + explicit receipt order + append-only PC decisions."""
import csv, datetime, hashlib, io, json, math, re, shutil, sqlite3, threading, time, uuid, zipfile
from pathlib import Path, PurePosixPath
from verify_export import verify, strict_json

LOCK=threading.RLock()
MAX_ZIP=1024**3
MAX_EXPANDED=2*1024**3
TABLES=('sessions','operations','recordings','locations','events','transcriptions','transcript_reviews','diary_entries','structured_logs','catch_items','gear_details')
META={'schema_version','exported_at_ms','time_basis','coordinate_system'}
def canonical(x):return json.dumps(x,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False)
def sha_file(p):
 h=hashlib.sha256()
 with Path(p).open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
def fingerprint(x):return hashlib.sha256(canonical(x).encode()).hexdigest()
def now():return int(time.time()*1000)
def connect(root):
 root=Path(root);root.mkdir(parents=True,exist_ok=True)
 c=sqlite3.connect(root/'records.sqlite3',timeout=30);c.row_factory=sqlite3.Row;c.execute('PRAGMA foreign_keys=ON')
 c.executescript('''
 CREATE TABLE IF NOT EXISTS sources(id TEXT PRIMARY KEY,head TEXT,receipt_no INTEGER NOT NULL DEFAULT 0);
 CREATE TABLE IF NOT EXISTS snapshots(sha TEXT PRIMARY KEY,source_id TEXT NOT NULL REFERENCES sources(id),previous_sha TEXT REFERENCES snapshots(sha),receipt_no INTEGER NOT NULL,filename TEXT NOT NULL,received_ms INTEGER NOT NULL,schema_version INTEGER NOT NULL,metadata TEXT NOT NULL,manifest TEXT NOT NULL,report TEXT NOT NULL,reason TEXT NOT NULL,UNIQUE(source_id,receipt_no));
 CREATE TABLE IF NOT EXISTS raw_rows(snapshot_sha TEXT NOT NULL REFERENCES snapshots(sha),table_name TEXT NOT NULL,row_id TEXT NOT NULL,ordinal INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(snapshot_sha,table_name,row_id),UNIQUE(snapshot_sha,table_name,ordinal));
 CREATE TABLE IF NOT EXISTS audio(snapshot_sha TEXT NOT NULL REFERENCES snapshots(sha),recording_id TEXT NOT NULL,sha TEXT NOT NULL,bytes INTEGER NOT NULL,path TEXT NOT NULL,PRIMARY KEY(snapshot_sha,recording_id));
 CREATE TABLE IF NOT EXISTS work_index(snapshot_sha TEXT NOT NULL REFERENCES snapshots(sha),work_id TEXT NOT NULL,fingerprint TEXT NOT NULL,PRIMARY KEY(snapshot_sha,work_id));
 CREATE TABLE IF NOT EXISTS decisions(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE NOT NULL,source_id TEXT NOT NULL REFERENCES sources(id),work_id TEXT NOT NULL,snapshot_sha TEXT NOT NULL REFERENCES snapshots(sha),evidence_sha TEXT NOT NULL,previous_id TEXT REFERENCES decisions(id),created_ms INTEGER NOT NULL,reviewer TEXT NOT NULL,status TEXT NOT NULL,reason TEXT NOT NULL,payload TEXT NOT NULL,missing TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS formal_catches(decision_id TEXT NOT NULL REFERENCES decisions(id),row_no INTEGER NOT NULL,species TEXT,quantity REAL,unit TEXT,weight_kg REAL,outcome TEXT NOT NULL,approximate INTEGER NOT NULL,PRIMARY KEY(decision_id,row_no));
 CREATE INDEX IF NOT EXISTS raw_lookup ON raw_rows(table_name,row_id);
 CREATE VIEW IF NOT EXISTS current_raw AS SELECT s.id AS source_id,r.* FROM sources s JOIN raw_rows r ON r.snapshot_sha=s.head;
 CREATE VIEW IF NOT EXISTS latest_decisions AS SELECT d.* FROM decisions d WHERE d.seq=(SELECT MAX(x.seq) FROM decisions x WHERE x.source_id=d.source_id AND x.work_id=d.work_id);
 CREATE VIEW IF NOT EXISTS current_formal AS SELECT d.* FROM latest_decisions d JOIN sources s ON s.id=d.source_id JOIN work_index w ON w.snapshot_sha=s.head AND w.work_id=d.work_id AND w.fingerprint=d.evidence_sha WHERE d.status='CONFIRMED';
 CREATE VIEW IF NOT EXISTS current_formal_catches AS SELECT d.source_id,d.work_id,c.* FROM current_formal d JOIN formal_catches c ON c.decision_id=d.id;
 ''')
 for t in ('snapshots','raw_rows','audio','work_index','decisions','formal_catches'):
  for act in ('UPDATE','DELETE'):
   c.execute(f"CREATE TRIGGER IF NOT EXISTS immutable_{t}_{act} BEFORE {act} ON {t} BEGIN SELECT RAISE(ABORT,'append-only history'); END")
 return c

def validate(path):
 if Path(path).stat().st_size>MAX_ZIP:raise ValueError('ZIP 한도는 1 GiB입니다. 원본을 나누거나 버리지 말고 지원 범위를 확인하세요.')
 with zipfile.ZipFile(path) as z:
  infos=z.infolist()
  if len(infos)>200000 or sum(i.file_size for i in infos)>MAX_EXPANDED:raise ValueError('압축 해제 한도 2 GiB 초과')
  for i in infos:
   p=PurePosixPath(i.filename)
   if str(p)!=i.filename or p.is_absolute() or '..' in p.parts or '\\' in i.filename or i.flag_bits&1 or (i.external_attr>>16)&0o170000==0o120000:raise ValueError('안전하지 않거나 지원하지 않는 ZIP 항목')
  if any(z.getinfo(n).file_size>128*1024**2 for n in ('records.json','manifest.json')):raise ValueError('메타데이터 한도 초과')
  d=strict_json(z.read('records.json'));m=strict_json(z.read('manifest.json'))
  if set(m)!={'schema_version','counts','audio_files'}:raise ValueError('알 수 없는 manifest 구조')
  if d.get('time_basis') != 'Unix epoch milliseconds (UTC); elapsed_ns is device monotonic clock' or d.get('coordinate_system') != 'WGS84':raise ValueError('시각 또는 좌표 기준 확인 필요')
  if type(d.get('exported_at_ms'))!=int:raise ValueError('내보낸 시각 형식 오류')
  for a in m['audio_files']:
   if set(a)!={'path','bytes','sha256','recording_id'} or not re.fullmatch('[0-9a-f]{64}',a['sha256']) or type(a['bytes'])!=int or a['bytes']<0:raise ValueError('원음 명세 오류')
   if not re.fullmatch(r'audio/[^/]+\.m4a',a['path']):raise ValueError('원음 경로 오류')
 result=verify(path)
 return d,m,result

def snapshot(c,sha):
 row=c.execute('SELECT metadata FROM snapshots WHERE sha=?',(sha,)).fetchone()
 if not row:raise ValueError('보관 판본 없음')
 d=strict_json(row[0])
 for t in TABLES:
  if t in d['tables']:d[t]=[strict_json(r[0]) for r in c.execute('SELECT payload FROM raw_rows WHERE snapshot_sha=? AND table_name=? ORDER BY ordinal',(sha,t))]
 d.pop('tables');return d

def works(d):
 """One work per operation; legacy recordings remain individual, explicitly unclassified."""
 groups={}
 for op in d.get('operations',[]):groups['op:'+op['id']]={'operation':op,'recordings':[]}
 for r in d['recordings']:
  key='op:'+r['operation_id'] if r.get('operation_id') else 'legacy:'+r['id']
  groups.setdefault(key,{'operation':None,'recordings':[]})['recordings'].append(r)
 result={}
 for key,g in groups.items():
  rids={r['id'] for r in g['recordings']};opid=g['operation']['id'] if g['operation'] else None
  sid=g['operation']['session_id'] if g['operation'] else g['recordings'][0]['session_id']
  ts=[t for t in d.get('transcriptions',[]) if t['recording_id'] in rids];tids={t['id'] for t in ts}
  reviews=[r for r in d.get('transcript_reviews',[]) if r['transcription_id'] in tids];reviewids={r['id'] for r in reviews}
  logs=[r for r in d.get('structured_logs',[]) if r['transcription_id'] in tids];lids={r['id'] for r in logs}
  events=[e for e in d['events'] if (opid is not None and e.get('operation_id')==opid) or e['recording_id'] in rids]
  locationids={r[k] for r in g['recordings'] for k in ('start_location_id','end_location_id') if r[k] is not None}
  evidence=dict(session=next(s for s in d['sessions'] if s['id']==sid),operation=g['operation'],recordings=g['recordings'],transcriptions=ts,transcript_reviews=reviews,structured_logs=logs,events=events,diary_entries=[r for r in d.get('diary_entries',[]) if r['review_id'] in reviewids],catch_items=[r for r in d.get('catch_items',[]) if r['structured_log_id'] in lids],gear_details=[r for r in d.get('gear_details',[]) if r['structured_log_id'] in lids],locations=[r for r in d['locations'] if r['id'] in locationids or r['recording_id'] in rids or opid is not None and r.get('operation_id')==opid])
  candidates=[]
  # Every revision remains selectable, ordered by source row order, never device clock.
  for log in logs:candidates.append({'id':log['id'],'kind':log['status'],'recording_id':next(t['recording_id'] for t in ts if t['id']==log['transcription_id']),'fields':strict_json(log['data_json'])})
  for e in events:
   if e['type']=='FIELD_REVIEW':candidates.append({'id':e['id'],'kind':'현장 확인 (PC 미확정)','fields':strict_json(e['detail'])['data']})
  mock=any(e['type']=='MOCK_FIXTURE' for e in events) or any(r['is_mock'] for r in evidence['locations'])
  result[key]={'id':key,'session_id':sid,'started_ms':g['operation']['started_ms'] if g['operation'] else g['recordings'][0]['started_ms'],'fingerprint':fingerprint(evidence),'evidence':evidence,'candidates':candidates,'is_mock':mock}
 return result

def plan(c,d,m,sha,source):
 if not isinstance(source,str) or not source.strip() or len(source)>120:raise ValueError('기기·자료군 이름을 1~120자로 입력하세요.')
 source=source.strip();s=c.execute('SELECT * FROM sources WHERE id=?',(source,)).fetchone();head=s['head'] if s else None
 old=snapshot(c,head) if head else {}
 existing=c.execute('SELECT source_id FROM snapshots WHERE sha=?',(sha,)).fetchone()
 if existing and existing[0]!=source:raise ValueError('이미 다른 자료군에 보관된 ZIP입니다: '+existing[0])
 if existing and head!=sha:raise ValueError('이미 보관된 과거 판본입니다. 현재 판본으로 되돌릴 수 없습니다.')
 if head and head!=sha and (d['schema_version']<old['schema_version'] or d['exported_at_ms']<old['exported_at_ms']):raise ValueError('이전 형식/시각의 ZIP입니다. 수신 순서·기기 시각 확인이 필요합니다.')
 differences={};changed=[]
 for t in TABLES:
  before={r['id']:r for r in old.get(t,[])};after={r['id']:r for r in d.get(t,[])}
  missing=set(before)-set(after)
  if missing:raise ValueError(f'{t}: 기존 {len(missing)}행 누락. 자동 보충 없이 갱신 중단. 예: {next(iter(missing))}')
  if list(before)!=[r['id'] for r in d.get(t,[]) if r['id'] in before]:raise ValueError(t+': 기존 행 순서가 달라 판본 순서 확인 필요')
  modified=[rid for rid in before if canonical(before[rid])!=canonical(after[rid])]
  for rid in modified:changed.append({'table':t,'id':rid,'before':before[rid],'after':after[rid]})
  for rid in after:
   overlap=c.execute('SELECT DISTINCT s.source_id FROM raw_rows r JOIN snapshots s ON s.sha=r.snapshot_sha WHERE r.table_name=? AND r.row_id=? AND s.source_id!=?',(t,rid,source)).fetchone()
   if overlap:raise ValueError(f'다른 자료군({overlap[0]})과 식별자가 겹칩니다. 같은 자료를 다른 출처로 중복 등록할 수 없습니다: {rid}')
  differences[t]={'read':len(after),'added':len(set(after)-set(before)),'unchanged':len(before)-len(modified),'changed':len(modified),'excluded':0,'missing':0}
 if head:
  before_audio={r['recording_id']:r['sha'] for r in c.execute('SELECT * FROM audio WHERE snapshot_sha=?',(head,))}
  if any(a['recording_id'] in before_audio and before_audio[a['recording_id']]!=a['sha256'] for a in m['audio_files']):raise ValueError('같은 녹음 식별자의 원음이 바뀌었습니다. 갱신 중단')
 return {'source_id':source,'previous_sha':head,'archive_sha256':sha,'duplicate':head==sha,'tables':differences,'changed_rows':changed,'counts':m['counts'],'audio_count':len(m['audio_files']),'schema_version':d['schema_version'],'exported_at_ms':d['exported_at_ms'],'period':{'start_ms':min((s['started_ms'] for s in d['sessions']),default=None),'end_ms':max((s['ended_ms'] or s['started_ms'] for s in d['sessions']),default=None)}}

def preview(path,root,source):
 d,m,v=validate(path);sha=sha_file(path);c=connect(root)
 try:return plan(c,d,m,sha,source)
 finally:c.close()

def import_zip(path,root,source,previous_sha=None,received_after=False,reason='',allow_changes=False,filename=None):
 path=Path(path);root=Path(root);d,m,v=validate(path);sha=sha_file(path)
 with LOCK:
  c=connect(root);created=[]
  try:
   c.execute('BEGIN IMMEDIATE');p=plan(c,d,m,sha,source);source=p['source_id']
   if p['duplicate']:
    audit_snapshot(c,root,sha);c.rollback();return dict(p,verified=True)
   if p['previous_sha']!=previous_sha:raise ValueError('현재 판본이 달라졌습니다. 검증 미리보기를 다시 여세요.')
   if previous_sha and not received_after:raise ValueError('같은 자료군에서 현재 판본 이후 받은 전체 ZIP인지 확인해야 합니다.')
   if p['changed_rows'] and (not allow_changes or not reason.strip()):raise ValueError('기존 행 변경이 있습니다. 전후 값 확인과 판본 채택 사유가 필요합니다.')
   for sub in ('archives','audio'):(root/sub).mkdir(exist_ok=True)
   def store_file(target,src,expected):
    if target.exists():
     if sha_file(target)!=expected:raise ValueError('보관 파일 지문 불일치: '+str(target))
    else:
     with target.open('xb') as out:created.append(target);shutil.copyfileobj(src,out)
     if sha_file(target)!=expected:raise ValueError('복사 후 지문 불일치')
   with path.open('rb') as f:store_file(root/'archives'/(sha+'.zip'),f,sha)
   with zipfile.ZipFile(path) as z:
    for a in m['audio_files']:
     with z.open(a['path']) as f:store_file(root/'audio'/(a['sha256']+'.m4a'),f,a['sha256'])
   c.execute('INSERT OR IGNORE INTO sources(id) VALUES(?)',(source,))
   n=c.execute('SELECT receipt_no FROM sources WHERE id=?',(source,)).fetchone()[0]+1
   metadata={k:d[k] for k in META};metadata['tables']=[t for t in TABLES if t in d]
   report=dict(p,verified=True,receipt_no=n,verification=v)
   c.execute('INSERT INTO snapshots VALUES(?,?,?,?,?,?,?,?,?,?,?)',(sha,source,previous_sha,n,filename or path.name,now(),d['schema_version'],canonical(metadata),canonical(m),canonical(report),reason))
   for t in metadata['tables']:
    c.executemany('INSERT INTO raw_rows VALUES(?,?,?,?,?)',((sha,t,r['id'],i,canonical(r)) for i,r in enumerate(d[t])))
   c.executemany('INSERT INTO audio VALUES(?,?,?,?,?)',((sha,a['recording_id'],a['sha256'],a['bytes'],a['path']) for a in m['audio_files']))
   c.executemany('INSERT INTO work_index VALUES(?,?,?)',((sha,k,w['fingerprint']) for k,w in works(d).items()))
   audit_snapshot(c,root,sha)
   c.execute('UPDATE sources SET head=?,receipt_no=? WHERE id=?',(sha,n,source))
   c.commit();return report
  except Exception:
   c.rollback()
   for f in created:f.unlink(missing_ok=True)
   raise
  finally:c.close()

def audit_snapshot(c,root,sha):
 root=Path(root);archive=root/'archives'/(sha+'.zip')
 if sha_file(archive)!=sha:raise ValueError('원본 ZIP 변조')
 with zipfile.ZipFile(archive) as z:original=strict_json(z.read('records.json'));m=strict_json(z.read('manifest.json'))
 if original!=snapshot(c,sha):raise ValueError('원본 ZIP과 DB 전체 행/순서/값 불일치')
 rows=list(c.execute('SELECT * FROM audio WHERE snapshot_sha=?',(sha,)))
 if len(rows)!=len(m['audio_files']):raise ValueError('원음 DB 명세 누락')
 expected={a['recording_id']:a for a in m['audio_files']}
 for r in rows:
  a=expected.get(r['recording_id']);p=root/'audio'/(r['sha']+'.m4a')
  if not a or a!={'recording_id':r['recording_id'],'sha256':r['sha'],'bytes':r['bytes'],'path':r['path']} or p.stat().st_size!=r['bytes'] or sha_file(p)!=r['sha']:raise ValueError('원음 사본 불일치')
 if c.execute('PRAGMA foreign_key_check').fetchall() or c.execute('PRAGMA integrity_check').fetchone()[0]!='ok':raise ValueError('SQLite 무결성 오류')
 return {'archive_sha256':sha,'tables':m['counts'],'rows':sum(m['counts'].values()),'audio':len(rows),'all_values_and_order_match':True}

FIELDS=('record_class','work_type','target_species','gear_quantity','notes_state','notes_text','gear_type','deployment_date','haul_quantity','redeploy_quantity','gear_unit','disposition','workers','outcome','notes')
CATCH_FIELDS=('species','quantity','unit','weight_kg','outcome','approximate')
MISSING_LABELS={'record_class':'자료 구분','work_type':'작업 내용','deployment_date':'투망일','haul_quantity':'양망 수량','redeploy_quantity':'재투망 수량','gear_type':'어법','gear_unit':'어구 단위','disposition':'작업 후 처리','outcome':'어획 유무','notes_text':'특이사항 내용','catches':'어획 내역','incomplete_audio':'불완전 녹음','interrupted_operation':'중단 작업','recordings':'원음'}
def missing_label(key):
 if key.startswith('catch['):
  index=key.split(']')[0][6:];field=key.split('.')[-1]
  return '어획 '+index+' '+{'species':'어종','unit':'단위','outcome':'유무','amount':'수량·중량'}.get(field,field)
 return MISSING_LABELS.get(key,key)

def blank_fields():return dict.fromkeys(FIELDS)|{'record_class':'UNCLASSIFIED','notes_state':'NOT_MENTIONED','outcome':'UNCONFIRMED','disposition':'UNCONFIRMED','catches':[]}
def normalize_fields(data):
 if not isinstance(data,dict) or set(data)!=set(FIELDS)|{'catches'}:raise ValueError('확인 항목 구조 불일치')
 d=strict_json(canonical(data));missing=[]
 for k in ('record_class','work_type','target_species','notes_state','notes_text','gear_type','deployment_date','gear_unit','disposition','outcome','notes'):
  if d[k] is not None and not isinstance(d[k],str):raise ValueError(k+': 문자열 필요')
  if d[k]=='':d[k]=None
 def number(v,k,integer=False):
  if v is None:return
  if type(v) not in (int,float) or not math.isfinite(v) or not 0<=v<=1e9 or integer and int(v)!=v:raise ValueError(k+': 수량 범위/형식 오류')
 for k in ('haul_quantity','redeploy_quantity','workers','gear_quantity'):number(d[k],k,True)
 if d['record_class'] not in ('UNCLASSIFIED','TEST','FIELD'):raise ValueError('자료 구분 오류')
 if d['record_class']=='UNCLASSIFIED':missing.append('record_class')
 if d['notes_state'] not in ('NOT_MENTIONED','EXPLICIT_NONE','REPORTED'):raise ValueError('특이사항 상태 오류')
 if d['notes_state']=='EXPLICIT_NONE' and d['notes_text']:raise ValueError('특이사항 없음과 입력한 내용이 충돌합니다.')
 if d['notes_state']=='REPORTED' and not d['notes_text']:missing.append('notes_text')
 if d['gear_quantity'] is not None and not d['gear_unit']:missing.append('gear_unit')
 if d['outcome'] not in ('UNCONFIRMED','CAUGHT','NO_CATCH','NOT_FISHED'):raise ValueError('어획 유무를 선택하세요.')
 if d['disposition'] not in ('UNCONFIRMED','REMOVED','REDEPLOYED'):raise ValueError('어구 처리 구분 오류')
 if d['gear_type'] not in (None,'POT','GILLNET','OTHER'):raise ValueError('어법 구분 오류')
 if d['gear_type'] in ('POT','GILLNET'):
  if d['gear_unit']!=('개' if d['gear_type']=='POT' else '폭'):raise ValueError('자망은 폭, 통발은 개 단위입니다.')
  for k in ('deployment_date','haul_quantity'):
   if d[k] is None:missing.append(k)
  if d['disposition']=='UNCONFIRMED':missing.append('disposition')
  if d['disposition']=='REDEPLOYED' and d['redeploy_quantity'] is None:missing.append('redeploy_quantity')
 if d['deployment_date'] is not None:datetime.date.fromisoformat(d['deployment_date'])
 if d['disposition']=='REMOVED' and d['redeploy_quantity'] not in (None,0):raise ValueError('최종 철거와 양수 재투망 수량이 충돌합니다.')
 if d['work_type'] is None:missing.append('work_type')
 if d['outcome']=='UNCONFIRMED':missing.append('outcome')
 if not isinstance(d['catches'],list):raise ValueError('어획 행 형식 오류')
 positive=False
 for i,r in enumerate(d['catches'],1):
  if not isinstance(r,dict) or set(r)!=set(CATCH_FIELDS):raise ValueError('어획 열 구조 오류')
  for k in ('species','unit'):
   if r[k] is not None and not isinstance(r[k],str):raise ValueError('어종/단위 문자열 필요')
   if r[k]=='':r[k]=None
  number(r['quantity'],'어획 수량');number(r['weight_kg'],'중량')
  if r['unit']=='마리' and r['quantity'] is not None and int(r['quantity'])!=r['quantity']:raise ValueError('마리 수는 정수여야 합니다.')
  if type(r['approximate'])!=bool or r['outcome'] not in ('UNCONFIRMED','CAUGHT','NO_CATCH'):raise ValueError('어획 상태 형식 오류')
  pos=(r['quantity'] or 0)>0 or (r['weight_kg'] or 0)>0;positive|=pos
  if r['outcome']=='NO_CATCH' and pos:raise ValueError('무어획과 양수 어획량 충돌')
  if r['species'] is None:missing.append(f'catch[{i}].species')
  if r['quantity'] is not None and r['unit'] is None:missing.append(f'catch[{i}].unit')
  if r['outcome']=='UNCONFIRMED':missing.append(f'catch[{i}].outcome')
  if r['outcome']!='NO_CATCH' and not pos:missing.append(f'catch[{i}].amount')
 if d['outcome'] in ('NO_CATCH','NOT_FISHED') and (positive or any(r['outcome']=='CAUGHT' for r in d['catches'])):raise ValueError('전체 무어획/미조업과 어획 기록 충돌')
 if d['outcome']=='CAUGHT' and not d['catches']:missing.append('catches')
 if d['outcome']=='CAUGHT' and d['catches'] and all(r['outcome']=='NO_CATCH' for r in d['catches']):raise ValueError('어획 있음과 모든 어종의 무어획이 충돌합니다.')
 return d,missing

def source_work(c,source,work_id):
 s=c.execute('SELECT * FROM sources WHERE id=?',(source,)).fetchone()
 if not s:raise ValueError('자료군 없음')
 w=works(snapshot(c,s['head'])).get(work_id)
 if not w:raise ValueError('현재 판본의 작업이 아닙니다.')
 return s,w

def save_decision(root,source,work_id,head,evidence_sha,previous_id,data,status,reviewer,reason,allow_missing=False):
 if status not in ('DRAFT','CONFIRMED'):raise ValueError('저장 상태 오류')
 if not isinstance(reviewer,str) or not reviewer.strip():raise ValueError('검토자 이름을 입력하세요.')
 if not isinstance(reason,str) or not reason.strip():raise ValueError('확인·수정 사유를 입력하세요.')
 d,missing=normalize_fields(data)
 with LOCK:
  c=connect(root)
  try:
   c.execute('BEGIN IMMEDIATE');s,w=source_work(c,source,work_id)
   if head!=s['head'] or evidence_sha!=w['fingerprint']:raise ValueError('원자료가 갱신됐습니다. 새 근거로 다시 검토하세요.')
   prev=c.execute('SELECT * FROM latest_decisions WHERE source_id=? AND work_id=?',(source,work_id)).fetchone()
   if previous_id!=(prev['id'] if prev else None):raise ValueError('다른 검토 결과가 저장됐습니다. 다시 여세요.')
   if w['is_mock'] and d['record_class']=='FIELD':raise ValueError('모의 원자료를 현장 실측으로 확정할 수 없습니다.')
   if d['deployment_date'] and d['deployment_date']>datetime.datetime.fromtimestamp(w['started_ms']/1000,datetime.timezone(datetime.timedelta(hours=9))).date().isoformat():raise ValueError('투망일이 기록일보다 미래입니다.')
   if any(r['status']!='COMPLETED' for r in w['evidence']['recordings']):missing.append('incomplete_audio')
   if w['evidence']['operation'] and w['evidence']['operation']['status']!='COMPLETED':missing.append('interrupted_operation')
   if not w['evidence']['recordings']:missing.append('recordings')
   if any(e['type']=='GEAR_WORK_CONTEXT' for e in w['evidence']['events']) and d['gear_type'] is None:missing.append('gear_type')
   if status=='CONFIRMED' and missing and allow_missing is not True:raise ValueError('미확인 항목: '+', '.join(missing_label(k) for k in missing)+' — 보완하거나 미확인 포함 확정을 선택하세요.')
   # Nulls remain null. Explicit reason is always required, including partial confirmations.
   ident=str(uuid.uuid4())
   c.execute('INSERT INTO decisions(id,source_id,work_id,snapshot_sha,evidence_sha,previous_id,created_ms,reviewer,status,reason,payload,missing) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',(ident,source,work_id,head,evidence_sha,previous_id,now(),reviewer.strip(),status,reason.strip(),canonical(d),canonical(missing)))
   for i,r in enumerate(d['catches'],1):c.execute('INSERT INTO formal_catches VALUES(?,?,?,?,?,?,?,?)',(ident,i,r['species'],r['quantity'],r['unit'],r['weight_kg'],r['outcome'],int(r['approximate'])))
   c.commit();return {'id':ident,'status':status,'missing':missing}
  except Exception:c.rollback();raise
  finally:c.close()

def detail(root,source,work_id):
 c=connect(root)
 try:
  s,w=source_work(c,source,work_id);w['source_id']=source;w['head']=s['head']
  w['history']=[dict(r) for r in c.execute('SELECT * FROM decisions WHERE source_id=? AND work_id=? ORDER BY seq',(source,work_id))]
  audio={r['recording_id']:r['sha'] for r in c.execute('SELECT * FROM audio WHERE snapshot_sha=?',(s['head'],))}
  w['audio']={rid:'/audio/'+sha+'.m4a' for rid,sha in audio.items() if rid in {r['id'] for r in w['evidence']['recordings']}}
  return w
 finally:c.close()

def overview(root):
 c=connect(root)
 try:
  sources=[];items=[]
  for s in c.execute('SELECT * FROM sources ORDER BY id'):
   source=dict(s);d=snapshot(c,s['head']);source['counts']={t:len(d[t]) for t in TABLES if t in d};sources.append(source)
   for w in works(d).values():
    dec=c.execute('SELECT * FROM latest_decisions WHERE source_id=? AND work_id=?',(s['id'],w['id'])).fetchone()
    state='미검토' if not dec else '재검토 필요' if dec['evidence_sha']!=w['fingerprint'] else '확정' if dec['status']=='CONFIRMED' else '검토 중'
    items.append({k:w[k] for k in ('id','session_id','started_ms','is_mock')}|{'source_id':s['id'],'status':state,'recordings':len(w['evidence']['recordings']),'missing':strict_json(dec['missing']) if dec else []})
  history=[dict(r) for r in c.execute('SELECT sha,source_id,previous_sha,receipt_no,filename,received_ms,schema_version,report,reason FROM snapshots ORDER BY received_ms DESC')]
  return {'sources':sources,'works':sorted(items,key=lambda x:(x['started_ms'],x['id']),reverse=True),'imports':history}
 finally:c.close()

def formal_export(root):
 c=connect(root)
 try:
  result=[]
  for r in c.execute('SELECT * FROM current_formal ORDER BY source_id,work_id'):
   item=dict(r);_,w=source_work(c,r['source_id'],r['work_id']);item['data']=strict_json(item.pop('payload'));item['missing']=strict_json(item['missing']);item['is_mock']=w['is_mock'];item['session_id']=w['session_id'];result.append(item)
  return {'format':'bada-pc-formal/1','exported_ms':now(),'records':result,'count':len(result),'rule':'현재 판본의 근거와 일치하는 작업별 마지막 PC 확정본. 모의 자료는 is_mock으로 구분. null은 미확인. 단위 환산·어획 자동 합산 없음.'}
 finally:c.close()

def formal_csv(root):
 rows=formal_export(root)['records'];out=io.StringIO(newline='');w=csv.writer(out)
 columns=['source_id','session_id','work_id','decision_id','is_mock',*FIELDS,'missing','reviewer','reason','catch_row',*CATCH_FIELDS]
 w.writerow(columns)
 def cell(v):
  if v is None:return ''
  if isinstance(v,str) and v[:1] in ('=','+','-','@','\t','\r'):return "'"+v
  return v
 for r in rows:
  # No catches still yields one work row; repeating work columns must not be summed.
  for i,catch in enumerate(r['data']['catches'] or [None],1):
   row=[r['source_id'],r['session_id'],r['work_id'],r['id'],r['is_mock'],*[r['data'].get(k) for k in FIELDS],canonical(r['missing']),r['reviewer'],r['reason'],i if catch else None,*[catch[k] if catch else None for k in CATCH_FIELDS]]
   w.writerow([cell(v) for v in row])
 return out.getvalue().encode('utf-8-sig')
