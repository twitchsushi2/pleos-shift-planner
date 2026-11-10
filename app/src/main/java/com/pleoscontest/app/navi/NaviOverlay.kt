package com.pleoscontest.app.navi

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 앱이 뒤로 가고 내비만 보일 때 내비 위에 띄우는 작은 안내.
 * Pleos는 화면 속 화면(PiP)을 지원하지 않아 떠 있는 창을 쓴다. 터치를 받지 않아 내비 조작을 막지 않는다.
 * 떠 있는 창 권한이 없으면 아무것도 하지 않는다.
 */
class NaviOverlay(context: Context) {
    private val appContext = context.applicationContext
    private val windows = appContext.getSystemService(WindowManager::class.java)
    private val title = label(TITLE_SIZE, Color.rgb(99, 99, 102), bold = false)
    private val detail = label(DETAIL_SIZE, Color.rgb(0, 122, 255), bold = true)
    private val hint = label(TITLE_SIZE, Color.rgb(28, 28, 30), bold = true)
    private val card = LinearLayout(appContext).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(PADDING_X, PADDING_Y, PADDING_X, PADDING_Y)
        background = GradientDrawable().apply {
            cornerRadius = CORNER
            setColor(Color.WHITE)
            setStroke(2, Color.argb(30, 0, 0, 0))
        }
        elevation = 12f
        addView(title)
        addView(detail)
        addView(hint)
    }
    private var shown = false

    /** 화면 오른쪽 끝 ⋮ 손잡이 바로 옆에 붙는 안내. 끌면 앱이 내비 옆에 온다는 것을 알려 준다. */
    private val handleHint = TextView(appContext).apply {
        text = "◀  끌면 교대 플래너가 옆에 떠요"
        textSize = TITLE_SIZE
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(PADDING_X, PADDING_Y, PADDING_X, PADDING_Y)
        background = GradientDrawable().apply {
            cornerRadius = CORNER
            setColor(Color.rgb(0, 122, 255))
        }
        elevation = 12f
    }
    private var hintShown = false
    private val handler = Handler(Looper.getMainLooper())
    private val hideHint = Runnable { hideHandleHint() }

    val allowed: Boolean get() = Settings.canDrawOverlays(appContext)

    /** [hintText]는 운전자에게 묻는 중일 때 셋째 줄로 보인다. 터치를 받지 않으니 음성 답을 안내한다. */
    fun show(titleText: String, detailText: String, hintText: String? = null) {
        if (!allowed) return
        title.text = titleText
        detail.text = detailText
        hint.text = hintText.orEmpty()
        hint.visibility = if (hintText == null) android.view.View.GONE else android.view.View.VISIBLE
        if (shown) return
        windows.addView(card, layoutParams(Gravity.TOP or Gravity.END, MARGIN_X, MARGIN_Y))
        shown = true
    }

    fun hide() {
        hideHandleHint()
        if (!shown) return
        windows.removeView(card)
        shown = false
    }

    /** 손잡이 안내를 잠깐 띄운다. 운전자가 내비를 크게 보고 싶을 수도 있어 계속 붙여 두지는 않는다. */
    fun showHandleHint() {
        if (!allowed) return
        handler.removeCallbacks(hideHint)
        handler.postDelayed(hideHint, HINT_MILLIS)
        if (hintShown) return
        windows.addView(handleHint, layoutParams(Gravity.CENTER_VERTICAL or Gravity.END, HINT_MARGIN_X, 0))
        hintShown = true
    }

    fun hideHandleHint() {
        handler.removeCallbacks(hideHint)
        if (!hintShown) return
        windows.removeView(handleHint)
        hintShown = false
    }

    private fun layoutParams(gravity: Int, x: Int, y: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        this.gravity = gravity
        this.x = x
        this.y = y
    }

    private fun label(size: Float, color: Int, bold: Boolean) = TextView(appContext).apply {
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private companion object {
        const val TITLE_SIZE = 22f
        const val DETAIL_SIZE = 34f
        const val PADDING_X = 36
        const val PADDING_Y = 24
        const val CORNER = 32f
        /** 내비 오른쪽 위의 2D 버튼 옆. 차가 화면 아래쪽에 있어 앞길을 덜 가린다. */
        const val MARGIN_X = 160
        const val MARGIN_Y = 24
        /** 손잡이(오른쪽 끝에서 14px)를 가리지 않을 만큼 띄운다. 터치는 받지 않아 손잡이를 끄는 데 방해가 없다. */
        const val HINT_MARGIN_X = 48
        const val HINT_MILLIS = 20_000L
    }
}
