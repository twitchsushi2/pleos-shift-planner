package com.pleoscontest.app

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import java.util.Locale

/**
 * Pleos TTS가 앱 등록 없이(ON_DEVICE) 한국어로 말하는지 확인하는 디버그 전용 화면.
 *   adb shell am start -n com.pleoscontest.app/.VoiceCheckActivity --es mode ON_DEVICE
 * 결과는 logcat의 VoiceCheck 태그로 본다.
 */
class VoiceCheckActivity : Activity() {
    private lateinit var tts: TextToSpeech
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 32f; setPadding(48, 48, 48, 48) }
        setContentView(status)

        val mode = Mode.valueOf(intent.getStringExtra("mode") ?: "ON_DEVICE")
        report("모드 $mode, SDK ${TextToSpeech.version} 초기화 중")
        tts = TextToSpeech(applicationContext, mode)
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                report("초기화 성공")
                // SDK가 초기화 전에 리스너를 받으면 죽는다(3.3.1). 반드시 onSuccess 뒤에 붙인다.
                tts.addEventListener(object : EventListener {
                    override fun onStart() = report("말하기 시작")
                    override fun onReady() = report("준비됨")
                    override fun onDone() = report("말하기 끝")
                    override fun onError(error: ErrorType) = report("말하기 오류: $error")
                    override fun onStop() = report("중지")
                    override fun onUpdatedRms(rms: Double) {}
                })
                tts.setLocale(Locale.KOREA)
                tts.speak("다음 교대는 행담도 휴게소, 38분 후입니다.", false)
            }

            override fun onFailure(error: ErrorType) = report("초기화 실패: $error")
        })
    }

    override fun onDestroy() {
        tts.release()
        super.onDestroy()
    }

    private fun report(message: String) {
        Log.i("VoiceCheck", message)
        runOnUiThread { status.append(message + "\n") }
    }
}
