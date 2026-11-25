package kr.go.nifs.fieldrecorder;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private LinearLayout body,list,historyPane,checkPanel;
    private TextView checkTitle,checkDetail;
    private ProgressBar checkSpinner;
    private String startingRecordingId="",progressKey="";
    private ScrollView mainPane;
    private boolean historyVisible=false;
    private android.window.OnBackInvokedCallback historyBack;
    private TextView captureStatus,timer,locationStatus,counts,exportStatus,driveStatus;
    private Button session,record,historyButton,fieldButton;
    private QuestionDialog questions;
    private String answerOperation,answerQuestion;
    private String closingToken,supplementPhase,lastSaveError="",selectedGearType;
    private boolean fieldDialogOpen=false,asrStarting=false;
    private String nextLabel="",primaryAction=CaptureService.BEGIN,secondaryAction=CaptureService.END;
    private int intervalChoice;
    private String pendingAction,historyKey="";
    private final Handler ui=new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private AlertDialog transcriptDialog;
    private String playingId;
    private boolean resumed=false;
    private final int teal=Color.rgb(23,107,96),ink=Color.rgb(23,46,53);
    private Store db(){return RecorderApp.store();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        intervalChoice=Math.max(0,Math.min(2,getPreferences(0).getInt("interval",1)));
        if(state!=null){nextLabel=state.getString("label","");pendingAction=state.getString("pending");selectedGearType=state.getString("gear_type");answerOperation=state.getString("answer_operation");answerQuestion=state.getString("answer_question");}
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setBackgroundColor(Color.rgb(243,245,241));
        body.setPadding(dp(20),dp(12),dp(20),dp(12));
        setContentView(body);
        if(Build.VERSION.SDK_INT>=30)body.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets edge=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(dp(20)+edge.left,dp(12)+edge.top,dp(20)+edge.right,dp(12)+edge.bottom);return insets;});
        mainPane=new ScrollView(this);mainPane.setFillViewport(true);body.addView(mainPane,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout main=new LinearLayout(this);main.setOrientation(LinearLayout.VERTICAL);mainPane.addView(main);

        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);main.addView(header);
        TextView title=text(header,"조업기록",32,ink,true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        Button more=button(header,"더보기",false,v->showMenu());
        more.setMinWidth(dp(84));more.setSingleLine(false);
        more.setLayoutParams(new LinearLayout.LayoutParams(-2,-2));

        LinearLayout capture=card(main);
        captureStatus=text(capture,"녹음 대기",24,teal,true);captureStatus.setGravity(Gravity.CENTER);
        checkPanel=new LinearLayout(this);checkPanel.setOrientation(LinearLayout.VERTICAL);checkPanel.setGravity(Gravity.CENTER);checkPanel.setPadding(dp(12),dp(12),dp(12),dp(12));
        checkPanel.setBackground(shape(Color.rgb(255,240,194),14));capture.addView(checkPanel,new LinearLayout.LayoutParams(-1,-2));
        text(checkPanel,"녹음 저장 완료",22,ink,true).setGravity(Gravity.CENTER);
        checkSpinner=new ProgressBar(this);LinearLayout.LayoutParams spinnerSize=new LinearLayout.LayoutParams(dp(44),dp(44));spinnerSize.gravity=Gravity.CENTER;checkPanel.addView(checkSpinner,spinnerSize);
        checkTitle=text(checkPanel,"",34,ink,true);checkTitle.setGravity(Gravity.CENTER);checkTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        checkDetail=text(checkPanel,"",22,ink,false);checkDetail.setGravity(Gravity.CENTER);checkPanel.setVisibility(View.GONE);
        timer=text(capture,"00:00",52,ink,true);timer.setGravity(Gravity.CENTER);timer.setFontFeatureSettings("tnum");
        record=button(capture,"출항",true,v->perform(primaryAction));
        record.setTextSize(34);record.setTypeface(null,Typeface.BOLD);record.setMinHeight(dp(112));
        locationStatus=text(capture,"녹음과 함께 위치도 기록합니다",13,Color.DKGRAY,false);
        locationStatus.setGravity(Gravity.CENTER);locationStatus.setMinHeight(dp(48));locationStatus.setOnClickListener(v->showLocation());
        locationStatus.setContentDescription("위치 상태. 누르면 자세히 보기");
        session=button(capture,"입항",false,v->perform(secondaryAction));session.setTextSize(28);session.setMinHeight(dp(88));session.setTypeface(null,Typeface.BOLD);session.setVisibility(View.GONE);
        exportStatus=text(main,"",13,teal,false);exportStatus.setVisibility(View.GONE);
        driveStatus=text(main,"",16,teal,false);driveStatus.setVisibility(View.GONE);driveStatus.setOnClickListener(v->startActivity(new Intent(this,DriveSettingsActivity.class)));
        fieldButton=button(main,"기록 확인",false,v->showOperationChecks(db().activeOperation(CaptureService.sessionId)));
        fieldButton.setVisibility(View.GONE);
        historyButton=button(main,"기록 보기",false,v->showHistory(true));

        historyPane=new LinearLayout(this);historyPane.setOrientation(LinearLayout.VERTICAL);
        body.addView(historyPane,new LinearLayout.LayoutParams(-1,-1));historyPane.setVisibility(View.GONE);
        LinearLayout historyHeader=new LinearLayout(this);historyHeader.setGravity(Gravity.CENTER_VERTICAL);historyPane.addView(historyHeader);
        TextView historyTitle=text(historyHeader,"기록 보기",30,ink,true);historyTitle.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        Button back=button(historyHeader,"작업 화면",false,v->showHistory(false));back.setSingleLine(false);back.setLayoutParams(new LinearLayout.LayoutParams(-2,-2));
        button(historyPane,"일지 보기",false,v->showDiary());
        counts=text(historyPane,"저장한 녹음",16,teal,false);
        ScrollView history=new ScrollView(this);history.setFillViewport(true);
        historyPane.addView(history,new LinearLayout.LayoutParams(-1,0,1));
        list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);history.addView(list);
        if(state!=null&&state.getBoolean("history",false))showHistory(true);
    }
    private void showHistory(boolean show){
        if(show==historyVisible)return;
        stopPlayback();historyVisible=show;historyKey="";
        mainPane.setVisibility(show?View.GONE:View.VISIBLE);historyPane.setVisibility(show?View.VISIBLE:View.GONE);
        if(Build.VERSION.SDK_INT>=33){
            if(show){historyBack=()->showHistory(false);getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,historyBack);}
            else if(historyBack!=null){getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(historyBack);historyBack=null;}
        }
        refresh();
    }
    // Android 8–12 fallback. API 33+ uses the OnBackInvokedCallback registered above.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){
        if(historyVisible)showHistory(false);else super.onBackPressed();
    }
    @Override protected void onDestroy(){
        if(Build.VERSION.SDK_INT>=33&&historyBack!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(historyBack);
        super.onDestroy();
    }
    private GradientDrawable shape(int color,int radius){GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(dp(radius));return bg;}
    private LinearLayout card(LinearLayout parent){
        LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(16),dp(12),dp(16),dp(12));
        p.setBackground(shape(Color.WHITE,18));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(10),0,dp(6));parent.addView(p,lp);return p;
    }
    private TextView text(LinearLayout p,String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(Math.max(22,size));t.setTextColor(color);t.setPadding(0,dp(4),0,dp(4));t.setLineSpacing(dp(2),1);
        if(bold)t.setTypeface(null,Typeface.BOLD);p.addView(t);return t;
    }
    private void buttonColor(Button b,int color){
        if(Integer.valueOf(color).equals(b.getTag()))return;
        b.setTag(color);
        b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22606060),shape(color,14),null));
    }
    private Button button(LinearLayout p,String s,boolean primary,View.OnClickListener click){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(24);b.setMinHeight(dp(64));b.setSingleLine(false);b.setMaxLines(Integer.MAX_VALUE);if(Build.VERSION.SDK_INT>=29)b.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED);b.setMinimumWidth(0);
        b.setPadding(dp(12),dp(10),dp(12),dp(10));b.setTextColor(primary?Color.WHITE:teal);buttonColor(b,primary?teal:Color.rgb(234,243,238));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(6),0,dp(4));p.addView(b,lp);b.setOnClickListener(click);return b;
    }
    private void showMenu(){
        new ReadableDialog.Builder(this).setTitle("더보기").setItems(new String[]{"자료 내보내기","위치 기록 간격","위치 상태 · 설정","다음 녹음 제목","모의 자료 추가","도움말","기록 중단","구글드라이브 자동 업로드"},(d,which)->{
            switch(which){
                case 0 -> {if(CaptureService.running)toast("입항 후 내보내세요.");else if(db().count("sessions")==0)toast("아직 저장된 자료가 없습니다.");else chooseExport();}
                case 1 -> showInterval();
                case 2 -> showLocation();
                case 3 -> showLabel();
                case 4 -> addDemo();
                case 5 -> new ReadableDialog.Builder(this).setTitle("사용 방법")
                    .setMessage("출항 → 작업 기록·녹음 → 자망/통발 선택 → 녹음 저장·항목 확인 → 기록 마침 → 입항\n\n한 항차에서 조업을 여러 번 반복할 수 있습니다. 위치는 출항부터 입항까지 기록됩니다.\n\n투망일·양망 수량·어획량·재투망 수량 또는 최종 철거를 한 번에 말해 주세요. 자망은 폭, 통발은 개로 기록합니다. 작업 인원은 묻지 않습니다. 모르는 값은 미확인으로 저장하고 필요한 내용만 추가 녹음할 수 있습니다. 과거 투망 기록 자동 연결은 아직 하지 않습니다. 녹음 실패 시 다시 녹음할 수 있습니다.\n\n중단이 필요하면 더보기 → 기록 중단을 이용하세요. 정상 입항으로 처리하지 않으며 기존 자료는 보존합니다.\n\n앱 삭제·데이터 초기화 전에는 반드시 자료를 내보내세요. 인터넷 없이 저장하며 강제 종료·재부팅 후 기록을 자동 재개하지 않습니다.")
                    .setPositiveButton("닫기",null).show();
                case 7 -> startActivity(new Intent(this,DriveSettingsActivity.class));
                case 6 -> {
                    if(!CaptureService.running){toast("진행 중인 항차가 없습니다.");return;}
                    new ReadableDialog.Builder(this).setTitle("기록을 중단할까요?").setMessage("녹음과 위치 기록을 멈춥니다. 자료는 보존하고 조업·항차를 ‘중단됨’으로 표시합니다. 정상 조업 종료나 입항으로 처리하지 않습니다.").setNegativeButton("계속 기록",null).setPositiveButton("중단",(x,w)->send(CaptureService.ABORT)).show();
                }
            }
        }).show();
    }
    private void showInterval(){
        if(CaptureService.running||RecorderApp.exporting||TranscriptionService.running){toast("항차·내보내기를 마친 뒤 변경하세요.");return;}
        new ReadableDialog.Builder(this).setTitle("위치 기록 간격")
            .setSingleChoiceItems(new String[]{"30초","1분 · 기본","5분"},intervalChoice,(d,which)->{
                intervalChoice=which;getPreferences(0).edit().putInt("interval",which).apply();d.dismiss();
            }).setNegativeButton("닫기",null).show();
    }
    private void showLocation(){
        new ReadableDialog.Builder(this).setTitle("위치 상태").setMessage(CaptureService.message+"\n\n실제 수신 간격은 설정과 다를 수 있습니다. 녹음 장소와 말로 설명한 조업 장소는 다를 수 있습니다.")
            .setPositiveButton("휴대전화 위치 설정",(d,w)->startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))).setNegativeButton("닫기",null).show();
    }
    private void showLabel(){
        if(CaptureService.recording){toast("녹음을 마친 뒤 제목을 입력하세요.");return;}
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint("제목 또는 장소 메모");input.setText(nextLabel);input.setPadding(dp(24),dp(12),dp(24),dp(12));
        new ReadableDialog.Builder(this).setTitle("다음 녹음 제목").setView(input).setPositiveButton("저장",(d,w)->nextLabel=input.getText().toString().trim()).setNegativeButton("취소",null).show();
    }
    private void addDemo(){
        if(CaptureService.running||RecorderApp.exporting||TranscriptionService.running){toast("항차·내보내기를 마친 뒤 추가하세요.");return;}
        new ReadableDialog.Builder(this).setTitle("모의 자료 추가").setMessage("합성음성 3개와 임의 좌표·시각을 추가합니다. 실제 녹음·GPS 자료가 아닙니다. 기존 자료는 유지됩니다.")
            .setNegativeButton("취소",null).setPositiveButton("추가",(d,w)->{
                try{int n=DemoData.install(db(),getAssets());historyKey="";refresh();toast(n==0?"이미 저장된 모의 자료입니다.":"모의 자료 3건을 추가했습니다.");}catch(Exception e){toast("모의 자료 추가 실패: "+e.getMessage());}
            }).show();
    }
    private void perform(String action){
        if(CaptureService.COMPLETE_OPERATION.equals(action)){confirmOperationEnd();return;}
        if(CaptureService.START_WORK.equals(action)){
            new ReadableDialog.Builder(this).setTitle("어떤 어구인가요?").setItems(new String[]{"자망 · 폭","통발 · 개"},(d,w)->{selectedGearType=w==0?"GILLNET":"POT";ensure(action);}).setNegativeButton("취소",null).show();return;
        }
        if(CaptureService.BEGIN.equals(action)||CaptureService.needsMicrophone(action))ensure(action);else send(action);
    }
    private boolean granted(String p){return checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED;}
    private void ensure(String action){
        if(TranscriptionService.running||asrStarting){
            new ReadableDialog.Builder(this).setTitle("항목 확인 중입니다").setMessage("확인을 중지하면 새 녹음을 시작할 수 있습니다. 원음은 보존됩니다.").setPositiveButton("확인 중지",(d,w)->{startService(new Intent(this,TranscriptionService.class).setAction("cancel"));}).setNegativeButton("기다리기",null).show();return;
        }
        if(RecorderApp.exporting){toast("내보내기가 끝난 뒤 시작하세요.");return;}
        pendingAction=action;ArrayList<String> needed=new ArrayList<>();
        if(!granted(Manifest.permission.ACCESS_FINE_LOCATION)&&!granted(Manifest.permission.ACCESS_COARSE_LOCATION)){needed.add(Manifest.permission.ACCESS_FINE_LOCATION);needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);}
        if(CaptureService.needsMicrophone(action)&&!granted(Manifest.permission.RECORD_AUDIO))needed.add(Manifest.permission.RECORD_AUDIO);
        if(Build.VERSION.SDK_INT>=33&&!granted(Manifest.permission.POST_NOTIFICATIONS)&&!getPreferences(0).getBoolean("notificationAsked",false)){needed.add(Manifest.permission.POST_NOTIFICATIONS);getPreferences(0).edit().putBoolean("notificationAsked",true).apply();}
        if(!needed.isEmpty()){requestPermissions(needed.toArray(new String[0]),10);return;}
        pendingAction=null;send(action);
    }
    @Override public void onRequestPermissionsResult(int request,String[] p,int[] results){super.onRequestPermissionsResult(request,p,results);if(request!=10||pendingAction==null)return;String action=pendingAction;pendingAction=null;
        if(!granted(Manifest.permission.ACCESS_COARSE_LOCATION)&&!granted(Manifest.permission.ACCESS_FINE_LOCATION)){permissionHelp("위치와 연결해 기록하려면 위치 권한이 필요합니다.");return;}
        if(CaptureService.needsMicrophone(action)&&!granted(Manifest.permission.RECORD_AUDIO)){permissionHelp("녹음하려면 마이크 권한이 필요합니다.");return;}send(action);
    }
    private void permissionHelp(String message){new ReadableDialog.Builder(this).setMessage(message+" 앱 설정에서 권한을 변경할 수 있습니다.").setPositiveButton("앱 설정",(d,w)->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).setNegativeButton("닫기",null).show();}
    private void send(String action){
        if(CaptureService.BEGIN.equals(action)||CaptureService.needsMicrophone(action)){if(questions!=null){questions.close();questions=null;}stopPlayback();getPreferences(0).edit().putInt("interval",intervalChoice).apply();}
        Intent i=new Intent(this,CaptureService.class).setAction(action).putExtra("interval",new long[]{30000,60000,300000}[intervalChoice]).putExtra("label",nextLabel).putExtra("check_token",closingToken).putExtra("phase",supplementPhase).putExtra("gear_type",selectedGearType).putExtra("operation_id",answerOperation).putExtra("answer_question",answerQuestion);
        closingToken=null;answerOperation=null;answerQuestion=null;
        try{if(CaptureService.BEGIN.equals(action)||CaptureService.needsMicrophone(action))startForegroundService(i);else startService(i);}catch(Exception e){toast("시작할 수 없습니다: "+e.getMessage());}
        historyKey="";ui.postDelayed(this::refresh,350);
    }
    private final Runnable tick=new Runnable(){@Override public void run(){if(!resumed)return;refresh();ui.postDelayed(this,1000);}};
    @Override protected void onResume(){super.onResume();resumed=true;DriveSync.resume(this);ui.post(tick);}
    @Override protected void onPause(){if(questions!=null){questions.close();questions=null;}resumed=false;ui.removeCallbacks(tick);super.onPause();}
    @Override protected void onStop(){stopPlayback();super.onStop();}
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("label",nextLabel);b.putString("pending",pendingAction);b.putString("gear_type",selectedGearType);b.putString("answer_operation",answerOperation);b.putString("answer_question",answerQuestion);b.putBoolean("history",historyVisible);}
    private String clockText(long seconds){return String.format(Locale.KOREA,"%02d:%02d",seconds/60,seconds%60);}
    private String shortLocation(boolean active){
        String m=CaptureService.message;
        if(m.contains("오류")||m.contains("실패")||m.contains("꺼짐")||m.contains("켜주세요")||m.contains("필요합니다"))return m;
        if(!active)return "위치 기록 대기";
        if(m.startsWith("위치 갱신 지연"))return "위치 갱신 지연 · 눌러서 확인";
        if(m.startsWith("위치 ")&&m.contains(" · 정확도 "))return m.endsWith("gps")?"위치 수신됨 · GPS":"위치 수신됨 · 네트워크";
        return "위치 찾는 중";
    }
    private void refresh(){
        if(isFinishing())return;
        try{
            boolean active=CaptureService.running,rec=CaptureService.recording;
            if(rec)lastSaveError="";
            String opId=db().activeOperation(CaptureService.sessionId);JSONObject op=opId==null?null:db().operation(opId);
            String stage=op==null?"":op.getString("stage");
            String stateText,mainText,subText="";
            if(rec){
                stateText=(op==null?"":op.getInt("sequence_no")+"차 조업 · ")+phaseName(CaptureService.recordingPhase)+" 녹음 중";
                mainText="■  녹음 종료 · 저장";primaryAction=CaptureService.STOP_RECORD;
            }else if(!active){stateText=CaptureService.message.startsWith("입항 완료")?"입항 완료":"출항 전";mainText="출항";primaryAction=CaptureService.BEGIN;}
            else if(op==null){stateText="자망·통발 작업 기록";mainText="작업 기록 · 녹음";primaryAction=CaptureService.START_WORK;subText="입항";secondaryAction=CaptureService.END;}
            else{
                boolean gearWork=GearLog.isWork(db(),opId);stateText=op.getInt("sequence_no")+(gearWork?"번째 작업 · "+GearLog.name(GearLog.type(db(),opId)):"차 조업");
                switch(stage){
                    case "WORK_REQUIRED" -> {stateText+=" · 녹음 필요";mainText="작업 기록 · 녹음";primaryAction=CaptureService.RECORD;}
                    case "START_REQUIRED" -> {stateText+=" · 시작 녹음 필요";mainText="시작 녹음";primaryAction=CaptureService.RECORD;}
                    case "FISHING" -> {stateText+=" 중";mainText="특이사항 녹음";primaryAction=CaptureService.NOTE;subText="조업 마무리 · 녹음";secondaryAction=CaptureService.WRAP;}
                    case "END_REQUIRED" -> {stateText+=" · 마무리 녹음 필요";mainText="마무리 녹음";primaryAction=CaptureService.RECORD;}
                    case "READY_TO_END" -> {stateText+=gearWork?" · 녹음 저장됨":" · 마무리 녹음 저장됨";mainText=gearWork?"기록 마침":"조업 종료";primaryAction=CaptureService.COMPLETE_OPERATION;if(gearWork){subText="추가 녹음";secondaryAction=CaptureService.SUPPLEMENT;supplementPhase="WORK";}}
                    default -> throw new IllegalStateException("알 수 없는 조업 단계");
                }
            }
            captureStatus.setText(stateText);captureStatus.setTextColor(rec?Color.rgb(185,54,50):teal);
            timer.setVisibility(rec?View.VISIBLE:View.GONE);timer.setText(clockText(Math.max(0,(System.currentTimeMillis()-CaptureService.recordingStarted)/1000)));
            record.setText(rec?"녹음 종료\n저장":mainText.replace(" · 녹음","\n녹음"));record.setContentDescription(mainText);
            buttonColor(record,RecorderApp.exporting?Color.GRAY:rec?Color.rgb(185,54,50):teal);record.setEnabled(!RecorderApp.exporting);
            locationStatus.setText(shortLocation(active));
            updateFieldChecks(opId);
            updateCheckProgress(opId,rec);
            session.setText(subText.replace(" · 녹음","\n녹음"));session.setContentDescription(subText);session.setVisibility(subText.isEmpty()?View.GONE:View.VISIBLE);session.setEnabled(!RecorderApp.exporting);
            historyButton.setEnabled(!rec);
            int n=db().count("recordings"),l=db().count("locations");counts.setText(String.format(Locale.KOREA,"녹음 목록  %d",n));
            String result=RecorderApp.exporting?"자료 내보내는 중…":RecorderApp.exportResult.startsWith("ZIP 내보내기 완료")?"자료 내보내기 완료":RecorderApp.exportResult;
            if(TranscriptionService.running&&historyVisible)result=TranscriptionService.message;
            exportStatus.setText(result);exportStatus.setVisibility(result.isEmpty()?View.GONE:View.VISIBLE);
            if(DriveAuth.enabled(this)){try(DriveQueue q=new DriveQueue(this)){driveStatus.setText("드라이브 · 대기 "+q.pending()+"건 / 완료 "+q.completed()+"건 · 설정 보기");driveStatus.setVisibility(View.VISIBLE);}}else driveStatus.setVisibility(View.GONE);
            String key=n+":"+rec+":"+active+":"+opId+":"+stage+":"+(playingId==null?"":playingId)+":"+l+":"+TranscriptionService.revision+":"+db().count("transcript_reviews");
            if(historyVisible&&!key.equals(historyKey)){historyKey=key;renderHistory();}
            pumpFieldChecks();
        }catch(Exception e){String error="저장자료 확인 오류: "+e.getMessage();counts.setText(error);exportStatus.setText(error);exportStatus.setVisibility(View.VISIBLE);}
    }
    private String phaseName(String phase){return switch(phase){case "WORK"->"작업";case "WORK_SUPPLEMENT"->"추가";case "START"->"시작";case "START_SUPPLEMENT"->"시작 추가";case "END_SUPPLEMENT"->"마무리 추가";case "NOTE"->"특이사항";case "END"->"마무리";default->"이전 자료 · 미분류";};}
    private String recordingTitle(JSONObject r){
        if(r.isNull("operation_id"))return "이전 자료 · 미분류";
        JSONObject op=db().operation(r.optString("operation_id"));String phase=r.optString("phase");try{if("NOTE".equals(phase)&&FieldChecks.required(FieldChecks.effectivePhase(db(),r)))phase=FieldChecks.effectivePhase(db(),r)+"_SUPPLEMENT";}catch(JSONException ignored){}
        return op.optInt("sequence_no")+("WORK".equals(phase)?"번째 작업 · ":"차 조업 · ")+phaseName(phase)+("INTERRUPTED".equals(op.optString("status"))?" · 조업 중단":"");
    }
    private String when(long ms){return new SimpleDateFormat("MM.dd  HH:mm",Locale.KOREA).format(new Date(ms));}
    private void renderHistory() throws JSONException {
        list.removeAllViews();JSONArray recordings=db().rows("recordings");
        if(recordings.length()==0){text(list,"저장한 녹음이 여기에 표시됩니다.",15,Color.DKGRAY,false);return;}
        Map<String,JSONObject> trips=new HashMap<>();JSONArray sessions=db().rows("sessions");
        for(int i=0;i<sessions.length();i++){JSONObject trip=sessions.getJSONObject(i);trips.put(trip.getString("id"),trip);}
        String lastTrip=null;
        for(int i=recordings.length()-1;i>=0;i--){
            JSONObject r=recordings.getJSONObject(i);String id=r.getString("id"),state=r.getString("status");
            String tripId=r.getString("session_id");
            if(!tripId.equals(lastTrip)){JSONObject trip=trips.get(tripId);text(list,"항차 · "+when(trip.getLong("started_ms")),16,teal,true);lastTrip=tripId;}
            LinearLayout c=card(list);
            text(c,recordingTitle(r),26,ink,true);
            String caption=r.getString("label");text(c,when(r.getLong("started_ms"))+(caption.isEmpty()?"":" · "+caption),13,Color.DKGRAY,false);
            String status=switch(state){case "COMPLETED"->"저장됨";case "RECORDING"->"녹음 중";case "INTERRUPTED"->"중단됨 · 확인 필요";case "FAILED"->"녹음 오류";default->"준비 중";};
            String duration=r.isNull("ended_ms")?"":clockText(Math.max(0,(r.getLong("ended_ms")-r.getLong("started_ms"))/1000))+" · ";
            text(c,duration+status,14,"COMPLETED".equals(state)?teal:Color.rgb(146,79,26),false);
            LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.VERTICAL);c.addView(actions);
            if("COMPLETED".equals(state)){
                Button play=button(actions,id.equals(playingId)?"■  재생 중지":"▶  듣기",false,v->play(id));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(6),0,dp(4));play.setLayoutParams(lp);play.setEnabled(!CaptureService.recording);
            }
            if("COMPLETED".equals(state)){
                JSONObject transcript=db().latest("transcriptions","recording_id",id);
                boolean processing=TranscriptionService.running&&id.equals(TranscriptionService.recordingId);
                Button asr=button(c,processing?"전사 중 · 중지":transcript==null?"음성 → 글로": "전사 보기",false,v->{if(processing)startService(new Intent(this,TranscriptionService.class).setAction("cancel"));else showTranscription(id,null);});
                asr.setEnabled(processing||!CaptureService.recording&&!RecorderApp.exporting&&!TranscriptionService.running);
            }
            if(!r.isNull("operation_id"))button(actions,"현장 확인",false,v->showOperationChecks(r.optString("operation_id")));
            Button detail=button(actions,"상세 보기",false,v->showDetails(id));
            LinearLayout.LayoutParams detailParams=new LinearLayout.LayoutParams(-1,-2);detailParams.setMargins(0,dp(6),0,dp(4));detail.setLayoutParams(detailParams);
        }
    }
    private void showDiary(){
        try{
            JSONArray reviews=db().rows("transcript_reviews");Map<String,JSONObject> latest=new HashMap<>();
            for(int i=0;i<reviews.length();i++){JSONObject review=reviews.getJSONObject(i),attempt=db().row("transcriptions",review.getString("transcription_id"));latest.put(attempt.getString("recording_id"),review);}
            LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(20),dp(8),dp(20),dp(8));
            if(latest.isEmpty())text(panel,"전사를 확인하고 ‘일지에 저장’을 누르면 여기에 표시됩니다.",16,ink,false);
            JSONArray recordings=db().rows("recordings");
            for(int i=0;i<recordings.length();i++){
                JSONObject r=recordings.getJSONObject(i),review=latest.get(r.getString("id"));if(review==null)continue;
                LinearLayout c=card(panel);text(c,when(r.getLong("started_ms"))+" · "+recordingTitle(r),15,teal,true);JSONObject structured=db().latest("structured_logs","review_id",review.getString("id"));
                String summary=structured!=null&&"CONFIRMED".equals(structured.optString("status"))?StructuredLog.summary(new JSONObject(structured.getString("data_json"))):"";
                text(c,summary.isEmpty()?review.getString("text"):summary,26,ink,!summary.isEmpty());
                String id=r.getString("id"),tid=review.getString("transcription_id");button(c,"듣기 · 수정",false,v->showTranscription(id,tid));
            }
            ScrollView scroll=new ScrollView(this);scroll.addView(panel);new ReadableDialog.Builder(this).setTitle("일지 · 확인한 기록").setView(scroll).setPositiveButton("닫기",null).show();
        }catch(Exception e){toast("일지 확인 오류: "+e.getMessage());}
    }
    private void startTranscription(String id){
        if(CaptureService.recording||RecorderApp.exporting||TranscriptionService.running||asrStarting){toast("녹음·항목 확인·내보내기를 마친 뒤 실행하세요.");return;}
        stopPlayback();
        try{asrStarting=true;startingRecordingId=id;startForegroundService(new Intent(this,TranscriptionService.class).putExtra("recording_id",id));ui.postDelayed(()->{asrStarting=false;startingRecordingId="";historyKey="";refresh();},500);}
        catch(Exception e){asrStarting=false;startingRecordingId="";toast("전사를 시작할 수 없습니다: "+e.getMessage());}
    }
    private void transcriptionHistory(String id){
        try{
            JSONArray all=db().rows("transcriptions");ArrayList<String> labels=new ArrayList<>(),ids=new ArrayList<>();
            labels.add("다시 전사");ids.add(null);
            for(int i=all.length()-1;i>=0;i--){JSONObject t=all.getJSONObject(i);if(id.equals(t.getString("recording_id"))){ids.add(t.getString("id"));labels.add(when(t.getLong("created_ms"))+" · "+transcriptionStatus(t.getString("status")));}}
            new ReadableDialog.Builder(this).setTitle("전사 이력").setItems(labels.toArray(new String[0]),(d,w)->{if(w==0)startTranscription(id);else showTranscription(id,ids.get(w));}).setNegativeButton("닫기",null).show();
        }catch(Exception e){toast(e.getMessage());}
    }
    private String transcriptionStatus(String s){return switch(s){case "COMPLETED"->"완료";case "RUNNING"->"전사 중";case "CANCELLED"->"중지";case "INTERRUPTED"->"중단됨";default->"실패";};}
    private void showTranscription(String id,String attemptId){
        try{
            JSONObject t=attemptId==null?db().latest("transcriptions","recording_id",id):db().row("transcriptions",attemptId);
            if(t==null){startTranscription(id);return;}
            if(!"COMPLETED".equals(t.getString("status"))){
                new ReadableDialog.Builder(this).setTitle("전사 "+transcriptionStatus(t.getString("status"))).setMessage(t.getString("error")+"\n원음은 보존되어 있습니다.").setPositiveButton("다시 전사",(d,w)->startTranscription(id)).setNeutralButton("전사 이력",(d,w)->transcriptionHistory(id)).setNegativeButton("닫기",null).show();return;
            }
            String tid=t.getString("id");JSONObject review=db().latest("transcript_reviews","transcription_id",tid),recording=db().row("recordings",id);
            LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(20),dp(8),dp(20),dp(8));
            text(panel,recordingTitle(recording)+" · "+when(recording.getLong("started_ms")),14,teal,true);
            text(panel,review==null?"틀린 말을 고친 뒤 항목을 확인하세요.":"확인한 일지 · 수정 이력은 보존됩니다.",14,Color.DKGRAY,false);
            EditText input=new EditText(this);input.setText(review==null?t.getString("text"):review.getString("text"));input.setTextSize(26);input.setMinLines(4);input.setGravity(Gravity.TOP);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);panel.addView(input);
            button(panel,"원음 듣기",false,v->play(id));
            button(panel,"자동 전사 원문",false,v->new ReadableDialog.Builder(this).setTitle("자동 전사 원문").setMessage(t.optString("text").isEmpty()?"인식된 문장이 없습니다.":t.optString("text")).setPositiveButton("닫기",null).show());
            button(panel,"전사 이력",false,v->{if(transcriptDialog!=null)transcriptDialog.dismiss();transcriptionHistory(id);});
            ScrollView scroll=new ScrollView(this);scroll.addView(panel);
            AlertDialog dialog=new ReadableDialog.Builder(this).setTitle("전사 확인").setView(scroll).setPositiveButton("항목 확인",null).setNegativeButton("닫기",null).create();
            transcriptDialog=dialog;
            dialog.setOnShowListener(v->{ReadableDialog.style(dialog);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
                if(RecorderApp.exporting||TranscriptionService.running){toast("진행 중인 작업을 마친 뒤 저장하세요.");return;}
                String reviewed=input.getText().toString();if(reviewed.trim().isEmpty()){toast("전사 내용을 입력하세요.");return;}dialog.dismiss();StructuredReviewDialog.show(this,db(),tid,reviewed,()->{historyKey="";refresh();});
            });});dialog.show();
        }catch(Exception e){toast("전사 확인 오류: "+e.getMessage());}
    }
    private void showDetails(String id){
        try{
            JSONArray recordings=db().rows("recordings"),locations=db().rows("locations");JSONObject row=null,fix=null;
            for(int i=0;i<recordings.length();i++)if(id.equals(recordings.getJSONObject(i).getString("id"))){row=recordings.getJSONObject(i);break;}
            if(row==null){toast("녹음을 찾을 수 없습니다.");return;}
            String fixId=row.optString("start_location_id");boolean atStart=true;
            if(fixId.isEmpty()||"null".equals(fixId)){fixId=row.optString("end_location_id");atStart=false;}
            for(int i=0;i<locations.length();i++)if(fixId.equals(locations.getJSONObject(i).getString("id"))){fix=locations.getJSONObject(i);break;}
            StringBuilder message=new StringBuilder(recordingTitle(row)+"\n").append(new SimpleDateFormat("yyyy.MM.dd HH:mm:ss",Locale.KOREA).format(new Date(row.getLong("started_ms"))));
            if(fix==null)message.append("\n\n위치 미확보 · 좌표 없음");
            else{
                message.append(fix.optInt("is_mock",0)==1?"\n\n모의 좌표 · 실제 GPS 관측 아님":"\n\n"+(atStart?"시작":"종료")+" 위치");
                message.append(String.format(Locale.KOREA,"\n%.5f, %.5f",fix.getDouble("latitude"),fix.getDouble("longitude")));
                message.append("\n측정 ").append(when(fix.getLong("measured_ms"))).append(" · ").append(fix.getString("provider"));
                message.append(fix.isNull("accuracy_m")?"\n정확도 미제공":"\n정확도 ±"+Math.round(fix.getDouble("accuracy_m"))+"m");
            }
            if(!row.getString("error").isEmpty())message.append("\n\n").append(row.getString("error"));
            message.append("\n\n항차 ").append(row.getString("session_id").substring(0,8)).append(" · ").append(db().audio(id).length()/1024).append(" KB");
            AlertDialog.Builder dialog=new ReadableDialog.Builder(this).setTitle("녹음 상세").setMessage(message.toString()).setNegativeButton("닫기",null);
            if(fix!=null){double lat=fix.getDouble("latitude"),lon=fix.getDouble("longitude");dialog.setPositiveButton("지도 보기",(d,w)->{
                try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("geo:"+lat+","+lon+"?q="+lat+","+lon)));}catch(ActivityNotFoundException e){toast("설치된 지도 앱이 없습니다.");}
            });}
            dialog.show();
        }catch(Exception e){toast("상세 정보 확인 오류: "+e.getMessage());}
    }
    private void play(String id){if(id.equals(playingId)){stopPlayback();refresh();return;}stopPlayback();try{player=new MediaPlayer();player.setDataSource(db().audio(id).getAbsolutePath());player.setOnCompletionListener(p->{stopPlayback();refresh();});player.setOnErrorListener((p,w,e)->{stopPlayback();toast("녹음파일을 재생할 수 없습니다.");return true;});player.prepare();player.start();playingId=id;historyKey="";refresh();}catch(Exception e){stopPlayback();toast("재생 오류: "+e.getMessage());}}
    private void stopPlayback(){if(player!=null){player.release();player=null;}playingId=null;historyKey="";}
    private void chooseExport(){if(CaptureService.running||RecorderApp.exporting||TranscriptionService.running)return;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE,"조업기록_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.KOREA).format(new Date())+".zip");try{startActivityForResult(i,20);}catch(ActivityNotFoundException e){toast("파일 저장 앱을 찾을 수 없습니다.");}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=20||result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(CaptureService.running||RecorderApp.exporting||TranscriptionService.running){toast("항차를 마친 뒤 다시 내보내세요.");return;}
        RecorderApp.exporting=true;RecorderApp.exportResult="";Context app=getApplicationContext();refresh();
        new Thread(()->{File temp=null;try{temp=File.createTempFile("field-export-",".zip",app.getCacheDir());Archive.write(RecorderApp.store(),temp);try(InputStream in=new FileInputStream(temp);OutputStream out=app.getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("선택한 위치에 저장할 수 없습니다.");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.flush();}
            String expected;try(InputStream in=new FileInputStream(temp)){expected=Archive.sha256(in);}
            try(InputStream in=app.getContentResolver().openInputStream(uri)){if(in==null||!expected.equals(Archive.sha256(in)))throw new IOException("저장된 ZIP 검증 실패");}
            RecorderApp.exportResult="ZIP 내보내기 완료 · 휴대전화 원자료도 보존되어 있습니다.";}catch(Exception e){RecorderApp.exportResult="내보내기 실패 · 원자료는 보존됩니다. "+e.getMessage();try{DocumentsContract.deleteDocument(app.getContentResolver(),uri);}catch(Exception ignored){}}finally{if(temp!=null)temp.delete();RecorderApp.exporting=false;}},"archive-export").start();
    }
    private void updateCheckProgress(String op,boolean recording) throws JSONException {
        CheckProgress state=recording?null:CheckProgress.read(db(),op,TranscriptionService.running?TranscriptionService.recordingId:"",startingRecordingId);
        checkPanel.setVisibility(state==null?View.GONE:View.VISIBLE);
        if(state==null){progressKey="";getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);return;}
        checkTitle.setText(state.title);checkTitle.setContentDescription(state.title.replace('\n',' '));checkDetail.setText(state.detail);
        checkSpinner.setVisibility(state.busy?View.VISIBLE:View.GONE);
        checkPanel.setBackground(shape(state.busy?Color.rgb(255,240,194):Color.rgb(225,241,232),14));
        String key=op+":"+state.title;
        if(!key.equals(progressKey)&&state.busy&&!historyVisible)mainPane.post(()->mainPane.smoothScrollTo(0,0));
        progressKey=key;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(!state.busy)getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void updateFieldChecks(String op) throws JSONException {
        fieldButton.setVisibility(op==null||CaptureService.recording?View.GONE:View.VISIBLE);
        if(op!=null){int count="FISHING".equals(db().operation(op).optString("stage"))?FieldChecks.assess(db(),op,"START").issues.length():FieldChecks.operationIssues(db(),op).length();fieldButton.setText(TranscriptionService.running?"항목 확인 중 · 보기":count==0?"필수 항목 확인됨":"기록 확인 · "+count+"건");}
    }
    private void pumpFieldChecks() throws JSONException {
        if(!resumed||!hasWindowFocus()||fieldDialogOpen||CaptureService.recording||RecorderApp.exporting)return;
        if(!CaptureService.saveError.isEmpty()&&!CaptureService.saveError.equals(lastSaveError)){
            lastSaveError=CaptureService.saveError;new ReadableDialog.Builder(this).setTitle("녹음 저장 확인 필요").setMessage(lastSaveError).setPositiveButton("확인",null).show();return;
        }
        if(!TranscriptionService.running&&!asrStarting){String queued=FieldChecks.queued(db());if(queued!=null){JSONObject r=db().row("recordings",queued);db().event(r.getString("session_id"),queued,r.getString("operation_id"),"FIELD_ASR_ATTEMPTED",new JSONObject().put("version",1).toString());startTranscription(queued);return;}}
        if(TranscriptionService.running||asrStarting)return;
        // Show a new result once. Arrival/reopening never repeatedly interrupts the user.
        JSONArray events=db().rows("events");for(int i=events.length()-1;i>=0;i--){JSONObject e=events.getJSONObject(i);if(!FieldChecks.REQUEST.equals(e.optString("type")))continue;
            String op=e.optString("operation_id"),phase=new JSONObject(e.getString("detail")).getString("phase");FieldChecks.Check c=FieldChecks.assess(db(),op,phase);
            if(c.pending||FieldChecks.seen(db(),c))continue;
            FieldChecks.log(db(),c,FieldChecks.SHOWN,new JSONObject());if(c.issues.length()>0){if("WORK".equals(c.phase))showQuestions(c,false);else showFieldCheck(c);return;}
        }
    }
    private void showOperationChecks(String op){
        if(op==null)return;
        try{if(GearLog.isWork(db(),op)){FieldChecks.Check c=FieldChecks.assess(db(),op,"WORK");if(c.issues.length()>0&&!c.pending)showQuestions(c,true);else showFieldCheck(c);return;}}catch(Exception e){toast(e.getMessage());return;}
        new ReadableDialog.Builder(this).setTitle("기록 확인").setItems(new String[]{"시작 기록 확인","마무리 기록 확인"},(d,w)->{try{showFieldCheck(FieldChecks.assess(db(),op,w==0?"START":"END"));}catch(Exception e){toast(e.getMessage());}}).setNegativeButton("닫기",null).show();
    }
    private void showQuestions(FieldChecks.Check c,boolean revisit){
        if(CaptureService.recording||TranscriptionService.running||asrStarting)return;
        stopPlayback();fieldDialogOpen=true;
        questions=new QuestionDialog(this,db(),c.operation,revisit,(operation,question)->{
            if(!resumed)return;answerOperation=operation;answerQuestion=question.toString();supplementPhase="WORK";perform(CaptureService.ANSWER);
        },()->{fieldDialogOpen=true;try{FieldReviewDialog.show(this,db(),FieldChecks.assess(db(),c.operation,"WORK"),()->{fieldDialogOpen=false;historyKey="";refresh();});}catch(Exception e){fieldDialogOpen=false;toast(e.getMessage());}},()->{fieldDialogOpen=false;historyKey="";});
        questions.show();
    }
    private void showFieldCheck(FieldChecks.Check c){
        if(CaptureService.recording){toast("녹음을 저장한 뒤 확인하세요.");return;}
        fieldDialogOpen=true;
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(20),dp(8),dp(20),dp(8));
        text(panel,c.message(),24,ink,true);
        if("WORK".equals(c.phase))text(panel,GearLog.summary(c.data),22,ink,false);
        if(c.issues.length()==0)try{text(panel,StructuredLog.summary(c.data),22,ink,false);}catch(Exception ignored){}
        text(panel,"시각 자동 기록 · 특이사항은 선택",22,Color.DKGRAY,false);
        ScrollView scroll=new ScrollView(this);scroll.addView(panel);
        AlertDialog dialog=new ReadableDialog.Builder(this).setTitle(c.title()+" 확인").setView(scroll).setNegativeButton("나중에 확인",(d,w)->{try{FieldChecks.log(db(),c,FieldChecks.DEFER,new JSONObject().put("issues",c.issues));}catch(Exception e){toast(e.getMessage());}}).create();
        dialog.setOnDismissListener(v->fieldDialogOpen=false);
        if(TranscriptionService.running)button(panel,"음성 확인 중지",false,v->{startService(new Intent(this,TranscriptionService.class).setAction("cancel"));dialog.dismiss();});
        button(panel,"직접 입력 · 확인",false,v->{if(TranscriptionService.running||asrStarting){toast("음성 확인을 마치거나 중지한 뒤 입력하세요.");return;}dialog.dismiss();fieldDialogOpen=true;FieldReviewDialog.show(this,db(),c,()->{fieldDialogOpen=false;historyKey="";refresh();});});
        if("ACTIVE".equals(db().operation(c.operation).optString("status")))button(panel,"추가 녹음",false,v->{dialog.dismiss();supplementPhase=c.phase;perform(CaptureService.SUPPLEMENT);});
        try{for(int i=0;i<c.sources.length();i++){String rid=c.sources.getJSONObject(i).getString("recording_id");if("COMPLETED".equals(db().row("recordings",rid).optString("status")))button(panel,"음성 다시 확인",false,v->{dialog.dismiss();startTranscription(rid);});}}catch(Exception e){toast(e.getMessage());}
        dialog.show();
    }
    private void confirmOperationEnd(){
        try{String op=db().activeOperation(CaptureService.sessionId);if(op==null)return;
            JSONArray issues=FieldChecks.operationIssues(db(),op);String token=FieldChecks.fingerprint(db(),op);
            if(CheckProgress.pending(db(),op,TranscriptionService.running?TranscriptionService.recordingId:"",startingRecordingId)){
                fieldDialogOpen=true;
                LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(24),dp(12),dp(24),dp(12));
                text(panel,"아직 녹음 내용을\n확인 중입니다",30,ink,true);
                text(panel,"누락된 항목이 더 나올 수 있습니다.\n기다리면 완료 시 진동으로 알려드립니다.",22,ink,false);
                text(panel,"지금 종료해도 녹음과 확인 결과는 보존됩니다.",22,ink,false);
                ScrollView scroll=new ScrollView(this);scroll.addView(panel);
                AlertDialog dialog=new ReadableDialog.Builder(this).setTitle("조업 종료 전 확인").setView(scroll)
                    .setNegativeButton("기다리기",null).setNeutralButton("미확인으로 종료",(d,w)->closeWithToken(op,token)).create();
                dialog.setOnDismissListener(d->fieldDialogOpen=false);dialog.show();return;
            }
            if(issues.length()==0){closingToken=token;send(CaptureService.COMPLETE_OPERATION);return;}
            StringBuilder text=new StringBuilder();for(String phase:FieldChecks.phases(db(),op)){FieldChecks.Check c=FieldChecks.assess(db(),op,phase);if(c.issues.length()>0)text.append(c.title()).append('\n').append(c.message()).append("\n\n");}text.append("미확인 항목은 나중에 보완할 수 있습니다.");
            new ReadableDialog.Builder(this).setTitle("남은 확인 사항").setMessage(text).setPositiveButton("기록 보완",(d,w)->showOperationChecks(op)).setNeutralButton("미확인으로 종료",(d,w)->{closeWithToken(op,token);}).setNegativeButton("돌아가기",null).show();
        }catch(Exception e){toast(e.getMessage());}
    }
    private void closeWithToken(String op,String token){
        try{if(!token.equals(FieldChecks.fingerprint(db(),op))){toast("확인 결과가 도착했습니다. 종료 전 다시 확인해 주세요.");confirmOperationEnd();return;}
            closingToken=token;send(CaptureService.COMPLETE_OPERATION);
        }catch(Exception e){toast(e.getMessage());}
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
