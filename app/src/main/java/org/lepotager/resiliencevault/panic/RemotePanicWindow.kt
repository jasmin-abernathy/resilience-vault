package org.lepotager.resiliencevault.panic

object RemotePanicWindow {
    fun isValid(
        arm: ArmedRemotePanic,
        now: PanicClockSnapshot
    ): Boolean {
        if (now.bootId == null || now.bootId != arm.bootId) return false
        if (now.elapsedRealtimeMs < 0 || now.utcMs < 0) return false

        val elapsedDelta = try {
            Math.subtractExact(now.elapsedRealtimeMs, arm.startedElapsedRealtimeMs)
        } catch (_: ArithmeticException) {
            return false
        }
        if (elapsedDelta < 0 || elapsedDelta >= arm.durationMs) return false

        val expiry = expiresUtcMs(arm) ?: return false
        if (now.utcMs >= expiry) return false

        val utcDelta = try {
            Math.subtractExact(now.utcMs, arm.startedUtcMs)
        } catch (_: ArithmeticException) {
            return false
        }
        val drift = try {
            Math.subtractExact(utcDelta, elapsedDelta)
        } catch (_: ArithmeticException) {
            return false
        }

        return drift in
            -RemotePanicPolicy.DRIFT_TOLERANCE_MS..RemotePanicPolicy.DRIFT_TOLERANCE_MS
    }

    fun expiresUtcMs(arm: ArmedRemotePanic): Long? =
        try {
            Math.addExact(arm.startedUtcMs, arm.durationMs)
        } catch (_: ArithmeticException) {
            null
        }
}
