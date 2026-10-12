package fr.earthshade.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Met à jour les données puis redessine le widget. Android le lance toutes les 15 minutes (le minimum autorisé). */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (Store.isLoggedIn(ctx)) {
            try {
                withContext(Dispatchers.IO) { Api.sync(ctx) }
            } catch (e: Exception) {
                Store.setError(ctx, e.message ?: "Pas de réseau")
            }
        }
        EsWidget().updateAll(ctx)   // même sans réseau : la date du jour change à minuit
        return Result.success()
    }

    companion object {
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("es-sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun now(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(net).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("es-sync-now", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
