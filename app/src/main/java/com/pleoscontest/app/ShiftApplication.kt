package com.pleoscontest.app

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.pleoscontest.app.shift.RouteCatalog
import androidx.lifecycle.ViewModelStoreOwner

/**
 * 여행 상태를 화면이 아니라 앱 수명에 묶는다.
 * Pleos는 창을 옮기거나 다시 띄울 때 화면을 새로 만들 수 있는데, 주행 중에 계획이 사라지면 안 된다.
 */
class ShiftApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    override fun onCreate() {
        super.onCreate()
        // 전국 고속도로망(약 860KB)을 화면 스레드 밖에서 미리 읽어 둔다. 처음 경로를 그릴 때 화면이 멈추지 않게.
        Thread({ runCatching { RouteCatalog.places } }, "expressway-preload").start()
    }
}
