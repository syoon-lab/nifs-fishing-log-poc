package kr.go.nifs.fieldrecorder;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Drive v3 resumable upload; only fixed Google HTTPS endpoints receive authorization. */
final class DriveApi {
 interface Transport { Response request(String method,String url,String token,Map<String,String> headers,byte[] body) throws IOException; }
 static final class Response {
  final int code;final Map<String,String> headers;final String body;
  Response(int code,Map<String,String> headers,String body){this.code=code;this.headers=headers;this.body=body;}
  String header(String key){for(Map.Entry<String,String> e:headers.entrySet())if(key.equalsIgnoreCase(e.getKey()))return e.getValue();return "";}
 }
 static final class Http implements Transport {
  public Response request(String method,String url,String token,Map<String,String> headers,byte[] body) throws IOException {
   validateUrl(url);HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(20000);c.setReadTimeout(45000);c.setRequestMethod(method);c.setRequestProperty("Authorization","Bearer "+token);
   for(Map.Entry<String,String> h:headers.entrySet())c.setRequestProperty(h.getKey(),h.getValue());
   try{if(body!=null){c.setDoOutput(true);c.setFixedLengthStreamingMode(body.length);try(OutputStream out=c.getOutputStream()){out.write(body);}}
    int code=c.getResponseCode();Map<String,String> hs=new HashMap<>();for(Map.Entry<String,List<String>> h:c.getHeaderFields().entrySet())if(h.getKey()!=null&&!h.getValue().isEmpty())hs.put(h.getKey(),h.getValue().get(0));
    InputStream input=code>=400?c.getErrorStream():c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream();if(input!=null)try(InputStream in=input){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>1024*1024)throw new IOException("서버 응답이 너무 큽니다.");out.write(buf,0,n);}}
    return new Response(code,hs,out.toString(StandardCharsets.UTF_8.name()));
   }finally{c.disconnect();}
  }
 }
 static class Failure extends IOException {final int code;final boolean retry;Failure(int code,boolean retry,String message){super(message);this.code=code;this.retry=retry;}}
 private static final String API="https://www.googleapis.com/drive/v3/";
 private final Transport transport;private final String token;
 DriveApi(String token){this(token,new Http());}DriveApi(String token,Transport transport){this.token=token;this.transport=transport;}
 static void validateUrl(String value) throws IOException {try{URI u=URI.create(value);if(!"https".equals(u.getScheme())||!"www.googleapis.com".equals(u.getHost())||u.getUserInfo()!=null||u.getPort()!=-1||u.getFragment()!=null)throw new Exception();}catch(Exception e){throw new IOException("허용되지 않은 업로드 주소입니다.");}}
 private Response send(String method,String url,Map<String,String> h,byte[] b) throws IOException {validateUrl(url);return transport.request(method,url,token,h,b);}
 private static String enc(String s){try{return URLEncoder.encode(s,"UTF-8");}catch(Exception e){throw new IllegalArgumentException(e);}}
 static Failure error(Response r){boolean retry=r.code==408||r.code==429||r.code>=500||r.code==403&&(r.body.contains("rateLimitExceeded")||r.body.contains("userRateLimitExceeded"));String msg=r.code==401?"구글 계정 재연결이 필요합니다.":r.code==403&&r.body.contains("storageQuotaExceeded")?"구글드라이브 저장 공간이 부족합니다.":retry?"통신 또는 서버 문제로 다시 시도합니다.":"구글드라이브 전송을 확인해 주세요. ("+r.code+")";return new Failure(r.code,retry,msg);}
 private JSONObject json(Response r) throws Exception {if(r.code<200||r.code>=300)throw error(r);return new JSONObject(r.body);}
 JSONObject account() throws Exception {return json(send("GET",API+"about?fields=user(emailAddress,permissionId)",Collections.emptyMap(),null)).getJSONObject("user");}
 String generateId() throws Exception {return json(send("GET",API+"files/generateIds?count=1&space=drive&type=files",Collections.emptyMap(),null)).getJSONArray("ids").getString(0);}
 JSONObject metadata(String id) throws Exception {Response r=send("GET",API+"files/"+enc(id)+"?fields=id,size,md5Checksum,trashed,mimeType,webViewLink,appProperties",Collections.emptyMap(),null);if(r.code==404)return null;return json(r);}
 String ensureFolder(String id) throws Exception {
  JSONObject current=metadata(id);if(current!=null){if(current.optBoolean("trashed")||!"application/vnd.google-apps.folder".equals(current.optString("mimeType")))throw new Failure(400,false,"업로드 폴더가 삭제되었거나 변경되었습니다. 계정 연결을 확인해 주세요.");return id;}
  JSONObject body=new JSONObject().put("id",id).put("name","조업기록 자동업로드").put("mimeType","application/vnd.google-apps.folder");Response r=send("POST",API+"files?fields=id",Collections.singletonMap("Content-Type","application/json; charset=UTF-8"),body.toString().getBytes(StandardCharsets.UTF_8));if(r.code!=409)json(r);else {current=metadata(id);if(current==null||current.optBoolean("trashed")||!"application/vnd.google-apps.folder".equals(current.optString("mimeType")))throw error(r);}return id;
 }
 String start(String fileId,String folder,String name,long size,String sha) throws Exception {
  JSONObject data=new JSONObject().put("id",fileId).put("name",name).put("mimeType","application/zip").put("parents",new JSONArray().put(folder)).put("appProperties",new JSONObject().put("sha256",sha).put("source","fishing-log"));
  Map<String,String> h=new HashMap<>();h.put("Content-Type","application/json; charset=UTF-8");h.put("X-Upload-Content-Type","application/zip");h.put("X-Upload-Content-Length",Long.toString(size));
  Response r=send("POST","https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,size,md5Checksum,webViewLink",h,data.toString().getBytes(StandardCharsets.UTF_8));if(r.code<200||r.code>=300)throw error(r);String uri=r.header("Location");validateUrl(uri);return uri;
 }
 Response status(String uri,long size) throws IOException {return send("PUT",uri,Collections.singletonMap("Content-Range","bytes */"+size),new byte[0]);}
 Response chunk(String uri,File f,long offset,int length) throws IOException {
  if(offset<0||length<=0||offset+length>f.length())throw new IOException("업로드 범위 오류");byte[] data=new byte[length];try(RandomAccessFile in=new RandomAccessFile(f,"r")){in.seek(offset);in.readFully(data);}
  Map<String,String> h=new HashMap<>();h.put("Content-Type","application/zip");h.put("Content-Range","bytes "+offset+"-"+(offset+length-1)+"/"+f.length());return send("PUT",uri,h,data);
 }
 static long offset(Response r,long size) throws IOException {String range=r.header("Range");if(range.isEmpty())return 0;if(!range.matches("bytes=0-[0-9]+"))throw new IOException("서버 전송 범위 확인 실패");try{long n=Math.addExact(Long.parseLong(range.substring(8)),1);if(n<0||n>size)throw new Exception();return n;}catch(Exception e){throw new IOException("서버 전송 범위 오류");}}
 static void verify(JSONObject meta,String id,long size,String md5,String sha) throws Exception {if(meta==null||!id.equals(meta.optString("id"))||meta.optBoolean("trashed")||!"application/zip".equals(meta.optString("mimeType"))||meta.optLong("size",-1)!=size||!md5.equalsIgnoreCase(meta.optString("md5Checksum"))||meta.optJSONObject("appProperties")==null||!sha.equals(meta.getJSONObject("appProperties").optString("sha256")))throw new Failure(400,false,"업로드 파일 검증 실패 · 원본은 휴대전화에 보존했습니다.");}
 static String digest(File f,String algorithm) throws Exception {MessageDigest d=MessageDigest.getInstance(algorithm);try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
}
