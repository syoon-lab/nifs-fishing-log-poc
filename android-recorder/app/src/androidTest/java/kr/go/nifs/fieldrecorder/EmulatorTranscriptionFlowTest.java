package kr.go.nifs.fieldrecorder;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.test.InstrumentationTestCase;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class EmulatorTranscriptionFlowTest extends InstrumentationTestCase {
    private void waitFor(java.util.function.BooleanSupplier predicate,long timeout) throws Exception {
        long end=SystemClock.elapsedRealtime()+timeout;while(!predicate.getAsBoolean()){if(SystemClock.elapsedRealtime()>end)fail("Service timeout: "+TranscriptionService.message);Thread.sleep(100);}
    }
    public void testVisibleServiceCancelRetryReviewExportPreservesOriginals() throws Exception {
        assertTrue(Build.MODEL.toLowerCase(Locale.ROOT).contains("sdk"));
        Context context=getInstrumentation().getTargetContext();Store store=RecorderApp.store();JSONObject before=store.snapshot();
        String sid=store.startSession(60000),r=store.prepareRecording(sid,"합성 시험 · 실제 조업 아님",null);
        try(InputStream in=getInstrumentation().getContext().getAssets().open("asr/start.m4a");OutputStream out=new FileOutputStream(store.audio(r))){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        store.recordingStarted(r);store.endRecording(r,"COMPLETED","",null);store.event(sid,r,"MOCK_FIXTURE","합성 음성, 가상기기 전사 서비스 시험, 현재 생성 시각은 실제 조업 시각이 아님");store.endSession(sid,"COMPLETED");
        Activity activity=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));getInstrumentation().waitForIdleSync();
        getInstrumentation().runOnMainSync(()->activity.startForegroundService(new Intent(activity,TranscriptionService.class).putExtra("recording_id",r)));
        waitFor(()->{try{return store.latest("transcriptions","recording_id",r)!=null;}catch(Exception e){return false;}},10000);
        getInstrumentation().runOnMainSync(()->activity.startService(new Intent(activity,TranscriptionService.class).setAction("cancel")));
        waitFor(()->!TranscriptionService.running,30000);getInstrumentation().waitForIdleSync();
        assertEquals("CANCELLED",store.latest("transcriptions","recording_id",r).getString("status"));
        getInstrumentation().runOnMainSync(()->activity.startForegroundService(new Intent(activity,TranscriptionService.class).putExtra("recording_id",r)));
        waitFor(()->TranscriptionService.running,10000);waitFor(()->!TranscriptionService.running,120000);getInstrumentation().waitForIdleSync();
        JSONObject transcript=store.latest("transcriptions","recording_id",r);assertEquals(TranscriptionService.message,"COMPLETED",transcript.getString("status"));
        // Deliberate human-review fixture. Never supplied to the ASR engine.
        String reviewed="통발을 걷기 시작합니다. 목표 어종은 꽃게입니다. 이번에 걷는 통발은 쉰 개이고 작업 인원은 세 명입니다.";
        store.reviewTranscript(transcript.getString("id"),reviewed);
        JSONObject after=store.snapshot();for(String table:Store.TABLES){JSONArray old=before.getJSONArray(table),now=after.getJSONArray(table);for(int i=0;i<old.length();i++)assertEquals(table,old.getJSONObject(i).toString(),now.getJSONObject(i).toString());}
        File qa=new File(context.getFilesDir(),"asr-qa");qa.mkdirs();Archive.write(store,new File(qa,"service-export.zip"));
        JSONObject report=new JSONObject().put("recording_id",r).put("transcription_id",transcript.getString("id")).put("cancel_retry_passed",true).put("original_rows_preserved",true).put("confirmed_test_text",reviewed).put("machine_text",transcript.getString("text"));
        Files.write(new File(qa,"service-result.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
