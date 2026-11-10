package com.pleoscontest.app.voice

import com.pleoscontest.app.BuildConfig

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Locale

/**
 * 교대 안내를 소리로 낸다. 서버 모드는 Playground에서 "준비 중"이라 앱 등록이 없어도 되는 ON_DEVICE만 쓴다.
 * Pleos 음성 서비스가 없는 기기(폰, 구글 AAOS)에서는 로그만 남기고 조용히 넘어간다.
 * 에뮬레이터에서 말하기 끝(onDone) 이벤트가 오지 않으므로 끝나기를 기다리는 흐름은 만들지 않는다.
 */
class VoiceGuide(context: Context) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    /** 엔진이 준비되기 전에 온 안내. 앱을 막 켰을 때 여러 개가 겹쳐도 차례로 말한다. */
    private val pending = mutableListOf<String>()

    init {
        if (isAvailable()) {
            val engine = TextToSpeech(appContext, Mode.ON_DEVICE)
            tts = engine
            engine.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    engine.setLocale(Locale.KOREA)
                    synchronized(pending) {
                        ready = true
                        pending.takeLast(MAX_PENDING).forEach { engine.speak(it, false) }
                        pending.clear()
                    }
                }

                override fun onFailure(error: ErrorType) {
                    Log.w(TAG, "음성 초기화 실패: $error")
                }
            })
        }
    }

    fun speak(text: String) {
        // 운전자 이름이 들어 있어 출시 빌드에서는 남기지 않는다.
        if (BuildConfig.DEBUG) Log.i(TAG, text)
        val engine = tts ?: return
        synchronized(pending) {
            if (ready) engine.speak(text, false) else pending += text
        }
    }

    fun release() {
        tts?.release()
        tts = null
    }

    private fun isAvailable(): Boolean =
        appContext.packageManager.queryIntentServices(Intent(SPEECH_ACTION), 0).isNotEmpty()

    private companion object {
        const val TAG = "VoiceGuide"
        const val SPEECH_ACTION = "ai.pleos.playground.caas.intent.action.SPEECH_START"
        /** 준비가 아주 늦으면 지난 안내는 버리고 최근 몇 개만 말한다. */
        const val MAX_PENDING = 3
    }
}
