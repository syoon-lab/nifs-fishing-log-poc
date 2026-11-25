#include <jni.h>
#include <whisper.h>
#include <atomic>
#include <fstream>
#include <sstream>
#include <vector>
#include <memory>
#include <stdexcept>
#include <chrono>
static std::atomic<bool> cancelled{false};
static std::atomic<int> progress{0};
static std::string utf(JNIEnv *env, jstring s) {
    const char *p=env->GetStringUTFChars(s,nullptr);if(!p)throw std::runtime_error("Path allocation failed");
    std::string v(p);env->ReleaseStringUTFChars(s,p);return v;
}
static std::string quote(const char *text) {
    std::string s="\"";for(const unsigned char *p=(const unsigned char*)text;*p;p++) {
        if(*p=='"'||*p=='\\'){s+='\\';s+=(char)*p;}
        else if(*p<32){char b[7];snprintf(b,sizeof(b),"\\u%04x",*p);s+=b;}
        else s+=(char)*p;
    }return s+'"';
}
extern "C" JNIEXPORT void JNICALL Java_kr_go_nifs_fieldrecorder_OfflineAsr_reset(JNIEnv*,jclass){cancelled=false;progress=0;}
extern "C" JNIEXPORT void JNICALL Java_kr_go_nifs_fieldrecorder_OfflineAsr_cancel(JNIEnv*,jclass){cancelled=true;}
extern "C" JNIEXPORT jint JNICALL Java_kr_go_nifs_fieldrecorder_OfflineAsr_progress(JNIEnv*,jclass){return progress.load();}
extern "C" JNIEXPORT jbyteArray JNICALL Java_kr_go_nifs_fieldrecorder_OfflineAsr_recognize(JNIEnv *env,jclass,jstring model,jstring pcm,jint threads,jint beam,jstring hint,jint audioContext) {
    try {
        std::ifstream file(utf(env,pcm),std::ios::binary|std::ios::ate);
        auto size=file.tellg();if(size<=0||size>1800LL*16000*4||size%4!=0)throw std::runtime_error("Invalid PCM length (maximum 30 minutes)");
        std::vector<float> samples((size_t)size/4);file.seekg(0);file.read((char*)samples.data(),size);if(!file)throw std::runtime_error("PCM read failed");
        if(cancelled)throw std::runtime_error("Cancelled");
        if(threads<1||threads>16||beam<1||beam>8||audioContext<0||audioContext>1500)throw std::runtime_error("Invalid decoder settings");
        if(audioContext>0 && samples.size()>(size_t)audioContext*320)throw std::runtime_error("Audio context would truncate recording");
        auto load_begin=std::chrono::steady_clock::now();
        auto cp=whisper_context_default_params();cp.use_gpu=false;cp.flash_attn=true;
        std::unique_ptr<whisper_context,decltype(&whisper_free)> ctx(whisper_init_from_file_with_params(utf(env,model).c_str(),cp),whisper_free);
        if(!ctx)throw std::runtime_error("Model initialization failed");
        auto loaded=std::chrono::steady_clock::now();
        std::string prompt=hint?utf(env,hint):"";
        auto p=whisper_full_default_params(beam>1?WHISPER_SAMPLING_BEAM_SEARCH:WHISPER_SAMPLING_GREEDY);
        p.beam_search.beam_size=beam;p.initial_prompt=prompt.empty()?nullptr:prompt.c_str();
        p.audio_ctx=audioContext;
        p.n_threads=threads;p.language="ko";p.translate=false;p.no_context=true;
        p.print_realtime=false;p.print_progress=false;p.print_timestamps=false;p.print_special=false;
        p.suppress_nst=true;p.temperature=0;p.temperature_inc=0;p.greedy.best_of=1;
        p.abort_callback=[](void*){return cancelled.load();};
        p.progress_callback=[](whisper_context*,whisper_state*,int value,void*){progress=value;};
        if(whisper_full(ctx.get(),p,samples.data(),(int)samples.size())!=0||cancelled)throw std::runtime_error(cancelled?"Cancelled":"Inference failed");
        auto inferred=std::chrono::steady_clock::now();
        std::ostringstream out;out<<"{\"segments\":[";std::string full;
        int n=whisper_full_n_segments(ctx.get());
        for(int i=0;i<n;i++) {
            const char *t=whisper_full_get_segment_text(ctx.get(),i);full+=t;
            if(i)out<<',';
            out<<"{\"start_ms\":"<<whisper_full_get_segment_t0(ctx.get(),i)*10<<",\"end_ms\":"<<whisper_full_get_segment_t1(ctx.get(),i)*10<<",\"text\":"<<quote(t)<<"}";
        }
        out<<"],\"text\":"<<quote(full.c_str())<<",\"duration_ms\":"<<samples.size()/16<<",\"audio_ctx\":"<<audioContext<<",\"load_ms\":"<<std::chrono::duration_cast<std::chrono::milliseconds>(loaded-load_begin).count()<<",\"infer_ms\":"<<std::chrono::duration_cast<std::chrono::milliseconds>(inferred-loaded).count()<<"}";
        auto s=out.str();auto result=env->NewByteArray((jsize)s.size());if(result)env->SetByteArrayRegion(result,0,(jsize)s.size(),(const jbyte*)s.data());return result;
    }catch(const std::exception &e){env->ThrowNew(env->FindClass("java/io/IOException"),e.what());return nullptr;}
}
