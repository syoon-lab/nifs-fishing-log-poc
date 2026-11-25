package kr.go.nifs.fieldrecorder;

import android.content.*;
import androidx.work.*;
import org.json.*;
import java.util.concurrent.*;

final class DriveSync {
 private static final ExecutorService CONTROL=Executors.newSingleThreadExecutor();
 static final String WORK="fishing-log-drive-upload",RECOVERY="fishing-log-drive-recovery";
 static volatile String message="";
 static void onArrival(Context context){resume(context);}
 static void resume(Context context){Context c=context.getApplicationContext();if(!DriveAuth.enabled(c))return;CONTROL.execute(()->{
  try(DriveQueue q=new DriveQueue(c)){
   schedule(c);
   PeriodicWorkRequest periodic=new PeriodicWorkRequest.Builder(DriveUploadWorker.class,15,TimeUnit.MINUTES).setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();
   WorkManager.getInstance(c).enqueueUniquePeriodicWork(RECOVERY,ExistingPeriodicWorkPolicy.KEEP,periodic);
   reconcile(c,RecorderApp.store(),q);
  }catch(Exception e){message="자동 업로드 대기열 확인 필요 · 자료는 폰에 보존됩니다.";}
 });}
 static void reconcile(Context c,Store s,DriveQueue q) throws Exception {
  long since=DriveAuth.prefs(c).getLong("enabled_since",Long.MAX_VALUE);String owner=DriveAuth.owner(c),email=DriveAuth.email(c);if(owner.isEmpty())return;
  JSONArray sessions=s.rows("sessions");for(int i=0;i<sessions.length();i++){JSONObject trip=sessions.getJSONObject(i);if("COMPLETED".equals(trip.optString("status"))&&trip.optLong("ended_ms",0)>=since)q.add(trip.getString("id"),owner,email,false);}
 }
 static Constraints constraints(){return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();}
 static void schedule(Context c){if(!DriveAuth.enabled(c))return;OneTimeWorkRequest work=new OneTimeWorkRequest.Builder(DriveUploadWorker.class).setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();WorkManager.getInstance(c).enqueueUniqueWork(WORK,ExistingWorkPolicy.KEEP,work);}
 static void pause(Context c){DriveAuth.prefs(c).edit().putBoolean("enabled",false).commit();WorkManager.getInstance(c).cancelUniqueWork(WORK);WorkManager.getInstance(c).cancelUniqueWork(RECOVERY);}
 static void requestNow(Context c) throws Exception {
  if(!DriveAuth.enabled(c))throw new IllegalStateException("먼저 구글 계정을 연결해 주세요.");
  if(CaptureService.running||TranscriptionService.running||RecorderApp.exporting)throw new IllegalStateException("입항하고 음성 확인이 끝난 뒤 전송하세요.");
  JSONArray trips=RecorderApp.store().rows("sessions");if(trips.length()==0)throw new IllegalStateException("아직 저장된 기록이 없습니다.");
  try(DriveQueue q=new DriveQueue(c)){reconcile(c,RecorderApp.store(),q);q.retryBlocked(DriveAuth.owner(c));if(q.next(DriveAuth.owner(c))==null)q.add(trips.getJSONObject(trips.length()-1).getString("id"),DriveAuth.owner(c),DriveAuth.email(c),true);}schedule(c);
 }
}
