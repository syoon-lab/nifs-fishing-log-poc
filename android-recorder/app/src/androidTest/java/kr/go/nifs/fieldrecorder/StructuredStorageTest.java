package kr.go.nifs.fieldrecorder;
import android.content.*;
import android.test.*;
import org.json.*;
import java.io.*;
import java.nio.file.Files;

public class StructuredStorageTest extends AndroidTestCase {
 public void testOldAndNewExtractorExportTogetherWithoutRewritingHistory() throws Exception {
  String text="꽃게 스무 마리입니다.",t=transcribe(text);JSONObject old=store.rows("structured_logs").getJSONObject(0),fields=new JSONObject(old.getString("data_json")).put("version","common-fields-ko/1.0");
  ContentValues v=new ContentValues();v.put("extractor_version","common-fields-ko/1.0");v.put("data_json",fields.toString());store.getWritableDatabase().update("structured_logs",v,"id=?",new String[]{old.getString("id")});
  String before=store.row("structured_logs",old.getString("id")).toString();store.reviewFields(t,text,fields);transcribe("고등어 열두 마리입니다.");
  Archive.write(store,new File(files,"mixed-versions.zip"));assertEquals(before,store.row("structured_logs",old.getString("id")).toString());
  assertEquals("common-fields-ko/1.0",store.rows("structured_logs").getJSONObject(1).getString("extractor_version"));assertEquals(FisheryExtractor.VERSION,store.rows("structured_logs").getJSONObject(2).getString("extractor_version"));
 }
 public void testUnknownExtractorVersionRemainsRejected() throws Exception {
  JSONObject fields=FisheryExtractor.extract("꽃게 스무 마리입니다.","END").put("version","common-fields-ko/99.0");
  try{StructuredLog.validate(fields);fail("unknown version accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("버전"));}
 }
 private Store store;private File files;
 @Override protected void setUp() throws Exception {super.setUp();files=new File(getContext().getCacheDir(),"structured-test-"+Store.id());assertTrue(files.mkdirs());Context ctx=new RenamingDelegatingContext(getContext(),"fields_"+Store.id()+"_"){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return files;}};store=new Store(ctx);}
 @Override protected void tearDown() throws Exception {store.close();super.tearDown();}
 private String transcribe(String text) throws Exception {String s=store.startSession(60000),r=store.prepareRecording(s,"test",null);Files.write(store.audio(r).toPath(),new byte[]{1,2,3});store.recordingStarted(r);store.endRecording(r,"COMPLETED","",null);store.endSession(s,"COMPLETED");String sha;try(InputStream in=new FileInputStream(store.audio(r))){sha=Archive.sha256(in);}String t=store.beginTranscription(r,"fixture",OfflineAsr.SHA,sha);store.finishTranscription(t,new JSONObject().put("text",text).put("duration_ms",1000).put("segments",new JSONArray().put(new JSONObject().put("start_ms",0).put("end_ms",1000).put("text",text))));return t;}
 public void testAutomaticDraftAndConfirmedTypedRowsKeepOriginal() throws Exception {String source="꽃게 스무 마리, 무게는 사 킬로그램입니다.",t=transcribe(source);assertEquals(1,store.count("structured_logs"));assertEquals(1,store.count("catch_items"));JSONObject draft=store.rows("structured_logs").getJSONObject(0);assertEquals("DRAFT",draft.getString("status"));assertTrue(draft.isNull("review_id"));assertEquals(20,store.rows("catch_items").getJSONObject(0).getInt("quantity"));JSONObject f=FisheryExtractor.extract(source,"LEGACY");f.getJSONArray("catches").getJSONObject(0).put("quantity",21d);store.reviewFields(t,source,f);assertEquals(2,store.count("structured_logs"));assertEquals("CONFIRMED",store.rows("structured_logs").getJSONObject(1).getString("status"));assertEquals(20,store.rows("catch_items").getJSONObject(0).getInt("quantity"));assertEquals(21,store.rows("catch_items").getJSONObject(1).getInt("quantity"));assertEquals(source,store.row("transcriptions",t).getString("text"));Archive.write(store,new File(files,"fields.zip"));}
 public void testMissingCatchRowBlocksExport() throws Exception {transcribe("고등어 열두 마리입니다.");store.getWritableDatabase().delete("catch_items",null,null);try{Archive.write(store,new File(files,"missing.zip"));fail("missing catch accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("누락"));}}
 public void testTypedColumnCorruptionBlocksExport() throws Exception {transcribe("고등어 열두 마리입니다.");store.getWritableDatabase().execSQL("UPDATE catch_items SET quantity=99");try{Archive.write(store,new File(files,"bad.zip"));fail("mismatch accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("불일치"));}}
 public void testRejectedConfirmationIsAtomic() throws Exception {String t=transcribe("꽃게 세 마리입니다.");JSONObject f=FisheryExtractor.extract("꽃게 세 마리입니다.","LEGACY");f.put("outcome","NO_CATCH");try{store.reviewFields(t,"꽃게 세 마리입니다.",f);fail("contradiction accepted");}catch(IllegalArgumentException expected){}assertEquals(0,store.count("transcript_reviews"));assertEquals(0,store.count("diary_entries"));assertEquals(1,store.count("structured_logs"));}
 public void testUnknownBoxWeightAndSourceRemainNull() throws Exception {String t=transcribe("두 상자 정도 나왔습니다.");JSONObject c=store.rows("catch_items").getJSONObject(0);assertTrue(c.isNull("species"));assertTrue(c.isNull("weight_kg"));assertEquals("상자",c.getString("unit"));assertEquals(1,c.getInt("approximate"));assertEquals(2,c.getInt("quantity"));Archive.write(store,new File(files,"unknown.zip"));}
 public void testQualityRetryRetainsCompletedFirstPassOnFailure() throws Exception {
  String first=transcribe("곶게는 10에서 15마리 정도인 것 같습니다.");JSONObject before=store.row("transcriptions",first);String recording=before.getString("recording_id");
  String retry=store.beginTranscription(recording,RecognitionPolicy.RETRY_ENGINE,OfflineAsr.SHA,before.getString("audio_sha256"));store.failTranscription(retry,"CANCELLED","시험 중지");
  assertEquals(before.toString(),store.row("transcriptions",first).toString());assertEquals(2,store.count("transcriptions"));assertEquals(1,store.count("structured_logs"));assertEquals("CANCELLED",store.row("transcriptions",retry).getString("status"));Archive.write(store,new File(files,"retry-keeps-first.zip"));
 }
}
