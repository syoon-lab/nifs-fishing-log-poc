package kr.go.nifs.fieldrecorder;
import android.content.*;
import android.test.*;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

public class MissingQuestionsTest extends AndroidTestCase {
 private Store s;private File files;private String trip,op;
 @Override protected void setUp() throws Exception {super.setUp();files=new File(getContext().getCacheDir(),"questions-"+Store.id());assertTrue(files.mkdirs());Context c=new RenamingDelegatingContext(getContext(),"q_"+Store.id()+"_"){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return files;}};s=new Store(c);trip=s.depart(60000);op=s.startGearOperation(trip,"POT");}
 @Override protected void tearDown() throws Exception {s.close();super.tearDown();}
 private String transcribe(String rid,String text) throws Exception {
  Files.write(s.audio(rid).toPath(),new byte[]{7,8,9});s.recordingStarted(rid);s.completeOperationRecording(rid,"COMPLETED","",null);String hash;try(InputStream in=new FileInputStream(s.audio(rid))){hash=Archive.sha256(in);}String tid=s.beginTranscription(rid,"fixture",OfflineAsr.SHA,hash);s.finishTranscription(tid,new JSONObject().put("text",text).put("duration_ms",1000).put("segments",new JSONArray().put(new JSONObject().put("start_ms",0).put("end_ms",1000).put("text",text))));return tid;
 }
 private void initial(String text) throws Exception {transcribe(s.prepareOperationRecording(op,"WORK","시험",null),text);}
 private FieldChecks.Check check() throws Exception {return FieldChecks.assess(s,op,"WORK");}
 private MissingQuestions.Question question(String key) throws Exception {for(MissingQuestions.Question q:MissingQuestions.all(check()))if(q.key.equals(key))return q;throw new AssertionError("missing question "+key+" "+check().data);}
 private String answer(String key,String raw) throws Exception {MissingQuestions.Question q=question(key);return transcribe(s.prepareAnswer(op,MissingQuestions.request(check(),q),null),raw);}
 public void testHaulAndRedeployShortRepliesNeverCross() throws Exception {
  initial("꽃게 스무 마리 잡았습니다. 통발을 다시 놓았습니다.");answer("haul_quantity","서른 개입니다.");assertEquals(30,check().data.getInt("haul_quantity"));assertTrue(check().data.isNull("redeploy_quantity"));answer("redeploy_quantity","스물다섯 개입니다.");assertEquals(30,check().data.getInt("haul_quantity"));assertEquals(25,check().data.getInt("redeploy_quantity"));assertEquals(1,check().data.getJSONArray("catches").length());assertEquals("꽃게",check().data.getJSONArray("catches").getJSONObject(0).getString("species"));
 }
 public void testDeferringOneSpeciesSurvivesAnswerForAnother() throws Exception {
  initial("꽃게와 문어를 잡았습니다.");MissingQuestions.defer(s,check(),question("catch_amount:문어"));answer("catch_amount:꽃게","20마리입니다.");assertFalse(MissingQuestions.available(s,check(),false).stream().anyMatch(q->q.key.equals("catch_amount:문어")));
 }
 public void testNaturalShortRepliesWithGearNameAndConjunction() throws Exception {
  initial("통발을 걷었습니다. 꽃게를 잡았습니다.");answer("haul_quantity","통발은 서른 개입니다.");assertEquals(30,check().data.getInt("haul_quantity"));answer("catch_amount:꽃게","스무 마리고 사 킬로그램입니다.");JSONArray catches=check().data.getJSONArray("catches");assertEquals(1,catches.length());assertEquals(20,catches.getJSONObject(0).getInt("quantity"));assertEquals(4d,catches.getJSONObject(0).getDouble("weight_kg"));
 }
 public void testWrongUnitVagueAndUnknownNotInvented() throws Exception {
  initial("통발 양망했습니다.");answer("haul_quantity","열 폭입니다.");assertTrue(check().data.isNull("haul_quantity"));answer("haul_quantity","서른 개 정도입니다.");assertTrue(check().data.isNull("haul_quantity"));answer("haul_quantity","모릅니다.");assertTrue(check().data.isNull("haul_quantity"));
 }
 public void testQuestionUnitsAndNoCrew() throws Exception {
  initial("양망했습니다.");assertEquals("이번에 걷은 통발은 몇 개입니까?",question("haul_quantity").text);for(MissingQuestions.Question q:MissingQuestions.all(check()))assertFalse(q.text.contains("인원"));
  s.finishOperationChecked(op,FieldChecks.fingerprint(s,op));op=s.startGearOperation(trip,"GILLNET");initial("양망했습니다.");assertEquals("이번에 걷은 자망은 몇 폭입니까?",question("haul_quantity").text);answer("haul_quantity","열 폭입니다.");assertEquals(10,check().data.getInt("haul_quantity"));
 }
 public void testDateAnswerRemainsCandidate() throws Exception {initial("통발 30개 양망했습니다.");answer("deployment_date","11월 8일입니다.");assertEquals("2026-11-08",check().data.getString("deployment_date_candidate"));assertTrue(check().data.isNull("deployment_date"));assertEquals("CANDIDATE",check().data.getString("date_status"));}
 public void testSpeciesScopedShortAnswerDoesNotAffectOtherSpecies() throws Exception {
  initial("꽃게와 문어를 잡았습니다.");answer("catch_amount:꽃게","스무 마리 사 킬로그램입니다.");JSONArray rows=check().data.getJSONArray("catches");JSONObject flower=null,octopus=null;for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);if("꽃게".equals(r.optString("species")))flower=r;if("문어".equals(r.optString("species")))octopus=r;}
  assertNotNull(flower);assertNotNull(octopus);assertEquals(20,flower.getInt("quantity"));assertEquals(4d,flower.getDouble("weight_kg"));assertTrue(octopus.isNull("quantity"));
 }
 public void testNoCatchAndDispositionAnswers() throws Exception {initial("통발 양망했습니다.");answer("outcome","없습니다.");assertEquals("NO_CATCH",check().data.getString("outcome"));for(MissingQuestions.Question q:MissingQuestions.all(check()))assertFalse(q.key.startsWith("catch"));answer("disposition","전부 철거했습니다.");assertEquals("REMOVED",check().data.getString("disposition"));}
 public void testDeferredFieldStaysMissingAndCanBeRevisited() throws Exception {
  initial("통발 다시 놓았습니다.");FieldChecks.Check c=check();MissingQuestions.defer(s,c,question("deployment_date"));assertTrue(check().data.isNull("deployment_date"));assertFalse(MissingQuestions.available(s,check(),false).stream().anyMatch(q->q.key.equals("deployment_date")));assertTrue(MissingQuestions.available(s,check(),true).stream().anyMatch(q->q.key.equals("deployment_date")));
  answer("haul_quantity","30개입니다.");assertFalse(MissingQuestions.available(s,check(),false).stream().anyMatch(q->q.key.equals("deployment_date")));
 }
 public void testStaleQuestionAndClosedOperationRejected() throws Exception {
  initial("양망했습니다.");JSONObject stale=MissingQuestions.request(check(),question("haul_quantity"));answer("haul_quantity","30개입니다.");int before=s.count("recordings");try{s.prepareAnswer(op,stale,null);fail();}catch(IllegalStateException expected){}assertEquals(before,s.count("recordings"));
  JSONObject next=MissingQuestions.request(check(),question("deployment_date"));s.finishOperationChecked(op,FieldChecks.fingerprint(s,op));try{s.prepareAnswer(op,next,null);fail();}catch(IllegalStateException expected){}assertEquals(before,s.count("recordings"));
 }
 public void testPlainSupplementDoesNotBorrowQuestionContext() throws Exception {initial("양망했습니다.");String r=s.prepareSupplement(op,"WORK","일반 보완",null);transcribe(r,"서른 개입니다.");assertTrue(check().data.isNull("haul_quantity"));assertNull(FieldChecks.event(s,MissingQuestions.ANSWER,r,null,null));}
 public void testOriginalRowsAndRawAnswerSurviveExport() throws Exception {
  initial("통발 다시 놓았습니다.");JSONObject before=s.snapshot();String tid=answer("haul_quantity","서른 개입니다.");assertEquals("서른 개입니다.",s.row("transcriptions",tid).getString("text"));
  for(String table:Store.TABLES){JSONArray old=before.getJSONArray(table);for(int i=0;i<old.length();i++){JSONObject r=old.getJSONObject(i);assertEquals(r.toString(),s.row(table,r.getString("id")).toString());}}
  String r=s.row("transcriptions",tid).getString("recording_id");JSONObject context=FieldChecks.event(s,MissingQuestions.ANSWER,r,null,null);assertEquals("haul_quantity",new JSONObject(context.getString("detail")).getString("key"));assertTrue(check().data.isNull("redeploy_quantity"));s.finishOperationChecked(op,FieldChecks.fingerprint(s,op));s.arrive(trip);Archive.write(s,new File(files,"question-answer.zip"));
 }
 public void testPriorManualReviewPreservedWhenAnswerAdded() throws Exception {
  initial("통발 30개 양망했습니다. 꽃게를 잡았습니다.");FieldChecks.Check c=check();JSONObject d=new JSONObject(c.data.toString()).put("deployment_date","2026-11-08").put("date_status","CONFIRMED");FieldChecks.saveReview(s,c,d);answer("catch_amount:꽃게","20마리입니다.");assertEquals("2026-11-08",check().data.getString("deployment_date"));assertEquals(20,check().data.getJSONArray("catches").getJSONObject(0).getInt("quantity"));
 }
 public void testPackCoversEveryQuestionWithMatchingTextAndHash() throws Exception {
  JSONObject manifest;try(InputStream in=getContext().getAssets().open("questions/manifest.json")){manifest=new JSONObject(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
  JSONObject prompts=manifest.getJSONObject("prompts");assertEquals(12+FisheryExtractor.SPECIES.length,prompts.length());
  initial("꽃게를 잡았습니다.");for(String type:new String[]{"POT","GILLNET"}){
   FieldChecks.Check c=check();c.data=GearLog.extract("",type,"2026-11-09");for(MissingQuestions.Question q:MissingQuestions.all(c))assertEquals(q.text,prompts.getJSONObject(q.audio).getString("text"));
   c.data.put("disposition","REDEPLOYED");JSONArray catches=new JSONArray();for(String fish:FisheryExtractor.SPECIES)catches.put(new JSONObject().put("species",fish));c.data.put("catches",catches);for(MissingQuestions.Question q:MissingQuestions.all(c))assertEquals(q.text,prompts.getJSONObject(q.audio).getString("text"));
  }
  for(Iterator<String> keys=prompts.keys();keys.hasNext();){JSONObject prompt=prompts.getJSONObject(keys.next());try(InputStream in=getContext().getAssets().open("questions/"+prompt.getString("file"))){assertEquals(prompt.getString("sha256"),Archive.sha256(in));}assertTrue(prompt.getDouble("duration_seconds")>1);}
 }

 public void testRehearsalSentenceAnswerResolvesQuestionWithoutDuplicatingCatch() throws Exception {
  initial("문어 8마리 12킬로그램 잡았습니다. 통발을 다시 놓았습니다.");
  answer("haul_quantity","이번에 거둔 통발은 서른개입니다.");assertEquals(30,check().data.getInt("haul_quantity"));assertFalse(MissingQuestions.all(check()).stream().anyMatch(q->q.key.equals("haul_quantity")));
  assertTrue(check().data.isNull("redeploy_quantity"));answer("redeploy_quantity","다시 놓은 통발은 스물다섯개입니다.");assertEquals(25,check().data.getInt("redeploy_quantity"));
  assertEquals(1,check().data.getJSONArray("catches").length());assertEquals(8,check().data.getJSONArray("catches").getJSONObject(0).getInt("quantity"));
 }
 public void testRehearsalFullRecordOnlyDateConfirmationRemains() throws Exception {
  initial("11월 20일에 놓은 통발 30개를 거뒀습니다. 문어 8마리를 잡았습니다. 문어 무게는 모두 합쳐 12킬로그램입니다. 통발 25개를 다시 놓았습니다.");
  assertEquals(1,MissingQuestions.all(check()).size());assertEquals("deployment_date",MissingQuestions.all(check()).get(0).key);
  FieldChecks.Check c=check();FieldChecks.saveReview(s,c,new JSONObject(c.data.toString()).put("deployment_date","2026-11-20").put("date_status","CONFIRMED"));assertEquals(0,MissingQuestions.all(check()).size());assertEquals(0,check().issues.length());
 }
 public void testRehearsalNegatedSentenceAnswerStaysMissing() throws Exception {
  initial("통발 양망했습니다.");answer("haul_quantity","이번에 거둔 통발은 서른 개가 아닙니다.");assertTrue(check().data.isNull("haul_quantity"));answer("haul_quantity","서른 개 아니 스무 개입니다.");assertTrue(check().data.isNull("haul_quantity"));
 }

 private String stableSnapshot() throws Exception {JSONObject d=s.snapshot();d.remove("exported_at_ms");return d.toString();}
 private void makeLegacyDraft() throws Exception {
  JSONObject log=s.rows("structured_logs").getJSONObject(0),d=new JSONObject(log.getString("data_json"));d.put("version","gillnet-pot/1.0").put("haul_quantity",JSONObject.NULL);
  android.content.ContentValues v=new android.content.ContentValues();v.put("extractor_version","gillnet-pot/1.0");v.put("data_json",d.toString());s.getWritableDatabase().update("structured_logs",v,"id=?",new String[]{log.getString("id")});
  v=new android.content.ContentValues();v.putNull("haul_quantity");s.getWritableDatabase().update("gear_details",v,"structured_log_id=?",new String[]{log.getString("id")});
 }
 public void testLegacyDraftRecomputedWithoutChangingOriginalRowsOrAudio() throws Exception {
  initial("11월 20일에 놓은 통발 30개를 거뒀습니다. 문어 8마리 12킬로그램 잡았습니다. 통발 25개 다시 놓았습니다.");makeLegacyDraft();String before=stableSnapshot();
  assertEquals(30,check().data.getInt("haul_quantity"));assertEquals(before,stableSnapshot());assertFalse(MissingQuestions.all(check()).stream().anyMatch(q->q.key.equals("haul_quantity")));s.finishOperationChecked(op,FieldChecks.fingerprint(s,op));s.arrive(trip);Archive.write(s,new File(files,"legacy-draft.zip"));
 }
 public void testLegacyManualReviewWinsOverRecomputedDraft() throws Exception {
  initial("통발 30개를 거뒀습니다.");makeLegacyDraft();FieldChecks.Check c=check();FieldChecks.saveReview(s,c,new JSONObject(c.data.toString()).put("version","gillnet-pot/1.0").put("haul_quantity",31));String before=stableSnapshot();assertEquals(31,check().data.getInt("haul_quantity"));assertEquals(before,stableSnapshot());
 }
 public void testLegacyConfirmedLogNeverRecomputed() throws Exception {
  initial("통발 30개를 거뒀습니다.");JSONObject t=s.rows("transcriptions").getJSONObject(0);JSONObject d=new JSONObject(check().data.toString()).put("version","gillnet-pot/1.0").put("haul_quantity",31);s.reviewFields(t.getString("id"),t.getString("text"),d);String before=stableSnapshot();assertEquals(31,check().data.getInt("haul_quantity"));assertEquals(before,stableSnapshot());
 }
 public void testFieldDateAsrVerbErrorUsesSavedQuestionAndStaysCandidate() throws Exception {
  initial("통발 350개 양망했습니다.");answer("deployment_date","11월 3일날 도망했습니다.");
  assertEquals("2026-11-03",check().data.getString("deployment_date_candidate"));assertTrue(check().data.isNull("deployment_date"));
 }
 public void testFieldRedeploymentSentenceDoesNotRepeatOrInventCatch() throws Exception {
  initial("통발 350개 양망했습니다. 다시 투망했습니다. 문어 25마리 50키로.");
  answer("redeploy_quantity","투망한 통발은 350개입니다.");
  assertEquals(350,check().data.getInt("redeploy_quantity"));assertEquals(350,check().data.getInt("haul_quantity"));
  assertEquals(1,check().data.getJSONArray("catches").length());assertFalse(MissingQuestions.all(check()).stream().anyMatch(q->q.key.equals("redeploy_quantity")));
 }
 public void testFieldContextDoesNotGuessNegationOrConflictingQuantity() throws Exception {
  initial("다시 투망했습니다.");
  for(String raw:new String[]{"투망한 통발은 350개가 아닙니다.","투망한 통발은 350개 정도입니다.","투망한 통발은 350개 아니 300개입니다.","투망한 통발은 350폭입니다."}){answer("redeploy_quantity",raw);assertTrue(raw,check().data.isNull("redeploy_quantity"));}
 }
 public void testFieldManualDateResolutionKeepsSourceAndStopsStaleWarning() throws Exception {
  initial("11월 2일에 놓은 통발 30개, 11월 3일에 놓은 통발 20개 걷었습니다. 전부 철거했습니다. 어획이 없습니다.");
  FieldChecks.Check c=check();assertTrue(c.data.optBoolean("multiple_deployments"));String transcripts=s.rows("transcriptions").toString(),drafts=s.rows("structured_logs").toString();
  JSONObject d=new JSONObject(c.data.toString());GearLog.confirmDeploymentDate(d,"2026-11-03");d.put("haul_quantity",50);FieldChecks.saveReview(s,c,d);
  assertEquals(0,check().issues.length());assertEquals(0,MissingQuestions.all(check()).size());assertTrue(check().data.optBoolean("multiple_deployments_acknowledged"));
  assertEquals(transcripts,s.rows("transcriptions").toString());assertEquals(drafts,s.rows("structured_logs").toString());
 }
 public void testFieldRepetitiveAnswerIsPreservedWithoutInventingValues() throws Exception {
  initial("통발 양망했습니다.");String raw=String.join(" ",Collections.nCopies(19,"어종원 문어."));String tid=answer("haul_quantity",raw);
  assertEquals(raw,s.row("transcriptions",tid).getString("text"));assertTrue(check().data.optBoolean("recognition_uncertain"));assertTrue(check().data.isNull("haul_quantity"));assertEquals(0,check().data.getJSONArray("catches").length());
 }

 public void testFieldTodayDateAnswerUsesQuestionInsteadOfDiscardingIt() throws Exception {initial("통발 30개 양망했습니다.");answer("deployment_date","오늘 투망했습니다.");assertEquals(check().data.getString("recorded_date"),check().data.getString("deployment_date_candidate"));assertTrue(check().data.isNull("deployment_date"));}

 public void testActualFieldFullContextRetryExtractsHaulWithoutCorrectionAsCatch() throws Exception {
  initial("문어 25마리 50키로입니다. 다시 투망했습니다.");
  answer("haul_quantity","어종원, 문어, 오늘 통발은 몇 개입니까? 물어봐서. 그럼 몇 개, 55개로 하면 안 되겠네. 몇 개를 해야지. 350개. 오늘 거둔 양망한 통발은 350개.");
  assertEquals(350,check().data.getInt("haul_quantity"));assertEquals(1,check().data.getJSONArray("catches").length());assertEquals(25,check().data.getJSONArray("catches").getJSONObject(0).getInt("quantity"));
 }

}
