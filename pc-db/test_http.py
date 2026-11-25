import json,zipfile,tempfile,threading,unittest,urllib.error,urllib.parse,urllib.request
from pathlib import Path
from http.server import ThreadingHTTPServer
import app,store
from test_pc import BASE

class HTTP(unittest.TestCase):
 def setUp(self):
  if not BASE.exists():self.skipTest('시험 기기 내보내기 자료가 없어 건너뜀(공개 저장소 미포함)')
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
  self.server=ThreadingHTTPServer(('127.0.0.1',0),app.Handler);self.server.data_root=self.root
  self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start();self.addCleanup(self.stop)
  self.url='http://127.0.0.1:'+str(self.server.server_port)
 def stop(self):self.server.shutdown();self.server.server_close();self.thread.join()
 def request(self,path,body=None,headers=None):
  h={'X-Local-Import':'1','Origin':self.url};h.update(headers or {})
  if isinstance(body,dict):body=json.dumps(body).encode();h['Content-Type']='application/json'
  with urllib.request.urlopen(urllib.request.Request(self.url+path,data=body,headers=h),timeout=5) as r:return r.status,r.headers,r.read()
 def test_preview_commit_review_export_audio_and_audit(self):
  h={'X-Source':urllib.parse.quote('시험기기'),'X-Filename':'test.zip'}
  preview=json.loads(self.request('/api/preview',BASE.read_bytes(),h)[2]);self.assertEqual(len(store.overview(self.root)['sources']),0)
  result=json.loads(self.request('/api/import',{'token':preview['token'],'previous_sha':None})[2]);self.assertTrue(result['verified'])
  overview=json.loads(self.request('/api/overview')[2]);work=overview['works'][0]
  w=json.loads(self.request('/api/work?'+urllib.parse.urlencode({'source':work['source_id'],'work':work['id']}))[2])
  self.assertEqual(len(w['evidence']['recordings']),2)
  d=store.blank_fields();d.update(record_class='TEST',work_type='통발 양망',gear_type='POT',gear_unit='개',haul_quantity=30,disposition='REMOVED',outcome='CAUGHT',catches=[dict(species='꽃게',quantity=20,unit='마리',weight_kg=4,outcome='CAUGHT',approximate=False)])
  args=dict(source=w['source_id'],work_id=w['id'],head=w['head'],evidence_sha=w['fingerprint'],previous_id=None,data=d,status='CONFIRMED',reviewer='HTTP 시험',reason='투망일 미확인',allow_missing=True)
  decision=json.loads(self.request('/api/decision',args)[2]);self.assertEqual(decision['missing'],['deployment_date'])
  exported=json.loads(self.request('/api/formal.json')[2]);self.assertEqual(exported['count'],1);self.assertIsNone(exported['records'][0]['data']['deployment_date']);self.assertEqual(exported['records'][0]['data']['catches'][0]['weight_kg'],4)
  self.assertIn('꽃게',self.request('/api/formal.csv')[2].decode('utf-8-sig'))
  with zipfile.ZipFile(BASE) as z:
   a=max(json.loads(z.read('manifest.json'))['audio_files'],key=lambda a:a['bytes']);expected=z.read(a['path'])[:16]
  audio=w['audio'][a['recording_id']];code,headers,body=self.request(audio,headers={'Range':'bytes=0-15'});self.assertEqual(code,206);self.assertEqual(body,expected)
  self.assertTrue(json.loads(self.request('/api/audit',{})[2])['verified'])
 def test_rejects_foreign_origin_and_missing_local_header(self):
  for headers in ({'Origin':'http://evil.example'},{'X-Local-Import':'0'}):
   with self.assertRaises(urllib.error.HTTPError) as error:self.request('/api/audit',{},headers)
   self.assertEqual(error.exception.code,403)
 def test_html_js_css_health_served_and_no_arbitrary_paths(self):
  for path in ('/','/app.js','/style.css','/api/health'):self.assertEqual(self.request(path)[0],200)
  with self.assertRaises(urllib.error.HTTPError) as error:self.request('/../store.py')
  self.assertEqual(error.exception.code,404)

if __name__=='__main__':unittest.main(verbosity=2)
