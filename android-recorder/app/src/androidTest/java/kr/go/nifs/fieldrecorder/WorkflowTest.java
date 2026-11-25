package kr.go.nifs.fieldrecorder;

import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipFile;

public class WorkflowTest extends AndroidTestCase {
    private Store store;private Context ctx;private File files;
    @Override protected void setUp() throws Exception {
        super.setUp();files=new File(getContext().getCacheDir(),"workflow-"+Store.id());assertTrue(files.mkdirs());
        ctx=new RenamingDelegatingContext(getContext(),"workflow_"+Store.id()+"_"){
            @Override public Context getApplicationContext(){return this;}
            @Override public File getFilesDir(){return files;}
        };
        store=new Store(ctx);
    }
    @Override protected void tearDown() throws Exception {store.close();super.tearDown();}
    private String voice(String op,String phase,String state) throws Exception {
        String r=store.prepareOperationRecording(op,phase,"test-"+phase,null);
        Files.write(store.audio(r).toPath(),new byte[]{1,2,3,4});
        store.recordingStarted(r);store.completeOperationRecording(r,state,"COMPLETED".equals(state)?"":"test error",null);return r;
    }
    private interface Attempt {void run() throws Exception;}
    private void rejects(Attempt a) throws Exception {try{a.run();fail("Illegal transition accepted");}catch(IllegalStateException expected){}}
    public void testTwoOperationsNotesArrivalAndExport() throws Exception {
        String s=store.depart(60000),a=store.startOperation(s);voice(a,"START","COMPLETED");
        String note=voice(a,"NOTE","COMPLETED");voice(a,"NOTE","COMPLETED");
        Location l=new Location("gps");l.setLatitude(35);l.setLongitude(129);l.setTime(1000);
        store.location(s,note,a,l);store.location(s,note,a,l);
        store.beginWrap(a);voice(a,"END","COMPLETED");store.finishOperation(a);
        assertEquals("ACTIVE",store.rows("sessions").getJSONObject(0).getString("status"));
        store.location(s,null,null,l);
        String b=store.startOperation(s);assertEquals(2,store.operation(b).getInt("sequence_no"));voice(b,"START","COMPLETED");store.beginWrap(b);voice(b,"END","COMPLETED");store.finishOperation(b);store.arrive(s);
        assertEquals(2,store.count("operations"));assertEquals(6,store.count("recordings"));assertEquals(3,store.count("locations"));
        File out=new File(files,"two.zip");Archive.write(store,out);
        try(ZipFile zip=new ZipFile(out)){assertNotNull(zip.getEntry("operations.csv"));assertNotNull(zip.getEntry("gear_details.csv"));assertEquals(20,zip.size());}
    }
    public void testOrderGuardsAndRetryFailures() throws Exception {
        rejects(()->store.startOperation("missing"));
        String s=store.depart(60000);rejects(()->store.depart(60000));String op=store.startOperation(s);
        rejects(()->store.startOperation(s));rejects(()->store.arrive(s));rejects(()->voice(op,"NOTE","COMPLETED"));rejects(()->store.beginWrap(op));rejects(()->store.finishOperation(op));
        voice(op,"START","FAILED");assertEquals("START_REQUIRED",store.operation(op).getString("stage"));
        voice(op,"START","COMPLETED");rejects(()->voice(op,"START","COMPLETED"));
        store.beginWrap(op);voice(op,"END","FAILED");assertEquals("END_REQUIRED",store.operation(op).getString("stage"));
        rejects(()->store.finishOperation(op));voice(op,"END","COMPLETED");rejects(()->voice(op,"NOTE","COMPLETED"));
        store.finishOperation(op);rejects(()->store.finishOperation(op));store.arrive(s);rejects(()->store.arrive(s));
        Archive.write(store,new File(files,"retry.zip"));assertEquals(4,store.count("recordings"));
    }
    public void testInterruptedRequiredVoicePreservesUnknownTimes() throws Exception {
        String s=store.depart(60000),op=store.startOperation(s),r=store.prepareOperationRecording(op,"START","interrupted",null);
        Files.write(store.audio(r).toPath(),new byte[]{9,8,7});store.recordingStarted(r);store.recoverInterrupted();
        JSONObject operation=store.operation(op);assertEquals("INTERRUPTED",operation.getString("status"));assertTrue(operation.isNull("ended_ms"));
        assertTrue(store.rows("sessions").getJSONObject(0).isNull("ended_ms"));assertTrue(store.rows("recordings").getJSONObject(0).isNull("ended_ms"));
        assertEquals(3L,store.audio(r).length());Archive.write(store,new File(files,"interrupted.zip"));
    }
    public void testWrongOperationRelationshipBlocksExport() throws Exception {
        String s=store.depart(60000),op=store.startOperation(s);voice(op,"START","COMPLETED");store.beginWrap(op);voice(op,"END","COMPLETED");store.finishOperation(op);store.arrive(s);
        String other=store.depart(60000);store.arrive(other);
        Location l=new Location("gps");l.setLatitude(35);l.setLongitude(129);store.location(other,null,op,l);
        try{Archive.write(store,new File(files,"invalid.zip"));fail("Wrong operation/session accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("연결"));}
    }
    public void testMissingStartVoiceBlocksCompletedOperationExport() throws Exception {
        String s=store.depart(60000),op=store.startOperation(s);String r=voice(op,"START","COMPLETED");store.beginWrap(op);voice(op,"END","COMPLETED");store.finishOperation(op);store.arrive(s);
        ContentValues v=new ContentValues();v.put("phase","NOTE");store.getWritableDatabase().update("recordings",v,"id=?",new String[]{r});
        try{Archive.write(store,new File(files,"invalid.zip"));fail("Missing required voice accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("녹음 누락"));}
    }
    public void testMigrationPreservesLegacyRowsAndAudio() throws Exception {
        store.close();
        SQLiteDatabase old=ctx.openOrCreateDatabase("field-records.db",0,null);
        old.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,started_ms INTEGER NOT NULL,ended_ms INTEGER,interval_ms INTEGER NOT NULL,status TEXT NOT NULL)");
        old.execSQL("CREATE TABLE recordings(id TEXT PRIMARY KEY,session_id TEXT NOT NULL,started_ms INTEGER NOT NULL,ended_ms INTEGER,label TEXT NOT NULL,path TEXT NOT NULL UNIQUE,status TEXT NOT NULL,error TEXT NOT NULL DEFAULT '',start_location_id TEXT,end_location_id TEXT)");
        old.execSQL("CREATE TABLE locations(id TEXT PRIMARY KEY,session_id TEXT NOT NULL,recording_id TEXT,measured_ms INTEGER NOT NULL,received_ms INTEGER NOT NULL,elapsed_ns INTEGER NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL,accuracy_m REAL,provider TEXT NOT NULL,is_mock INTEGER NOT NULL)");
        old.execSQL("CREATE TABLE events(id TEXT PRIMARY KEY,session_id TEXT,recording_id TEXT,time_ms INTEGER NOT NULL,type TEXT NOT NULL,detail TEXT NOT NULL)");
        old.execSQL("INSERT INTO sessions VALUES('0001',10,90,60000,'COMPLETED')");
        old.execSQL("INSERT INTO recordings VALUES('0002','0001',20,80,'원문,제목','audio/0002.m4a','COMPLETED','','0003','0004')");
        old.execSQL("INSERT INTO locations VALUES('0003','0001','0002',30,31,40,35.0,129.0,10.0,'gps',0)");
        old.execSQL("INSERT INTO locations VALUES('0004','0001','0002',30,31,40,35.0,129.0,10.0,'gps',0)");
        old.execSQL("INSERT INTO events VALUES('0005','0001','0002',50,'OLD_EVENT','원문')");
        old.setVersion(1);old.close();
        File audio=new File(files,"records/audio/0002.m4a");assertTrue(audio.getParentFile().mkdirs());Files.write(audio.toPath(),new byte[]{5,6,7});
        store=new Store(ctx);JSONObject data=store.snapshot();assertEquals(5,data.getInt("schema_version"));assertEquals(0,store.count("operations"));assertEquals(2,store.count("locations"));
        JSONObject r=data.getJSONArray("recordings").getJSONObject(0);assertEquals("0002",r.getString("id"));assertEquals("원문,제목",r.getString("label"));assertEquals("LEGACY",r.getString("phase"));assertTrue(r.isNull("operation_id"));
        assertEquals("LEGACY",data.getJSONArray("sessions").getJSONObject(0).getString("workflow"));assertTrue(Arrays.equals(new byte[]{5,6,7},Files.readAllBytes(audio.toPath())));
        Archive.write(store,new File(files,"migrated.zip"));
    }
}
