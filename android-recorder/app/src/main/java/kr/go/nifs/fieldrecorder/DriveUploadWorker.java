package kr.go.nifs.fieldrecorder;

import android.content.*;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.work.*;
import org.json.*;
import java.io.*;

public final class DriveUploadWorker extends Worker {
 private static final Object SERIAL=new Object();
 public DriveUploadWorker(@NonNull Context c,@NonNull WorkerParameters p){super(c,p);}
 @NonNull @Override public Result doWork(){synchronized(SERIAL){return transfer();}}
 private Result transfer(){
  Context c=getApplicationContext();if(!DriveAuth.enabled(c))return Result.success();String current=null,token=null;long deadline=SystemClock.elapsedRealtime()+7*60*1000;
  try(DriveQueue q=new DriveQueue(c)){
   DriveSync.reconcile(c,RecorderApp.store(),q);JSONObject job=q.next(DriveAuth.owner(c));if(job==null)return Result.success();
   while(job!=null&&!isStopped()&&DriveAuth.enabled(c)&&SystemClock.elapsedRealtime()<deadline){
    current=job.getString("id");
    if(!DriveAuth.email(c).equals(job.getString("email"))||!DriveAuth.owner(c).equals(job.getString("account_key"))){q.state(current,"AUTH_REQUIRED","전송 계정이 다릅니다. 기존 계정으로 다시 연결해 주세요.");return Result.success();}
    if(!prepare(c,q,job))return Result.retry();job=q.get(current);
    token=DriveAuth.token(c,job.getString("email"));DriveApi api=new DriveApi(token);JSONObject account=api.account();
    if(!job.getString("account_key").equals(account.optString("permissionId"))){q.state(current,"AUTH_REQUIRED","연결 계정이 다릅니다. 업로드를 보류했습니다.");return Result.success();}
    String folder=DriveAuth.prefs(c).getString("folder_id","");if(folder.isEmpty()){folder=api.generateId();if(!DriveAuth.prefs(c).edit().putString("folder_id",folder).commit())throw new IOException("폴더 정보를 저장하지 못했습니다.");}api.ensureFolder(folder);
    ContentValues v=new ContentValues();v.put("attempts",job.getInt("attempts")+1);v.put("status","UPLOADING");v.put("error","");q.update(current,v);
    boolean done=new DriveTransfer(q,api,()->isStopped()||!DriveAuth.enabled(c)||SystemClock.elapsedRealtime()>=deadline).upload(current,folder);
    if(!done){q.state(current,"RETRY","연결되면 이어서 전송합니다.");return Result.retry();}
    DriveSync.message="구글드라이브 업로드 완료";job=q.next(DriveAuth.owner(c));
   }
   return job==null?Result.success():Result.retry();
  }catch(DriveAuth.Required e){setState(c,current,"AUTH_REQUIRED",e.getMessage());return Result.success();}
  catch(DriveApi.Failure e){if(e.code==401){if(token!=null)DriveAuth.invalidate(c,token);setState(c,current,"RETRY","계정 인증을 갱신한 뒤 다시 전송합니다.");return Result.retry();}setState(c,current,e.retry?"RETRY":"FAILED",e.getMessage());return e.retry?Result.retry():Result.success();}
  catch(Exception e){if(isStopped())return Result.retry();setState(c,current,"RETRY","연결 또는 계정 확인 후 자동 재시도합니다. 원본은 폰에 있습니다.");return Result.retry();}
 }
 private void setState(Context c,String id,String state,String message){DriveSync.message=message;if(id!=null)try(DriveQueue q=new DriveQueue(c)){q.state(id,state,message);}catch(Exception ignored){}}
 static boolean prepare(Context c,DriveQueue q,JSONObject job) throws Exception {
  if(!job.isNull("sha256"))return true;
  synchronized(RecorderApp.class){if(CaptureService.running||CaptureService.recording||TranscriptionService.running||RecorderApp.exporting)return false;RecorderApp.exporting=true;}
  File target=q.archive(job.getString("id")),part=new File(target.getPath()+".part");
  try{Store s=RecorderApp.store();synchronized(s){Archive.write(s,part);}if(target.exists()&&!target.delete())throw new IOException("기존 대기 파일 확인 필요");if(!part.renameTo(target))throw new IOException("전송 자료 저장 실패");
   ContentValues v=new ContentValues();v.put("archive_path",target.getName());v.put("size",target.length());v.put("sha256",DriveApi.digest(target,"SHA-256"));v.put("md5",DriveApi.digest(target,"MD5"));q.update(job.getString("id"),v);return true;
  }finally{RecorderApp.exporting=false;if(part.exists())part.delete();}
 }
}
