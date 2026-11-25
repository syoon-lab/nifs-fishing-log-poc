package kr.go.nifs.fieldrecorder;
import android.content.*;
import android.test.*;
import org.json.*;
import java.io.*;
import java.nio.file.Files;

public class GearLogTest extends AndroidTestCase {
 private Store s;private File files;private String trip,op;
 @Override protected void setUp() throws Exception {super.setUp();files=new File(getContext().getCacheDir(),"gear-"+Store.id());assertTrue(files.mkdirs());Context c=new RenamingDelegatingContext(getContext(),"gear_"+Store.id()+"_"){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return files;}};s=new Store(c);trip=s.depart(60000);op=s.startGearOperation(trip,"POT");}
 @Override protected void tearDown() throws Exception {s.close();super.tearDown();}
 private JSONObject parse(String text,String type) throws Exception {JSONObject d=GearLog.extract(text,type,"2026-11-09");StructuredLog.validate(d);return d;}
 private String voice(String text,boolean extra) throws Exception {
  String rid=extra?s.prepareSupplement(op,"WORK","시험 보완",null):s.prepareOperationRecording(op,"WORK","시험",null);Files.write(s.audio(rid).toPath(),new byte[]{9,8,7});s.recordingStarted(rid);s.completeOperationRecording(rid,"COMPLETED","",null);
  String hash;try(InputStream in=new FileInputStream(s.audio(rid))){hash=Archive.sha256(in);}String tid=s.beginTranscription(rid,"fixture",OfflineAsr.SHA,hash);
  s.finishTranscription(tid,new JSONObject().put("text",text).put("duration_ms",1000).put("segments",new JSONArray().put(new JSONObject().put("start_ms",0).put("end_ms",1000).put("text",text))));return tid;
 }
 public void testPotDifferentHaulAndResetCountsAndSpecies() throws Exception {
  JSONObject d=parse("11월 8일에 놓은 통발 서른 개 걷었습니다. 꽃게 스무 마리 사 킬로그램, 문어 세 마리 육 킬로그램 잡았습니다. 통발 스물다섯 개 다시 투망했습니다.","POT");
  assertEquals("2026-11-08",d.getString("deployment_date_candidate"));assertTrue(d.isNull("deployment_date"));assertEquals("CANDIDATE",d.getString("date_status"));assertEquals(30,d.getInt("haul_quantity"));assertEquals(25,d.getInt("redeploy_quantity"));assertEquals("개",d.getString("gear_unit"));assertTrue(d.isNull("workers"));assertEquals(2,d.getJSONArray("catches").length());assertEquals(20,d.getJSONArray("catches").getJSONObject(0).getInt("quantity"));assertEquals(4d,d.getJSONArray("catches").getJSONObject(0).getDouble("weight_kg"));assertEquals(3,d.getJSONArray("catches").getJSONObject(1).getInt("quantity"));assertEquals("REDEPLOYED",d.getString("disposition"));assertEquals(1,GearLog.issues(d).length());
 }
 public void testGillnetUnitAndAffirmativeCatchWithoutAmount() throws Exception {
  JSONObject d=parse("8일에 설치한 자망 열 폭 양망했습니다. 꽃게를 잡았습니다. 자망 열 폭 다시 투망했습니다.","GILLNET");assertEquals(10,d.getInt("haul_quantity"));assertEquals(10,d.getInt("redeploy_quantity"));assertEquals("폭",d.getString("gear_unit"));assertEquals("CAUGHT",d.getString("outcome"));assertFalse(GearLog.issues(d).toString().contains("어획이 있었는지"));assertTrue(GearLog.issues(d).toString().contains("꽃게"));
 }
 public void testRelativeDateUsesRecordingDateNotProcessingClock() throws Exception {
  assertEquals("2026-11-08",parse("어제 설치한 통발 30개 걷었습니다.","POT").getString("deployment_date_candidate"));
  assertEquals("2026-11-06",parse("사흘 전에 놓은 통발 30개 양망했습니다.","POT").getString("deployment_date_candidate"));
  assertEquals("2026-10-31",GearLog.extract("어제 놓은 통발 30개 걷었습니다.","POT","2026-11-01").getString("deployment_date_candidate"));
 }
 public void testSinoDateAndExplicitYear() throws Exception {assertEquals("2026-11-08",parse("십일월 팔일에 놓은 통발 서른 개 걷었습니다.","POT").getString("deployment_date_candidate"));assertEquals("2025-12-31",parse("2025년 12월 31일에 설치한 통발 30개 걷었습니다.","POT").getString("deployment_date_candidate"));}
 public void testImpossibleFutureAndMultipleDatesNotGuessed() throws Exception {
  for(String text:new String[]{"2월 30일에 놓은 통발 30개 걷었습니다.","12월 1일에 놓은 통발 30개 걷었습니다.","11월 7일에 놓은 통발 10개, 11월 8일에 놓은 통발 20개 걷었습니다."})assertTrue(text,parse(text,"POT").isNull("deployment_date_candidate"));
 }
 public void testTodayRedeploymentIsNotPreviousDeploymentDate() throws Exception {JSONObject d=parse("통발 30개 걷었습니다. 오늘 다시 통발 25개 투망했습니다.","POT");assertTrue(d.isNull("deployment_date_candidate"));assertEquals(30,d.getInt("haul_quantity"));assertEquals(25,d.getInt("redeploy_quantity"));}
 public void testResetDoesNotFillHaulAndHaulDoesNotFillReset() throws Exception {JSONObject a=parse("통발 30개 다시 투망했습니다.","POT");assertTrue(a.isNull("haul_quantity"));assertEquals(30,a.getInt("redeploy_quantity"));JSONObject b=parse("통발 30개 양망했습니다.","POT");assertTrue(b.isNull("redeploy_quantity"));assertEquals("UNCONFIRMED",b.getString("disposition"));}
 public void testConflictsWrongUnitsAndApproximateStayUnconfirmed() throws Exception {
  assertTrue(parse("자망 열 개 양망했습니다.","GILLNET").isNull("haul_quantity"));
  assertTrue(parse("통발 30개 양망했습니다. 통발 25개 양망했습니다.","POT").isNull("haul_quantity"));
  assertTrue(parse("통발 서른 개 정도 걷었습니다.","POT").isNull("haul_quantity"));
  assertTrue(parse("통발 10에서 15개 걷었습니다.","POT").isNull("haul_quantity"));
 }
 public void testNoCatchFinalRemovalAndNoCrewRequirement() throws Exception {JSONObject d=parse("8일에 놓은 통발 30개 양망했습니다. 어획은 없습니다. 어구는 전부 철거했습니다.","POT");assertEquals("REMOVED",d.getString("disposition"));assertEquals("NO_CATCH",d.getString("outcome"));assertEquals(1,GearLog.issues(d).length());assertFalse(GearLog.issues(d).toString().contains("인원"));}
 public void testRemovalDoesNotMeanRedeploymentZero() throws Exception {JSONObject d=parse("통발 30개 걷고 전부 철거했습니다.","POT");assertTrue(d.isNull("redeploy_quantity"));assertEquals("REMOVED",d.getString("disposition"));}
 public void testManualDateValidationAndBlankPreserved() throws Exception {JSONObject d=parse("통발 30개 걷었습니다.","POT");d.put("deployment_date","2026-02-30").put("date_status","CONFIRMED");try{StructuredLog.validate(d);fail();}catch(IllegalArgumentException expected){}d.put("deployment_date",JSONObject.NULL).put("date_status","MISSING");StructuredLog.validate(d);assertTrue(d.isNull("deployment_date"));}
 public void testSingleRecordingCompletesWorkAndExportIncludesTypedGear() throws Exception {
  String tid=voice("8일에 놓은 통발 30개 걷었습니다. 꽃게 스무 마리 사 킬로그램 잡았습니다. 통발 25개 다시 투망했습니다.",false);assertEquals("READY_TO_END",s.operation(op).getString("stage"));assertEquals(1,s.count("gear_details"));assertEquals(30,s.rows("gear_details").getJSONObject(0).getInt("haul_quantity"));
  FieldChecks.Check c=FieldChecks.assess(s,op,"WORK");assertTrue(c.data.isNull("workers"));assertEquals(1,c.issues.length());JSONObject raw=s.row("transcriptions",tid);String oldDraft=s.rows("structured_logs").toString();
  JSONObject confirmed=new JSONObject(c.data.toString()).put("deployment_date",c.data.get("deployment_date_candidate")).put("date_status","CONFIRMED");FieldChecks.saveReview(s,c,confirmed);assertEquals(0,FieldChecks.operationIssues(s,op).length());assertEquals(oldDraft,s.rows("structured_logs").toString());assertEquals(raw.toString(),s.row("transcriptions",tid).toString());
  s.reviewFields(tid,raw.getString("text"),confirmed);assertEquals(2,s.count("gear_details"));assertEquals("CONFIRMED",s.rows("gear_details").getJSONObject(1).getString("date_status"));s.finishOperationChecked(op,null);s.arrive(trip);Archive.write(s,new File(files,"single.zip"));assertEquals(1,s.count("recordings"));
 }
 public void testSupplementKeepsConfirmedDateAndConflictsStayMissing() throws Exception {
  voice("8일에 놓은 통발 30개 걷었습니다.",false);FieldChecks.Check c=FieldChecks.assess(s,op,"WORK");JSONObject d=new JSONObject(c.data.toString()).put("deployment_date",c.data.get("deployment_date_candidate")).put("date_status","CONFIRMED");FieldChecks.saveReview(s,c,d);
  voice("꽃게 스무 마리 잡았습니다. 통발 25개 다시 투망했습니다.",true);c=FieldChecks.assess(s,op,"WORK");assertEquals("2026-11-08",c.data.getString("deployment_date"));assertEquals(30,c.data.getInt("haul_quantity"));assertEquals(25,c.data.getInt("redeploy_quantity"));
  voice("통발 35개 양망했습니다.",true);assertTrue(FieldChecks.assess(s,op,"WORK").data.isNull("haul_quantity"));
 }
 public void testFailedAudioAndNoRecordingCannotFinish() throws Exception {try{s.finishOperationChecked(op,null);fail();}catch(IllegalStateException expected){}String r=s.prepareOperationRecording(op,"WORK","",null);s.completeOperationRecording(r,"FAILED","short",null);assertEquals("WORK_REQUIRED",s.operation(op).getString("stage"));assertNull(FieldChecks.queued(s));}
 public void testCorruptTypedGearBlocksExport() throws Exception {voice("통발 30개 걷었습니다.",false);s.finishOperationChecked(op,FieldChecks.fingerprint(s,op));s.arrive(trip);s.getWritableDatabase().execSQL("UPDATE gear_details SET haul_quantity=99");try{Archive.write(s,new File(files,"bad.zip"));fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("불일치"));}}
 public void testOtherMethodsAreNotSelectable() throws Exception {try{GearLog.extract("연승 양승했습니다.","LONGLINE","2026-11-09");fail();}catch(IllegalArgumentException expected){}assertTrue(parse("자망 10폭 양망했습니다.","POT").optBoolean("gear_conflict"));}

 public void testNegativeAndPlannedDeploymentNotRecordedAsDone() throws Exception {
  for(String sentence:new String[]{"통발 30개 다시 투망 안했습니다.","통발 30개 다시 투망하지 않았습니다.","내일 통발 30개 다시 놓을 겁니다.","통발 30개 다시 투망할 예정입니다."}){
   JSONObject d=parse(sentence,"POT");assertTrue(sentence,d.isNull("redeploy_quantity"));assertEquals(sentence,"UNCONFIRMED",d.getString("disposition"));
  }
  assertTrue(parse("8일에 설치하지 않았습니다.","POT").isNull("deployment_date_candidate"));
 }

 public void testNegativeCountsNeverBecomePositive() throws Exception {assertTrue(parse("통발 -30개 양망했습니다.","POT").isNull("haul_quantity"));assertTrue(parse("자망 −10폭 다시 투망했습니다.","GILLNET").isNull("redeploy_quantity"));}
 public void testMultipleDeploymentDatesDoNotKeepOnlyLastHaulCount() throws Exception {JSONObject d=parse("7일에 놓은 통발 10개, 8일에 놓은 통발 20개 걷었습니다.","POT");assertTrue(d.isNull("deployment_date_candidate"));assertTrue(d.isNull("haul_quantity"));assertTrue(d.optBoolean("multiple_deployments"));}
 public void testRemainingPartialRemovalDoesNotOverwriteHaul() throws Exception {JSONObject d=parse("통발 30개 걷고 25개 다시 투망했습니다. 나머지 5개 철거했습니다.","POT");assertEquals(30,d.getInt("haul_quantity"));assertEquals(25,d.getInt("redeploy_quantity"));assertEquals("REDEPLOYED",d.getString("disposition"));}

 public void testRehearsalFullOctopusRecordAndActualTranscript() throws Exception {
  for(String verb:new String[]{"걷었습니다","거뒀습니다","거두었습니다","건졌습니다"}){
   JSONObject d=GearLog.extract("11월 20일에 놓은 통발 30개를 "+verb+". 문어 8마리를 잡았습니다. 문어 무게는 모두 합쳐 12킬로그램입니다. 통발 25개를 다시 놓았습니다.","POT","2026-11-22");
   assertEquals(verb,30,d.getInt("haul_quantity"));assertEquals(25,d.getInt("redeploy_quantity"));assertEquals("2026-11-20",d.getString("deployment_date_candidate"));assertEquals(8,d.getJSONArray("catches").getJSONObject(0).getInt("quantity"));assertEquals(12d,d.getJSONArray("catches").getJSONObject(0).getDouble("weight_kg"));StructuredLog.validate(d);
  }
 }
 public void testRehearsalFrontLoadedCounts() throws Exception {
  for(String verb:new String[]{"거둔","걷은","건진","회수한","양망한"}){
   JSONObject d=parse("이번에 "+verb+" 통발은 서른개입니다. 다시 놓은 통발은 스물다섯 개입니다.","POT");assertEquals(verb,30,d.getInt("haul_quantity"));assertEquals(25,d.getInt("redeploy_quantity"));
  }
 }
 public void testRehearsalOverallNoCatchAndTargetOnlyAbsence() throws Exception {
  JSONObject d=GearLog.extract("11월 21일에 놓은 통발 20개를 걷었습니다. 문어도 없고 다른 어획물도 없습니다. 통발 20개를 다시 놓았습니다.","POT","2026-11-22");assertEquals("NO_CATCH",d.getString("outcome"));assertEquals(20,d.getInt("haul_quantity"));assertEquals(20,d.getInt("redeploy_quantity"));
  assertFalse("NO_CATCH".equals(parse("문어는 없습니다.","POT").getString("outcome")));
  JSONObject other=parse("문어도 없고 다른 어획물도 없습니다. 꽃게 2마리 잡았습니다.","POT");assertEquals("UNCONFIRMED",other.getString("outcome"));
 }
 public void testRehearsalCountNegationConflictAndNoContext() throws Exception {
  for(String t:new String[]{"이번에 거둔 통발은 서른 개가 아닙니다.","이번에 거둔 통발은 서른 개 정도입니다.","이번에 거둔 통발은 서른 개 아니 스무 개입니다.","이번에 거둔 통발은 서른 개입니다. 이번에 거둔 통발은 스무 개입니다.","서른개입니다.","이번에 거둔 통발은 열 폭입니다."})assertTrue(t,parse(t,"POT").isNull("haul_quantity"));
 }
 public void testActualFieldMixedInterviewAndRedeploymentKeepsNovemberThird() throws Exception {
  String raw="11월 3일날 통발 55개 투망해서 오늘 어양양은 25마리에 50키로 정도 되겠습니다. 문어 25마리입니다. 문어 25마리입니다. 문어. 그리고 오늘 몇 개 또 투망했습니까? 오늘 55개 투망했습니다.";
  JSONObject d=GearLog.extract(raw,"POT","2026-11-23");StructuredLog.validate(d);
  assertEquals("2026-11-03",d.getString("deployment_date_candidate"));assertFalse(d.optBoolean("multiple_deployments"));assertTrue(d.isNull("deployment_date"));
  assertTrue("Do not infer haul from deployment",d.isNull("haul_quantity"));assertEquals(1,d.getJSONArray("catches").length());assertEquals("문어",d.getJSONArray("catches").getJSONObject(0).getString("species"));assertEquals(25,d.getJSONArray("catches").getJSONObject(0).getInt("quantity"));
 }
 public void testTodayRetrospectiveDateAndRealMultipleDatesStillWork() throws Exception {
  assertEquals("2026-11-09",parse("오늘 놓은 통발 30개 양망하고 25개 다시 투망했습니다.","POT").getString("deployment_date_candidate"));
  assertTrue(parse("7일에 놓은 통발 10개, 8일에 놓은 통발 20개 걷었습니다.","POT").optBoolean("multiple_deployments"));
 }
 public void testInterviewerQuestionsNeverBecomeCatchStatements() throws Exception {
  JSONObject d=parse("오늘 문어 55마리 잡았습니까? 문어 25마리 잡았습니다.","POT");assertEquals(1,d.getJSONArray("catches").length());assertEquals(25,d.getJSONArray("catches").getJSONObject(0).getInt("quantity"));
 }
 public void testPreviousExtractorVersionsRemainReadable() throws Exception {
  for(String version:new String[]{"gillnet-pot/1.0","gillnet-pot/1.1","gillnet-pot/1.2"})StructuredLog.validate(parse("통발 30개 양망했습니다.","POT").put("version",version));
 }

}
