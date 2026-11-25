package kr.go.nifs.fieldrecorder;

import android.media.*;
import android.os.SystemClock;
import java.io.*;
import java.nio.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Streaming AAC/PCM decode and linear resampling: bounded Java heap even for 30 minute recordings. */
final class AudioDecoder {
    static long decode(File source,File destination,BooleanSupplier stopped) throws Exception {
        MediaExtractor extractor=new MediaExtractor();MediaCodec codec=null;
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(destination),65536))) {
            extractor.setDataSource(source.getAbsolutePath());int track=-1;MediaFormat format=null;
            for(int i=0;i<extractor.getTrackCount();i++){MediaFormat f=extractor.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/")){if(track>=0)throw new IOException("여러 오디오 트랙은 지원하지 않습니다.");track=i;format=f;}}
            if(track<0)throw new IOException("오디오 트랙이 없습니다.");extractor.selectTrack(track);
            if(format.containsKey(MediaFormat.KEY_DURATION)&&format.getLong(MediaFormat.KEY_DURATION)>1_801_000_000L)throw new IOException("한 번에 30분까지 전사할 수 있습니다.");
            codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));codec.configure(format,null,null,0);codec.start();
            int rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),encoding=AudioFormat.ENCODING_PCM_16BIT;
            long frames=0,written=0,lastProgress=SystemClock.elapsedRealtime();float previous=0;boolean inputDone=false,outputDone=false;
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while(!outputDone){
                if(stopped.getAsBoolean())throw new CancellationException("전사 중지");
                if(SystemClock.elapsedRealtime()-lastProgress>30_000)throw new IOException("음성 변환 응답 지연");
                if(!inputDone){int index=codec.dequeueInputBuffer(10000);if(index>=0){ByteBuffer buffer=codec.getInputBuffer(index);int size=extractor.readSampleData(buffer,0);if(size<0){codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{codec.queueInputBuffer(index,0,size,extractor.getSampleTime(),0);extractor.advance();}lastProgress=SystemClock.elapsedRealtime();}}
                int index=codec.dequeueOutputBuffer(info,10000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    MediaFormat f=codec.getOutputFormat();int newRate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE),newChannels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if(frames>0&&(rate!=newRate||channels!=newChannels))throw new IOException("중간에 음성 형식이 변경되었습니다.");
                    rate=newRate;channels=newChannels;encoding=f.containsKey(MediaFormat.KEY_PCM_ENCODING)?f.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
                    if(rate<8000||rate>192000||channels<1||channels>8||!(encoding==AudioFormat.ENCODING_PCM_16BIT||encoding==AudioFormat.ENCODING_PCM_FLOAT))throw new IOException("지원하지 않는 음성 형식");
                }else if(index>=0){
                    ByteBuffer b=codec.getOutputBuffer(index).order(ByteOrder.LITTLE_ENDIAN);b.position(info.offset);b.limit(info.offset+info.size);
                    int bytes=encoding==AudioFormat.ENCODING_PCM_FLOAT?4:2;
                    if(info.size%(channels*bytes)!=0)throw new IOException("불완전한 음성 프레임");
                    while(b.remaining()>=channels*bytes){
                        float value=0;for(int c=0;c<channels;c++)value+=encoding==AudioFormat.ENCODING_PCM_FLOAT?b.getFloat():b.getShort()/32768f;value/=channels;
                        if(!Float.isFinite(value))throw new IOException("유효하지 않은 음성 값");
                        while(written*(double)rate/16000<=frames){
                            if(written>=1800L*16000)throw new IOException("한 번에 30분까지 전사할 수 있습니다.");
                            double at=written*(double)rate/16000;float sample=frames==0?value:(float)(previous+(value-previous)*(at-(frames-1)));
                            out.writeInt(Integer.reverseBytes(Float.floatToIntBits(sample)));written++;
                        }
                        previous=value;frames++;
                    }
                    outputDone=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(index,false);lastProgress=SystemClock.elapsedRealtime();
                }
            }
            if(written<1600)throw new IOException("전사할 음성이 너무 짧습니다.");return written;
        }finally{if(codec!=null){try{codec.stop();}finally{codec.release();}}extractor.release();}
    }
}
