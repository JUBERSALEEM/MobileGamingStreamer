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
 @Test fun noiseGatePassesSpeechLevel(){val g=VoiceNoiseGate(48000,1);val y=g.process(sine(8000,1000.0,9600));assertTrue(peakOf(y)>7000)}
 @Test fun noiseGateAttenuatesQuietBackground(){val g=VoiceNoiseGate(48000,1);val y=g.process(pcm(50,9600));assertTrue(peakOf(y)<20)}
 @Test fun noiseGateClosesGraduallyAfterSpeech(){val g=VoiceNoiseGate(48000,1);g.process(pcm(8000,4800));val y=g.process(pcm(60,48000));assertTrue(peakOf(y,0,960)>40);assertTrue(peakOf(y,y.size-960,y.size)<20)}
 @Test fun autoGainBoostsQuietSpeech(){val a=VoiceAutoGain(48000,1);val x=sine(1163,1000.0,48000);var y=x;repeat(4){y=a.process(x)};val ratio=rmsOf(y)/rmsOf(x);assertTrue("$ratio",ratio in 3.0..4.2);assertTrue(peakOf(y)<9000)}
 @Test fun autoGainIgnoresSilence(){val a=VoiceAutoGain(48000,1);assertEquals(0,peakOf(a.process(ByteArray(48000))))}
 @Test fun autoGainLeavesLoudSpeechNearUnity(){val a=VoiceAutoGain(48000,1);val x=sine(20000,1000.0,48000);var y=x;repeat(3){y=a.process(x)};assertTrue(peakOf(y) in 12000..16000)}
 @Test fun compressorTamesLoudPeaks(){val c=VoiceCompressor(48000,1);val y=c.process(pcm(16384,9600));assertTrue(peakOf(y,4800,y.size) in 5900..7200)}
 @Test fun compressorLeavesQuietSpeechUntouched(){val c=VoiceCompressor(48000,1);assertEquals(1024,peakOf(c.process(pcm(1024,4800))))}
 @Test fun presenceEqBoostsClarityBand(){val e=VoicePresenceEq(48000,1);val x=sine(6000,3200.0,14400);val y=e.process(x);val ratio=peakOf(y,9600,y.size).toDouble()/peakOf(x,9600,x.size);assertTrue("$ratio",ratio in 1.32..1.50)}
 @Test fun presenceEqLeavesLowSpeechUntouched(){val e=VoicePresenceEq(48000,1);val x=sine(6000,300.0,14400);val y=e.process(x);val ratio=peakOf(y,9600,y.size).toDouble()/peakOf(x,9600,x.size);assertTrue("$ratio",ratio in 0.94..1.06)}
 @Test fun optimizerChainProducesHealthySpeechLevel(){val o=VoiceOptimizer(48000,1);val x=sine(4000,800.0,48000);var y=x;repeat(3){y=o.process(x)};val rms=rmsOf(y);assertTrue("$rms",rms in 0.05..0.15)}
 @Test fun optimizerChainHandlesStereo(){val o=VoiceOptimizer(48000,2);val x=ByteArray(9600);for(f in 0 until 2400){val l=(sin(2.0*PI*800*f/48000)*4000).toInt();val r=(sin(2.0*PI*800*f/48000)*2000).toInt();x[f*4]=l.toByte();x[f*4+1]=(l shr 8).toByte();x[f*4+2]=r.toByte();x[f*4+3]=(r shr 8).toByte()};val y=o.process(x);assertEquals(x.size,y.size);assertTrue(peakOf(y)>1000)}
 private fun pcm(v:Int,n:Int):ByteArray{val a=ByteArray(n);for(i in a.indices step 2){a[i]=v.toByte();a[i+1]=(v shr 8).toByte()};return a}
 private fun sine(peak:Int,freqHz:Double,samples:Int,rate:Int=48000):ByteArray{val a=ByteArray(samples*2);for(i in 0 until samples){val v=(sin(2.0*PI*freqHz*i/rate)*peak).toInt();a[i*2]=v.toByte();a[i*2+1]=(v shr 8).toByte()};return a}
 private fun peakOf(a:ByteArray,from:Int=0,until:Int=a.size):Int{var p=0;var i=from-(from%2);while(i<until){p=maxOf(p,abs(sample(a,i)));i+=2};return p}
 private fun rmsOf(a:ByteArray):Double{var s=0.0;var n=0;var i=0;while(i<a.size){val v=sample(a,i)/32768.0;s+=v*v;n++;i+=2};return kotlin.math.sqrt(s/n)}
 private fun sample(a:ByteArray,i:Int):Int{val r=(a[i].toInt()and 255)or(a[i+1].toInt()shl 8);return r.toShort().toInt()}
}
