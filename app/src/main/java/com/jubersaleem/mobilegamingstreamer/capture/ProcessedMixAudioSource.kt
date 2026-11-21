package com.jubersaleem.mobilegamingstreamer.capture
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioPlaybackCaptureConfiguration
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.util.Log
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.audio.MicrophoneManager
import com.pedro.encoder.input.sources.audio.AudioSource
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Independent AudioRecord inputs. Only the microphone session receives AEC/NS. */
class ProcessedMixAudioSource(
 private val projection: MediaProjection,
 private val includeGamePlayback: Boolean,
 private val microphoneAudioSource: Int = MediaRecorder.AudioSource.MIC
) : AudioSource() {
 private val micFrames=ArrayBlockingQueue<Frame>(4);private val gameFrames=ArrayBlockingQueue<Frame>(4)
 private var voiceChain:VoiceOptimizer?=null
 private var micManager:SafeMicrophoneManager?=null
 @Volatile private var preferredInput:AudioDeviceInfo?=null
 @Volatile private var output:GetMicrophoneData?=null
 @Volatile private var running=false
 @Volatile private var muted=false
 @Volatile private var micGain=1f
 @Volatile private var gameGain=1f
 private var mixerThread:Thread?=null
 private var aec:AcousticEchoCanceler?=null
 private var ns:NoiseSuppressor?=null
 private val micCallback=object:GetMicrophoneData{
  override fun inputPCMData(frame:Frame){
   val data=voiceChain?.process(frame.buffer,frame.offset,frame.size)?:frame.buffer.copyOfRange(frame.offset,frame.offset+frame.size)
   val clean=Frame(data,0,data.size,frame.timeStamp)
   if(includeGamePlayback)offerLatest(micFrames,clean)
   else output?.inputPCMData(Frame(if(muted)ByteArray(clean.size)else Pcm16AudioMixer.mix(clean.buffer,null,micGain,0f),0,clean.size,clean.timeStamp))
  }
 }
 private val gameManager=SafeMicrophoneManager(object:GetMicrophoneData{
  override fun inputPCMData(frame:Frame){val data=frame.buffer.copyOfRange(frame.offset,frame.offset+frame.size);offerLatest(gameFrames,Frame(data,0,data.size,frame.timeStamp))}
 })
 override fun create(sampleRate:Int,isStereo:Boolean,echoCanceler:Boolean,noiseSuppressor:Boolean):Boolean{
  // A second create() without an intervening release must never stack duplicate platform effects.
  releaseAudioEffects()
  voiceChain=VoiceOptimizer(sampleRate,if(isStereo)2 else 1,MIC_HIGHPASS_HZ)
  val accessibilityService = MgsMicAccessibilityService.instance
  micManager = accessibilityService?.createMicrophoneManager(micCallback) ?: SafeMicrophoneManager(micCallback)
  val mic = micManager ?: return false
  preferredInput?.let { mic.setPreferredDevice(it) }
  if(!mic.createMicrophone(microphoneAudioSource,sampleRate,isStereo,false,false)){
   Log.e(TAG,"Mic AudioRecord init failed source="+microphoneAudioSource+" accessibility="+(accessibilityService != null))
   releaseResources()
   return false
  }
  val sessionId=mic.microphoneSessionId()
  // On the built-in speaker route, the microphone must retain teammate voice played
  // acoustically by the phone. AEC/NS can classify that speaker output as echo/noise
  // and remove it, so keep the raw speaker path for the Glip-compatible route.
  val preserveSpeakerPlayback = accessibilityService != null && preferredInput == null
  if(sessionId!=null && !preserveSpeakerPlayback){
   if(echoCanceler){
    if(!AcousticEchoCanceler.isAvailable())Log.w(TAG,"Platform AEC requested but not available on this device")
    else{aec=runCatching{AcousticEchoCanceler.create(sessionId)?.apply{enabled=true}}.getOrNull()
     if(aec==null)Log.w(TAG,"Platform AEC attach failed for microphone session "+sessionId)}
   }
   if(noiseSuppressor){
    if(!NoiseSuppressor.isAvailable())Log.w(TAG,"Platform NS requested but not available on this device")
    else{ns=runCatching{NoiseSuppressor.create(sessionId)?.apply{enabled=true}}.getOrNull()
     if(ns==null)Log.w(TAG,"Platform NS attach failed for microphone session "+sessionId)}
   }
  }else if(sessionId!=null){
   Log.i(TAG,"Platform AEC/NS skipped on BGMI speaker route to preserve acoustic teammate voice")
  }
  if(includeGamePlayback){
   val cfg=AudioPlaybackCaptureConfiguration.Builder(projection).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
   if(!gameManager.createInternalMicrophone(cfg,sampleRate,isStereo,false,false)){Log.e(TAG,"Playback AudioRecord init failed");releaseResources();return false}
  }
  Log.i(TAG,"Split inputs ready rate="+sampleRate+" game="+includeGamePlayback+" micSource="+microphoneAudioSource+" accessibility="+(accessibilityService != null)+" preserveSpeaker="+preserveSpeakerPlayback+" AEC="+(aec?.enabled==true)+" NS="+(ns?.enabled==true)+" voiceChain=gate+agc+compressor+presence highpass="+MIC_HIGHPASS_HZ+"Hz playbackDSP=off")
  return true
 }
 /** Live effect state reported to the UI while broadcasting. */
 val aecAttached:Boolean get()=aec?.enabled==true
 val nsAttached:Boolean get()=ns?.enabled==true
 val voiceOptimizerActive:Boolean get()=voiceChain!=null
 fun setPreferredDevice(device:AudioDeviceInfo?):Boolean {
  preferredInput=device
  return micManager?.setPreferredDevice(device) ?: true
 }
 fun setMicrophoneGain(value:Float){micGain=value.coerceIn(0f,2f)}
 fun setPlaybackGain(value:Float){gameGain=value.coerceIn(0f,2f)}
 fun mute(){muted=true}
 fun unMute(){muted=false}
 override fun start(getMicrophoneData:GetMicrophoneData){
  output=getMicrophoneData;if(running)return;if(!created)throw IllegalStateException("Audio source not prepared");running=true
  try{if(includeGamePlayback){mixerThread=Thread({mixLoop()},"MGS-AudioMixer").apply{priority=Thread.NORM_PRIORITY;start()};gameManager.start()};micManager?.start()}
  catch(e:Exception){stop();throw IllegalStateException("Could not start split audio capture",e)}
 }
 /**
  * Mixer timing notes. Microphone and playback frames come from independent
  * AudioRecords on the same monotonic clock, but equal frame sizes do NOT imply
  * synchronization: the two recorders start at different times and drift
  * independently. This loop therefore never blocks one path on the other — the
  * microphone (bounded queue, newest wins) always flows, and the freshest
  * available playback frame is mixed in opportunistically, so a stalled or
  * stopped game path can never silence the microphone. Output frames carry the
  * newer of the two timestamps. Sample-accurate alignment is deliberately not
  * attempted: the inputs are uncorrelated captures, not an echo-reference pair.
  */
 private fun mixLoop(){while(running&&!Thread.currentThread().isInterrupted)try{
  val mic=micFrames.poll(250,TimeUnit.MILLISECONDS)?:continue;val game=gameFrames.poll(12,TimeUnit.MILLISECONDS)
  val data=if(muted)ByteArray(mic.size)else Pcm16AudioMixer.mix(mic.buffer,game?.buffer,micGain,gameGain)
  output?.inputPCMData(Frame(data,0,data.size,maxOf(mic.timeStamp,game?.timeStamp?:mic.timeStamp)))
 }catch(_:InterruptedException){Thread.currentThread().interrupt();break}catch(e:Exception){Log.e(TAG,"Mixer error",e)}}
 private fun offerLatest(q:ArrayBlockingQueue<Frame>,f:Frame){if(!q.offer(f)){q.poll();q.offer(f)}}
 override fun stop(){running=false;mixerThread?.interrupt();releaseResources();try{mixerThread?.join(300)}catch(_:InterruptedException){Thread.currentThread().interrupt()};mixerThread=null;micFrames.clear();gameFrames.clear();output=null}
 private fun releaseAudioEffects(){
  runCatching{aec?.enabled=false};runCatching{ns?.enabled=false};runCatching{aec?.release()};runCatching{ns?.release()};aec=null;ns=null
 }
 private fun releaseResources(){
  releaseAudioEffects();voiceChain=null
  micManager?.safeStop();micManager=null
  if(includeGamePlayback)gameManager.safeStop()
 }
 override fun isRunning():Boolean=running&&(micManager?.isRunning==true)&&(!includeGamePlayback||gameManager.isRunning)
 override fun release()=stop()
 companion object{private const val TAG="MGS-SplitAudio";const val MIC_HIGHPASS_HZ=80f}
}

/** RootEncoder's stop() assumes recording has started; prepare failures need a safe release path. */
internal class SafeMicrophoneManager(callback:GetMicrophoneData):MicrophoneManager(callback){
 fun microphoneSessionId():Int?=audioRecord?.audioSessionId
 fun safeStop(){if(isRunning)runCatching{super.stop()}else{
  runCatching{audioRecord?.release()};audioRecord=null
  runCatching{audioRecordDevice?.release()};audioRecordDevice=null
 }}
}
