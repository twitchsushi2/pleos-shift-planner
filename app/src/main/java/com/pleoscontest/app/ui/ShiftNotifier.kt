package com.pleoscontest.app.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.lifecycle.ViewModelProvider
import com.pleoscontest.app.MainActivity
import com.pleoscontest.app.R
import com.pleoscontest.app.ShiftApplication
import android.app.Notification

/**
 * 앱이 가려져 있을 때 차의 알림으로 알린다. 내비를 보는 중에도 교대·휴식 때를 놓치지 않게 하고,
 * 운전자가 내비에서 장거리 안내를 시작하면 교대 계획을 먼저 제안한다.
 * 알림 권한이 없으면 차가 알림을 보여 주지 않을 뿐 앱은 그대로 돈다.
 */
class ShiftNotifier(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "교대·휴식 안내", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "교대·휴식 때와 장거리 출발을 알려 줘요"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(TRIP_CHANNEL, "여행 기록 중", NotificationManager.IMPORTANCE_MIN).apply {
                description = "내비를 보는 동안에도 운전 시간을 기록하고 있다는 표시예요"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(QUIET_CHANNEL, "내비 위 안내와 함께 보는 알림", NotificationManager.IMPORTANCE_LOW).apply {
                description = "내비 위 작은 안내가 떠 있을 때는 화면 위에 띄우지 않고 알림 목록에만 둬요"
            },
        )
    }

    /** 내비에서 시작한 장거리 안내를 보고 교대 계획을 제안한다. 누르면 운전자 수 입력부터 이어진다. */
    fun suggestPlan(placeName: String, distanceKm: Int, minutes: Int) = post(
        SUGGEST_ID, "$placeName · ${durationText(minutes)}",
        "${distanceKm}km 장거리예요. 교대·휴식 계획을 만들까요?",
        open(ACTION_PLAN_FROM_NAVI),
        listOf(action("계획 만들기", open(ACTION_PLAN_FROM_NAVI))),
    )

    /**
     * 교대·휴식이 가까워졌다. 알림에서 바로 수락하거나 미룰 수 있다.
     * [quiet]이면 화면 위에 띄우지 않는다. 내비 위 작은 안내가 같은 내용을 보여 주고, 차 알림은 내비의 회전 안내를 가리기 때문이다.
     */
    fun upcoming(title: String, text: String, quiet: Boolean = false) = post(
        SHIFT_ID, title, text, open(null),
        listOf(action("수락", broadcast(ACTION_ACCEPT)), action("미루기", broadcast(ACTION_POSTPONE))), quiet,
    )

    fun info(title: String, text: String, quiet: Boolean = false) = post(SHIFT_ID, title, text, open(null), emptyList(), quiet)

    /** 여행 중 포그라운드 서비스가 띄워 두는 알림. 소리도 팝업도 없이 알림 목록에만 있다. */
    fun tripOngoing(): Notification = Notification.Builder(app, TRIP_CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("교대 플래너")
        .setContentText("운전 시간을 기록하고 교대·휴식 때를 알려 드려요")
        .setContentIntent(open(null))
        .setOngoing(true)
        .build()

    fun cancelSuggestion() = manager.cancel(SUGGEST_ID)

    fun cancelShift() = manager.cancel(SHIFT_ID)

    private fun post(id: Int, title: String, text: String, content: PendingIntent, actions: List<Notification.Action>, quiet: Boolean = false) {
        val notification = Notification.Builder(app, if (quiet) QUIET_CHANNEL else CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(content)
            .setAutoCancel(true)
            // 차는 내비게이션 알림을 중요도와 상관없이 화면 위에 띄운다. 조용히 둘 알림에는 붙이지 않는다.
            .apply { if (!quiet) setCategory(Notification.CATEGORY_NAVIGATION) }
            .apply { actions.forEach(::addAction) }
            .build()
        runCatching { manager.notify(id, notification) }
    }

    private fun action(label: String, intent: PendingIntent) =
        Notification.Action.Builder(Icon.createWithResource(app, R.drawable.ic_launcher_foreground), label, intent).build()

    private fun open(action: String?): PendingIntent = PendingIntent.getActivity(
        app, action.hashCode(),
        Intent(app, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun broadcast(action: String): PendingIntent = PendingIntent.getBroadcast(
        app, action.hashCode(), Intent(app, NotificationActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_PLAN_FROM_NAVI = "com.pleoscontest.app.PLAN_FROM_NAVI"
        const val ACTION_ACCEPT = "com.pleoscontest.app.ACCEPT_SHIFT"
        const val ACTION_POSTPONE = "com.pleoscontest.app.POSTPONE_SHIFT"
        private const val CHANNEL = "shift"
        private const val QUIET_CHANNEL = "shift_quiet"
        private const val TRIP_CHANNEL = "trip"
        private const val SUGGEST_ID = 1
        private const val SHIFT_ID = 2
    }
}

/** 알림의 수락·미루기 버튼. 화면을 열지 않고 여행 상태에 바로 반영한다. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ShiftApplication
        val vm = ViewModelProvider(app, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[ShiftViewModel::class.java]
        when (intent.action) {
            ShiftNotifier.ACTION_ACCEPT -> vm.accept()
            ShiftNotifier.ACTION_POSTPONE -> vm.postpone()
        }
    }
}
