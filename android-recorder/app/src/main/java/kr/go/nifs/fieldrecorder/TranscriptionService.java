package kr.go.nifs.fieldrecorder;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.util.concurrent.CancellationException;

/** Visible activity starts saved-recording checks; location capture may continue. No boot start or upload. */
public final class TranscriptionService extends Service {
    public static volatile boolean running;
    public static volatile String recordingId="",message="";
    public static volatile long revision;
    private volatile boolean stopped;
    private volatile String attempt;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private static final int NOTICE=102;
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("transcription","휴대전화 음성 전사",NotificationManager.IMPORTANCE_LOW));}
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel=PendingIntent.getService(this,2,new Intent(this,TranscriptionService.class).setAction("cancel"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"transcription").setSmallIcon(R.drawable.ic_notification).setContentTitle("조업기록 · 녹음 내용 확인 중").setContentText(message).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"전사 중지",cancel).build()).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){if(!running)stopSelf();return START_NOT_STICKY;}
        if("cancel".equals(intent.getAction())){if(running){stopped=true;OfflineAsr.cancel();message="전사를 중지하는 중…";}else stopSelf();return START_NOT_STICKY;}
        if(running)return START_NOT_STICKY;
        String id=intent.getStringExtra("recording_id");
        // startForeground must run even if another UI action won the race.
        message="녹음 내용 확인 준비 중…";
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTICE,notification(),Build.VERSION.SDK_INT>=35?ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING:ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(NOTICE,notification());
        if(id==null||CaptureService.recording||RecorderApp.exporting){message="녹음·내보내기를 마친 뒤 확인하세요.";stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
        attempt=null;stopped=false;running=true;recordingId=id;revision++;OfflineAsr.reset();
        wakeLock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"badagirok:transcription");wakeLock.acquire(60*60*1000L);
        handler.postDelayed(deadline,59*60*1000L);
        new Thread(()->{
            Store db=RecorderApp.store();
            boolean completed=false;
            try{
                boolean gear="WORK".equals(db.row("recordings",id).optString("phase"));
                File audio=db.audio(id);String hash;
                try(InputStream in=new BufferedInputStream(new FileInputStream(audio))){hash=Archive.sha256(in);}
                if(stopped)throw new CancellationException("전사 중지");
                attempt=db.beginTranscription(id,OfflineAsr.ENGINE+(gear?";gear-hint-v1":""),OfflineAsr.SHA,hash);revision++;
                message="녹음 내용 확인 중…";
                JSONObject result=gear?OfflineAsr.transcribeGear(getApplicationContext(),audio,()->stopped):OfflineAsr.transcribe(getApplicationContext(),audio,()->stopped);
                if(stopped)throw new CancellationException("전사 중지");
                try(InputStream in=new BufferedInputStream(new FileInputStream(audio))){if(!hash.equals(Archive.sha256(in)))throw new IOException("전사 중 원음이 변경되었습니다.");}
                db.finishTranscription(attempt,result);
                // Commit the first pass before retrying so cancellation/failure never loses it.
                if(!stopped&&RecognitionPolicy.needsFullContext(result,db.row("recordings",id).getString("phase"))){
                    message="불확실한 항목을 다시 확인하는 중…";revision++;
                    attempt=db.beginTranscription(id,RecognitionPolicy.RETRY_ENGINE+(gear?";gear-hint-v1":""),OfflineAsr.SHA,hash);
                    result=OfflineAsr.transcribeVariant(getApplicationContext(),audio,()->stopped,5,gear?OfflineAsr.GEAR_HINT:OfflineAsr.HINT);
                    if(stopped)throw new CancellationException("전사 중지");
                    try(InputStream in=new BufferedInputStream(new FileInputStream(audio))){if(!hash.equals(Archive.sha256(in)))throw new IOException("전사 중 원음이 변경되었습니다.");}
                    db.finishTranscription(attempt,result);
                }
                completed=true;message="녹음 내용 확인 완료 · 결과를 확인해 주세요.";
            }catch(Exception|LinkageError e){
                if(attempt!=null)db.failTranscription(attempt,stopped?"CANCELLED":"FAILED",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());
                message=stopped?"전사 중지 · 원음은 보존되었습니다.":"전사 실패 · 원음 보존: "+e.getMessage();
            }finally{final boolean notifyCompletion=completed&&!stopped;handler.post(()->{handler.removeCallbacks(pulse);handler.removeCallbacks(deadline);if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();stopForeground(STOP_FOREGROUND_REMOVE);running=false;recordingId="";revision++;if(notifyCompletion)completionVibration();stopSelf();});}
        },"offline-transcription").start();handler.postDelayed(pulse,2000);return START_NOT_STICKY;
    }
    private void completionVibration(){
        // Exactly once after the final pass; UI refresh/recreation never triggers this.
        try{Vibrator vibrator=getSystemService(Vibrator.class);if(vibrator!=null&&vibrator.hasVibrator())vibrator.vibrate(
            VibrationEffect.createWaveform(new long[]{0,180,100,180},-1),
            new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build());
        }catch(RuntimeException ignored){/* Device vibration policy must not mark saved results as failed. */}
    }
    private final Runnable pulse=new Runnable(){@Override public void run(){if(running){getSystemService(NotificationManager.class).notify(NOTICE,notification());handler.postDelayed(this,5000);}}};
    private final Runnable deadline=()->onTimeout(0,0);
    @Override public void onTimeout(int startId,int type){
        stopped=true;OfflineAsr.cancel();if(attempt!=null)RecorderApp.store().failTranscription(attempt,"INTERRUPTED","Android 전사 시간 제한");message="시간 제한으로 중단 · 다시 전사할 수 있습니다.";stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    @Override public void onDestroy(){stopped=true;if(running)OfflineAsr.cancel();handler.removeCallbacks(pulse);handler.removeCallbacks(deadline);if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();super.onDestroy();}
}
