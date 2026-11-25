#!/usr/bin/env python3
"""Local PC review app. Standard library only. Bind to loopback."""
import argparse,json,mimetypes,re,tempfile,uuid
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs,urlsplit,unquote
import store
import processing
import pipeline
ROOT=Path(__file__).resolve().parent

def controller(server):
 if not getattr(server, 'processor', None):server.processor=pipeline.Controller(server.data_root)
 return server.processor

def transcript_search_text(run):
 data=run.get('input',{});review=data.get('transcript_review')
 original=data.get('transcript_parent',{}) if review is not None else run
 return ' '.join(str(text or '') for text in (original.get('asr_text'),original.get('dialect_text'),
                                             review.get('text') if isinstance(review,dict) else None))

def enriched_overview(root):
 result=store.overview(root)
 runs=processing.list_runs(root)
 c=store.connect(root)
 try:
  search={}
  for source in result['sources']:
   for key,w in store.works(store.snapshot(c,source['head'])).items():
    e=w['evidence']
    search[(source['id'],key)]=' '.join(str(x.get(field) or '') for table,field in [('recordings','label'),('transcriptions','text'),('transcript_reviews','text')] for x in e[table])
  for work in result['works']:
   related=[r for r in runs if r['source_id']==work['source_id'] and r['work_id']==work['id'] and r.get('current',True)]
   latest={}
   for r in related:latest.setdefault(r['recording_id'],r)
   states=[r['status'] for r in latest.values()]
   work['pc_status']=next((s for s in ['RUNNING','QUEUED','FAILED','INTERRUPTED'] if s in states),'COMPLETED' if states and len(states)==work['recordings'] else 'NOT_PROCESSED')
   work['search_text']=search[(work['source_id'],work['id'])]+' '+ ' '.join(transcript_search_text(r) for r in related)
  return result
 finally:c.close()

class Handler(BaseHTTPRequestHandler):
 server_version='FishingLogPC/1.0'
 def allowed(self):return self.headers.get('Host') in (f'127.0.0.1:{self.server.server_port}',f'localhost:{self.server.server_port}')
 def send(self,body,kind='application/json; charset=utf-8',status=200,headers=None):
  self.send_response(status)
  for k,v in {'Content-Type':kind,'Content-Length':str(len(body)),'Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer','Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self'; media-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'",**(headers or {})}.items():self.send_header(k,v)
  self.end_headers();self.wfile.write(body)
 def json(self,v,status=200):self.send(store.canonical(v).encode(),status=status)
 def do_GET(self):
  if not self.allowed():return self.json({'error':'로컬 접속만 허용합니다.'},403)
  u=urlsplit(self.path);q=parse_qs(u.query);root=self.server.data_root
  try:
   if u.path=='/api/health':return self.json({'application':'bada-pc/1','data_root':str(root.resolve())})
   if u.path=='/api/overview':return self.json(enriched_overview(root))
   if u.path=='/api/processing':return self.json(controller(self.server).status())
   if u.path=='/api/work':return self.json(store.detail(root,q['source'][0],q['work'][0])|{'pc_runs':processing.list_runs(root,source=q['source'][0],work_id=q['work'][0])})
   if u.path=='/api/formal.json':return self.send(store.canonical(store.formal_export(root)).encode(),headers={'Content-Disposition':'attachment; filename="fishing-log-formal.json"'})
   if u.path=='/api/formal.csv':return self.send(store.formal_csv(root),'text/csv; charset=utf-8',headers={'Content-Disposition':'attachment; filename="fishing-log-formal.csv"'})
   if re.fullmatch(r'/audio/[0-9a-f]{64}\.m4a',u.path):
    p=root/'audio'/u.path.rsplit('/',1)[1]
    if not p.is_file():return self.json({'error':'원음 없음'},404)
    size=p.stat().st_size;start=0;end=size-1;status=200
    if self.headers.get('Range'):
     m=re.fullmatch(r'bytes=(\d+)-(\d*)',self.headers['Range'])
     if not m:return self.json({'error':'범위 오류'},416)
     start=int(m[1]);end=min(int(m[2]) if m[2] else size-1,size-1);status=206
     if start>end:return self.json({'error':'범위 초과'},416)
    self.send_response(status);self.send_header('Content-Type','audio/mp4');self.send_header('Content-Length',str(max(0,end-start+1)));self.send_header('Accept-Ranges','bytes');self.send_header('Cache-Control','no-store')
    if status==206:self.send_header('Content-Range',f'bytes {start}-{end}/{size}')
    self.end_headers()
    with p.open('rb') as f:
     f.seek(start);left=end-start+1
     while left>0:
      b=f.read(min(65536,left))
      if not b:break
      self.wfile.write(b);left-=len(b)
    return
   files={'/':'index.html','/app.js':'app.js','/style.css':'style.css'}
   if u.path in files:
    p=ROOT/'static'/files[u.path];return self.send(p.read_bytes(),(mimetypes.guess_type(p)[0] or 'text/plain')+'; charset=utf-8')
   return self.json({'error':'찾을 수 없습니다.'},404)
  except (BrokenPipeError,ConnectionResetError):pass
  except Exception as e:self.json({'error':str(e)},400)
 def do_POST(self):
  origin=self.headers.get('Origin')
  if not self.allowed() or origin and origin!='http://'+self.headers.get('Host','') or self.headers.get('X-Local-Import')!='1':return self.json({'error':'같은 로컬 화면에서 실행하세요.'},403)
  root=self.server.data_root
  try:
   length=int(self.headers.get('Content-Length','0'));self.connection.settimeout(60)
   if self.path=='/api/preview':
    if not 0<length<=store.MAX_ZIP:raise ValueError('ZIP 크기 한도 1 GiB')
    source=unquote(self.headers.get('X-Source',''));name=Path(unquote(self.headers.get('X-Filename','export.zip'))).name
    with tempfile.TemporaryDirectory(prefix='bada-stage-') as td:
     p=Path(td)/'upload.zip'
     with p.open('wb') as f:
      left=length
      while left:
       b=self.rfile.read(min(1024*1024,left))
       if not b:raise ValueError('업로드 중단')
       f.write(b);left-=len(b)
     report=store.preview(p,root,source);staging=root/'incoming';staging.mkdir(exist_ok=True)
     token=uuid.uuid4().hex;target=staging/(token+'.zip');p.replace(target)
     (staging/(token+'.json')).write_text(store.canonical({'source':source,'filename':name,'sha':report['archive_sha256']}))
    return self.json(report|{'token':token})
   if not 0<length<=4*1024**2:raise ValueError('요청 크기 오류')
   data=store.strict_json(self.rfile.read(length))
   if self.path=='/api/import':
    token=data['token']
    if not re.fullmatch('[a-f0-9]{32}',token):raise ValueError('잘못된 검증 번호')
    p=root/'incoming'/(token+'.zip');meta=store.strict_json(p.with_suffix('.json').read_text())
    if store.sha_file(p)!=meta['sha']:raise ValueError('검증 후 파일 변경')
    result=store.import_zip(p,root,meta['source'],previous_sha=data.get('previous_sha'),received_after=data.get('received_after') is True,reason=data.get('reason',''),allow_changes=data.get('allow_changes') is True,filename=meta['filename'])
    p.unlink();p.with_suffix('.json').unlink();return self.json(result)
   if self.path=='/api/process':return self.json(controller(self.server).enqueue(data['source'],data.get('work_id'),data.get('dialect')))
   if self.path=='/api/reextract':
    required={'source','work_id','parent_run_id','head','evidence_sha','selection','text','reviewer','reason'}
    if not isinstance(data,dict) or set(data)!=required:raise ValueError('전사 검토 요청의 항목을 확인하세요.')
    return self.json(controller(self.server).enqueue_review(**data))
   if self.path=='/api/decision':return self.json(store.save_decision(root,**data))
   if self.path=='/api/audit':
    c=store.connect(root)
    try:
     result=[store.audit_snapshot(c,root,r[0]) for r in c.execute('SELECT sha FROM snapshots')]
     return self.json({'verified':True,'snapshots':result})
    finally:c.close()
   self.json({'error':'찾을 수 없습니다.'},404)
  except Exception as e:self.json({'error':str(e)},400)

def main():
 p=argparse.ArgumentParser();p.add_argument('--data',type=Path,default=ROOT/'data');sub=p.add_subparsers(dest='command',required=True)
 s=sub.add_parser('serve');s.add_argument('--port',type=int,default=8771)
 v=sub.add_parser('preview');v.add_argument('zip',type=Path);v.add_argument('--source',required=True)
 i=sub.add_parser('import');i.add_argument('zip',type=Path);i.add_argument('--source',required=True);i.add_argument('--previous-sha');i.add_argument('--received-after',action='store_true');i.add_argument('--allow-changes',action='store_true');i.add_argument('--reason',default='')
 sub.add_parser('audit');sub.add_parser('export')
 a=p.parse_args()
 if a.command=='preview':result=store.preview(a.zip,a.data,a.source)
 elif a.command=='import':result=store.import_zip(a.zip,a.data,a.source,a.previous_sha,a.received_after,a.reason,a.allow_changes)
 elif a.command=='export':result=store.formal_export(a.data)
 elif a.command=='audit':
  c=store.connect(a.data)
  try:result=[store.audit_snapshot(c,a.data,r[0]) for r in c.execute('SELECT sha FROM snapshots')]
  finally:c.close()
 else:
  store.connect(a.data).close();server=ThreadingHTTPServer(('127.0.0.1',a.port),Handler);server.data_root=a.data;server.processor=pipeline.Controller(a.data)
  print(f'조업기록 PC: http://127.0.0.1:{a.port}',flush=True)
  try:server.serve_forever()
  except KeyboardInterrupt:pass
  finally:server.processor.close();server.server_close()
  return
 print(json.dumps(result,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
