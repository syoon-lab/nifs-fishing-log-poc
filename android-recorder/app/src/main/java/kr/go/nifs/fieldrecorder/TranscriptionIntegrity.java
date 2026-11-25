package kr.go.nifs.fieldrecorder;

import org.json.*;
import java.io.IOException;
import java.util.*;

final class TranscriptionIntegrity {
    private static Map<String,JSONObject> index(JSONObject data,String table) throws Exception {
        Map<String,JSONObject> map=new HashMap<>();JSONArray rows=data.getJSONArray(table);
        for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);if(map.put(r.getString("id"),r)!=null)fail("중복 전사 식별자");}return map;
    }
    private static void fail(String message) throws IOException {throw new IOException(message);}
    static void validate(JSONObject data) throws Exception {
        Map<String,JSONObject> recordings=index(data,"recordings"),attempts=index(data,"transcriptions"),reviews=index(data,"transcript_reviews");
        for(JSONObject t:attempts.values()){
            JSONObject r=recordings.get(t.getString("recording_id"));if(r==null||!"COMPLETED".equals(r.getString("status")))fail("전사의 녹음 연결 오류");
            String status=t.getString("status");if(!Arrays.asList("COMPLETED","FAILED","CANCELLED","INTERRUPTED").contains(status))fail("전사를 마친 뒤 내보내세요.");
            if(!t.getString("audio_sha256").matches("[0-9a-f]{64}")||!t.getString("model_sha256").matches("[0-9a-f]{64}"))fail("전사 원음/모델 지문 오류");
            JSONArray segments=new JSONArray(t.getString("segments_json"));
            if("COMPLETED".equals(status)){
                if(t.isNull("finished_ms")||t.isNull("duration_ms")||t.getLong("duration_ms")<=0)fail("전사 완료 정보 누락");
                StringBuilder text=new StringBuilder();long last=0;
                for(int i=0;i<segments.length();i++){JSONObject segment=segments.getJSONObject(i);long start=segment.getLong("start_ms"),end=segment.getLong("end_ms");if(start<last||end<start)fail("전사 구간 시각 오류");last=start;text.append(segment.getString("text"));}
                if(!text.toString().equals(t.getString("text")))fail("전사 구간/원문 불일치");
            }else if(!t.getString("text").isEmpty()||segments.length()!=0||!t.isNull("duration_ms"))fail("실패한 전사의 완료 내용");
        }
        Set<String> linked=new HashSet<>();
        for(JSONObject review:reviews.values()){
            JSONObject t=attempts.get(review.getString("transcription_id"));if(t==null||!"COMPLETED".equals(t.getString("status"))||review.getString("text").trim().isEmpty())fail("전사 확인본 연결 오류");
        }
        for(JSONObject entry:index(data,"diary_entries").values()){
            String id=entry.getString("review_id");JSONObject review=reviews.get(id);if(review==null||!linked.add(id))fail("일지 확인본 연결 오류");
            JSONObject t=attempts.get(review.getString("transcription_id")),r=recordings.get(t.getString("recording_id")),d=new JSONObject(entry.getString("data_json"));
            if(d.getInt("schema_version")!=1||!"TRANSCRIPT_CONFIRMED".equals(d.getString("review_status"))||!"recording.started_ms".equals(d.getString("time_source"))||!d.getString("text").equals(review.getString("text"))||d.getLong("recorded_at_ms")!=r.getLong("started_ms"))fail("일지 원문/자동 시각 불일치");
            for(String key:new String[]{"session_id","operation_id","phase"})if(!Objects.equals(d.get(key),r.get(key)))fail("일지 조업 연결 오류");
            if(!d.getString("recording_id").equals(r.getString("id")))fail("일지 녹음 연결 오류");
        }
        if(linked.size()!=reviews.size())fail("확인본의 일지 누락");
    }
}
