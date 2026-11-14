package com.sepwoo.manager

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!NotificationHelper.isNotificationsEnabled(applicationContext)) {
            return@withContext Result.success()
        }
        if (!NotificationHelper.hasNotificationPermission(applicationContext)) {
            return@withContext Result.success()
        }

        // 1. Check patch bundle updates from manifest.json
        runCatching {
            val manifest = LocalApkPatcher.fetchManifest()
            val patchKey = "${manifest.patchVersion}|${manifest.patchBundleSha256}"
            val lastNotified = NotificationHelper.getLastNotifiedPatchKey(applicationContext)
            val lastApplied = NotificationHelper.getLastAppliedPatchKey(applicationContext)

            if (patchKey != lastNotified && patchKey != lastApplied) {
                // Only notify if we had a prior baseline or if Sinewix PLUS is already installed
                val installed = runCatching {
                    applicationContext.packageManager.getPackageInfo(manifest.targetPackage, 0)
                }.isSuccess
                if (lastNotified != null || installed) {
                    NotificationHelper.showPatchUpdateNotification(applicationContext, manifest)
                }
                NotificationHelper.setLastNotifiedPatchKey(applicationContext, patchKey)
            }
        }

        // 2. Check Sepwoo Manager self-update from GitHub Releases
        runCatching {
            val update = LocalApkPatcher.checkManagerUpdate(applicationContext)
            if (update != null && update.latestTag != NotificationHelper.getLastNotifiedManagerTag(applicationContext)) {
                NotificationHelper.showManagerUpdateNotification(applicationContext, update)
                NotificationHelper.setLastNotifiedManagerTag(applicationContext, update.latestTag)
            }
        }

        Result.success()
    }
}
