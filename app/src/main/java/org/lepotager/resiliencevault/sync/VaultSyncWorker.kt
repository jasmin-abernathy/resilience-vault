package org.lepotager.resiliencevault.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import org.lepotager.resiliencevault.BuildConfig

class VaultSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (!BuildConfig.PRODUCTION_CRYPTO_READY) {
            return Result.success(
                Data.Builder().putString("state", "blocked_crypto_not_audited").build()
            )
        }
        return Result.failure()
    }
}
