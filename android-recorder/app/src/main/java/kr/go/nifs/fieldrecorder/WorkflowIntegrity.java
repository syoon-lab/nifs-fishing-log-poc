package kr.go.nifs.fieldrecorder;

import org.json.*;
import java.io.IOException;
import java.util.*;

/** Validate the entire exported graph, including incomplete and legacy records. */
final class WorkflowIntegrity {
    private static Map<String,JSONObject> index(JSONArray rows) throws Exception {
        Map<String,JSONObject> result=new HashMap<>();
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(result.put(row.getString("id"),row)!=null)fail("중복 식별자");}return result;
    }
    private static void fail(String message) throws IOException {throw new IOException(message);}
    static void validate(JSONObject data) throws Exception {
        Map<String,JSONObject> sessions=index(data.getJSONArray("sessions")),ops=index(data.getJSONArray("operations")),recordings=index(data.getJSONArray("recordings"));
        Set<String> gearOps=new HashSet<>();JSONArray events=data.getJSONArray("events");for(int i=0;i<events.length();i++){JSONObject e=events.getJSONObject(i);if(GearLog.CONTEXT.equals(e.optString("type"))){JSONObject detail=new JSONObject(e.getString("detail"));if(detail.getInt("version")!=1||!GearLog.supported(detail.getString("gear_type"))||!gearOps.add(e.getString("operation_id")))fail("자망·통발 작업 연결 오류");}}
        Map<String,Set<Integer>> sequences=new HashMap<>();
        for(JSONObject op:ops.values()){
            JSONObject s=sessions.get(op.getString("session_id"));if(s==null||!"SEQUENTIAL".equals(s.getString("workflow")))fail("조업의 항차 연결 오류");
            if("ACTIVE".equals(op.getString("status")))fail("조업을 종료한 뒤 내보내세요.");
            int sequence=op.getInt("sequence_no");if(sequence<1||!sequences.computeIfAbsent(s.getString("id"),x->new HashSet<>()).add(sequence))fail("조업 회차 오류");
            String status=op.getString("status");
            if(!Arrays.asList("COMPLETED","INTERRUPTED").contains(status)||!status.equals(op.getString("stage")))fail("조업 상태 오류");
            if("COMPLETED".equals(status)&&op.isNull("ended_ms"))fail("조업 종료 시각 누락");
            if(!"COMPLETED".equals(status)&&!op.isNull("ended_ms"))fail("중단 조업에 종료 시각이 있음");
            if("COMPLETED".equals(s.getString("status"))&&!"COMPLETED".equals(status))fail("미완료 조업이 있는 항차의 입항 처리");
        }
        for(Set<Integer> set:sequences.values())for(int n=1;n<=set.size();n++)if(!set.contains(n))fail("조업 회차 누락");
        Map<String,int[]> voices=new HashMap<>();
        for(JSONObject r:recordings.values()){
            if(Arrays.asList("PREPARING","RECORDING").contains(r.getString("status")))fail("진행 중인 녹음");
            JSONObject s=sessions.get(r.getString("session_id"));if(s==null)fail("녹음의 항차 연결 누락");
            if(r.isNull("operation_id")){
                if(!"LEGACY".equals(r.getString("phase"))||!"LEGACY".equals(s.getString("workflow")))fail("녹음의 조업 연결 누락");
                continue;
            }
            JSONObject op=ops.get(r.getString("operation_id"));
            if(op==null||!op.getString("session_id").equals(r.getString("session_id")))fail("녹음의 조업 연결 오류");
            String phase=r.getString("phase");if(!Arrays.asList("START","NOTE","END","WORK").contains(phase))fail("녹음 종류 오류");
            if(gearOps.contains(op.getString("id"))!=phase.equals("WORK"))fail("기존 녹음과 자망·통발 작업 혼합 오류");
            if("COMPLETED".equals(r.getString("status"))){
                int[] count=voices.computeIfAbsent(op.getString("id"),x->new int[3]);
                if("START".equals(phase))count[0]++;if("END".equals(phase))count[1]++;if("WORK".equals(phase))count[2]++;
            }
        }
        for(JSONObject op:ops.values()){
            int[] count=voices.getOrDefault(op.getString("id"),new int[3]);
            if(gearOps.contains(op.getString("id"))){if("COMPLETED".equals(op.getString("status"))&&count[2]<1)fail("완료 작업의 녹음 누락");continue;}
            if(count[0]>1||count[1]>1)fail("시작·마무리 녹음 중복");
            if("COMPLETED".equals(op.getString("status"))&&(count[0]!=1||count[1]!=1))fail("완료 조업의 시작·마무리 녹음 누락");
        }
        for(String table:new String[]{"locations","events"}){
            JSONArray rows=data.getJSONArray(table);index(rows);
            for(int i=0;i<rows.length();i++){
                JSONObject r=rows.getJSONObject(i);String sid=r.optString("session_id");
                if(!r.isNull("session_id")&&!sessions.containsKey(sid))fail("이벤트/위치 항차 연결 오류");
                if(!r.isNull("operation_id")){
                    JSONObject op=ops.get(r.getString("operation_id"));if(op==null||!sid.equals(op.getString("session_id")))fail("이벤트/위치 조업 연결 오류");
                }
                if(!r.isNull("recording_id")){
                    JSONObject voice=recordings.get(r.getString("recording_id"));
                    if(voice==null||!sid.equals(voice.getString("session_id"))||!Objects.equals(r.opt("operation_id"),voice.opt("operation_id")))fail("이벤트/위치 녹음 연결 오류");
                }
            }
        }
    }
}
