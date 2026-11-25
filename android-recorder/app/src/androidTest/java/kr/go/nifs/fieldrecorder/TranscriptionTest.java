package kr.go.nifs.fieldrecorder;

import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.test.InstrumentationTestCase;
import android.test.RenamingDelegatingContext;
import android.os.Build;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;

public class TranscriptionTest extends InstrumentationTestCase {
    private Store store;private File files;private Context context;
    @Override protected void setUp() throws Exception {
        super.setUp();context=getInstrumentation().getTargetContext();files=new File(context.getCacheDir(),"asr-test-"+Store.id());assertTrue(files.mkdirs());
        Context isolated=new RenamingDelegatingContext(context,"asr_test_"+Store.id()+"_"){
            @Override public Context getApplicationContext(){return this;}
            @Override public File getFilesDir(){return files;}
        };store=new Store(isolated);
    }
    @Override protected void tearDown() throws Exception {store.close();super.tearDown();}
    private String voice() throws Exception {String sid=store.startSession(60000),r=store.prepareRecording(sid,"합성 시험음성",null);Files.write(store.audio(r).toPath(),new byte[]{1,2,3,4});store.recordingStarted(r);store.endRecording(r,"COMPLETED","",null);store.endSession(sid,"COMPLETED");return r;}
    private String attempt(String r) throws Exception {try(InputStream in=new FileInputStream(store.audio(r))){return store.beginTranscription(r,"test-only",OfflineAsr.SHA,Archive.sha256(in));}}
    private JSONObject result(String text) throws Exception {return new JSONObject().put("text",text).put("duration_ms",1000).put("segments",new JSONArray().put(new JSONObject().put("start_ms",0).put("end_ms",1000).put("text",text)));}
    public void testReviewHistoryOriginalAndAutomaticTime() throws Exception {
        String r=voice(),a=attempt(r);JSONObject original=store.row("recordings",r);store.finishTranscription(a,result("꽃개 두 상자"));
        store.reviewTranscript(a,"꽃게 두 상자");store.reviewTranscript(a,"꽃게 약 두 상자. 무게 미측정.");
        assertEquals("꽃개 두 상자",store.row("transcriptions",a).getString("text"));assertEquals(2,store.count("transcript_reviews"));assertEquals(2,store.count("diary_entries"));
        JSONObject d=new JSONObject(store.rows("diary_entries").getJSONObject(1).getString("data_json"));assertEquals(original.getLong("started_ms"),d.getLong("recorded_at_ms"));assertEquals("꽃게 약 두 상자. 무게 미측정.",d.getString("text"));assertEquals(original.toString(),store.row("recordings",r).toString());
        Archive.write(store,new File(files,"review.zip"));
    }
    public void testFailureRetryRecoveryAndSingleJob() throws Exception {
        String r=voice(),a=attempt(r);try{attempt(r);fail("concurrent jobs accepted");}catch(android.database.sqlite.SQLiteConstraintException expected){}
        store.recoverInterrupted();assertEquals("INTERRUPTED",store.row("transcriptions",a).getString("status"));assertTrue(store.row("transcriptions",a).isNull("finished_ms"));
        String b=attempt(r);store.failTranscription(b,"CANCELLED","사용자 중지");String c=attempt(r);store.finishTranscription(c,result("다시 전사"));assertEquals(3,store.count("transcriptions"));assertEquals(4L,store.audio(r).length());Archive.write(store,new File(files,"retry.zip"));
    }
    public void testRejectActiveExportAndMissingDiary() throws Exception {
        String r=voice(),a=attempt(r);try{Archive.write(store,new File(files,"active.zip"));fail("active transcription exported");}catch(IOException expected){assertTrue(expected.getMessage().contains("전사"));}
        store.finishTranscription(a,result("기록"));store.reviewTranscript(a,"기록");store.getWritableDatabase().delete("diary_entries",null,null);
        try{Archive.write(store,new File(files,"missing.zip"));fail("lost diary accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("누락"));}
    }
    public void testWrongSourceHashBlocksExport() throws Exception {
        String r=voice(),a=attempt(r);store.finishTranscription(a,result("기록"));Files.write(store.audio(r).toPath(),new byte[]{9,8,7});
        try{Archive.write(store,new File(files,"changed.zip"));fail("changed source accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("지문"));}
    }
    public void testActualKoreanOfflineInferenceAndExport() throws Exception {
        assertTrue("Emulator only",Build.MODEL.toLowerCase(java.util.Locale.ROOT).contains("sdk")||Build.FINGERPRINT.contains("generic"));
        String r=voice();try(InputStream in=getInstrumentation().getContext().getAssets().open("asr/start.m4a");OutputStream out=new FileOutputStream(store.audio(r))){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        String a=attempt(r); // Replace test provenance with the actual engine before execution.
        android.content.ContentValues v=new android.content.ContentValues();v.put("engine",OfflineAsr.ENGINE);store.getWritableDatabase().update("transcriptions",v,"id=?",new String[]{a});
        OfflineAsr.reset();long before=android.os.SystemClock.elapsedRealtime();JSONObject result=OfflineAsr.transcribe(context,store.audio(r),()->false);long elapsed=android.os.SystemClock.elapsedRealtime()-before;
        assertFalse("No recognized Korean text",result.getString("text").trim().isEmpty());assertTrue(result.getString("text").matches("(?s).*[가-힣].*"));assertTrue(result.getLong("duration_ms")>1000);
        store.finishTranscription(a,result);store.reviewTranscript(a,result.getString("text"));
        File qa=new File(context.getFilesDir(),"asr-qa");qa.mkdirs();Archive.write(store,new File(qa,"native-transcription.zip"));
        JSONObject report=new JSONObject().put("emulator_model",Build.MODEL).put("elapsed_ms",elapsed).put("result",result).put("engine",OfflineAsr.ENGINE).put("model_sha256",OfflineAsr.SHA).put("source","synthetic Korean fixture; not S24 performance");
        Files.write(new File(qa,"native-result.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
        File cancelled=new File(files,"cancel.f32");try{AudioDecoder.decode(store.audio(r),cancelled,()->true);fail("cancel ignored");}catch(CancellationException expected){}
        File invalid=new File(files,"invalid.m4a");Files.write(invalid.toPath(),new byte[]{0,1});try{AudioDecoder.decode(invalid,new File(files,"bad.f32"),()->false);fail("invalid audio accepted");}catch(IOException expected){}
    }
}
