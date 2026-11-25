package kr.go.nifs.fieldrecorder;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CancellationException;

/** No network API or permission. The same bundled model is used for every recording. */
public final class OfflineAsr {
    static { System.loadLibrary("field_asr"); }
    public static final String MODEL="ggml-large-v3-turbo-q5_0.bin";
    public static final String SHA="394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2";
    public static final String ENGINE_BASE="whisper.cpp/1.8.7@48f628a;cpu;ko;beam5;temperature0;no_context;fishery-numbers-v3";
    public static final String ENGINE=ENGINE_BASE+";audio_ctx=short-pad128-min512-v2;quality-retry-v1;bounded-segments-v1";
    static final String HINT="어선의 조업 기록입니다. 어종: 고등어, 갈치, 멸치, 참조기, 꽃게, 대게, 홍게, 오징어, 문어, 낙지, 대구, 전갱이, 삼치, 방어, 넙치, 새우. 단위: 마리, 킬로그램, 그램, 상자, 개, 폭. 통발, 자망, 연승, 회수, 투망, 작업 인원. 수량: 한, 두, 세, 네, 다섯, 여섯, 일곱, 여덟, 아홉, 열, 스무, 서른, 마흔, 쉰, 예순.";
    static final String GEAR_HINT="자망과 통발의 양망 기록입니다. 투망일, 설치일, 양망, 회수, 재투망, 재설치, 최종 철거. 자망은 폭, 통발은 개. 어종: 꽃게, 문어, 고등어, 갈치, 대게, 홍게, 오징어, 낙지, 참조기, 대구, 전갱이, 새우. 마리, 킬로그램. 수량: 한, 두, 세, 네, 열, 스무, 서른, 마흔, 쉰, 예순.";
    static JSONObject transcribeGear(Context context,File audio,java.util.function.BooleanSupplier stopped) throws Exception {
        return transcribeConfigured(context,audio,stopped,Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()-1)),5,GEAR_HINT,-1);
    }
    public static native void reset();
    public static native void cancel();
    public static native int progress();
    private static native byte[] recognize(String model,String pcm,int threads,int beam,String hint,int audioContext) throws IOException;
    private static boolean matches(File file) throws Exception {
        if(!file.isFile())return false;
        try(InputStream in=new BufferedInputStream(new FileInputStream(file))){return SHA.equals(Archive.sha256(in));}
    }
    static File model(Context context) throws Exception {
        File dir=new File(context.getFilesDir(),"asr-model");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("전사 모델 폴더 생성 실패");
        File target=new File(dir,MODEL);
        if(matches(target))return target;
        File temp=new File(dir,MODEL+".part");
        try(InputStream in=context.getAssets().open("models/"+MODEL);OutputStream out=new BufferedOutputStream(new FileOutputStream(temp))){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
        if(!matches(temp)){temp.delete();throw new IOException("전사 모델 검증 실패");}
        Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);return target;
    }
    public static JSONObject transcribe(Context context,File audio,java.util.function.BooleanSupplier stopped) throws Exception {
        return transcribeConfigured(context,audio,stopped,Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()-1)),5,HINT,-1);
    }
    static JSONObject transcribeVariant(Context context,File audio,java.util.function.BooleanSupplier stopped,int beam,String hint) throws Exception {
        return transcribeConfigured(context,audio,stopped,Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()-1)),beam,hint,0);
    }
    // 50 encoder frames/second, plus 2.56s padding; never shrink below the complete audio.
    static int shortAudioContext(long samples) {
        if(samples<=0)throw new IllegalArgumentException("Empty audio");
        long required=(samples+319)/320+128;
        long rounded=Math.max(512,((required+255)/256)*256);
        return rounded>=1500?0:(int)rounded; // full 30s windows for longer recordings
    }
    // Keep decoder timestamps as evidence when padding makes them exceed the source.
    static void boundSegments(JSONObject result) throws org.json.JSONException {
        long duration=result.getLong("duration_ms"),previous=0;
        if(duration<0)throw new IllegalArgumentException("Negative audio duration");
        org.json.JSONArray segments=result.getJSONArray("segments");
        for(int i=0;i<segments.length();i++) {
            JSONObject segment=segments.getJSONObject(i);
            long rawStart=segment.getLong("start_ms"),rawEnd=segment.getLong("end_ms");
            long start=Math.max(previous,Math.min(duration,Math.max(0,rawStart)));
            long end=Math.max(start,Math.min(duration,Math.max(0,rawEnd)));
            if(start!=rawStart||end!=rawEnd)segment.put("decoder_start_ms",rawStart).put("decoder_end_ms",rawEnd).put("timing_adjusted",true);
            segment.put("start_ms",start).put("end_ms",end);previous=end;
        }
    }
    static JSONObject transcribeConfigured(Context context,File audio,java.util.function.BooleanSupplier stopped,int threads,int beam,String hint,int audioContext) throws Exception {
        File pcm=File.createTempFile("asr-",".f32",context.getCacheDir());
        try {
            long began=android.os.SystemClock.elapsedRealtime();
            File model=model(context);long loaded=android.os.SystemClock.elapsedRealtime();
            if(stopped.getAsBoolean())throw new CancellationException("전사 중지");
            AudioDecoder.decode(audio,pcm,stopped);
            if(stopped.getAsBoolean())throw new CancellationException("전사 중지");
            long decoded=android.os.SystemClock.elapsedRealtime();
            if(audioContext==-1)audioContext=shortAudioContext(pcm.length()/4);
            JSONObject result=new JSONObject(new String(recognize(model.getAbsolutePath(),pcm.getAbsolutePath(),threads,beam,hint,audioContext),StandardCharsets.UTF_8));
            boundSegments(result);
            result.put("model_ms",loaded-began).put("decode_ms",decoded-loaded).put("native_ms",android.os.SystemClock.elapsedRealtime()-decoded);
            return result;
        }finally{pcm.delete();}
    }
}
