package kr.go.nifs.fieldrecorder;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.location.Location;
import android.os.SystemClock;
import org.json.*;
import java.io.*;
import java.util.*;

/** SQLite holds identity/relationships; audio files stay in app-private storage. */
public final class Store extends SQLiteOpenHelper {
    public static final String[] TABLES={"sessions","operations","recordings","locations","events","transcriptions","transcript_reviews","diary_entries","structured_logs","catch_items","gear_details"};
    private final Context context;
    public Store(Context c) { super(c, "field-records.db", null, 5); context=c.getApplicationContext(); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,started_ms INTEGER NOT NULL,ended_ms INTEGER,interval_ms INTEGER NOT NULL,status TEXT NOT NULL)");
        db.execSQL("CREATE TABLE recordings(id TEXT PRIMARY KEY,session_id TEXT NOT NULL REFERENCES sessions(id),started_ms INTEGER NOT NULL,ended_ms INTEGER,label TEXT NOT NULL,path TEXT NOT NULL UNIQUE,status TEXT NOT NULL,error TEXT NOT NULL DEFAULT '',start_location_id TEXT,end_location_id TEXT)");
        db.execSQL("CREATE TABLE locations(id TEXT PRIMARY KEY,session_id TEXT NOT NULL REFERENCES sessions(id),recording_id TEXT,measured_ms INTEGER NOT NULL,received_ms INTEGER NOT NULL,elapsed_ns INTEGER NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL,accuracy_m REAL,provider TEXT NOT NULL,is_mock INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX location_session_time ON locations(session_id,measured_ms)");
        db.execSQL("CREATE TABLE events(id TEXT PRIMARY KEY,session_id TEXT,recording_id TEXT,time_ms INTEGER NOT NULL,type TEXT NOT NULL,detail TEXT NOT NULL)");
        addWorkflowSchema(db);
        addTranscriptionSchema(db);
        StructuredLog.schema(db);
        GearLog.schema(db);
    }
    private void addWorkflowSchema(SQLiteDatabase db){
        db.execSQL("ALTER TABLE sessions ADD COLUMN workflow TEXT NOT NULL DEFAULT 'LEGACY'");
        db.execSQL("CREATE TABLE operations(id TEXT PRIMARY KEY,session_id TEXT NOT NULL REFERENCES sessions(id),sequence_no INTEGER NOT NULL,started_ms INTEGER NOT NULL,ended_ms INTEGER,status TEXT NOT NULL,stage TEXT NOT NULL,UNIQUE(session_id,sequence_no))");
        db.execSQL("ALTER TABLE recordings ADD COLUMN operation_id TEXT REFERENCES operations(id)");
        db.execSQL("ALTER TABLE recordings ADD COLUMN phase TEXT NOT NULL DEFAULT 'LEGACY'");
        db.execSQL("ALTER TABLE locations ADD COLUMN operation_id TEXT REFERENCES operations(id)");
        db.execSQL("ALTER TABLE events ADD COLUMN operation_id TEXT REFERENCES operations(id)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int old,int next) {
        if(old<1||next!=5)throw new IllegalStateException("Unsupported database migration");
        if(old<2)addWorkflowSchema(db);
        if(old<3)addTranscriptionSchema(db);
        if(old<4)StructuredLog.schema(db);
        if(old<5)GearLog.schema(db);
    }
    private void addTranscriptionSchema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE transcriptions(id TEXT PRIMARY KEY,recording_id TEXT NOT NULL REFERENCES recordings(id),status TEXT NOT NULL,created_ms INTEGER NOT NULL,finished_ms INTEGER,engine TEXT NOT NULL,model_sha256 TEXT NOT NULL,audio_sha256 TEXT NOT NULL,text TEXT NOT NULL DEFAULT '',segments_json TEXT NOT NULL DEFAULT '[]',duration_ms INTEGER,error TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE UNIQUE INDEX one_running_transcription ON transcriptions(status) WHERE status='RUNNING'");
        db.execSQL("CREATE INDEX transcript_recording ON transcriptions(recording_id,created_ms)");
        db.execSQL("CREATE TABLE transcript_reviews(id TEXT PRIMARY KEY,transcription_id TEXT NOT NULL REFERENCES transcriptions(id),created_ms INTEGER NOT NULL,text TEXT NOT NULL)");
        db.execSQL("CREATE TABLE diary_entries(id TEXT PRIMARY KEY,review_id TEXT NOT NULL UNIQUE REFERENCES transcript_reviews(id),created_ms INTEGER NOT NULL,data_json TEXT NOT NULL)");
    }
    public synchronized JSONObject row(String table,String id) throws JSONException {
        JSONArray all=rows(table);for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).getString("id")))return all.getJSONObject(i);
        throw new IllegalArgumentException("저장된 자료가 없습니다.");
    }
    public synchronized JSONObject latest(String table,String key,String value) throws JSONException {
        JSONArray all=rows(table);for(int i=all.length()-1;i>=0;i--){JSONObject r=all.getJSONObject(i);if(value.equals(r.optString(key)))return r;}return null;
    }
    public synchronized String beginTranscription(String recording,String engine,String model,String audio) throws JSONException {
        if(!"COMPLETED".equals(row("recordings",recording).getString("status")))throw new IllegalStateException("완료된 녹음만 전사할 수 있습니다.");
        ContentValues v=new ContentValues();String id=id();v.put("id",id);v.put("recording_id",recording);v.put("status","RUNNING");v.put("created_ms",System.currentTimeMillis());v.put("engine",engine);v.put("model_sha256",model);v.put("audio_sha256",audio);
        getWritableDatabase().insertOrThrow("transcriptions",null,v);return id;
    }
    public synchronized void finishTranscription(String id,JSONObject result) throws JSONException {
        if(!"RUNNING".equals(row("transcriptions",id).getString("status")))throw new IllegalStateException("전사가 이미 종료되었습니다.");
        ContentValues v=new ContentValues();v.put("status","COMPLETED");v.put("finished_ms",System.currentTimeMillis());v.put("text",result.getString("text"));v.put("segments_json",result.getJSONArray("segments").toString());v.put("duration_ms",result.getLong("duration_ms"));
        SQLiteDatabase sql=getWritableDatabase();sql.beginTransaction();try{
            sql.update("transcriptions",v,"id=? AND status='RUNNING'",new String[]{id});
            JSONObject recording=row("recordings",row("transcriptions",id).getString("recording_id"));
            JSONObject fields=extractRecording(recording,result.getString("text"));
            StructuredLog.insert(sql,id,null,result.getString("text"),fields,"DRAFT");
            sql.setTransactionSuccessful();
        }finally{sql.endTransaction();}
    }
    public synchronized void failTranscription(String id,String status,String error) {
        if(!Arrays.asList("FAILED","CANCELLED","INTERRUPTED").contains(status))throw new IllegalArgumentException();
        ContentValues v=new ContentValues();v.put("status",status);v.put("finished_ms",System.currentTimeMillis());v.put("error",error);
        getWritableDatabase().update("transcriptions",v,"id=? AND status='RUNNING'",new String[]{id});
    }
    public synchronized String reviewTranscript(String transcript,String text) throws JSONException {
        return saveReview(transcript,text,null);
    }
    public synchronized String reviewFields(String transcript,String text,JSONObject fields) throws JSONException {
        StructuredLog.validate(fields);return saveReview(transcript,text,fields);
    }
    private String saveReview(String transcript,String text,JSONObject fields) throws JSONException {
        if(text.trim().isEmpty())throw new IllegalArgumentException("전사 내용을 입력하세요.");
        JSONObject t=row("transcriptions",transcript);if(!"COMPLETED".equals(t.getString("status")))throw new IllegalStateException("완료된 전사만 확인할 수 있습니다.");
        SQLiteDatabase sql=getWritableDatabase();sql.beginTransaction();try{
            long now=System.currentTimeMillis();String review=id();ContentValues v=new ContentValues();v.put("id",review);v.put("transcription_id",transcript);v.put("created_ms",now);v.put("text",text);sql.insertOrThrow("transcript_reviews",null,v);
            JSONObject r=row("recordings",t.getString("recording_id"));
            JSONObject diary=new JSONObject().put("schema_version",1).put("recording_id",r.getString("id")).put("session_id",r.getString("session_id")).put("operation_id",r.get("operation_id")).put("phase",r.getString("phase")).put("recorded_at_ms",r.getLong("started_ms")).put("time_source","recording.started_ms").put("text",text).put("review_status","TRANSCRIPT_CONFIRMED");
            v=new ContentValues();v.put("id",id());v.put("review_id",review);v.put("created_ms",now);v.put("data_json",diary.toString());sql.insertOrThrow("diary_entries",null,v);
            JSONObject extracted=fields==null?extractRecording(r,text):fields;
            if(!r.getString("phase").equals(extracted.getString("phase")))throw new IllegalArgumentException("녹음 단계 불일치");
            StructuredLog.insert(sql,transcript,review,text,extracted,fields==null?"DRAFT":"CONFIRMED");
            sql.setTransactionSuccessful();return review;
        }finally{sql.endTransaction();}
    }
    synchronized JSONObject extractRecording(JSONObject recording,String text) throws JSONException {
        return "WORK".equals(recording.getString("phase"))?AnswerFields.extract(this,recording,text):StructuredLog.extract(text,recording.getString("phase"));
    }
    public synchronized String startGearOperation(String session,String type) throws JSONException {
        if(!GearLog.supported(type))throw new IllegalArgumentException("자망 또는 통발을 선택하세요.");
        SQLiteDatabase sql=getWritableDatabase();sql.beginTransaction();try{
            String op=startOperation(session);ContentValues v=new ContentValues();v.put("stage","WORK_REQUIRED");sql.update("operations",v,"id=?",new String[]{op});
            event(session,null,op,GearLog.CONTEXT,new JSONObject().put("version",1).put("gear_type",type).put("scope","SINGLE_WORK_NO_DEPLOYMENT_LINK").toString());sql.setTransactionSuccessful();return op;
        }finally{sql.endTransaction();}
    }
    public File root() { File f=new File(context.getFilesDir(),"records"); if(!f.isDirectory()&&!f.mkdirs())throw new IllegalStateException("저장 폴더를 만들 수 없습니다.");return f; }
    public static String id() { return UUID.randomUUID().toString(); }
    public synchronized String startSession(long interval) {
        String id=id(); ContentValues v=new ContentValues();v.put("id",id);v.put("started_ms",System.currentTimeMillis());v.put("interval_ms",interval);v.put("status","ACTIVE");
        getWritableDatabase().insertOrThrow("sessions",null,v);event(id,null,"SESSION_START","requested_interval_ms="+interval);return id;
    }
    public synchronized void endSession(String id,String status) {
        ContentValues v=new ContentValues();v.put("ended_ms",System.currentTimeMillis());v.put("status",status);getWritableDatabase().update("sessions",v,"id=?",new String[]{id});event(id,null,"SESSION_END",status);
    }
    public synchronized String prepareRecording(String session,String label,String locationId) throws IOException {
        String id=id(),relative="audio/"+id+".m4a";File f=new File(root(),relative);
        if(!f.getParentFile().isDirectory()&&!f.getParentFile().mkdirs())throw new IOException("녹음 폴더 생성 실패");
        if(!f.createNewFile())throw new IOException("녹음파일 이름 충돌");
        ContentValues v=new ContentValues();v.put("id",id);v.put("session_id",session);v.put("started_ms",System.currentTimeMillis());v.put("label",label);v.put("path",relative);v.put("status","PREPARING");v.put("start_location_id",locationId);
        try { getWritableDatabase().insertOrThrow("recordings",null,v); } catch(RuntimeException e) { if(f.length()==0)f.delete(); throw e; } return id;
    }
    public synchronized File audio(String id) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT path FROM recordings WHERE id=?",new String[]{id})) {
            if(!c.moveToFirst())throw new IllegalArgumentException("녹음 정보 없음"); return new File(root(),c.getString(0));
        }
    }
    public synchronized void recordingStarted(String id) {
        ContentValues v=new ContentValues();v.put("status","RECORDING");v.put("started_ms",System.currentTimeMillis());getWritableDatabase().update("recordings",v,"id=?",new String[]{id});
    }
    public synchronized void endRecording(String id,String status,String error,String locationId) {
        ContentValues v=new ContentValues();v.put("ended_ms",System.currentTimeMillis());v.put("status",status);v.put("error",error);v.put("end_location_id",locationId);getWritableDatabase().update("recordings",v,"id=?",new String[]{id});
    }
    public synchronized void attachFirstFix(String recording,String locationId) {
        ContentValues v=new ContentValues();v.put("start_location_id",locationId);getWritableDatabase().update("recordings",v,"id=? AND start_location_id IS NULL",new String[]{recording});
    }
    public synchronized String location(String session,String recording,Location l) { return location(session,recording,null,l); }
    public synchronized String location(String session,String recording,String operation,Location l) {
        String id=id();ContentValues v=new ContentValues();v.put("id",id);v.put("session_id",session);v.put("recording_id",recording);v.put("operation_id",operation);v.put("measured_ms",l.getTime());v.put("received_ms",System.currentTimeMillis());v.put("elapsed_ns",l.getElapsedRealtimeNanos());v.put("latitude",l.getLatitude());v.put("longitude",l.getLongitude());if(l.hasAccuracy())v.put("accuracy_m",l.getAccuracy());v.put("provider",l.getProvider()==null?"unknown":l.getProvider());v.put("is_mock",l.isFromMockProvider()?1:0);getWritableDatabase().insertOrThrow("locations",null,v);return id;
    }
    public synchronized void event(String session,String recording,String type,String detail) { event(session,recording,null,type,detail); }
    public synchronized void event(String session,String recording,String operation,String type,String detail) {
        ContentValues v=new ContentValues();v.put("id",id());v.put("session_id",session);v.put("recording_id",recording);v.put("operation_id",operation);v.put("time_ms",System.currentTimeMillis());v.put("type",type);v.put("detail",detail);getWritableDatabase().insertOrThrow("events",null,v);
    }
    public synchronized String[] columns(String table){
        if(!Arrays.asList(TABLES).contains(table))throw new IllegalArgumentException();
        ArrayList<String> names=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("PRAGMA table_info("+table+")",null)){while(c.moveToNext())names.add(c.getString(1));}
        return names.toArray(new String[0]);
    }
    public synchronized JSONObject operation(String id){
        try{JSONArray all=rows("operations");for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).getString("id")))return all.getJSONObject(i);}
        catch(JSONException e){throw new IllegalStateException(e);}throw new IllegalStateException("조업 정보가 없습니다.");
    }
    public synchronized String activeOperation(String session){
        if(session==null)return null;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM operations WHERE session_id=? AND status='ACTIVE'",new String[]{session})){return c.moveToFirst()?c.getString(0):null;}
    }
    private void requireSession(String session){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM sessions WHERE id=? AND status='ACTIVE' AND workflow='SEQUENTIAL'",new String[]{session})){
            if(!c.moveToFirst())throw new IllegalStateException("먼저 출항하세요.");
        }
    }
    private void requireNoRecording(String session){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM recordings WHERE session_id=? AND status IN ('PREPARING','RECORDING')",new String[]{session})){
            if(c.moveToFirst())throw new IllegalStateException("녹음을 종료·저장하세요.");
        }
    }
    public synchronized String depart(long interval){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM sessions WHERE status='ACTIVE'",null)){if(c.moveToFirst())throw new IllegalStateException("이미 출항 중입니다.");}
        SQLiteDatabase sql=getWritableDatabase();sql.beginTransaction();try{
            String id=startSession(interval);ContentValues v=new ContentValues();v.put("workflow","SEQUENTIAL");sql.update("sessions",v,"id=?",new String[]{id});event(id,null,"DEPARTURE","출항");sql.setTransactionSuccessful();return id;
        }finally{sql.endTransaction();}
    }
    public synchronized String startOperation(String session){
        SQLiteDatabase transaction=getWritableDatabase();transaction.beginTransaction();try{

        requireSession(session);requireNoRecording(session);if(activeOperation(session)!=null)throw new IllegalStateException("진행 중인 조업을 먼저 종료하세요.");
        int seq;try(Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(MAX(sequence_no),0)+1 FROM operations WHERE session_id=?",new String[]{session})){c.moveToFirst();seq=c.getInt(0);}
        String id=id();ContentValues v=new ContentValues();v.put("id",id);v.put("session_id",session);v.put("sequence_no",seq);v.put("started_ms",System.currentTimeMillis());v.put("status","ACTIVE");v.put("stage","START_REQUIRED");
        getWritableDatabase().insertOrThrow("operations",null,v);event(session,null,id,"OPERATION_START","sequence_no="+seq);transaction.setTransactionSuccessful();return id;
    
        }finally{transaction.endTransaction();}
    }
    public synchronized String pendingPhase(String op){
        return switch(operation(op).optString("stage")){case "START_REQUIRED"->"START";case "END_REQUIRED"->"END";case "WORK_REQUIRED"->"WORK";default->throw new IllegalStateException("필요한 녹음 단계가 아닙니다.");};
    }
    public synchronized void beginWrap(String op){
        SQLiteDatabase transaction=getWritableDatabase();transaction.beginTransaction();try{

        JSONObject o=operation(op);requireSession(o.optString("session_id"));requireNoRecording(o.optString("session_id"));
        if(!"ACTIVE".equals(o.optString("status"))||!"FISHING".equals(o.optString("stage")))throw new IllegalStateException("시작 녹음을 먼저 저장하세요.");
        ContentValues v=new ContentValues();v.put("stage","END_REQUIRED");getWritableDatabase().update("operations",v,"id=?",new String[]{op});
        event(o.optString("session_id"),null,op,"WRAP_REQUESTED","마무리 녹음 시작");
    
        transaction.setTransactionSuccessful();

        }finally{transaction.endTransaction();}
    }
    public synchronized String prepareOperationRecording(String op,String phase,String label,String fix) throws IOException{
        SQLiteDatabase transaction=getWritableDatabase();transaction.beginTransaction();try{

        JSONObject o=operation(op);String sid=o.optString("session_id");requireSession(sid);requireNoRecording(sid);
        String expected=switch(phase){case "START"->"START_REQUIRED";case "NOTE"->"FISHING";case "END"->"END_REQUIRED";case "WORK"->"WORK_REQUIRED";default->throw new IllegalStateException("녹음 종류 오류");};
        if(!"ACTIVE".equals(o.optString("status"))||!expected.equals(o.optString("stage")))throw new IllegalStateException("현재 조업 단계에서 할 수 없는 녹음입니다.");
        String r=prepareRecording(sid,label,fix);ContentValues v=new ContentValues();v.put("operation_id",op);v.put("phase",phase);getWritableDatabase().update("recordings",v,"id=?",new String[]{r});transaction.setTransactionSuccessful();return r;
    
        }finally{transaction.endTransaction();}
    }
    public synchronized String prepareSupplement(String op,String phase,String label,String fix) throws IOException,JSONException {
        if(!FieldChecks.required(phase))throw new IllegalArgumentException("추가 녹음 단계 오류");
        SQLiteDatabase sql=getWritableDatabase();sql.beginTransaction();try{
            JSONObject o=operation(op);String sid=o.getString("session_id");requireSession(sid);requireNoRecording(sid);
            if(!"ACTIVE".equals(o.getString("status"))||!("FISHING".equals(o.getString("stage"))||"READY_TO_END".equals(o.getString("stage"))))throw new IllegalStateException("시작·마무리 녹음을 먼저 저장하세요.");
            if("END".equals(phase)&&!"READY_TO_END".equals(o.getString("stage")))throw new IllegalStateException("마무리 녹음을 먼저 저장하세요.");
            if("WORK".equals(phase)!=GearLog.isWork(this,op))throw new IllegalStateException("녹음 종류와 작업이 다릅니다.");
            String r=prepareRecording(sid,label,fix);ContentValues v=new ContentValues();v.put("operation_id",op);v.put("phase","WORK".equals(phase)?"WORK":"NOTE");sql.update("recordings",v,"id=?",new String[]{r});
            event(sid,r,op,FieldChecks.SUPPLEMENT,new JSONObject().put("version",1).put("phase",phase).toString());sql.setTransactionSuccessful();return r;
        }finally{sql.endTransaction();}
    }
    public synchronized String prepareAnswer(String op,JSONObject request,String fix) throws IOException,JSONException {
        SQLiteDatabase sql=getWritableDatabase();sql.beginTransaction();try{
            MissingQuestions.Question q=MissingQuestions.current(this,op,request);
            FieldChecks.Check check=FieldChecks.assess(this,op,"WORK");
            String rid=prepareSupplement(op,"WORK","질문 답변 · "+q.text,fix);
            event(operation(op).getString("session_id"),rid,op,MissingQuestions.ANSWER,MissingQuestions.request(check,q).toString());
            sql.setTransactionSuccessful();return rid;
        }finally{sql.endTransaction();}
    }
    public synchronized void finishOperationChecked(String op,String token) throws JSONException {
        SQLiteDatabase sql=getWritableDatabase();sql.beginTransaction();try{
            JSONArray issues=FieldChecks.operationIssues(this,op);String current=FieldChecks.fingerprint(this,op);
            if(issues.length()>0&&!current.equals(token))throw new IllegalStateException("남은 확인 사항을 확인한 뒤 조업을 종료하세요.");
            event(operation(op).getString("session_id"),null,op,"FIELD_OPERATION_CLOSE",new JSONObject().put("version",1).put("issues",issues).put("acknowledged",issues.length()>0).put("signature",current).toString());
            finishOperation(op);sql.setTransactionSuccessful();
        }finally{sql.endTransaction();}
    }
    public synchronized void completeOperationRecording(String recording,String status,String error,String fix){
        SQLiteDatabase sql=getWritableDatabase();sql.beginTransaction();try{
            endRecording(recording,status,error,fix);
            try(Cursor c=sql.rawQuery("SELECT operation_id,phase FROM recordings WHERE id=?",new String[]{recording})){
                if(!c.moveToFirst())throw new IllegalStateException("녹음 정보 없음");
                if(!c.isNull(0)&&"COMPLETED".equals(status)){
                    String phase=c.getString(1);ContentValues v=new ContentValues();
                    if("START".equals(phase))v.put("stage","FISHING");else if("END".equals(phase)||"WORK".equals(phase))v.put("stage","READY_TO_END");
                    if(v.size()>0)sql.update("operations",v,"id=? AND status='ACTIVE'",new String[]{c.getString(0)});
                }
            }
            if("COMPLETED".equals(status))try{FieldChecks.request(this,recording);}catch(JSONException e){throw new IllegalStateException(e);}
            sql.setTransactionSuccessful();
        }finally{sql.endTransaction();}
    }
    public synchronized void finishOperation(String op){
        SQLiteDatabase transaction=getWritableDatabase();transaction.beginTransaction();try{

        JSONObject o=operation(op);String sid=o.optString("session_id");requireSession(sid);requireNoRecording(sid);
        if(!"ACTIVE".equals(o.optString("status"))||!"READY_TO_END".equals(o.optString("stage")))throw new IllegalStateException("마무리 녹음을 먼저 저장하세요.");
        ContentValues v=new ContentValues();v.put("status","COMPLETED");v.put("stage","COMPLETED");v.put("ended_ms",System.currentTimeMillis());getWritableDatabase().update("operations",v,"id=?",new String[]{op});
        event(sid,null,op,"OPERATION_END","조업 종료");
    
        transaction.setTransactionSuccessful();

        }finally{transaction.endTransaction();}
    }
    public synchronized void arrive(String session){
        SQLiteDatabase transaction=getWritableDatabase();transaction.beginTransaction();try{

        requireSession(session);requireNoRecording(session);if(activeOperation(session)!=null)throw new IllegalStateException("조업을 종료한 뒤 입항하세요.");
        endSession(session,"COMPLETED");event(session,null,"ARRIVAL","입항");
    
        transaction.setTransactionSuccessful();

        }finally{transaction.endTransaction();}
    }
    public synchronized void interruptSession(String session,String reason){
        SQLiteDatabase sql=getWritableDatabase();sql.beginTransaction();try{
            event(session,null,activeOperation(session),"WORKFLOW_INTERRUPTED",reason);
            sql.execSQL("UPDATE operations SET status='INTERRUPTED',stage='INTERRUPTED' WHERE session_id=? AND status='ACTIVE'",new Object[]{session});
            sql.execSQL("UPDATE sessions SET status='INTERRUPTED' WHERE id=? AND status='ACTIVE'",new Object[]{session});
            sql.execSQL("UPDATE recordings SET status='INTERRUPTED',error=? WHERE session_id=? AND status IN ('PREPARING','RECORDING')",new Object[]{reason,session});
            sql.setTransactionSuccessful();
        }finally{sql.endTransaction();}
    }
    public synchronized void recoverInterrupted() {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try {
            try(Cursor c=db.rawQuery("SELECT id FROM sessions WHERE status='ACTIVE'",null)) {
                while(c.moveToNext())event(c.getString(0),null,"INTERRUPTION_DETECTED","앱 재시작 시 발견. 실제 중단 시각과 중단 중 위치는 알 수 없음.");
            }
            db.execSQL("UPDATE operations SET status='INTERRUPTED',stage='INTERRUPTED' WHERE status='ACTIVE'");
            db.execSQL("UPDATE sessions SET status='INTERRUPTED' WHERE status='ACTIVE'");
            db.execSQL("UPDATE recordings SET status='INTERRUPTED',error='Unexpected process termination; audio may be incomplete' WHERE status IN ('PREPARING','RECORDING')");
            db.execSQL("UPDATE transcriptions SET status='INTERRUPTED',error='앱 종료로 전사 중단. 다시 전사할 수 있습니다.' WHERE status='RUNNING'");
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }
    public synchronized int count(String table) {
        if(!Arrays.asList(TABLES).contains(table))throw new IllegalArgumentException();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table,null)){c.moveToFirst();return c.getInt(0);}
    }
    public synchronized JSONArray rows(String table) throws JSONException {
        if(!Arrays.asList(TABLES).contains(table))throw new IllegalArgumentException();
        JSONArray a=new JSONArray();try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM "+table+" ORDER BY rowid",null)){
            while(c.moveToNext()){JSONObject o=new JSONObject();for(int i=0;i<c.getColumnCount();i++){
                Object value=switch(c.getType(i)){case Cursor.FIELD_TYPE_NULL->JSONObject.NULL;case Cursor.FIELD_TYPE_INTEGER->c.getLong(i);case Cursor.FIELD_TYPE_FLOAT->c.getDouble(i);case Cursor.FIELD_TYPE_STRING->c.getString(i);case Cursor.FIELD_TYPE_BLOB->throw new IllegalStateException("예상하지 못한 BLOB 열");default->throw new IllegalStateException("알 수 없는 열 형식");};o.put(c.getColumnName(i),value);
            }a.put(o);}
        }return a;
    }
    public synchronized JSONObject snapshot() throws JSONException {
        SQLiteDatabase db=getReadableDatabase();db.beginTransaction();try{
            JSONObject o=new JSONObject();o.put("schema_version",5);o.put("exported_at_ms",System.currentTimeMillis());o.put("time_basis","Unix epoch milliseconds (UTC); elapsed_ns is device monotonic clock");o.put("coordinate_system","WGS84");
            for(String t:TABLES)o.put(t,rows(t));db.setTransactionSuccessful();return o;
        }finally{db.endTransaction();}
    }
}
