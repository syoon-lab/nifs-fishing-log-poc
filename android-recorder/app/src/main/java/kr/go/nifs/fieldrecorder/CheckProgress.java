package kr.go.nifs.fieldrecorder;

import org.json.*;

/** Derived display state only: no writes to audio, recognition results, or reviews. */
final class CheckProgress {
    final String title,detail;
    final boolean busy;
    CheckProgress(String title,String detail,boolean busy){this.title=title;this.detail=detail;this.busy=busy;}
    static boolean pending(Store db,String op,String runningId,String startingId) throws JSONException {
        for(String id:new String[]{runningId,startingId})if(id!=null&&!id.isEmpty()&&op.equals(db.row("recordings",id).optString("operation_id")))return true;
        for(String phase:FieldChecks.phases(db,op)){FieldChecks.Check c=FieldChecks.assess(db,op,phase);if(c.pending&&!c.reviewed)return true;}
        return false;
    }
    static CheckProgress read(Store db,String op,String runningId,String startingId) throws JSONException {
        String id=runningId!=null&&!runningId.isEmpty()?runningId:startingId;
        if(id!=null&&!id.isEmpty())return checking();
        if(op==null)return null;
        JSONObject request=FieldChecks.event(db,FieldChecks.REQUEST,null,op,null);
        if(request==null)return null;
        if(pending(db,op,"",""))return checking();
        String phase=new JSONObject(request.getString("detail")).getString("phase");
        FieldChecks.Check c=FieldChecks.assess(db,op,phase);
        JSONObject attempt=db.latest("transcriptions","recording_id",request.getString("recording_id"));
        if(!c.reviewed&&(attempt==null||!"COMPLETED".equals(attempt.optString("status"))))return new CheckProgress("확인을 마치지\n못했어요","녹음은 저장됨 · 기록 확인을 눌러 주세요",false);
        return c.issues.length()>0?new CheckProgress("확인할 항목이 있어요","아래 ‘기록 확인’을 눌러 주세요",false):new CheckProgress("필수 항목\n확인 완료","녹음은 저장되어 있습니다",false);
    }
    static CheckProgress checking(){return new CheckProgress("녹음 내용\n확인 중","끝나면 진동으로 알려드려요",true);}
}
