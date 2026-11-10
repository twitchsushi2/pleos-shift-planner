package com.pleoscontest.app

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import java.util.Locale

/**
 * 온디바이스 음성 인식이 한국어를 알아듣는지 보는 디버그 전용 화면. 에뮬레이터에는 말을 넣을 수 없어 합성 음성으로 만든 PCM을 넣는다.
 *   tools/gen_stt_samples.sh 로 assets/stt/ 를 만들고
 *   adb shell am start -n com.pleoscontest.app/.SttCheckActivity
 * 결과는 logcat의 SttCheck 태그로 본다.
 */
class SttCheckActivity : Activity(), ResultListener {
    private lateinit var stt: SpeechToText
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val samples = ArrayDeque<String>()
    private var current = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 20f; setPadding(32, 32, 32, 32) }
        setContentView(status)
        samples += assets.list("stt").orEmpty().filter { it.endsWith(".pcm") }.sorted()
        report("샘플 ${samples.size}개")
        stt = SpeechToText(applicationContext, Mode.ON_DEVICE)
        stt.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                report("초기화 성공")
                stt.addListener(this@SttCheckActivity)
                stt.setLocale(Locale.KOREA)
                handler.postDelayed(::next, 1000)
            }

            override fun onFailure(error: ErrorType) = report("초기화 실패: $error")
        })
    }

    private fun next() {
        current = samples.removeFirstOrNull() ?: return report("끝")
        report("--- $current")
        val pcm = assets.open("stt/$current").use { it.readBytes() }
        var offset = 0
        val send = object : Runnable {
            override fun run() {
                if (offset >= pcm.size) {
                    stt.completeAudioSend()
                    return
                }
                val end = minOf(offset + BYTES_PER_100_MS, pcm.size)
                stt.sendAudio(pcm.copyOfRange(offset, end))
                offset = end
                handler.postDelayed(this, 100)
            }
        }
        send.run()
        // 끝 이벤트가 오지 않아도 다음 샘플로 넘어간다.
        handler.removeCallbacks(::next)
        handler.postDelayed(nextIfStuck, pcm.size / 32L + 6000)
    }

    private val nextIfStuck = Runnable { report("시간 초과"); next() }

    override fun onUpdated(text: String, isFinal: Boolean) = report("결과(final=$isFinal): $text")
    override fun onUpdatedEpdData(start: Long, end: Long) {}
    override fun onStartedRecognition() = report("인식 시작")
    override fun onEndedRecognition() {
        report("인식 끝")
        handler.removeCallbacks(nextIfStuck)
        handler.postDelayed(::next, 1000)
    }
    override fun onError(error: ErrorType) = report("오류: $error")
    override fun onReady() = report("준비됨")
    override fun onUpdatedRms(rms: Double) {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stt.removeListener(this)
        stt.release()
        super.onDestroy()
    }

    private fun report(message: String) {
        Log.i("SttCheck", message)
        runOnUiThread { status.append(message + "\n") }
    }

    private companion object {
        const val BYTES_PER_100_MS = 3_200
    }
}
