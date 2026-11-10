package com.pleoscontest.app.voice

import ai.pleos.playground.stt.SpeechToText
import com.pleoscontest.app.BuildConfig
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/**
 * 운전자의 짧은 응답을 듣는다. 앱 등록이 필요 없는 ON_DEVICE만 쓴다. 음성 서비스가 없는 기기에서는 아무것도 하지 않는다.
 * 에뮬레이터에서 인식 끝(onEndedRecognition)이 최종 결과보다 먼저 왔으므로, 끝은 최종 결과로 판단한다.
 */
class VoiceListener(context: Context, private val onHeard: (String) -> Unit) : ResultListener {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var stt: SpeechToText? = null
    private var ready = false
    private val audio = appContext.getSystemService(AudioManager::class.java)
    // 듣는 동안 내비 음성 안내를 잠시 멈춘다. 내비 소리를 운전자의 대답으로 듣지 않게 한다.
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        .build()

    init {
        if (appContext.packageManager.queryIntentServices(Intent(SPEECH_ACTION), 0).isNotEmpty()) {
            val engine = SpeechToText(appContext, Mode.ON_DEVICE)
            stt = engine
            engine.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    // TTS와 마찬가지로 초기화가 끝난 뒤에 리스너를 붙인다.
                    engine.addListener(this@VoiceListener)
                    engine.setLocale(Locale.KOREA)
                    ready = true
                }

                override fun onFailure(error: ErrorType) {
                    Log.w(TAG, "음성 인식 초기화 실패: $error")
                }
            })
        }
    }

    /** 차량 마이크로 듣기 시작한다. */
    fun listen() {
        if (!ready) return
        val granted = audio.requestAudioFocus(focus)
        Log.i(TAG, "듣기 오디오 포커스: ${if (granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "받음" else "못 받음($granted)"}")
        stt?.request()
    }

    fun stop() {
        if (!ready) return
        stt?.stop()
        audio.abandonAudioFocusRequest(focus)
    }

    /** 마이크 대신 녹음(16kHz 16bit mono PCM)을 넣는다. 에뮬레이터 시험과 데모 영상에 쓴다. */
    fun feed(pcm: ByteArray) {
        val engine = stt ?: return
        if (!ready) return
        var offset = 0
        val send = object : Runnable {
            override fun run() {
                if (offset >= pcm.size) {
                    engine.completeAudioSend()
                    return
                }
                val end = minOf(offset + BYTES_PER_100_MS, pcm.size)
                engine.sendAudio(pcm.copyOfRange(offset, end))
                offset = end
                handler.postDelayed(this, 100)
            }
        }
        send.run()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        stt?.let {
            if (ready) it.removeListener(this)
            it.release()
        }
        stt = null
    }

    override fun onUpdated(text: String, isFinal: Boolean) {
        // 운전자가 한 말이라 출시 빌드에서는 남기지 않는다.
        if (BuildConfig.DEBUG) Log.i(TAG, "들음(final=$isFinal): $text")
        if (isFinal) onHeard(text)
    }

    override fun onError(error: ErrorType) {
        Log.w(TAG, "음성 인식 오류: $error")
    }

    override fun onUpdatedEpdData(start: Long, end: Long) {}
    override fun onStartedRecognition() {}
    override fun onEndedRecognition() {}
    override fun onReady() {}
    override fun onUpdatedRms(rms: Double) {}

    private companion object {
        const val TAG = "VoiceListener"
        const val SPEECH_ACTION = "ai.pleos.playground.caas.intent.action.SPEECH_START"
        const val BYTES_PER_100_MS = 3_200
    }
}
