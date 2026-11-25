package kr.go.nifs.fieldrecorder;

import android.content.Context;
import android.location.Location;
import android.os.SystemClock;
import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipFile;

public class StorageTest extends AndroidTestCase {
    private Store store;private File files;
    @Override protected void setUp() throws Exception {
        super.setUp();files=new File(getContext().getCacheDir(),"storage-test-"+Store.id());assertTrue(files.mkdirs());
        Context ctx=new RenamingDelegatingContext(getContext(),"test_"+Store.id()+"_"){
            @Override public Context getApplicationContext(){return this;}
            @Override public File getFilesDir(){return files;}
        };
        store=new Store(ctx);
    }
    @Override protected void tearDown() throws Exception {store.close();super.tearDown();}
    private String recording(String session,String label) throws Exception {String r=store.prepareRecording(session,label,null);Files.write(store.audio(r).toPath(),new byte[]{1,2,3,4});store.recordingStarted(r);store.endRecording(r,"COMPLETED","",null);return r;}
    public void testMissingLocationRemainsNull() throws Exception {
        String s=store.startSession(60000),r=recording(s,"위치 없는 기록");JSONObject row=store.rows("recordings").getJSONObject(0);assertTrue(row.isNull("start_location_id"));assertTrue(row.isNull("end_location_id"));assertEquals(0,store.count("locations"));
    }
    public void testRepeatedPositionsAndMultipleSessionsPreserved() throws Exception {
        String s=store.startSession(60000),r=recording(s,"정상 반복");Location l=new Location("gps");l.setLatitude(35.1);l.setLongitude(129.1);l.setTime(1000);l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());l.setAccuracy(12);store.location(s,r,l);store.location(s,r,l);store.endSession(s,"COMPLETED");String s2=store.startSession(300000);String r2=recording(s2,"정상 반복");store.endRecording(r2,"FAILED","시험 오류",null);store.endSession(s2,"COMPLETED");
        File zip=new File(files,"out.zip");Archive.write(store,zip);try(ZipFile z=new ZipFile(zip)){
            JSONObject data=new JSONObject(new String(z.getInputStream(z.getEntry("records.json")).readAllBytes(),StandardCharsets.UTF_8));assertEquals(2,data.getJSONArray("sessions").length());assertEquals(2,data.getJSONArray("recordings").length());assertEquals("FAILED",data.getJSONArray("recordings").getJSONObject(1).getString("status"));assertEquals(2,data.getJSONArray("locations").length());assertNotNull(z.getEntry("gear_details.csv"));assertEquals(16,z.size());
            JSONObject manifest=new JSONObject(new String(z.getInputStream(z.getEntry("manifest.json")).readAllBytes(),StandardCharsets.UTF_8));assertEquals(2,manifest.getJSONArray("audio_files").length());assertEquals(2,manifest.getJSONObject("counts").getInt("locations"));
        }
    }
    public void testUnexpectedTerminationPreservesUnknownEnd() throws Exception {
        String s=store.startSession(60000),r=store.prepareRecording(s,"중단",null);store.recordingStarted(r);store.recoverInterrupted();JSONObject session=store.rows("sessions").getJSONObject(0),audio=store.rows("recordings").getJSONObject(0);assertEquals("INTERRUPTED",session.getString("status"));assertTrue(session.isNull("ended_ms"));assertEquals("INTERRUPTED",audio.getString("status"));assertTrue(audio.isNull("ended_ms"));assertTrue(store.audio(r).exists());assertEquals(2,store.count("events"));
    }
    public void testExportRefusesMissingAudio() throws Exception {
        String s=store.startSession(60000),r=recording(s,"누락");store.endSession(s,"COMPLETED");assertTrue(store.audio(r).delete());try{Archive.write(store,new File(files,"bad.zip"));fail("missing audio silently accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("누락"));}
    }
    public void testExportRefusesUnlinkedAudio() throws Exception {
        String s=store.startSession(60000);recording(s,"기록");store.endSession(s,"COMPLETED");assertTrue(new File(store.root(),"audio/unlinked.m4a").createNewFile());try{Archive.write(store,new File(files,"bad.zip"));fail("unlinked audio silently dropped");}catch(IOException expected){assertTrue(expected.getMessage().contains("연결 정보"));}
    }
    public void testExportRefusesActiveSession() throws Exception {
        store.startSession(60000);try{Archive.write(store,new File(files,"bad.zip"));fail("active capture exported");}catch(IOException expected){assertTrue(expected.getMessage().contains("항차"));}
    }
    public void testExportRefusesDanglingLocationReference() throws Exception {
        String s=store.startSession(60000),r=recording(s,"연결 검증");store.endRecording(r,"COMPLETED","","missing-location-id");store.endSession(s,"COMPLETED");try{Archive.write(store,new File(files,"bad.zip"));fail("dangling reference accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("위치 연결"));}
    }
    public void testCsvEscapesLabelsAndJsonRetainsExactText() throws Exception {
        String label="=1+1,\"인용\"\n둘째줄";String s=store.startSession(60000);recording(s,label);store.endSession(s,"COMPLETED");assertEquals(label,store.rows("recordings").getJSONObject(0).getString("label"));String cell=Archive.csvCell(label);assertTrue(cell.startsWith("\"'="));assertTrue(cell.contains("\"\"인용\"\""));assertEquals("",Archive.csvCell(JSONObject.NULL));
    }
}
