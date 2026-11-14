package com.sepwoo.manager

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object NotificationHelper {

    const val CHANNEL_UPDATES = "sepwoo_updates"
    private const val PREFS_NAME = "sepwoo_prefs"
    private const val KEY_NOTIF_ENABLED = "notifications_enabled"
    private const val KEY_ASKED_PERM = "asked_notif_perm"
    private const val KEY_LAST_PATCH = "last_notified_patch_key"
    private const val KEY_APPLIED_PATCH = "last_applied_patch_key"
    private const val KEY_LAST_MANAGER_TAG = "last_notified_manager_tag"
    private const val UNIQUE_WORK_NAME = "sepwoo_periodic_update_check"

    private const val NOTIF_ID_PATCH = 1001
    private const val NOTIF_ID_MANAGER = 1002
    private const val NOTIF_ID_TEST = 1003

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_UPDATES) == null) {
            val channel = NotificationChannel(
                CHANNEL_UPDATES,
                "Yeni Yama ve Güncellemeler",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Yeni Sinewix PLUS yamaları ve Sepwoo Manager güncellemeleri için bildirimler"
                enableVibration(true)
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isNotificationsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIF_ENABLED, true)

    fun setNotificationsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIF_ENABLED, enabled).apply()
        if (enabled) {
            schedulePeriodicChecks(context)
        } else {
            cancelPeriodicChecks(context)
        }
    }

    fun hasAskedPermission(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASKED_PERM, false)

    fun markAskedPermission(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED_PERM, true).apply()
    }

    fun markPatchApplied(context: Context, patchVersion: String, sha256: String) {
        val key = "$patchVersion|$sha256"
        prefs(context).edit()
            .putString(KEY_APPLIED_PATCH, key)
            .putString(KEY_LAST_PATCH, key)
            .apply()
    }

    fun getLastNotifiedPatchKey(context: Context): String? =
        prefs(context).getString(KEY_LAST_PATCH, null)

    fun getLastAppliedPatchKey(context: Context): String? =
        prefs(context).getString(KEY_APPLIED_PATCH, null)

    fun isNewPatchAvailable(
        context: Context,
        patchVersion: String,
        sha256: String,
        isInstalled: Boolean
    ): Boolean {
        if (patchVersion.isBlank() && sha256.isBlank()) return false
        val applied = prefs(context).getString(KEY_APPLIED_PATCH, null)
            ?: return isInstalled
        return applied != "$patchVersion|$sha256"
    }

    fun setLastNotifiedPatchKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_LAST_PATCH, key).apply()
    }

    fun getLastNotifiedManagerTag(context: Context): String? =
        prefs(context).getString(KEY_LAST_MANAGER_TAG, null)

    fun setLastNotifiedManagerTag(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_LAST_MANAGER_TAG, tag).apply()
    }

    fun schedulePeriodicChecks(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancelPeriodicChecks(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    fun showPatchUpdateNotification(context: Context, manifest: LocalApkPatcher.ManifestInfo) {
        val summary = if (manifest.changelog.isNotEmpty()) {
            manifest.changelog.take(3).joinToString("\n") { "• $it" }
        } else {
            "Yeni yama paketi (${manifest.patchVersion}) indirmeye hazır."
        }
        postNotification(
            context = context,
            id = NOTIF_ID_PATCH,
            title = "Yeni Yama: ${manifest.appName} ${manifest.patchVersion}",
            shortText = "${manifest.patchVersion} yaması yayınlandı. Güncellemek için dokun.",
            bigText = summary,
            actionLabel = "Yöneticiyi Aç"
        )
    }

    fun showManagerUpdateNotification(context: Context, update: LocalApkPatcher.ManagerUpdateInfo) {
        val notes = update.releaseNotes.trim().ifBlank {
            "Sepwoo Manager ${update.latestTag} sürümü yayınlandı."
        }
        postNotification(
            context = context,
            id = NOTIF_ID_MANAGER,
            title = "Sepwoo Manager ${update.latestTag} Çıktı!",
            shortText = "Yeni Manager güncellemesi indirilebilir.",
            bigText = notes,
            actionLabel = "Güncelle"
        )
    }

    fun showTestNotification(context: Context) {
        postNotification(
            context = context,
            id = NOTIF_ID_TEST,
            title = "Sepwoo Bildirimleri Aktif",
            shortText = "Yeni yamalar ve Manager güncellemeleri buradan bildirilecek.",
            bigText = "Harika! Arka planda 6 saatte bir yeni Sinewix PLUS yamaları ve Manager güncellemeleri kontrol edilip sana bildirilecek.",
            actionLabel = "Yöneticiyi Aç"
        )
    }

    private fun postNotification(
        context: Context,
        id: Int,
        title: String,
        shortText: String,
        bigText: String,
        actionLabel: String
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val largeIcon = runCatching {
            BitmapFactory.decodeResource(context.resources, R.drawable.sinewix_plus_logo)
        }.getOrNull()

        val builder = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFE50914.toInt())
            .setContentTitle(title)
            .setContentText(shortText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_notification, actionLabel, pendingIntent)

        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        }
    }
}
