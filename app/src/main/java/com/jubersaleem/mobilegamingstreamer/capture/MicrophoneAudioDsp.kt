package com.jubersaleem.mobilegamingstreamer.capture
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
internal class MicrophoneHighPass(sampleRate:Int,private val channels:Int,cutoffHz:Float=80f){
 private val alpha=exp((-2.0*PI*cutoffHz/sampleRate).toFloat());private val px=FloatArray(channels);private val py=FloatArray(channels)
 init{require(sampleRate>0&&(channels==1||channels==2));require(cutoffHz>0f&&cutoffHz<sampleRate/2f)}
 fun process(input:ByteArray,offset:Int=0,size:Int=input.size-offset):ByteArray{
  require(offset>=0&&size>=0&&offset+size<=input.size);val n=size-size%2;val out=ByteArray(n);var i=0
  while(i<n){val r=(input[offset+i].toInt()and 255)or(input[offset+i+1].toInt()shl 8);val x=r.toShort().toInt().toFloat();val c=(i/2)%channels
   val y=alpha*(py[c]+x-px[c]);px[c]=x;py[c]=y;val v=y.roundToInt().coerceIn(Short.MIN_VALUE.toInt(),Short.MAX_VALUE.toInt())
   out[i]=(v and 255).toByte();out[i+1]=((v shr 8)and 255).toByte();i+=2
  };return out
 }
}
internal object Pcm16AudioMixer{
 fun mix(microphone:ByteArray,game:ByteArray?,microphoneGain:Float=1f,gameGain:Float=1f):ByteArray{
  val ms=microphone.size-microphone.size%2;val gs=game?.size?.let{it-it%2}?:0;val out=ByteArray(maxOf(ms,gs));var i=0
  while(i<out.size){val m=if(i<ms)read(microphone,i)*microphoneGain else 0f;val g=if(game!=null&&i<gs)read(game,i)*gameGain else 0f;val sum=m+g;val a=abs(sum)
   val lim=if(a<=28000f)sum else(if(sum<0f)-1f else 1f)*(28000f+(a-28000f)*.22f).coerceAtMost(32400f)
   val v=lim.roundToInt().coerceIn(Short.MIN_VALUE.toInt(),Short.MAX_VALUE.toInt());out[i]=(v and 255).toByte();out[i+1]=((v shr 8)and 255).toByte();i+=2
  };return out
 }
 private fun read(d:ByteArray,i:Int):Float{val r=(d[i].toInt()and 255)or(d[i+1].toInt()shl 8);return r.toShort().toInt().toFloat()}
}
