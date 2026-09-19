package org.lepotager.resiliencevault

import android.app.Application
import org.lepotager.resiliencevault.sync.VaultSyncScheduler

class ResilienceVaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        VaultSyncScheduler.ensureScheduled(this)
    }
}
