package com.jubersaleem.mobilegamingstreamer.capture
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioPlaybackCaptureConfiguration
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.audio.MicrophoneManager
import com.pedro.encoder.input.sources.audio.AudioSource
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
/** Separate microphone and playback AudioRecord sessions; AEC/NS are applied to mic only. */
class ProcessedMixAudioSource(private val projection:MediaProjection,private val includeGamePlayback:Boolean):AudioSource(){
 private val micFrames=ArrayBlockingQueue<Frame>(4);private val gameFrames=ArrayBlockingQueue<Frame>(4)
 private var highPass:MicrophoneHighPass?=null
 @Volatile private var output:GetMicrophoneData?=null
 @Volatile private var running=false
 @Volatile private var muted=false
 @Volatile private var micGain=1f
 @Volatile private var gameGain=1f
 private var mixerThread:Thread?=null
 private val micManager=MicrophoneManager(object:GetMicrophoneData{
  override fun inputPCMData(frame:Frame){
   val data=highPass?.process(frame.buffer,frame.offset,frame.size)?:frame.buffer.copyOfRange(frame.offset,frame.offset+frame.size)
   val clean=Frame(data,0,data.size,frame.timeStamp)
   if(includeGamePlayback)offerLatest(micFrames,clean)
   else output?.inputPCMData(Frame(if(muted)ByteArray(clean.size)else Pcm16AudioMixer.mix(clean.buffer,null,micGain,0f),0,clean.size,clean.timeStamp))
  }
 })
 private val gameManager=MicrophoneManager(object:GetMicrophoneData{
  override fun inputPCMData(frame:Frame){val data=frame.buffer.copyOfRange(frame.offset,frame.offset+frame.size);offerLatest(gameFrames,Frame(data,0,data.size,frame.timeStamp))}
 })
 override fun create(sampleRate:Int,isStereo:Boolean,echoCanceler:Boolean,noiseSuppressor:Boolean):Boolean{
  highPass=MicrophoneHighPass(sampleRate,if(isStereo)2 else 1,MIC_HIGHPASS_HZ)
  if(!micManager.createMicrophone(MediaRecorder.AudioSource.DEFAULT,sampleRate,isStereo,echoCanceler,noiseSuppressor)){Log.e(TAG,"Mic AudioRecord init failed");return false}
  if(includeGamePlayback){
   val cfg=AudioPlaybackCaptureConfiguration.Builder(projection).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
   if(!gameManager.createInternalMicrophone(cfg,sampleRate,isStereo,false,false)){Log.e(TAG,"Playback AudioRecord init failed");return false}
  }
  Log.i(TAG,"Split inputs ready rate="+sampleRate+" game="+includeGamePlayback+" micAEC="+echoCanceler+" micNS="+noiseSuppressor+" highpass="+MIC_HIGHPASS_HZ+"Hz playbackDSP=off")
  return true
 }
 fun setPreferredDevice(device:AudioDeviceInfo?):Boolean=micManager.setPreferredDevice(device)
 fun setMicrophoneGain(value:Float){micGain=value.coerceIn(0f,2f)}
 fun setPlaybackGain(value:Float){gameGain=value.coerceIn(0f,2f)}
 fun mute(){muted=true}
 fun unMute(){muted=false}
 override fun start(getMicrophoneData:GetMicrophoneData){
  output=getMicrophoneData;if(running)return;if(!created)throw IllegalStateException("Audio source not prepared");running=true
  try{if(includeGamePlayback){mixerThread=Thread({mixLoop()},"MGS-AudioMixer").apply{priority=Thread.NORM_PRIORITY;start()};gameManager.start()};micManager.start()}
  catch(e:Exception){stop();throw IllegalStateException("Could not start split audio capture",e)}
 }
 private fun mixLoop(){while(running&&!Thread.currentThread().isInterrupted)try{
  val mic=micFrames.poll(250,TimeUnit.MILLISECONDS)?:continue;val game=gameFrames.poll(12,TimeUnit.MILLISECONDS)
  val data=if(muted)ByteArray(mic.size)else Pcm16AudioMixer.mix(mic.buffer,game?.buffer,micGain,gameGain)
  output?.inputPCMData(Frame(data,0,data.size,maxOf(mic.timeStamp,game?.timeStamp?:mic.timeStamp)))
 }catch(_:InterruptedException){Thread.currentThread().interrupt();break}catch(e:Exception){Log.e(TAG,"Mixer error",e)}}
 private fun offerLatest(q:ArrayBlockingQueue<Frame>,f:Frame){if(!q.offer(f)){q.poll();q.offer(f)}}
 override fun stop(){running=false;mixerThread?.interrupt();runCatching{micManager.stop()}.onFailure{Log.w(TAG,"Mic release",it)}
  if(includeGamePlayback)runCatching{gameManager.stop()}.onFailure{Log.w(TAG,"Playback release",it)}
  try{mixerThread?.join(300)}catch(_:InterruptedException){Thread.currentThread().interrupt()};mixerThread=null;micFrames.clear();gameFrames.clear();output=null}
 override fun isRunning():Boolean=running&&micManager.isRunning&&(!includeGamePlayback||gameManager.isRunning)
 override fun release()=stop()
 companion object{private const val TAG="MGS-SplitAudio";const val MIC_HIGHPASS_HZ=80f}
}
