package kr.go.nifs.fieldrecorder;
import android.content.*;
import android.test.*;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

public class DriveTransferTest extends AndroidTestCase {
 private DriveQueue queue;private File files;private Context ctx;
 @Override protected void setUp() throws Exception {super.setUp();files=new File(getContext().getCacheDir(),"drive-test-"+Store.id());assertTrue(files.mkdirs());ctx=new RenamingDelegatingContext(getContext(),"drive_test_"+Store.id()+"_"){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return files;}};queue=new DriveQueue(ctx);}
 @Override protected void tearDown() throws Exception {queue.close();super.tearDown();}
 private String job(int size) throws Exception {String id=queue.add("trip", "owner","test@example.com",false);byte[] bytes=new byte[size];new Random(123).nextBytes(bytes);Files.write(queue.archive(id).toPath(),bytes);ContentValues v=new ContentValues();v.put("size",size);v.put("sha256",DriveApi.digest(queue.archive(id),"SHA-256"));v.put("md5",DriveApi.digest(queue.archive(id),"MD5"));v.put("archive_path",queue.archive(id).getName());queue.update(id,v);return id;}
 private DriveApi.Response response(int code,String body){return new DriveApi.Response(code,Collections.emptyMap(),body);}
 private class Server implements DriveApi.Transport {
  String id;JSONObject meta;long received;int starts,chunks;boolean loseReply,expired,corrupt;List<Long> offsets=new ArrayList<>();
  Server(String job) throws Exception {JSONObject j=queue.get(job);meta=new JSONObject().put("id","remote").put("size",j.getLong("size")).put("mimeType","application/zip").put("md5Checksum",j.getString("md5")).put("appProperties",new JSONObject().put("sha256",j.getString("sha256")));}
  public DriveApi.Response request(String method,String url,String token,Map<String,String> headers,byte[] body) throws IOException {try{
   if(url.contains("generateIds"))return response(200,"{\"ids\":[\"remote\"]}");
   if(method.equals("GET"))return received==meta.getLong("size")?response(200,(corrupt?new JSONObject(meta.toString()).put("md5Checksum","wrong"):meta).toString()):response(404,"{}");
   if(method.equals("POST")){starts++;return new DriveApi.Response(200,Collections.singletonMap("Location","https://www.googleapis.com/upload/session"),"{}");}
   if(expired){expired=false;received=0;return response(410,"{}");}
   if(body.length>0){String range=headers.get("Content-Range");long offset=Long.parseLong(range.substring(6,range.indexOf('-')));offsets.add(offset);if(offset!=received)throw new IOException("wrong offset");received+=body.length;chunks++;}
   if(received==meta.getLong("size")){if(loseReply){loseReply=false;throw new IOException("lost final response");}return response(200,meta.toString());}
   return new DriveApi.Response(308,received==0?Collections.emptyMap():Collections.singletonMap("Range","bytes=0-"+(received-1)),"");
  }catch(JSONException e){throw new IOException(e);}}
 }
 public void testCompletedFileVerifiedAndOnlyStagingDeleted() throws Exception {String j=job(20);File source=new File(files,"source.m4a");Files.write(source.toPath(),new byte[]{1,2,3});Server server=new Server(j);assertTrue(new DriveTransfer(queue,new DriveApi("fake",server),()->false).upload(j,"folder"));assertEquals("DONE",queue.get(j).getString("status"));assertFalse(queue.archive(j).exists());assertTrue(source.exists());assertEquals(1,server.starts);}
 public void testRestartResumesFromServerOffset() throws Exception {String j=job(DriveTransfer.CHUNK+50);Server server=new Server(j);assertFalse(new DriveTransfer(queue,new DriveApi("fake",server),()->server.chunks==1).upload(j,"folder"));queue.close();queue=new DriveQueue(ctx);assertTrue(new DriveTransfer(queue,new DriveApi("fake",server),()->false).upload(j,"folder"));assertEquals(1,server.starts);assertEquals(Long.valueOf(DriveTransfer.CHUNK),server.offsets.get(1));}
 public void testLostFinalResponseDoesNotDuplicate() throws Exception {String j=job(50);Server server=new Server(j);server.loseReply=true;try{new DriveTransfer(queue,new DriveApi("fake",server),()->false).upload(j,"folder");fail();}catch(IOException expected){}assertTrue(queue.archive(j).exists());assertTrue(new DriveTransfer(queue,new DriveApi("fake",server),()->false).upload(j,"folder"));assertEquals(1,server.starts);assertEquals(1,server.chunks);}
 public void testExpiredSessionRestartsWithSameFileId() throws Exception {String j=job(DriveTransfer.CHUNK+50);Server server=new Server(j);assertFalse(new DriveTransfer(queue,new DriveApi("fake",server),()->server.chunks==1).upload(j,"folder"));server.expired=true;assertFalse(new DriveTransfer(queue,new DriveApi("fake",server),()->false).upload(j,"folder"));assertTrue(queue.get(j).isNull("upload_uri"));assertTrue(new DriveTransfer(queue,new DriveApi("fake",server),()->false).upload(j,"folder"));assertEquals(2,server.starts);assertEquals("remote",queue.get(j).getString("file_id"));}
 public void testRemoteCorruptionDoesNotMarkCompleteOrDelete() throws Exception {String j=job(20);Server server=new Server(j);server.corrupt=true;try{new DriveTransfer(queue,new DriveApi("fake",server),()->false).upload(j,"folder");fail();}catch(DriveApi.Failure e){assertFalse(e.retry);}assertTrue(queue.archive(j).exists());assertFalse("DONE".equals(queue.get(j).getString("status")));}
 public void testLocalCorruptionStopsBeforeAnyRequest() throws Exception {String j=job(20);Files.write(queue.archive(j).toPath(),new byte[20]);Server server=new Server(j);try{new DriveTransfer(queue,new DriveApi("fake",server),()->false).upload(j,"folder");fail();}catch(DriveApi.Failure expected){}assertEquals(0,server.starts);assertEquals(0,server.chunks);}
 public void testArrivalIdempotentAndAccountBound() throws Exception {String j=job(20);assertEquals(j,queue.add("trip","owner","test@example.com",false));assertEquals(1,queue.pending());assertNull(queue.next("different-owner"));queue.state(j,"AUTH_REQUIRED","reconnect");assertNull(queue.next("owner"));queue.retryBlocked("different-owner");assertNull(queue.next("owner"));queue.retryBlocked("owner");assertNotNull(queue.next("owner"));}
 public void testQuotaAndRateLimitDistinct() {assertFalse(DriveApi.error(response(403,"storageQuotaExceeded")).retry);assertTrue(DriveApi.error(response(403,"userRateLimitExceeded")).retry);assertTrue(DriveApi.error(response(503,"")).retry);assertFalse(DriveApi.error(response(401,"")).retry);}
 public void testUntrustedUploadLocationsRejected() throws Exception {for(String bad:new String[]{"http://www.googleapis.com/upload","https://evil.example/upload","https://www.googleapis.com@evil.example/upload","https://www.googleapis.com:444/upload"}){try{DriveApi.validateUrl(bad);fail(bad);}catch(IOException expected){}}DriveApi.validateUrl("https://www.googleapis.com/upload/session");}
 public void testInvalidResumeOffsetsRejected() throws Exception {for(String range:new String[]{"bytes=0-100","bytes=1-9","bytes=0-999999999999999999999"}){try{DriveApi.offset(new DriveApi.Response(308,Collections.singletonMap("Range",range),""),50);fail(range);}catch(IOException expected){}}assertEquals(0,DriveApi.offset(response(308,""),50));}
 public void testReconcileIncludesEveryCompletedArrivalAndNoActiveTrip() throws Exception {
  android.content.SharedPreferences prefs=ctx.getSharedPreferences("drive-reconcile-test-"+Store.id(),0);
  Context bound=new android.content.ContextWrapper(ctx){@Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return prefs;}};
  prefs.edit().putString("owner","owner").putString("email","test@example.com").putLong("enabled_since",0).commit();
  try(Store s=new Store(ctx)){String a=s.startSession(60000);s.endSession(a,"COMPLETED");String b=s.startSession(60000);s.endSession(b,"COMPLETED");String incomplete=s.startSession(60000);s.endSession(incomplete,"INTERRUPTED");s.startSession(60000);DriveSync.reconcile(bound,s,queue);DriveSync.reconcile(bound,s,queue);assertEquals(2,queue.pending());assertEquals(2,queue.rows().length());assertEquals(4,s.count("sessions"));}
 }
 public void testNetworkConstraintRequired(){assertEquals(androidx.work.NetworkType.CONNECTED,DriveSync.constraints().getRequiredNetworkType());}
}
