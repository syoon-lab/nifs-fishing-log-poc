package kr.go.nifs.fieldrecorder;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Explicit end-to-end test on the disposable emulator's copied data only. */
public class EmulatorWorkflowTest extends InstrumentationTestCase {
    private Activity activity;
    private Store store;
    private void waitFor(BooleanSupplier condition,String reason) throws Exception {
        long until=SystemClock.uptimeMillis()+12000;
        while(!condition.getAsBoolean()&&SystemClock.uptimeMillis()<until)SystemClock.sleep(100);
        assertTrue(reason,condition.getAsBoolean());
    }
    private Button find(View v,String text){
        if(v instanceof Button&&(text.equals(((Button)v).getText().toString())||text.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())))return (Button)v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){Button b=find(g.getChildAt(i),text);if(b!=null)return b;}}
        return null;
    }
    private boolean visible(String text){
        final boolean[] result={false};getInstrumentation().runOnMainSync(()->{Button b=find(activity.getWindow().getDecorView(),text);result[0]=b!=null&&b.isShown()&&b.isEnabled();});return result[0];
    }
    private void tap(String text) throws Exception {
        waitFor(()->visible(text),"Missing UI button: "+text);
        getInstrumentation().runOnMainSync(()->{Button b=find(activity.getWindow().getDecorView(),text);assertNotNull(b);assertTrue(b.performClick());});
    }
    private void screen(String name) throws Exception {
        getInstrumentation().waitForIdleSync();SystemClock.sleep(350);
        Bitmap bitmap=getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        try(OutputStream out=new FileOutputStream(new File(getInstrumentation().getTargetContext().getExternalFilesDir(null),name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
    }
    private void saveVoice(String nextButton) throws Exception {
        waitFor(()->CaptureService.recording,"Microphone not recording");SystemClock.sleep(1800);
        tap("■  녹음 종료 · 저장");waitFor(()->!CaptureService.recording,"Microphone did not stop");waitFor(()->visible(nextButton),"Next stage not shown");
    }
    private Activity open(){
        return getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
    public void testDepartureTwoOperationsArrival() throws Exception {
        assertTrue("Do not run this test on a real phone",Build.MODEL.contains("SDK"));
        activity=open();waitFor(()->visible("출항"),"Departure missing");
        store=RecorderApp.store();JSONObject before=store.snapshot();int oldOps=store.count("operations"),oldVoices=store.count("recordings");screen("01_출항전");
        tap("출항");waitFor(()->CaptureService.running,"Departure not active");String trip=CaptureService.sessionId;waitFor(()->visible("조업 시작 · 녹음"),"Start operation missing");screen("02_이동중");
        tap("조업 시작 · 녹음");waitFor(()->CaptureService.recording,"Start voice not recording");screen("03_시작녹음");
        // Closing/reopening only the screen must not close the voyage or current recording.
        getInstrumentation().runOnMainSync(()->activity.finish());SystemClock.sleep(700);activity=open();
        saveVoice("특이사항 녹음");screen("04_조업중");
        for(int i=0;i<2;i++){tap("특이사항 녹음");saveVoice("특이사항 녹음");}
        tap("조업 마무리 · 녹음");saveVoice("조업 종료");screen("05_조업종료대기");tap("조업 종료");waitFor(()->visible("조업 시작 · 녹음"),"Second operation missing");assertTrue(CaptureService.running);
        tap("조업 시작 · 녹음");saveVoice("특이사항 녹음");tap("조업 마무리 · 녹음");saveVoice("조업 종료");tap("조업 종료");waitFor(()->visible("입항"),"Arrival missing");
        tap("입항");waitFor(()->!CaptureService.running,"Arrival did not stop location");waitFor(()->visible("출항"),"Next departure missing");screen("06_입항후");
        assertEquals(oldOps+2,store.count("operations"));assertEquals(oldVoices+6,store.count("recordings"));
        JSONObject after=store.snapshot();
        for(String table:Store.TABLES){
            JSONArray previous=before.getJSONArray(table),current=after.getJSONArray(table);
            for(int i=0;i<previous.length();i++){
                JSONObject old=previous.getJSONObject(i);boolean found=false;
                for(int j=0;j<current.length();j++)if(old.getString("id").equals(current.getJSONObject(j).getString("id"))){assertEquals(old.toString(),current.getJSONObject(j).toString());found=true;break;}
                assertTrue("Original row lost: "+table,found);
            }
        }
        JSONArray sessions=after.getJSONArray("sessions");boolean arrived=false;
        for(int i=0;i<sessions.length();i++)if(trip.equals(sessions.getJSONObject(i).getString("id"))){assertEquals("COMPLETED",sessions.getJSONObject(i).getString("status"));arrived=true;}
        assertTrue(arrived);
        Archive.write(store,new File(getInstrumentation().getTargetContext().getExternalFilesDir(null),"workflow-ui.zip"));
    }
}
