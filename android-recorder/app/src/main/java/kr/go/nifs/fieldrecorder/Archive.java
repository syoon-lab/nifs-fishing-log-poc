package kr.go.nifs.fieldrecorder;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Stream audio without loading whole recordings into memory; preserve every row and file. */
public final class Archive {
    private Archive() {}
    private static void text(ZipOutputStream z,String name,String value) throws IOException {
        z.putNextEntry(new ZipEntry(name));z.write(value.getBytes(StandardCharsets.UTF_8));z.closeEntry();
    }
    static String csvCell(Object v) {
        if(v==null||v==JSONObject.NULL)return "";
        // Prevent spreadsheet formula interpretation; raw JSON always preserves the exact value.
        String s=v.toString();if(v instanceof String&&!s.isEmpty()&&"=+@-\t\r".indexOf(s.charAt(0))>=0)s="'"+s;
        return "\""+s.replace("\"","\"\"")+"\"";
    }
    static String csv(JSONArray rows,String[] keys) throws JSONException {
        StringBuilder s=new StringBuilder("\uFEFF").append(String.join(",",keys)).append("\r\n");
        for(int i=0;i<rows.length();i++){JSONObject o=rows.getJSONObject(i);for(int k=0;k<keys.length;k++){if(k>0)s.append(',');s.append(csvCell(o.opt(keys[k])));}s.append("\r\n");}return s.toString();
    }
    private static Map<String,JSONObject> index(JSONArray rows) throws JSONException,IOException {
        Map<String,JSONObject> map=new HashMap<>();for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(map.put(row.getString("id"),row)!=null)throw new IOException("중복 식별자 발견");}return map;
    }
    public static String sha256(InputStream in) throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)d.update(buffer,0,n);
        StringBuilder hex=new StringBuilder();for(byte b:d.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();
    }
    public static void write(Store store,File destination) throws Exception {
        JSONObject data=store.snapshot();WorkflowIntegrity.validate(data);TranscriptionIntegrity.validate(data);StructuredIntegrity.validate(data);JSONArray sessions=data.getJSONArray("sessions");
        for(int i=0;i<sessions.length();i++)if("ACTIVE".equals(sessions.getJSONObject(i).getString("status")))throw new IOException("항차를 마친 뒤 내보내세요.");
        JSONArray recordings=data.getJSONArray("recordings"),files=new JSONArray();Set<String> used=new HashSet<>();
        Map<String,JSONObject> sessionIndex=index(sessions),recordingIndex=index(recordings),locationIndex=index(data.getJSONArray("locations"));
        for(int i=0;i<recordings.length();i++){
            JSONObject r=recordings.getJSONObject(i);String sid=r.getString("session_id");
            if(!sessionIndex.containsKey(sid))throw new IOException("녹음의 항차 연결 누락");
            for(String key:new String[]{"start_location_id","end_location_id"})if(!r.isNull(key)){
                JSONObject point=locationIndex.get(r.getString(key));if(point==null||!sid.equals(point.getString("session_id")))throw new IOException("녹음의 위치 연결 오류");
            }
        }
        for(JSONObject point:locationIndex.values()){
            String sid=point.getString("session_id");if(!sessionIndex.containsKey(sid))throw new IOException("위치의 항차 연결 누락");
            if(!point.isNull("recording_id")){JSONObject r=recordingIndex.get(point.getString("recording_id"));if(r==null||!sid.equals(r.getString("session_id")))throw new IOException("위치의 녹음 연결 오류");}
        }
        Set<String> referenced=new HashSet<>();for(int i=0;i<recordings.length();i++)referenced.add(recordings.getJSONObject(i).getString("path"));
        File audioDir=new File(store.root(),"audio");File[] onDisk=audioDir.listFiles();
        if(audioDir.exists()&&onDisk==null)throw new IOException("녹음 폴더를 읽을 수 없습니다.");
        if(onDisk!=null)for(File file:onDisk)if(!referenced.contains("audio/"+file.getName()))throw new IOException("연결 정보가 없는 녹음파일 발견: "+file.getName()+". 누락 방지를 위해 확인이 필요합니다.");
        try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(destination)))) {
            byte[] buffer=new byte[65536];
            for(int i=0;i<recordings.length();i++){
                JSONObject r=recordings.getJSONObject(i);String relative=r.getString("path");
                File f=new File(store.root(),relative);String base=store.root().getCanonicalPath()+File.separator;
                if(!f.getCanonicalPath().startsWith(base)||!used.add(relative))throw new IOException("녹음 경로 또는 중복 참조 오류: "+relative);
                if(!f.isFile())throw new IOException("녹음파일 누락: "+relative+". 원자료를 확인하세요.");
                MessageDigest digest=MessageDigest.getInstance("SHA-256");long size=0;zip.putNextEntry(new ZipEntry(relative));
                try(InputStream in=new BufferedInputStream(new FileInputStream(f))){int n;while((n=in.read(buffer))!=-1){zip.write(buffer,0,n);digest.update(buffer,0,n);size+=n;}}
                zip.closeEntry();StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
                String audioHash=hex.toString();JSONArray attempts=data.getJSONArray("transcriptions");
                for(int a=0;a<attempts.length();a++){JSONObject attempt=attempts.getJSONObject(a);if(r.getString("id").equals(attempt.getString("recording_id"))&&!audioHash.equals(attempt.getString("audio_sha256")))throw new IOException("전사의 원음 지문 불일치");}
                files.put(new JSONObject().put("path",relative).put("bytes",size).put("sha256",hex.toString()).put("recording_id",r.getString("id")));
            }
            text(zip,"records.json",data.toString(2));
            JSONObject counts=new JSONObject();
            for(String t:Store.TABLES){text(zip,t+".csv",csv(data.getJSONArray(t),store.columns(t)));counts.put(t,data.getJSONArray(t).length());}
            text(zip,"manifest.json",new JSONObject().put("schema_version",5).put("counts",counts).put("audio_files",files).toString(2));
            text(zip,"읽어주세요.txt","조업기록 0.8 자망·통발 작업 기록 앱 내보내기\n모든 항차·조업·녹음·위치·이벤트·전사·수정 이력·일지를 포함합니다.\ntranscriptions는 전사 시도별 원문과 원음/모델 해시, transcript_reviews는 사용자 확인·수정 이력, diary_entries는 녹음 시각을 자동 연결한 확인본 일지입니다. 최신 수정본은 연결된 reviews의 행 순서로 판단하며 과거본을 중복 집계하지 마세요. structured_logs는 항목 추출 초안/확인본, catch_items는 어종별 수량·단위·중량입니다. DRAFT는 미확인 초안이며 CONFIRMED도 빈 값은 미확인으로 남습니다. 합계는 최신 확인본만 선택하고 서로 다른 단위는 합산하지 마세요.\noperations.csv는 항차 안의 조업 회차입니다. recordings.phase는 WORK(자망·통발 통합 작업), START(이전 시작), NOTE(이전 특이사항), END(이전 마무리), LEGACY(이전 자료·미분류)입니다.\nsessions.workflow=LEGACY는 과거 자료로 출항·입항이나 조업 단계를 추정하지 않았습니다.\n중단 조업·항차의 알 수 없는 종료·입항 시각은 null입니다.\n 원자료는 휴대전화에 그대로 남습니다.\nFIELD_* 이벤트 detail은 version=1 JSON입니다. FIELD_SUPPLEMENT는 추가 녹음이 보완하는 WORK/START/END 단계, FIELD_ANSWER_CONTEXT는 답변 녹음의 질문 키·문장·어종·근거 연결, FIELD_QUESTION_DEFERRED는 값 없이 보류한 질문, FIELD_REVIEW는 현장 확인 항목과 이전 값·근거 녹음, FIELD_DEFERRED는 보류, FIELD_OPERATION_CLOSE는 종료 시 미해결 항목과 확인 여부입니다. 현장 확인은 PC 최종 확정이 아닙니다. 추가 녹음을 별도 조업이나 별도 어획량으로 중복 합산하지 마세요.\ngear_details는 자망·통발 추출본별 투망일·날짜 후보·확인 상태·양망 수량·재투망 수량을 보존합니다. 자망은 폭, 통발은 개입니다. recorded_date는 녹음일(Asia/Seoul), deployment_date는 확인한 과거 투망일이며 후보는 확정값이 아닙니다. 과거 투망 기록 자동 연결은 없습니다. GEAR_WORK_CONTEXT는 새 단일 녹음 작업의 어법 선택, FIELD_REVIEW는 현장 확인이며 PC 최종 확정이 아닙니다. 작업 시작·종료 시각은 앱 기록 절차 시각으로 실제 양망 소요시간을 의미하지 않습니다.\n원음: audio/*.m4a / 원자료: records.json / 대조표: manifest.json (파일별 크기·SHA-256)\nCSV는 UTF-8 BOM 형식이며 수식으로 해석될 수 있는 문자열 앞에 작은따옴표를 붙입니다. 정확한 원문은 JSON에 있습니다.\n시각은 UTC 기준 Unix 밀리초. 위치는 WGS84, accuracy_m은 미터입니다.\nis_mock=1, provider=synthetic-demo, MOCK_FIXTURE 이벤트는 합성음성·임의 좌표·지정 시각인 모의 자료입니다. 실제 관측과 구분하세요.\nstart_location_id/end_location_id는 저장된 위치 레코드를 참조하며, 확보하지 못한 위치는 null입니다.\n좌표의 measured_ms와 녹음 started_ms/ended_ms가 다를 수 있으므로 함께 확인하세요.\n녹음 위치는 발화에서 언급한 조업 위치와 다를 수 있습니다.\nINTERRUPTED/FAILED 녹음은 불완전할 수 있습니다. 실제 종료 시각이 불명확하면 ended_ms는 null입니다.\n요청한 위치 간격은 보장되지 않으며 실제 measured_ms 간격과 이벤트를 확인해야 합니다.\n");
        }
        // Reopen the archive and verify every audio SHA-256 against the source digest.
        try(ZipFile z=new ZipFile(destination)) {
            if(z.size()!=recordings.length()+Store.TABLES.length+3)throw new IOException("내보내기 파일 수 불일치");
            for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);ZipEntry entry=z.getEntry(f.getString("path"));if(entry==null||entry.getSize()!=f.getLong("bytes"))throw new IOException("내보내기 크기 불일치");try(InputStream in=z.getInputStream(entry)){if(!sha256(in).equals(f.getString("sha256")))throw new IOException("내보내기 파일 검증 실패");}}
        }
    }
}
