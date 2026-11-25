package kr.go.nifs.fieldrecorder;

import android.test.InstrumentationTestCase;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Explicit demo integration run against this emulator's app data, not a destructive reset. */
public class DemoFlowTest extends InstrumentationTestCase {
    public void testStoreAndExportDemoPreservingExisting() throws Exception {
        Context ctx=getInstrumentation().getTargetContext();Store store=RecorderApp.store();
        JSONObject before=store.snapshot();int initial=store.count("recordings");
        File dir=ctx.getExternalFilesDir(null);assertNotNull(dir);
        Files.write(new File(dir,"before_demo.json").toPath(),before.toString(2).getBytes(StandardCharsets.UTF_8));
        int added=DemoData.install(store,ctx.getAssets());assertTrue(added==0||added==3);
        assertEquals(initial+added,store.count("recordings"));
        assertEquals(0,DemoData.install(store,ctx.getAssets()));
        JSONObject after=store.snapshot();
        for(String table:new String[]{"sessions","recordings","locations","events"}){
            JSONArray original=before.getJSONArray(table),all=after.getJSONArray(table);
            for(int i=0;i<original.length();i++){
                JSONObject old=original.getJSONObject(i);boolean found=false;
                for(int j=0;j<all.length();j++)if(old.getString("id").equals(all.getJSONObject(j).getString("id"))){assertEquals(old.toString(),all.getJSONObject(j).toString());found=true;break;}
                assertTrue("Existing record disappeared",found);
            }
        }
        for(int i=1;i<=3;i++){
            MediaMetadataRetriever m=new MediaMetadataRetriever();
            try{m.setDataSource(store.audio("mock-20261102-v1-recording-"+i).getAbsolutePath());assertTrue(Long.parseLong(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))>4000);}
            finally{m.release();}
        }
        Archive.write(store,new File(dir,"poc_261102.zip"));
        Files.write(new File(dir,"after_demo.json").toPath(),after.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
