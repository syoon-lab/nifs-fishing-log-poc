"""Integration/regression tests; only temporary databases, never production data."""
import copy,csv,hashlib,io,json,sqlite3,tempfile,unittest,zipfile
from pathlib import Path
from unittest.mock import patch
import store
ROOT=Path(__file__).resolve().parent.parent
OLD=ROOT/'보관/legacy/db-viewer/가져온자료/에뮬레이터_모의기록_261102.zip'
V2=ROOT/'android-recorder/검증자료/2026-11-06_작업순서/화면_실제내보내기.zip'
V3=ROOT/'android-recorder/검증자료/2026-11-07_온디바이스/ui-export.zip'
V4=ROOT/'android-recorder/검증자료/2026-11-08_0.6.2_내보내기/실제내보내기.zip'
BASE=ROOT/'android-recorder/검증자료/2026-11-09_음성누락질문/question-real-asr.zip'
MIGRATED=ROOT/'android-recorder/검증자료/2026-11-09_음성누락질문/gear-migrated-originals.zip'
FLOW=ROOT/'android-recorder/검증자료/2026-11-09_음성누락질문/questions-flow.zip'
FIXTURES=[OLD,V2,V3,V4,BASE,MIGRATED,FLOW]  # 시험 기기 내보내기 자료: 공개 저장소에는 넣지 않음

class PC(unittest.TestCase):
 def setUp(self):
  missing=[p.name for p in FIXTURES if not p.exists()]
  if missing:self.skipTest('시험 기기 내보내기 자료가 없어 건너뜀(공개 저장소 미포함): '+', '.join(missing))
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.db=self.root/'db';self.counter=0
 def repack(self,change=lambda d:None,source=BASE,drop=None,extra=None,csv_change=None):
  with zipfile.ZipFile(source) as z:parts={n:z.read(n) for n in z.namelist()}
  d=json.loads(parts['records.json']);d['exported_at_ms']+=1000;change(d);m=json.loads(parts['manifest.json'])
  parts['records.json']=store.canonical(d).encode()
  for t in store.TABLES:
   if t not in d:continue
   keys=next(csv.reader(io.StringIO(parts[t+'.csv'].decode('utf-8-sig'))));out=io.StringIO(newline='');writer=csv.writer(out);writer.writerow(keys)
   for r in d[t]:
    row=[]
    for k in keys:
     v=r[k]
     if isinstance(v,str) and v[:1] in ('=','+','@','-','\t','\r'):v="'"+v
     row.append(v)
    writer.writerow(row)
   parts[t+'.csv']=out.getvalue().encode('utf-8-sig');m['counts'][t]=len(d[t])
  parts['manifest.json']=store.canonical(m).encode()
  if csv_change:csv_change(parts)
  if drop:parts.pop(drop)
  if extra:parts.update(extra)
  self.counter+=1;p=self.root/f'case{self.counter}.zip'
  with zipfile.ZipFile(p,'w') as z:
   for n,b in parts.items():z.writestr(n,b)
  return p
 def initial(self):return store.import_zip(BASE,self.db,'시험기기')
 def w(self):
  o=store.overview(self.db)['works'][0];return store.detail(self.db,o['source_id'],o['id'])
 def decision(self,status='CONFIRMED',data=None,**kw):
  w=self.w();d=store.blank_fields();d.update(work_type='통발 양망',gear_type='POT',gear_unit='개',haul_quantity=30,disposition='REMOVED',outcome='CAUGHT',catches=[dict(species='꽃게',quantity=20,unit='마리',weight_kg=4,outcome='CAUGHT',approximate=False)])
  return store.save_decision(self.db,w['source_id'],w['id'],w['head'],w['fingerprint'],w['history'][-1]['id'] if w['history'] else None,data or d,status,'시험 검토자','투망일 미확인; 원음 대조',**({'allow_missing':True}|kw))
 def state(self):
  c=store.connect(self.db)
  try:return {t:[tuple(r) for r in c.execute('SELECT * FROM '+t)] for t in ('sources','snapshots','raw_rows','audio','work_index','decisions','formal_catches')}
  finally:c.close()
 def test_all_schema_versions_preserve_original_values_order_audio(self):
  for source in (OLD,V2,V3,V4,BASE,MIGRATED,FLOW):
   with self.subTest(source=source.name):
    db=self.root/('db'+str(len(list(self.root.iterdir()))));r=store.import_zip(source,db,'source')
    with zipfile.ZipFile(source) as z:d=json.loads(z.read('records.json'));m=json.loads(z.read('manifest.json'))
    c=sqlite3.connect(db/'records.sqlite3')
    for t in m['counts']:
     rows=[json.loads(x[0]) for x in c.execute('SELECT payload FROM raw_rows WHERE table_name=? ORDER BY ordinal',(t,))];self.assertEqual(rows,d[t]);self.assertEqual(len(rows),m['counts'][t])
    for a in m['audio_files']:
     b=(db/'audio'/(a['sha256']+'.m4a')).read_bytes();self.assertEqual(len(b),a['bytes']);self.assertEqual(hashlib.sha256(b).hexdigest(),a['sha256'])
    c.close();self.assertEqual((db/'archives'/(r['archive_sha256']+'.zip')).read_bytes(),source.read_bytes())
 def test_new_gear_extractor_version_preserves_import_and_rejects_unknown(self):
  def change(d,version):
   for row in d['structured_logs']:
    if row['phase']=='WORK':
     data=json.loads(row['data_json']);data['version']=version;row['data_json']=store.canonical(data);row['extractor_version']=version
  updated=self.repack(lambda d:change(d,'gillnet-pot/1.1'))
  store.import_zip(updated,self.db,'시험기기')
  c=store.connect(self.db)
  try:
   logs=[json.loads(r[0]) for r in c.execute("SELECT payload FROM raw_rows WHERE table_name='structured_logs'")]
   self.assertTrue(any(r['extractor_version']=='gillnet-pot/1.1' for r in logs))
  finally:c.close()
  invalid=self.repack(lambda d:change(d,'gillnet-pot/99.0'))
  with self.assertRaises(ValueError):store.import_zip(invalid,self.root/'invalid','시험기기')
 def test_field_gear_extractor_version_preserves_import_and_rejects_unknown(self):
  def change(d,version):
   for row in d['structured_logs']:
    if row['phase']=='WORK':
     data=json.loads(row['data_json']);data['version']=version;row['data_json']=store.canonical(data);row['extractor_version']=version
  updated=self.repack(lambda d:change(d,'gillnet-pot/1.2'))
  store.import_zip(updated,self.db,'시험기기')
  c=store.connect(self.db)
  try:
   logs=[json.loads(r[0]) for r in c.execute("SELECT payload FROM raw_rows WHERE table_name='structured_logs'")]
   self.assertTrue(any(r['extractor_version']=='gillnet-pot/1.2' for r in logs))
  finally:c.close()
  invalid=self.repack(lambda d:change(d,'gillnet-pot/99.0'))
  with self.assertRaises(ValueError):store.import_zip(invalid,self.root/'invalid','시험기기')
 def test_idempotent_reimport(self):
  self.initial();before=self.state();self.assertTrue(store.import_zip(BASE,self.db,'시험기기')['duplicate']);self.assertEqual(before,self.state())
 def test_receipt_confirmation_and_compare_swap_required(self):
  first=self.initial();p=self.repack();before=self.state()
  with self.assertRaises(ValueError):store.import_zip(p,self.db,'시험기기')
  with self.assertRaises(ValueError):store.import_zip(p,self.db,'시험기기',first['archive_sha256'])
  self.assertEqual(before,self.state());store.import_zip(p,self.db,'시험기기',first['archive_sha256'],True)
 def test_new_history_is_kept_no_union_counts(self):
  first=self.initial()
  def change(d):
   e=copy.deepcopy(d['events'][-1]);e.update(id='000001',type='PC_TEST_RECEIPT',detail='정상 반복 기록');d['events'].append(e)
  p=self.repack(change);r=store.import_zip(p,self.db,'시험기기',first['archive_sha256'],True)
  self.assertEqual(r['tables']['events']['added'],1);c=store.connect(self.db)
  self.assertEqual(c.execute('SELECT COUNT(*) FROM snapshots').fetchone()[0],2)
  self.assertEqual(c.execute("SELECT COUNT(*) FROM current_raw WHERE table_name='events'").fetchone()[0],13);c.close()
 def test_missing_old_rows_stop_update_no_fallback(self):
  first=self.initial();before=self.state();p=self.repack(lambda d:d['events'].pop())
  with self.assertRaisesRegex(ValueError,'누락'):store.import_zip(p,self.db,'시험기기',first['archive_sha256'],True)
  self.assertEqual(before,self.state())
 def test_prior_snapshot_cannot_be_reselected(self):
  first=self.initial();p=self.repack();store.import_zip(p,self.db,'시험기기',first['archive_sha256'],True);before=self.state()
  with self.assertRaisesRegex(ValueError,'과거'):store.import_zip(BASE,self.db,'시험기기')
  self.assertEqual(before,self.state())
 def test_changed_rows_require_review_preserve_both_versions(self):
  first=self.initial();p=self.repack(lambda d:d['recordings'][0].update(label='정정 제목'));before=self.state()
  with self.assertRaisesRegex(ValueError,'기존 행 변경'):store.import_zip(p,self.db,'시험기기',first['archive_sha256'],True)
  self.assertEqual(before,self.state());r=store.import_zip(p,self.db,'시험기기',first['archive_sha256'],True,'제목 정정 확인',True)
  self.assertEqual(r['tables']['recordings']['changed'],1);c=store.connect(self.db)
  self.assertNotEqual(store.snapshot(c,first['archive_sha256'])['recordings'][0]['label'],store.snapshot(c,r['archive_sha256'])['recordings'][0]['label']);c.close()
 def test_other_source_overlap_rejected(self):
  self.initial();p=self.repack()
  with self.assertRaisesRegex(ValueError,'겹칩니다'):store.import_zip(p,self.db,'다른 이름')
 def test_all_tables_unknown_and_missing_csv_rejected(self):
  for p in (self.repack(lambda d:d.update(unexpected=[])),self.repack(drop='gear_details.csv'),self.repack(extra={'unknown.csv':b'a,b'})):
   with self.assertRaises((ValueError,KeyError)):store.import_zip(p,self.db,'new')
 def test_duplicate_id_rejected(self):
  p=self.repack(lambda d:d['events'].append(copy.deepcopy(d['events'][0])))
  with self.assertRaisesRegex(ValueError,'duplicate identifier'):store.validate(p)
 def test_csv_value_disagreement_rejected(self):
  p=self.repack(csv_change=lambda parts:parts.update({'recordings.csv':parts['recordings.csv'].replace(b'COMPLETED',b'FAILED',1)}))
  with self.assertRaisesRegex(ValueError,'CSV value mismatch'):store.validate(p)
 def test_missing_corrupt_or_extra_audio_rejected(self):
  with zipfile.ZipFile(BASE) as z:path=json.loads(z.read('records.json'))['recordings'][0]['path']
  for p in (self.repack(drop=path),self.repack(extra={path:b'corrupt'}),self.repack(extra={'audio/orphan.m4a':b'orphan'})):
   with self.assertRaises((ValueError,KeyError)):store.validate(p)
 def test_event_reference_and_version_rejected(self):
  def badref(d):d['events'][0]['recording_id']='missing'
  def version(d):
   e=next(e for e in d['events'] if e['type'].startswith('FIELD_'));v=json.loads(e['detail']);v['version']=999;e['detail']=store.canonical(v)
  for change in (badref,version):
   with self.assertRaises(ValueError):store.validate(self.repack(change))
 def test_gear_typed_json_mismatch_rejected(self):
  with self.assertRaisesRegex(ValueError,'Gear details'):store.validate(self.repack(lambda d:d['gear_details'][0].update(haul_quantity=999)))
 def test_transaction_and_files_rollback_on_failure(self):
  with patch('store.audit_snapshot',side_effect=ValueError('simulated failure')):
   with self.assertRaisesRegex(ValueError,'simulated'):self.initial()
  self.assertFalse(self.state()['snapshots']);self.assertFalse(list((self.db/'archives').iterdir()));self.assertFalse(list((self.db/'audio').iterdir()))
 def test_normal_repeated_rows_and_leading_zero_ids_preserved(self):
  def repeat(d):
   a=copy.deepcopy(d['events'][-1]);a.update(id='000001',type='REPEAT',detail='same');b=copy.deepcopy(a);b['id']='000002';d['events'] += [a,b]
  p=self.repack(repeat);store.import_zip(p,self.db,'repeat');c=store.connect(self.db)
  rows=c.execute("SELECT row_id FROM raw_rows WHERE row_id IN ('000001','000002') ORDER BY ordinal").fetchall();self.assertEqual([r[0] for r in rows],['000001','000002']);c.close()
 def test_no_automatic_confirmation_or_supplement_double_count(self):
  self.initial();self.assertEqual(store.formal_export(self.db)['count'],0);self.assertEqual(len(store.overview(self.db)['works']),1);self.assertEqual(len(self.w()['evidence']['recordings']),2)
  self.decision();result=store.formal_export(self.db);self.assertEqual(result['count'],1);self.assertEqual(result['records'][0]['data']['haul_quantity'],30);self.assertEqual(result['records'][0]['data']['catches'][0]['quantity'],20)
 def test_missing_ack_and_null_preserved(self):
  self.initial()
  with self.assertRaisesRegex(ValueError,'미확인 항목'):self.decision(allow_missing=False)
  self.decision();d=store.formal_export(self.db)['records'][0];self.assertIsNone(d['data']['deployment_date']);self.assertIn('deployment_date',d['missing'])
 def test_decision_revisions_latest_only_and_hold_excludes(self):
  self.initial();first=self.decision();d=store.formal_export(self.db)['records'][0]['data'];d['haul_quantity']=31;second=self.decision(data=d)
  self.assertEqual(len(self.w()['history']),2);self.assertEqual(self.w()['history'][-1]['previous_id'],first['id']);self.assertEqual(store.formal_export(self.db)['count'],1)
  self.decision(status='DRAFT',data=d);self.assertEqual(store.formal_export(self.db)['count'],0);self.assertEqual(len(self.w()['history']),3)
 def test_new_evidence_invalidates_old_final_but_preserves_it(self):
  first=self.initial();self.decision();p=self.repack(lambda d:d['recordings'][0].update(label='new evidence'))
  store.import_zip(p,self.db,'시험기기',first['archive_sha256'],True,'정정',True)
  self.assertEqual(store.formal_export(self.db)['count'],0);self.assertEqual(store.overview(self.db)['works'][0]['status'],'재검토 필요');self.assertEqual(len(self.w()['history']),1)
 def test_identical_evidence_new_export_keeps_final_valid(self):
  first=self.initial();self.decision();p=self.repack();store.import_zip(p,self.db,'시험기기',first['archive_sha256'],True);self.assertEqual(store.formal_export(self.db)['count'],1)
 def test_stale_review_rejected(self):
  self.initial();w=self.w();self.decision()
  with self.assertRaisesRegex(ValueError,'다른 검토'):store.save_decision(self.db,w['source_id'],w['id'],w['head'],w['fingerprint'],None,store.blank_fields(),'DRAFT','검토자','stale')
 def test_contradictions_and_invalid_numbers_rejected(self):
  self.initial();self.decision();d=store.formal_export(self.db)['records'][0]['data']
  for key,value in [('outcome','NO_CATCH'),('haul_quantity',-1),('gear_unit','폭'),('redeploy_quantity',2),('deployment_date','2099-01-01')]:
   v=copy.deepcopy(d);v[key]=value
   with self.subTest(key=key),self.assertRaises(ValueError):self.decision(data=v)
 def test_same_species_repetition_and_different_units_not_deduplicated(self):
  self.initial();self.decision();d=store.formal_export(self.db)['records'][0]['data'];d['catches']*=2;d['catches'].append(dict(species='꽃게',quantity=2,unit='상자',weight_kg=None,outcome='CAUGHT',approximate=True));self.decision(data=d)
  got=store.formal_export(self.db)['records'][0]['data']['catches'];self.assertEqual(len(got),3);self.assertEqual(got[2]['unit'],'상자');self.assertIsNone(got[2]['weight_kg'])
  rows=list(csv.DictReader(io.StringIO(store.formal_csv(self.db).decode('utf-8-sig'))));self.assertEqual(len(rows),3)
 def test_raw_and_decision_history_immutable(self):
  self.initial();self.decision();c=store.connect(self.db)
  for sql in ("UPDATE raw_rows SET payload='{}'","DELETE FROM decisions"):
   with self.assertRaises(sqlite3.IntegrityError):c.execute(sql)
  c.close()
 def test_archive_corruption_detected_on_repeat_import(self):
  first=self.initial();p=self.db/'archives'/(first['archive_sha256']+'.zip');p.write_bytes(b'bad')
  with self.assertRaisesRegex(ValueError,'변조'):store.import_zip(BASE,self.db,'시험기기')

 def test_extra_csv_cell_rejected(self):
  def change(parts):
   lines=parts['events.csv'].decode('utf-8-sig').splitlines();lines[-1]+=',unexpected';parts['events.csv']='\r\n'.join(lines).encode('utf-8-sig')
  with self.assertRaisesRegex(ValueError,'malformed CSV'):store.validate(self.repack(csv_change=change))
 def test_duplicate_json_keys_rejected(self):
  p=self.repack(extra={'records.json':b'{"schema_version":5,"schema_version":4}'})
  with self.assertRaisesRegex(ValueError,'Duplicate JSON'):store.validate(p)
 def test_source_schema_upgrade_preserves_original_and_requires_review(self):
  first=store.import_zip(V4,self.db,'S24')
  preview=store.preview(MIGRATED,self.db,'S24');self.assertTrue(preview['counts']['recordings']>=10)
  store.import_zip(MIGRATED,self.db,'S24',first['archive_sha256'],True,'기존 항목 보존 확인',True)
  c=store.connect(self.db);self.assertEqual(len(store.snapshot(c,first['archive_sha256'])['recordings']),10);c.close()
 def test_unknown_field_event_version_stops_import(self):
  def change(d):
   e=copy.deepcopy(d['events'][-1]);e.update(id='unknown-field',type='FIELD_UNKNOWN',detail='{"version":2}');d['events'].append(e)
  with self.assertRaisesRegex(ValueError,'Unknown field event'):store.validate(self.repack(change))
 def test_original_s24_snapshot_reconstruction(self):
  from recover_snapshot import recover
  src=ROOT/'android-recorder/검증자료/2026-11-09_S24_0.8.1/시험후.tar'
  if not src.exists():self.skipTest('S24 원본 덤프(약 600MB)는 저장소에 포함하지 않음')
  report=recover(src,self.root/'recovered.zip',self.root/'provenance.json')
  self.assertEqual(report['total_rows'],277);self.assertEqual(report['audio'],20);self.assertEqual(report['excluded_data_rows'],0)
  store.import_zip(self.root/'recovered.zip',self.db,'S24 recovered');self.assertEqual(store.formal_export(self.db)['count'],0)

if __name__=='__main__':unittest.main(verbosity=2)
