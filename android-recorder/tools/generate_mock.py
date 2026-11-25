from pathlib import Path
import subprocess,json,datetime,tempfile,hashlib
out=Path(__file__).resolve().parents[1]/'app/src/main/assets/demo'
out.mkdir(parents=True,exist_ok=True)
specs=[('고등어',12,10,5,35.10,129.20),('전갱이',8,10,15,35.11,129.22),('삼치',5,10,25,35.12,129.24)]
rows=[]
with tempfile.TemporaryDirectory() as td:
 for i,(fish,kg,h,m,lat,lon) in enumerate(specs,1):
  text=f'2026년 11월 2일 오전 {h}시 {m}분, {fish} {kg}킬로그램 잡았다.'
  ai=Path(td)/f'{i}.aiff';dest=out/f'{i}.m4a'
  subprocess.run(['say','-v','Yuna','-r','155','-o',str(ai),text],check=True)
  subprocess.run(['/opt/homebrew/bin/ffmpeg','-v','error','-y','-i',str(ai),'-ac','1','-ar','44100','-c:a','aac','-b:a','96k',str(dest)],check=True)
  duration=float(subprocess.check_output(['/opt/homebrew/bin/ffprobe','-v','error','-show_entries','format=duration','-of','default=nw=1:nk=1',str(dest)],text=True))
  ts=int(datetime.datetime(2026,11,2,h,m,tzinfo=datetime.timezone(datetime.timedelta(hours=9))).timestamp()*1000)
  rows.append(dict(asset=f'demo/{i}.m4a',transcript=text,species=fish,catch_kg=kg,latitude=lat,longitude=lon,started_ms=ts,duration_ms=round(duration*1000),sha256=hashlib.sha256(dest.read_bytes()).hexdigest()))
(out/'samples.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2))
print(json.dumps(rows,ensure_ascii=False,indent=2))
