package kr.go.nifs.fieldrecorder;

import android.app.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** One large spoken question at a time; never starts the microphone automatically. */
final class QuestionDialog {
 interface Answer {void record(String operation,JSONObject question);}
 private final Activity a;private final Store store;private final String op;private final boolean revisit;private final Answer answer;private final Runnable done,manual;
 private final QuestionAudio audio;private final Handler ui=new Handler(Looper.getMainLooper());private final Set<String> skipped=new HashSet<>();
 private AlertDialog dialog;private LinearLayout panel;private FieldChecks.Check check;private MissingQuestions.Question question;private Button record,replay;private TextView status;private boolean closed;
 QuestionDialog(Activity a,Store store,String op,boolean revisit,Answer answer,Runnable manual,Runnable done){this.a=a;this.store=store;this.op=op;this.revisit=revisit;this.answer=answer;this.manual=manual;this.done=done;audio=new QuestionAudio(a);}
 private int dp(int n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
 private TextView text(String value,int size,boolean bold){TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(Color.rgb(23,46,53));t.setPadding(0,dp(6),0,dp(6));if(bold)t.setTypeface(null,Typeface.BOLD);panel.addView(t);return t;}
 private Button button(String title,boolean primary,Runnable click){Button b=new Button(a);b.setText(title);b.setAllCaps(false);b.setTextSize(primary?30:24);b.setMinHeight(dp(primary?88:64));b.setTextColor(primary?Color.WHITE:Color.rgb(23,107,96));GradientDrawable bg=new GradientDrawable();bg.setColor(primary?Color.rgb(23,107,96):Color.rgb(234,243,238));bg.setCornerRadius(dp(14));b.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(6),0,dp(4));panel.addView(b,lp);b.setOnClickListener(v->click.run());return b;}
 void show(){
  panel=new LinearLayout(a);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(20),dp(8),dp(20),dp(8));ScrollView scroll=new ScrollView(a);scroll.addView(panel);
  dialog=new ReadableDialog.Builder(a).setTitle("빠진 내용 확인").setView(scroll).setNegativeButton("닫기",null).create();dialog.setOnDismissListener(v->{closed=true;audio.stop();ui.removeCallbacksAndMessages(null);done.run();});dialog.show();a.setVolumeControlStream(AudioManager.STREAM_MUSIC);render();
 }
 private void render(){
  if(closed)return;audio.stop();panel.removeAllViews();
  try{
   check=FieldChecks.assess(store,op,"WORK");if(check.pending||TranscriptionService.running){text("녹음 내용을 확인 중입니다",32,true);text("확인이 끝나면 다시 열어 주세요",22,false);return;}
   List<MissingQuestions.Question> list=MissingQuestions.available(store,check,revisit);list.removeIf(q->skipped.contains(q.key));
   if(list.isEmpty()){text(check.issues.length()==0?"필수 항목 확인 완료":"미확인 항목은 보관했습니다",32,true);text(StructuredLog.summary(check.data),22,false);text(check.issues.length()==0?"내용을 확인한 뒤 기록 마침을 눌러 주세요":"기록 확인에서 다시 보완할 수 있습니다",22,false);button("직접 입력 · 확인",false,this::edit);return;}
   question=list.get(0);text("남은 질문 "+list.size()+"개",22,false);
   if(question.species!=null&&question.audio.equals("catch_amount"))text(question.species,24,true);
   boolean dateCandidate=question.key.equals("deployment_date")&&!check.data.isNull("deployment_date_candidate");
   TextView large=text(dateCandidate?"말씀하신 투망일을 확인해 주세요":question.text,34,true);large.setGravity(Gravity.CENTER);large.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
   status=text("",22,false);
   if(question.key.equals("deployment_date")&&!check.data.isNull("deployment_date_candidate")){String date=check.data.getString("deployment_date_candidate");button(date+" 맞음 · 적용",false,()->applyDate(date));}
   if(check.data.optBoolean("recognition_uncertain"))text("반복 전사가 감지됐습니다. 직접 입력으로 내용을 확인해 주세요.",22,true);
   record=button("답변 녹음",true,this::record);record.setEnabled(false);
   button("직접 입력 · 확인",false,this::edit);
   replay=button("다시 듣기",false,this::speak);
   button("모름·나중에 확인",false,()->{try{MissingQuestions.defer(store,check,question);skipped.add(question.key);render();}catch(Exception e){error(e);}});
   text("현재 반영된 내용\n"+StructuredLog.summary(check.data),20,false);
   if(check.sources.length()>0){String rid=check.sources.getJSONObject(check.sources.length()-1).getString("recording_id");JSONObject transcript=store.latest("transcriptions","recording_id",rid);if(transcript!=null){String heard=transcript.optString("text","").trim();if(!heard.isEmpty())text("최근 전사\n"+(heard.length()>180?heard.substring(0,180)+"…":heard),20,false);}}
   if(dateCandidate){status.setText("날짜가 맞으면 적용을 누르세요. 다시 말하지 않아도 됩니다.");record.setEnabled("ACTIVE".equals(store.operation(op).optString("status")));}
   else speak();
  }catch(Exception e){text("질문을 열지 못했습니다",30,true);text(e.getMessage(),22,false);}
 }
 private void speak(){if(closed||question==null)return;audio.play(question.audio,(busy,message)->{if(closed)return;status.setText(message);record.setEnabled(!busy&&"ACTIVE".equals(store.operation(op).optString("status")));record.setAlpha(record.isEnabled()?1f:0.45f);replay.setEnabled(!busy);replay.setAlpha(busy?0.45f:1f);});}
 private void applyDate(String date){try{audio.stop();FieldChecks.Check fresh=FieldChecks.assess(store,op,"WORK");if(!date.equals(fresh.data.optString("deployment_date_candidate")))throw new IllegalStateException("날짜 후보가 바뀌었습니다. 다시 확인해 주세요.");JSONObject data=new JSONObject(fresh.data.toString());GearLog.confirmDeploymentDate(data,date);FieldChecks.saveReview(store,fresh,data);render();}catch(Exception e){error(e);}}
 private void record(){
  if(QuestionAudio.isBusy()||closed)return;
  try{JSONObject request=MissingQuestions.request(check,question);MissingQuestions.current(store,op,request);audio.stop();dialog.dismiss();a.getWindow().getDecorView().postDelayed(()->{if(!a.isFinishing()&&!a.isDestroyed()&&a.hasWindowFocus())answer.record(op,request);},450);}catch(Exception e){error(e);}
 }
 private void edit(){audio.stop();dialog.dismiss();manual.run();}
 void close(){if(dialog!=null)dialog.dismiss();else{closed=true;audio.stop();}}
 private void error(Exception e){Toast.makeText(a,e.getMessage(),Toast.LENGTH_LONG).show();}
}
