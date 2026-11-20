package com.jubersaleem.mobilegamingstreamer.capture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
class MicrophoneAudioDspTest{
 @Test fun highPassRejectsLowFrequencyOffset(){val f=MicrophoneHighPass(48000,1);val x=pcm(10000,4096);var y=ByteArray(0);repeat(5){y=f.process(x)};assertTrue(abs(sample(y,y.size-2))<100)}
 @Test fun highPassPreservesSpeechBand(){val f=MicrophoneHighPass(48000,1);val x=ByteArray(8192);for(i in 0 until 4096){val v=(sin(2.0*PI*1000*i/48000)*8000).toInt();x[i*2]=v.toByte();x[i*2+1]=(v shr 8).toByte()};val y=f.process(x);assertTrue((0 until y.size step 2).maxOf{abs(sample(y,it))}>7000)}
 @Test fun mixerProtectsHeadroom(){val y=Pcm16AudioMixer.mix(pcm(28000,128),pcm(28000,128));val p=(0 until y.size step 2).maxOf{abs(sample(y,it))};assertTrue(p in 28000..32400)}
 @Test fun micContinuesWithoutPlayback(){assertEquals(5000,sample(Pcm16AudioMixer.mix(pcm(5000,64),null),0))}
 @Test fun shorterPlaybackDoesNotTruncateMic(){val m=pcm(5000,128);val y=Pcm16AudioMixer.mix(m,pcm(2000,32));assertEquals(m.size,y.size);assertEquals(5000,sample(y,100))}
 private fun pcm(v:Int,n:Int):ByteArray{val a=ByteArray(n);for(i in a.indices step 2){a[i]=v.toByte();a[i+1]=(v shr 8).toByte()};return a}
 private fun sample(a:ByteArray,i:Int):Int{val r=(a[i].toInt()and 255)or(a[i+1].toInt()shl 8);return r.toShort().toInt()}
}
