package org.lepotager.resiliencevault.panic

/** Trusted adapter sampled INSIDE admission transactions. Default admission is disabled.
 * The adapter combines the build capability, live SMS permission and a fresh clock.
 * It must be short/non-suspending. An SMS body must never construct this observation.
 */
fun interface PanicAdmissionEnvironment {
    fun observe(): PanicAdmissionObservation
}

data class PanicAdmissionObservation(
    val clock: PanicClockSnapshot,
    val smsChannelReady: Boolean
)
