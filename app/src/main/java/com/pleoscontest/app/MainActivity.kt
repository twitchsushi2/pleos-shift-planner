package com.pleoscontest.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pleoscontest.app.ui.DarkColors
import com.pleoscontest.app.ui.LightColors
import com.pleoscontest.app.ui.ShiftApp
import com.pleoscontest.app.ui.ShiftNotifier
import com.pleoscontest.app.ui.ShiftViewModel
import com.pleoscontest.app.ui.ThemeMode

private const val CAR_SPEED = "android.car.permission.CAR_SPEED"
private const val UI_SCALE = 1.5f

class MainActivity : ComponentActivity() {
    private val vm: ShiftViewModel by lazy {
        ViewModelProvider(application as ShiftApplication, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[ShiftViewModel::class.java]
    }

    // 권한을 받으면 속도 구독을 다시 건다. 알림 권한은 교대·휴식 알림에 쓴다.
    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.restartVehicle()
    }

    /** 디버그 빌드에서 녹음으로 대답을 넣는다. adb shell am start --activity-single-top -n .../.MainActivity --es answer 01.pcm */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotification(intent)
        val answer = intent.getStringExtra("answer")
        if (BuildConfig.DEBUG && answer != null) vm.feedAnswer(assets.open("stt/$answer").use { it.readBytes() })
    }

    /** 알림의 "계획 만들기"로 들어왔으면 내비 목적지로 계획을 이어 간다. */
    private fun handleNotification(intent: Intent?) {
        if (intent?.action == ShiftNotifier.ACTION_PLAN_FROM_NAVI) vm.planFromNavi()
    }

    override fun onStart() {
        super.onStart()
        vm.setAppVisible(true)
    }

    override fun onStop() {
        super.onStop()
        vm.setAppVisible(false)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshLocation()
        // 폰·구글 에뮬레이터처럼 차량 속도 권한이 없는 기기(모의 주행)는 안내하지 않는다.
        val needsSpeed = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        val speedGranted = checkSelfPermission(CAR_SPEED) == PackageManager.PERMISSION_GRANTED
        if (BuildConfig.DEBUG) Log.i("MainActivity", "속도 권한 needs=$needsSpeed granted=$speedGranted")
        vm.setSpeedAllowed(!needsSpeed || speedGranted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val missing = listOf(CAR_SPEED, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
        handleNotification(intent)
        // 내비 옆에 나란히 놓였는지 창 너비로 안다. 화면을 다 쓰면 화면 너비의 2/3쯤, 나란히면 1/3쯤이다.
        window.decorView.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val screen = display?.mode?.physicalWidth ?: resources.displayMetrics.widthPixels
            vm.setAppBeside(view.width < screen / 2)
        }
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            val dark = when (state.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
                // Pleos 화면은 2560x1440에 160dpi라 그대로 그리면 차 안에서 작다. 글자와 터치 영역을 함께 키운다.
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density * UI_SCALE, density.fontScale)) {
                    ShiftApp(state, vm)
                }
            }
        }
    }
}
