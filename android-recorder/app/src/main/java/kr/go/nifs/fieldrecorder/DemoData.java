package kr.go.nifs.fieldrecorder;

import android.content.ContentValues;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Explicit synthetic fixtures: never presented as microphone/GPS observations. */
public final class DemoData {
    public static final String SESSION="mock-20261102-v1-session";
    private DemoData() {}
    private static void copy(InputStream in,OutputStream out) throws IOException {
        byte[] buffer=new byte[8192];int n;
        while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
    }
    private static ContentValues values(Object... pairs) {
        ContentValues v=new ContentValues();
        for(int i=0;i<pairs.length;i+=2){String k=(String)pairs[i];Object x=pairs[i+1];
            if(x==null)v.putNull(k);else if(x instanceof Integer)v.put(k,(Integer)x);
            else if(x instanceof Long)v.put(k,(Long)x);else if(x instanceof Double)v.put(k,(Double)x);
            else v.put(k,x.toString());}
        return v;
    }
    public static int install(Store store,AssetManager assets) throws Exception {
        synchronized(store){
            SQLiteDatabase db=store.getWritableDatabase();
            try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM sessions WHERE status='ACTIVE'",null)){
                c.moveToFirst();if(c.getInt(0)>0)throw new IOException("항차를 마친 뒤 모의 자료를 추가하세요.");
            }
            JSONArray samples;
            try(InputStream in=assets.open("demo/samples.json")){
                ByteArrayOutputStream out=new ByteArrayOutputStream();copy(in,out);
                samples=new JSONArray(new String(out.toByteArray(),StandardCharsets.UTF_8));
            }
            boolean exists;
            try(Cursor c=db.rawQuery("SELECT id FROM sessions WHERE id=?",new String[]{SESSION})){exists=c.moveToFirst();}
            if(exists){
                for(int i=0;i<samples.length();i++){
                    String rid="mock-20261102-v1-recording-"+(i+1);
                    try(InputStream in=new FileInputStream(store.audio(rid))){
                        if(!Archive.sha256(in).equals(samples.getJSONObject(i).getString("sha256")))throw new IOException("기존 모의 음성 파일 검증 실패");
                    }
                }
                return 0;
            }
            ArrayList<File> created=new ArrayList<>();db.beginTransaction();boolean committed=false;
            try{
                JSONObject last=samples.getJSONObject(samples.length()-1);
                db.insertOrThrow("sessions",null,values("id",SESSION,"started_ms",samples.getJSONObject(0).getLong("started_ms"),"ended_ms",last.getLong("started_ms")+last.getLong("duration_ms"),"interval_ms",60000L,"status","COMPLETED"));
                for(int i=0;i<samples.length();i++){
                    JSONObject s=samples.getJSONObject(i);String rid="mock-20261102-v1-recording-"+(i+1),lid="mock-20261102-v1-location-"+(i+1);
                    String relative="audio/"+rid+".m4a";File audio=new File(store.root(),relative);
                    if(!audio.getParentFile().isDirectory()&&!audio.getParentFile().mkdirs())throw new IOException("저장 폴더 생성 실패");
                    if(!audio.createNewFile())throw new IOException("모의 자료 파일 충돌: "+relative);
                    created.add(audio);
                    try(InputStream in=assets.open(s.getString("asset"));OutputStream out=new FileOutputStream(audio)){copy(in,out);}
                    try(InputStream in=new FileInputStream(audio)){if(!Archive.sha256(in).equals(s.getString("sha256")))throw new IOException("모의 음성 검증 실패");}
                    long start=s.getLong("started_ms");
                    db.insertOrThrow("recordings",null,values("id",rid,"session_id",SESSION,"started_ms",start,"ended_ms",start+s.getLong("duration_ms"),"label","[모의] "+s.getString("species")+" "+s.getInt("catch_kg")+"kg","path",relative,"status","COMPLETED","error","","start_location_id",lid,"end_location_id",lid));
                    db.insertOrThrow("locations",null,values("id",lid,"session_id",SESSION,"recording_id",rid,"measured_ms",start,"received_ms",System.currentTimeMillis(),"elapsed_ns",0L,"latitude",s.getDouble("latitude"),"longitude",s.getDouble("longitude"),"accuracy_m",null,"provider","synthetic-demo","is_mock",1));
                    JSONObject detail=new JSONObject().put("synthetic",true).put("source","bundled Korean synthesized speech; assigned coordinates and time, not sensor data").put("transcript",s.getString("transcript")).put("species",s.getString("species")).put("catch_kg",s.getInt("catch_kg"));
                    db.insertOrThrow("events",null,values("id","mock-20261102-v1-event-"+(i+1),"session_id",SESSION,"recording_id",rid,"time_ms",start,"type","MOCK_FIXTURE","detail",detail.toString()));
                }
                db.setTransactionSuccessful();committed=true;
            }finally{db.endTransaction();if(!committed)for(File f:created)Files.deleteIfExists(f.toPath());}
            return samples.length();
        }
    }
}
