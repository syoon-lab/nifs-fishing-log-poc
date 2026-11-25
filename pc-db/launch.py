#!/usr/bin/env python3
"""Double-click launcher. Uses this app's own data folder and loopback port."""
import json,subprocess,sys,time,urllib.request,webbrowser
from pathlib import Path
ROOT=Path(__file__).resolve().parent
URL='http://127.0.0.1:8771'
def running():
 try:
  with urllib.request.urlopen(URL+'/api/health',timeout=1) as r:body=json.load(r)
  if body.get('application')!='bada-pc/1' or body.get('data_root')!=str((ROOT/'data').resolve()):raise RuntimeError('8771 포트를 다른 자료 폴더의 앱이 사용 중입니다.')
  return True
 except urllib.error.URLError:return False
if not running():
 (ROOT/'data').mkdir(exist_ok=True)
 with (ROOT/'data/server.log').open('ab') as log:
  subprocess.Popen([sys.executable,str(ROOT/'app.py'),'--data',str(ROOT/'data'),'serve','--port','8771'],cwd=ROOT,stdout=log,stderr=log,start_new_session=True)
 for _ in range(30):
  if running():break
  time.sleep(.2)
 else:raise SystemExit('실행 실패: data/server.log를 확인하세요.')
webbrowser.open(URL)
print('조업기록 PC를 열었습니다: '+URL)
