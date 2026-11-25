package kr.go.nifs.fieldrecorder;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.test.InstrumentationTestCase;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Emulator only: real player/microphone lifecycle plus explicitly controlled ASR results. */
public class QuestionFlowTest extends InstrumentationTestCase {
 private Activity activity;private Store s;private Context context;
 private void await(BooleanSupplier p,long ms,String reason) throws Exception {long until=SystemClock.elapsedRealtime()+ms;while(!p.getAsBoolean()&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);assertTrue(reason,p.getAsBoolean());}
 private AccessibilityNodeInfo node(AccessibilityNodeInfo n,String text){if(n==null)return null;if(text.contentEquals(n.getText()==null?"":n.getText())||text.contentEquals(n.getContentDescription()==null?"":n.getContentDescription()))return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo r=node(n.getChild(i),text);if(r!=null)return r;}return null;}
 private AccessibilityNodeInfo node(String text){return node(getInstrumentation().getUiAutomation().getRootInActiveWindow(),text);}
 private boolean visible(String text){return node(text)!=null;}
 private void tap(String text) throws Exception {await(()->visible(text),15000,"Missing: "+text);AccessibilityNodeInfo n=node(text);while(n!=null&&!n.isClickable())n=n.getParent();assertNotNull(n);assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(250);}
 private void screen(String name) throws Exception {Bitmap b=getInstrumentation().getUiAutomation().takeScreenshot();try(OutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
 private void open() throws Exception {assertTrue("Emulator only",Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk"));context=getInstrumentation().getTargetContext();activity=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));s=RecorderApp.store();for(int i=0;i<5;i++){Thread.sleep(1100);if(visible("닫기"))tap("닫기");else break;}await(()->!QuestionAudio.isBusy(),3000,"prior notice closed");}
 private String result(String rid,String text) throws Exception {String hash;try(InputStream in=new FileInputStream(s.audio(rid))){hash=Archive.sha256(in);}String tid=s.beginTranscription(rid,"UI controlled answer fixture",OfflineAsr.SHA,hash);s.finishTranscription(tid,new JSONObject().put("text",text).put("duration_ms",1000).put("segments",new JSONArray().put(new JSONObject().put("start_ms",0).put("end_ms",1000).put("text",text))));return tid;}
 public void testSpokenQuestionReplayDeferAnswerAndPreservedExport() throws Exception {
  open();JSONObject before=s.snapshot();Files.write(new File(context.getExternalFilesDir(null),"questions-before.json").toPath(),before.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  tap("출항");await(()->CaptureService.running,10000,"departure");String trip=CaptureService.sessionId;final String[] ids=new String[2];
  getInstrumentation().runOnMainSync(()->{try{ids[0]=s.startGearOperation(trip,"POT");ids[1]=s.prepareOperationRecording(ids[0],"WORK","음성 질문 UI 시험",null);try(InputStream in=getInstrumentation().getContext().getAssets().open("extraction/01_caught_02_end.m4a");OutputStream out=new FileOutputStream(s.audio(ids[1]))){in.transferTo(out);}s.recordingStarted(ids[1]);s.completeOperationRecording(ids[1],"COMPLETED","",null);result(ids[1],"통발 양망했습니다. 전부 철거했습니다. 어획이 없습니다.");}catch(Exception e){throw new RuntimeException(e);}});
  await(()->visible("이번에 걷은 통발은 언제 놓았습니까?"),10000,"date question");assertTrue(QuestionAudio.isBusy());assertFalse(node("답변 녹음").isEnabled());screen("questions_01_date_playing");
  tap("모름·나중에 확인");await(()->visible("이번에 걷은 통발은 몇 개입니까?"),5000,"next question");assertTrue(FieldChecks.assess(s,ids[0],"WORK").data.isNull("deployment_date"));
  await(()->!QuestionAudio.isBusy()&&node("답변 녹음").isEnabled(),12000,"question playback complete");assertFalse(CaptureService.recording);screen("questions_02_haul_ready");tap("다시 듣기");assertTrue(QuestionAudio.isBusy());assertFalse(node("답변 녹음").isEnabled());
  await(()->!QuestionAudio.isBusy()&&node("답변 녹음").isEnabled(),12000,"replay complete");tap("답변 녹음");await(()->CaptureService.recording,10000,"answer microphone");assertFalse(QuestionAudio.isBusy());String answer=CaptureService.recordingId;
  JSONObject link=FieldChecks.event(s,MissingQuestions.ANSWER,answer,null,null);assertNotNull(link);assertEquals("haul_quantity",new JSONObject(link.getString("detail")).getString("key"));assertEquals(ids[0],s.row("recordings",answer).getString("operation_id"));Thread.sleep(1600);screen("questions_03_answer_recording");tap("■  녹음 종료 · 저장");await(()->!CaptureService.recording,5000,"answer saved");
  await(()->TranscriptionService.running,15000,"answer auto transcription");getInstrumentation().runOnMainSync(()->activity.startService(new Intent(activity,TranscriptionService.class).setAction("cancel")));await(()->!TranscriptionService.running,60000,"cancel silent emulator answer");tap("닫기");
  result(answer,"이번에 거둔 통발은 서른개입니다.");await(()->visible("미확인 항목은 보관했습니다"),10000,"deferred date not asked again");FieldChecks.Check c=FieldChecks.assess(s,ids[0],"WORK");assertEquals(30,c.data.getInt("haul_quantity"));assertTrue(c.data.isNull("deployment_date"));assertTrue(c.data.isNull("redeploy_quantity"));assertEquals("NO_CATCH",c.data.getString("outcome"));screen("questions_04_deferred");tap("닫기");tap("기록 확인 · 1건");await(()->visible("이번에 걷은 통발은 언제 놓았습니까?"),5000,"revisit deferred date");
  getInstrumentation().runOnMainSync(()->activity.moveTaskToBack(true));await(()->!QuestionAudio.isBusy(),3000,"background stops question");assertFalse(CaptureService.recording);
  getInstrumentation().getUiAutomation().executeShellCommand("am start -n kr.go.nifs.fieldrecorder/.MainActivity").close();tap("기록 마침");tap("미확인으로 종료");await(()->"COMPLETED".equals(s.operation(ids[0]).optString("status")),5000,"unconfirmed close");tap("입항");await(()->!CaptureService.running,10000,"arrival");
  for(String table:Store.TABLES){JSONArray old=before.getJSONArray(table);for(int i=0;i<old.length();i++){JSONObject row=old.getJSONObject(i);assertEquals(table,row.toString(),s.row(table,row.getString("id")).toString());}}
  assertEquals(before.getJSONArray("recordings").length()+2,s.count("recordings"));Archive.write(s,new File(context.getExternalFilesDir(null),"questions-flow.zip"));Files.write(new File(context.getExternalFilesDir(null),"questions-result.json").toPath(),new JSONObject().put("operation_id",ids[0]).put("answer_recording_id",answer).put("fields",c.data).put("note","실제 플레이어/마이크 UI 시험. 답변은 무음 녹음 후 제어 전사문으로 연결 검증; 인식률 시험 아님.").toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
 }
 public void testBrokenPromptFallsBackAndStopDoesNotEnableStaleCallback() throws Exception {
  open();AtomicBoolean ready=new AtomicBoolean();final QuestionAudio[] audio=new QuestionAudio[1];getInstrumentation().runOnMainSync(()->{audio[0]=new QuestionAudio(activity);audio[0].play("missing_test_asset",(busy,message)->{if(!busy)ready.set(true);});});await(ready::get,3000,"text fallback");assertFalse(QuestionAudio.isBusy());
  ready.set(false);getInstrumentation().runOnMainSync(()->{audio[0].play("haul_pot",(busy,message)->{if(!busy)ready.set(true);});audio[0].stop();});Thread.sleep(900);assertFalse("stopped player callback",ready.get());assertFalse("player busy after stop",QuestionAudio.isBusy());assertFalse("unexpected microphone",CaptureService.recording);
 }

 public void testRehearsalDateCandidateOneTapAndNoRepeatedCountQuestion() throws Exception {
  open();tap("출항");await(()->CaptureService.running,10000,"departure");String trip=CaptureService.sessionId;final String[] ids=new String[2];
  getInstrumentation().runOnMainSync(()->{try{ids[0]=s.startGearOperation(trip,"POT");ids[1]=s.prepareOperationRecording(ids[0],"WORK","문어 통발 회귀 UI 시험",null);try(InputStream in=getInstrumentation().getContext().getAssets().open("extraction/01_caught_02_end.m4a");OutputStream out=new FileOutputStream(s.audio(ids[1]))){in.transferTo(out);}s.recordingStarted(ids[1]);s.completeOperationRecording(ids[1],"COMPLETED","",null);result(ids[1],"11월 20일에 놓은 통발 30개를 거뒀습니다. 문어 8마리 12킬로그램 잡았습니다. 통발 25개를 다시 놓았습니다.");}catch(Exception e){throw new RuntimeException(e);}});
  await(()->visible("말씀하신 투망일을 확인해 주세요"),10000,"date confirmation not repeated request");await(()->!QuestionAudio.isBusy(),1500,"no date question audio");screen("rehearsal_01_date_candidate");tap("2026-11-20 맞음 · 적용");
  await(()->visible("필수 항목 확인 완료"),10000,"no repeated haul question");assertEquals(0,FieldChecks.assess(s,ids[0],"WORK").issues.length());screen("rehearsal_02_complete");tap("닫기");tap("기록 마침");await(()->"COMPLETED".equals(s.operation(ids[0]).optString("status")),5000,"complete");tap("입항");await(()->!CaptureService.running,10000,"arrived");Archive.write(s,new File(context.getExternalFilesDir(null),"rehearsal-ui.zip"));
 }
}
