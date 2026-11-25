package kr.go.nifs.fieldrecorder;

import android.content.ContentValues;
import org.json.*;
import java.io.*;
import java.util.function.BooleanSupplier;

/** Crash/retry-safe state machine. Completion requires remote metadata reconciliation. */
final class DriveTransfer {
 static final int CHUNK=1024*1024;
 private final DriveQueue queue;private final DriveApi api;private final BooleanSupplier stop;
 DriveTransfer(DriveQueue queue,DriveApi api,BooleanSupplier stop){this.queue=queue;this.api=api;this.stop=stop;}
 boolean upload(String id,String folder) throws Exception {
  JSONObject job=queue.get(id);File file=queue.archive(id);long size=job.getLong("size");String sha=job.getString("sha256"),md5=job.getString("md5");
  if(!file.isFile()||file.length()!=size||!sha.equals(DriveApi.digest(file,"SHA-256")))throw new DriveApi.Failure(400,false,"전송 대기 파일 검증 실패 · 원자료를 확인해 주세요.");
  String fileId=job.isNull("file_id")?"":job.getString("file_id");if(fileId.isEmpty()){fileId=api.generateId();ContentValues v=new ContentValues();v.put("file_id",fileId);queue.update(id,v);}
  JSONObject existing=api.metadata(fileId);if(existing!=null){complete(id,existing,fileId,size,md5,sha);return true;}
  String uri=job.isNull("upload_uri")?"":job.getString("upload_uri");long offset=0;
  if(!uri.isEmpty()){
   DriveApi.Response r=api.status(uri,size);
   if(r.code==200||r.code==201){complete(id,api.metadata(fileId),fileId,size,md5,sha);return true;}
   if(r.code==404||r.code==410){clearSession(id);return false;}
   if(r.code!=308)throw DriveApi.error(r);offset=DriveApi.offset(r,size);
  }else{
   try{uri=api.start(fileId,folder,"조업기록_"+job.getLong("created_ms")+"_"+id+".zip",size,sha);}
   catch(DriveApi.Failure e){if(e.code!=409)throw e;JSONObject remote=api.metadata(fileId);if(remote==null)return false;complete(id,remote,fileId,size,md5,sha);return true;}
   ContentValues v=new ContentValues();v.put("upload_uri",uri);v.put("status","UPLOADING");queue.update(id,v);
  }
  while(offset<size){
   if(stop.getAsBoolean())return false;
   int length=(int)Math.min(CHUNK,size-offset);DriveApi.Response r=api.chunk(uri,file,offset,length);
   if(r.code==200||r.code==201){complete(id,api.metadata(fileId),fileId,size,md5,sha);return true;}
   if(r.code==404||r.code==410){clearSession(id);return false;}
   if(r.code!=308)throw DriveApi.error(r);long next=DriveApi.offset(r,size);
   if(next<=offset||next>offset+length)throw new IOException("서버 수신 위치를 다시 확인해야 합니다.");offset=next;ContentValues v=new ContentValues();v.put("offset",offset);v.put("status","UPLOADING");queue.update(id,v);
  }
  complete(id,api.metadata(fileId),fileId,size,md5,sha);return true;
 }
 private void clearSession(String id){ContentValues v=new ContentValues();v.putNull("upload_uri");v.put("offset",0);v.put("status","RETRY");queue.update(id,v);}
 private void complete(String id,JSONObject meta,String remoteId,long size,String md5,String sha) throws Exception {
  DriveApi.verify(meta,remoteId,size,md5,sha);ContentValues v=new ContentValues();v.put("status","DONE");v.put("completed_ms",System.currentTimeMillis());v.put("offset",size);v.put("error","");v.put("remote_link",meta.optString("webViewLink","https://drive.google.com/file/d/"+remoteId+"/view"));v.putNull("upload_uri");queue.update(id,v);
  // Only the verified staging ZIP is disposable. Never delete original records/audio.
  File archive=queue.archive(id);if(archive.exists())archive.delete();
 }
}
