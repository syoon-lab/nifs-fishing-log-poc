package kr.go.nifs.fieldrecorder;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.location.*;
import android.media.MediaRecorder;
import android.os.*;
import java.util.Locale;

public class CaptureService extends Service implements LocationListener {
    public static final String START_WORK="start_work",ANSWER="answer";
    public static final String BEGIN="depart",RECORD="retry_record",STOP_RECORD="stop_record",END="arrive",START_OPERATION="start_operation",NOTE="note",WRAP="wrap",COMPLETE_OPERATION="complete_operation",ABORT="abort",SUPPLEMENT="supplement";
    public static boolean needsMicrophone(String action){return ANSWER.equals(action)||START_WORK.equals(action)||SUPPLEMENT.equals(action)||RECORD.equals(action)||START_OPERATION.equals(action)||NOTE.equals(action)||WRAP.equals(action);}
    public static volatile String recordingPhase="",saveError="";
    public static volatile boolean running=false,recording=false;
    public static volatile String message="기록을 시작하면 위치 수신 상태가 여기에 표시됩니다.";
    public static volatile String sessionId=null,recordingId=null;
    public static volatile long recordingStarted=0;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LocationManager manager;
    private MediaRecorder recorder;
    private Location lastFix;
    private String lastFixId;
    private long interval;
    private boolean finishing=false;
    private String supplementPhase;
    private org.json.JSONObject answerRequest;
    private Store db;
    private static final int NOTIFICATION=101;
    private boolean permitted(String p){return checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED;}
    @Override public void onCreate(){super.onCreate();db=RecorderApp.store();manager=(LocationManager)getSystemService(LOCATION_SERVICE);NotificationManager n=getSystemService(NotificationManager.class);n.createNotificationChannel(new NotificationChannel("capture","녹음·위치 기록",NotificationManager.IMPORTANCE_LOW));}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        String action=intent.getAction();
        try{
            if(TranscriptionService.running&&(BEGIN.equals(action)||needsMicrophone(action)))throw new IllegalStateException("항목 확인을 마치거나 중지한 뒤 녹음하세요.");
            if(needsMicrophone(action)&&QuestionAudio.isBusy())throw new IllegalStateException("질문 음성이 끝난 뒤 녹음해 주세요.");
            if(RecorderApp.exporting)throw new IllegalStateException("내보내기가 끝난 뒤 시작하세요.");
            if(STOP_RECORD.equals(action)){stopRecording(false,"");return START_NOT_STICKY;}
            if(ABORT.equals(action)){finishSession("INTERRUPTED");return START_NOT_STICKY;}
            if(recording)throw new IllegalStateException("녹음을 먼저 종료·저장하세요.");
            if(BEGIN.equals(action)){
                if(running)throw new IllegalStateException("이미 출항 중입니다.");
                begin(intent.getLongExtra("interval",60000));return START_NOT_STICKY;
            }
            if(!running)throw new IllegalStateException("먼저 출항하세요.");
            if(END.equals(action)){
                db.arrive(sessionId);finishSession("COMPLETED");DriveSync.onArrival(getApplicationContext());return START_NOT_STICKY;
            }
            if(needsMicrophone(action)&&!permitted(Manifest.permission.RECORD_AUDIO))throw new SecurityException("마이크 권한이 필요합니다.");
            String op=db.activeOperation(sessionId);
            if(ANSWER.equals(action)){
                if(op==null||!op.equals(intent.getStringExtra("operation_id")))throw new IllegalStateException("현재 작업의 질문을 다시 열어 주세요.");
                answerRequest=new org.json.JSONObject(intent.getStringExtra("answer_question"));try{startRecording(op,"WORK","질문 답변");}finally{answerRequest=null;}
            }
            else if(SUPPLEMENT.equals(action)){if(op==null)throw new IllegalStateException("진행 중인 조업이 없습니다.");supplementPhase=intent.getStringExtra("phase");try{startRecording(op,"NOTE","추가 기록 · "+("WORK".equals(supplementPhase)?"작업":"START".equals(supplementPhase)?"시작":"마무리"));}finally{supplementPhase=null;}}
            else if(START_WORK.equals(action)){op=db.startGearOperation(sessionId,intent.getStringExtra("gear_type"));startRecording(op,"WORK",intent.getStringExtra("label"));}
            else if(START_OPERATION.equals(action)){op=db.startOperation(sessionId);startRecording(op,"START",intent.getStringExtra("label"));}
            else if(NOTE.equals(action)){if(op==null)throw new IllegalStateException("조업을 먼저 시작하세요.");startRecording(op,"NOTE",intent.getStringExtra("label"));}
            else if(WRAP.equals(action)){if(op==null)throw new IllegalStateException("조업을 먼저 시작하세요.");db.beginWrap(op);startRecording(op,"END",intent.getStringExtra("label"));}
            else if(RECORD.equals(action)){if(op==null)throw new IllegalStateException("조업을 먼저 시작하세요.");startRecording(op,db.pendingPhase(op),intent.getStringExtra("label"));}
            else if(COMPLETE_OPERATION.equals(action)){if(op==null)throw new IllegalStateException("진행 중인 조업이 없습니다.");db.finishOperationChecked(op,intent.getStringExtra("check_token"));message=locationMessage();}
            else throw new IllegalStateException("알 수 없는 작업");
        }catch(Exception e){message="기록 오류: "+e.getMessage();safeEvent("ERROR",message);if(!running){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}}
        return START_NOT_STICKY;
    }
    private void begin(long requested) throws Exception {
        if(!permitted(Manifest.permission.ACCESS_COARSE_LOCATION)&&!permitted(Manifest.permission.ACCESS_FINE_LOCATION))throw new SecurityException("위치 권한이 필요합니다.");
        if(!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)&&!manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))throw new IllegalStateException("휴대전화 위치 기능을 켜주세요.");
        finishing=false;
        interval=requested==30000||requested==300000?requested:60000;
        foreground(false); // User starts this service from a visible activity.
        sessionId=db.depart(interval);running=true;lastFix=null;lastFixId=null;RecorderApp.exportResult="";
        message="위치 수신 대기 · 좌표를 얻지 못해도 녹음은 저장됩니다.";
        boolean subscribed=false;
        try {
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED&&manager.getAllProviders().contains(LocationManager.GPS_PROVIDER)){
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,interval,0,this,Looper.getMainLooper());subscribed=true;
        }
        if((checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)&&manager.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)){
            manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,interval,0,this,Looper.getMainLooper());subscribed=true;
        }
        } catch(SecurityException e) { finishSession("FAILED"); throw e; }
        if(!subscribed){finishSession("FAILED");throw new IllegalStateException("사용 가능한 위치 제공자가 없습니다.");}
        handler.post(watchdog);
    }
    private final Runnable watchdog=new Runnable(){@Override public void run(){if(!running)return;if(lastFix!=null){long age=(SystemClock.elapsedRealtimeNanos()-lastFix.getElapsedRealtimeNanos())/1_000_000_000L;if(age>Math.max(90,interval/1000*2)){message="위치 갱신 지연 · 마지막 위치 "+age+"초 전. 실제 경로에 공백이 있을 수 있습니다.";}}handler.postDelayed(this,15000);}};
    private void foreground(boolean microphone){
        Intent open=new Intent(this,MainActivity.class);PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent end=new Intent(this,CaptureService.class).setAction(STOP_RECORD);PendingIntent stop=PendingIntent.getService(this,1,end,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=new Notification.Builder(this,"capture").setSmallIcon(R.drawable.ic_notification).setContentTitle(microphone?"조업기록 · 녹음 중":"조업기록 · 위치 기록 중").setContentText("출항부터 입항까지 위치 기록 · 누르면 앱 열기").setOngoing(true).setContentIntent(content);
        if(microphone)b.addAction(new Notification.Action.Builder(null,"녹음 종료 · 저장",stop).build());
        int type=Build.VERSION.SDK_INT>=29?ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION:0;
        if(microphone&&Build.VERSION.SDK_INT>=30)type|=ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,b.build(),type);else startForeground(NOTIFICATION,b.build());
    }
    private String locationMessage(){
        if(lastFix==null)return "위치 수신 대기 · 좌표를 얻지 못해도 녹음은 저장됩니다.";
        long age=(SystemClock.elapsedRealtimeNanos()-lastFix.getElapsedRealtimeNanos())/1_000_000_000L;
        if(age>Math.max(90,interval/1000*2))return "위치 갱신 지연 · 마지막 위치 "+age+"초 전";
        return String.format(Locale.KOREA,"위치 %.5f, %.5f · 정확도 %s · %s",lastFix.getLatitude(),lastFix.getLongitude(),lastFix.hasAccuracy()?String.format(Locale.KOREA,"±%.0fm",lastFix.getAccuracy()):"미제공",lastFix.getProvider());
    }
    private String freshLocation(){if(lastFix==null)return null;long age=SystemClock.elapsedRealtimeNanos()-lastFix.getElapsedRealtimeNanos();return age>=0&&age<=60_000_000_000L?lastFixId:null;}
    private void startRecording(String operation,String phase,String label) throws Exception {
        if(!permitted(Manifest.permission.RECORD_AUDIO))throw new SecurityException("마이크 권한이 필요합니다.");
        if(db.root().getUsableSpace()<30L*1024*1024)throw new IllegalStateException("저장공간이 부족합니다. 자료를 내보내고 공간을 확보하세요.");
        foreground(true);saveError="";
        String id=null;MediaRecorder candidate=null;
        try{
            id=answerRequest!=null?db.prepareAnswer(operation,answerRequest,freshLocation()):supplementPhase==null?db.prepareOperationRecording(operation,phase,label==null?"":label,freshLocation()):db.prepareSupplement(operation,supplementPhase,label,freshLocation());
            candidate=Build.VERSION.SDK_INT>=31?new MediaRecorder(this):new MediaRecorder();
            candidate.setAudioSource(MediaRecorder.AudioSource.MIC);candidate.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);candidate.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);candidate.setAudioChannels(1);candidate.setAudioSamplingRate(44100);candidate.setAudioEncodingBitRate(128000);candidate.setOutputFile(db.audio(id));
            candidate.setOnErrorListener((r,what,extra)->{message="녹음 오류 · 파일을 보존했습니다. 오류 코드 "+what+"/"+extra;stopRecording(true,message);});
            candidate.prepare();candidate.start();recorder=candidate;recordingId=id;recordingStarted=System.currentTimeMillis();recording=true;recordingPhase=supplementPhase==null?phase:supplementPhase+"_SUPPLEMENT";message=locationMessage();
            db.recordingStarted(id);db.event(sessionId,id,operation,"RECORDING_START",freshLocation()==null?"시작 위치 미확보. 위치를 임의 생성하지 않음.":"최근 60초 이내 위치 연결; measured_ms 확인 필요.");
        }catch(Exception e){if(candidate!=null){try{candidate.release();}catch(Exception ignored){}}recorder=null;recording=false;recordingId=null;if(id!=null)db.completeOperationRecording(id,"FAILED",e.toString(),freshLocation());foreground(false);throw e;}
    }
    private void stopRecording(boolean failed,String reason){
        if(recorder==null)return;MediaRecorder r=recorder;recorder=null;String id=recordingId;
        try{r.stop();}catch(RuntimeException e){failed=true;reason="녹음이 너무 짧거나 저장 중 오류가 발생했습니다: "+e.getMessage();}finally{r.release();recording=false;recordingId=null;recordingPhase="";}
        try{db.completeOperationRecording(id,failed?"FAILED":"COMPLETED",reason,freshLocation());db.event(sessionId,id,db.activeOperation(sessionId),"RECORDING_END",failed?reason:"녹음파일 저장 완료");}catch(Exception e){message="녹음 정보 저장 오류: "+e.getMessage();}
        if(failed)message="녹음 오류 · 불완전한 파일도 보존했습니다. "+reason;else if(!message.startsWith("녹음 정보 저장 오류"))message=locationMessage();
        if(failed||message.startsWith("녹음 정보 저장 오류"))saveError=message;
        if(running&&!finishing){try{foreground(false);}catch(Exception e){message="알림 갱신 오류: "+e.getMessage();}}
    }
    private void finishSession(String status){
        finishing=true;
        stopRecording(false,"");
        handler.removeCallbacks(watchdog);if(manager!=null)manager.removeUpdates(this);
        if(sessionId!=null&&!"COMPLETED".equals(status)){try{db.interruptSession(sessionId,"기록 중단: "+status);}catch(Exception e){message="항차 마감 정보 저장 오류: "+e.getMessage();}}
        running=false;recording=false;sessionId=null;recordingId=null;lastFix=null;lastFixId=null;
        if("COMPLETED".equals(status))message="입항 완료 · 자료를 내보낼 수 있습니다.";
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    @Override public void onLocationChanged(Location l){
        if(!running||sessionId==null)return;
        // Store measured time and accuracy, including repeated positions; do not invent fixes.
        if(!Double.isFinite(l.getLatitude())||!Double.isFinite(l.getLongitude()))return;
        try{
            String id=db.location(sessionId,recordingId,db.activeOperation(sessionId),l);long age=SystemClock.elapsedRealtimeNanos()-l.getElapsedRealtimeNanos();
            if(lastFix==null||l.getElapsedRealtimeNanos()>=lastFix.getElapsedRealtimeNanos()){lastFix=new Location(l);lastFixId=id;}
            if(recordingId!=null&&age>=0&&age<=60_000_000_000L&&System.currentTimeMillis()-recordingStarted<=30000)db.attachFirstFix(recordingId,id);
            message=String.format(Locale.KOREA,"위치 %.5f, %.5f · 정확도 %s · %s",l.getLatitude(),l.getLongitude(),l.hasAccuracy()?String.format(Locale.KOREA,"±%.0fm",l.getAccuracy()):"미제공",l.getProvider());
        }catch(Exception e){message="위치 저장 실패: "+e.getMessage();finishSession("FAILED");}
    }
    @Override public void onProviderDisabled(String provider){message="위치 제공자 꺼짐: "+provider+" · 위치 누락 가능";safeEvent("PROVIDER_DISABLED",provider);}
    @Override public void onProviderEnabled(String provider){safeEvent("PROVIDER_ENABLED",provider);}
    @Override public void onStatusChanged(String p,int s,Bundle b){}
    private void safeEvent(String type,String text){try{db.event(sessionId,recordingId,db.activeOperation(sessionId),type,text);}catch(Exception ignored){}}
    @Override public void onDestroy(){
        if(running){safeEvent("SERVICE_DESTROYED","위치 기록이 중단됨");finishSession("INTERRUPTED");}
        handler.removeCallbacksAndMessages(null);super.onDestroy();
    }
}
