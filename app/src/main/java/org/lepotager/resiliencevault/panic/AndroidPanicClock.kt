package org.lepotager.resiliencevault.panic

import android.content.ContentResolver
import android.os.SystemClock
import android.provider.Settings

class AndroidPanicClock(private val contentResolver: ContentResolver) {
    fun snapshot(): PanicClockSnapshot {
        val bootCount = try {
            Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (_: RuntimeException) {
            -1 // Missing/unreadable provider must disable arming, not crash the screen.
        }
        return PanicClockSnapshot(
            bootId = if (bootCount >= 0) "boot-count:$bootCount" else null,
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            utcMs = System.currentTimeMillis()
        )
    }
}
