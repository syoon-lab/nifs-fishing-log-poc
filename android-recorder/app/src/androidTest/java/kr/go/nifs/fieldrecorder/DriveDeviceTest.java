package kr.go.nifs.fieldrecorder;

import android.content.*;
import android.net.*;
import android.test.*;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

/** Explicitly selected on-device integration probe; never modifies the primary record store. */
public class DriveDeviceTest extends InstrumentationTestCase {
 private String sourceSnapshot(Store s) throws Exception {JSONObject snapshot=s.snapshot();snapshot.remove("exported_at_ms");return snapshot.toString();}
 public void testQueueSyntheticArchiveWhileOffline() throws Exception {
  Context app=getInstrumentation().getTargetContext();assertTrue("Connect Google account first",DriveAuth.enabled(app));
  ConnectivityManager cm=app.getSystemService(ConnectivityManager.class);android.net.Network network=cm.getActiveNetwork();NetworkCapabilities caps=network==null?null:cm.getNetworkCapabilities(network);assertTrue("Disconnect network for recovery test",caps==null||!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
  Store primary=RecorderApp.store();String before=sourceSnapshot(primary);
  File root=new File(app.getFilesDir(),"drive-device-qa-"+Store.id());assertTrue(root.mkdirs());String prefix="drive_device_"+Store.id()+"_";
  Context isolated=new RenamingDelegatingContext(app,prefix){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return root;}};
  try(Store fixture=new Store(isolated);DriveQueue q=new DriveQueue(app)){
   String trip=fixture.startSession(60000),rid=fixture.prepareRecording(trip,"자동 업로드 검증용 합성음성 · 실제 조업 아님",null);
   try(InputStream in=getInstrumentation().getContext().getAssets().open("questions/haul_30.m4a");OutputStream out=new FileOutputStream(fixture.audio(rid))){in.transferTo(out);}
   fixture.recordingStarted(rid);fixture.endRecording(rid,"COMPLETED","",null);fixture.event(trip,rid,"MOCK_FIXTURE","Automatic Drive recovery verification; synthetic voice; no real fishing or location data");fixture.endSession(trip,"COMPLETED");
   String id=q.add("qa-"+trip,DriveAuth.owner(app),DriveAuth.email(app),true);File zip=q.archive(id);Archive.write(fixture,zip);
   ContentValues v=new ContentValues();v.put("archive_path",zip.getName());v.put("size",zip.length());v.put("sha256",DriveApi.digest(zip,"SHA-256"));v.put("md5",DriveApi.digest(zip,"MD5"));q.update(id,v);
   File evidence=new File(app.getExternalFilesDir(null),"drive-device-qa");evidence.mkdirs();Files.copy(zip.toPath(),new File(evidence,"expected.zip").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
   JSONObject report=new JSONObject().put("job_id",id).put("source_sha256",DriveApi.digest(fixture.audio(rid),"SHA-256")).put("zip_sha256",DriveApi.digest(zip,"SHA-256")).put("zip_bytes",zip.length()).put("fixture_root",root.getName()).put("fixture_database_prefix",prefix).put("primary_unchanged",before.equals(sourceSnapshot(primary)));
   Files.write(new File(evidence,"queued.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));assertTrue(report.getBoolean("primary_unchanged"));DriveSync.schedule(app);Thread.sleep(3000);assertEquals("WAITING",q.get(id).getString("status"));assertEquals(0,q.get(id).getInt("attempts"));assertTrue(zip.exists());
  }
 }
 public void testLabelOnlySyntheticVerificationUploads() throws Exception {
  Context app=getInstrumentation().getTargetContext();String token=DriveAuth.token(app,DriveAuth.email(app));JSONArray renamed=new JSONArray();
  try(DriveQueue q=new DriveQueue(app)){JSONArray rows=q.rows();for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!row.getString("session_id").startsWith("qa-")||!"DONE".equals(row.getString("status")))continue;
   String name="검증용_합성음성_실제조업아님_"+row.getString("id")+".zip",url="https://www.googleapis.com/drive/v3/files/"+java.net.URLEncoder.encode(row.getString("file_id"),"UTF-8")+"?fields=id,name";
   DriveApi.Response response=new DriveApi.Http().request("PATCH",url,token,java.util.Collections.singletonMap("Content-Type","application/json"),new JSONObject().put("name",name).toString().getBytes(StandardCharsets.UTF_8));assertEquals(200,response.code);JSONObject result=new JSONObject(response.body);assertEquals(name,result.getString("name"));renamed.put(result);
  }}assertTrue(renamed.length()>0);Files.write(new File(new File(app.getExternalFilesDir(null),"drive-device-qa"),"verification-upload-names.json").toPath(),renamed.toString(2).getBytes(StandardCharsets.UTF_8));
 }
 public void testVerifyRemoteAndOriginalAfterRecovery() throws Exception {
  Context app=getInstrumentation().getTargetContext();File evidence=new File(app.getExternalFilesDir(null),"drive-device-qa");JSONObject report=new JSONObject(new String(Files.readAllBytes(new File(evidence,"queued.json").toPath()),StandardCharsets.UTF_8));
  try(DriveQueue q=new DriveQueue(app)){JSONObject job=q.get(report.getString("job_id"));assertEquals("DONE",job.getString("status"));String token=DriveAuth.token(app,DriveAuth.email(app));DriveApi api=new DriveApi(token);JSONObject meta=api.metadata(job.getString("file_id"));DriveApi.verify(meta,job.getString("file_id"),job.getLong("size"),job.getString("md5"),job.getString("sha256"));
   String downloadUrl="https://www.googleapis.com/drive/v3/files/"+java.net.URLEncoder.encode(job.getString("file_id"),"UTF-8")+"?alt=media";DriveApi.validateUrl(downloadUrl);
   java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL(downloadUrl).openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(20000);connection.setReadTimeout(45000);connection.setRequestProperty("Authorization","Bearer "+token);File downloaded=new File(evidence,"downloaded.zip");
   try{assertEquals(200,connection.getResponseCode());try(InputStream in=connection.getInputStream();OutputStream out=new FileOutputStream(downloaded)){byte[] b=new byte[65536];int n;long count=0;while((n=in.read(b))!=-1){count+=n;assertTrue(count<=job.getLong("size"));out.write(b,0,n);}}}finally{connection.disconnect();}
   assertEquals(job.getLong("size"),downloaded.length());assertEquals(report.getString("zip_sha256"),DriveApi.digest(downloaded,"SHA-256"));report.put("downloaded_sha256_matches",true);
   File root=new File(app.getFilesDir(),report.getString("fixture_root"));File[] audio=new File(root,"records/audio").listFiles();assertNotNull(audio);assertEquals(1,audio.length);assertEquals(report.getString("source_sha256"),DriveApi.digest(audio[0],"SHA-256"));assertFalse(q.archive(job.getString("id")).exists());
   JSONObject counts=new JSONObject();for(String table:Store.TABLES)counts.put(table,RecorderApp.store().count(table));report.put("primary_counts_after",counts).put("remote_metadata",meta).put("status","DONE").put("original_audio_preserved",true).put("attempts",job.getInt("attempts"));Files.write(new File(evidence,"verified.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
  }
 }
}
