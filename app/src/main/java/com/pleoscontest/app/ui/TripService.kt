package com.pleoscontest.app.ui

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log

/**
 * 여행 중에 앱 프로세스를 살려 둔다.
 * 내비가 앱을 덮으면 시스템이 10초쯤 뒤 앱을 얼려 운전 시간 기록, 교대 안내, 내비 이벤트가 멈춘다.
 * 포그라운드 서비스가 돌면 얼리지 않는다. 일은 [ShiftViewModel]이 하고, 서비스는 알림 하나를 띄워 둘 뿐이다.
 */
class TripService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 멈추라는 요청도 시작 요청 뒤에 차례로 온다. 그래서 알림을 띄운(startForeground) 다음에 멈추게 되어,
        // 시작하자마자 멈췄을 때 "startForeground를 부르지 않았다"며 앱이 죽는 일이 없다.
        if (intent?.action == ACTION_STOP) {
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { startForeground(NOTIFICATION_ID, ShiftNotifier(this).tripOngoing(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
            .onFailure {
                Log.w(TAG, "포그라운드 서비스를 시작하지 못함", it)
                running = false
                stopSelf()
            }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TripService"
        private const val NOTIFICATION_ID = 3
        private const val ACTION_STOP = "com.pleoscontest.app.STOP_TRIP_SERVICE"
        /** 시작을 요청했고 아직 멈추지 않았다. 돌고 있지 않은 서비스를 멈추려고 새로 띄우지 않게 한다. */
        @Volatile private var running = false

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, TripService::class.java)) }
                .onSuccess { running = true }
                .onFailure { Log.w(TAG, "여행 서비스를 띄우지 못함", it) }
        }

        fun stop(context: Context) {
            if (!running) return
            runCatching { context.startService(Intent(context, TripService::class.java).setAction(ACTION_STOP)) }
                .onFailure {
                    // 앱이 백그라운드라 요청을 보낼 수 없으면 바로 멈춘다. 이때는 이미 알림이 떠 있는 상태다.
                    Log.w(TAG, "멈춤 요청을 보내지 못해 바로 멈춤", it)
                    context.stopService(Intent(context, TripService::class.java))
                }
            running = false
        }
    }
}
