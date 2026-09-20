package org.lepotager.resiliencevault.panic

import android.content.ContentResolver
import android.os.SystemClock
import android.provider.Settings

class AndroidPanicClock(private val contentResolver: ContentResolver) {
    fun snapshot(): PanicClockSnapshot {
        val bootCount = Settings.Global.getInt(
            contentResolver,
            Settings.Global.BOOT_COUNT,
            -1
        )
        return PanicClockSnapshot(
            bootId = if (bootCount >= 0) "boot-count:$bootCount" else null,
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            utcMs = System.currentTimeMillis()
        )
    }
}
