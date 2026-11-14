package com.sepwoo.manager

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.FileProvider
import com.android.apksig.ApkSigner
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * On-device non-root APK patcher for Sinewix PLUS.
 * Fetches manifest & patch bundle from cronus.best, downloads official SineWix APK
 * from the upstream release (or uses a user-selected APK), merges patches locally,
 * signs with apksig, and launches Android's PackageInstaller.
 */
object LocalApkPatcher {

    const val DEFAULT_MANIFEST_URL = "https://cronus.best/sepwoo/manifest.json"
    const val GITHUB_LATEST_RELEASE_API = "https://api.github.com/repos/eyyun/sepwoo-manager/releases/latest"

    data class ManagerUpdateInfo(
        val latestTag: String,
        val apkUrl: String,
        val releaseNotes: String
    )

    data class ManifestInfo(
        val appName: String,
        val targetPackage: String,
        val targetVersionName: String,
        val officialApkUrl: String,
        val patchVersion: String,
        val patchBundleUrl: String,
        val patchBundleSha256: String,
        val changelog: List<String>
    )

    data class PatchConfig(
        val customAppName: String = "Sinewix PLUS",
        val customIconPngBytes: ByteArray? = null,
        val slimUnusedAbis: Boolean = true
    )

    private val LAUNCHER_ICON_ENTRIES = setOf(
        "res/N37.png", "res/9wU.png", "res/Eh5.png", "res/ynk.png", "res/FSV.png", "res/RJS.png", "res/o-_.png",
        "res/twT.png", "res/zRY.png", "res/vhE.png", "res/8cS.png", "res/wbD.png", "res/fOp.png", "res/Gc2.png"
    )

    private val EXCLUDED_PREFIXES = listOf(
        "META-INF/",
        "assets/audience_network/",
        "assets/iads/",
        "assets/ad-viewer/",
        "com/ibm/icu/impl/data/icudata/brkitr/",
        "com/ibm/icu/impl/data/icudata/translit/",
        "com/ibm/icu/impl/data/icudata/rbnf/"
    )

    private class CountingOutputStream(private val out: java.io.OutputStream) : java.io.OutputStream() {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            bytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            bytesWritten += len
        }

        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    private fun writeStoredAligned(
        zos: ZipOutputStream,
        cos: CountingOutputStream,
        name: String,
        bytes: ByteArray,
        alignment: Int
    ) {
        val nameLen = name.toByteArray(Charsets.UTF_8).size
        val baseOffset = cos.bytesWritten + 30L + nameLen
        val pad = ((alignment - (baseOffset % alignment)) % alignment).toInt()
        val outEntry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = java.util.zip.CRC32().apply { update(bytes) }.value
            if (pad > 0) extra = ByteArray(pad)
        }
        zos.putNextEntry(outEntry)
        zos.write(bytes)
        zos.closeEntry()
    }

    fun checkManagerUpdate(context: Context): ManagerUpdateInfo? = runCatching {
        val currentVer = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        val conn = (URL(GITHUB_LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SepwooManager/$currentVer")
        }
        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val json = JSONObject(body)
        val tag = json.optString("tag_name", "").removePrefix("v")
        if (tag.isBlank() || tag == currentVer) return@runCatching null
        val assets = json.optJSONArray("assets") ?: return@runCatching null
        var apkUrl = ""
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                apkUrl = a.optString("browser_download_url")
                break
            }
        }
        if (apkUrl.isBlank()) return@runCatching null
        ManagerUpdateInfo(
            latestTag = "v$tag",
            apkUrl = apkUrl,
            releaseNotes = json.optString("body", "")
        )
    }.getOrNull()

    fun fetchManifest(url: String = DEFAULT_MANIFEST_URL): ManifestInfo {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val json = JSONObject(body)
        val notes = buildList {
            val arr = json.optJSONArray("changelog")
            if (arr != null) {
                for (i in 0 until arr.length()) add(arr.getString(i))
            }
        }
        return ManifestInfo(
            appName = json.optString("appName", "Sinewix PLUS"),
            targetPackage = json.optString("targetPackage", "com.wxsmash.game"),
            targetVersionName = json.optString("targetVersionName", "2.6.3"),
            officialApkUrl = json.optString(
                "officialApkUrl",
                "https://github.com/Sinewix/sinewixapk/releases/download/2.6.3/SineWix2.6.3.apk"
            ),
            patchVersion = json.optString("patchVersion", "2.6.3-plus.1"),
            patchBundleUrl = json.getString("patchBundleUrl"),
            patchBundleSha256 = json.optString("patchBundleSha256", ""),
            changelog = notes
        )
    }

    fun downloadFile(
        url: String,
        destFile: File,
        label: String,
        onProgress: (String, Float?) -> Unit
    ) {
        onProgress("$label bağlanılıyor...", null)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        val total = conn.contentLengthLong
        var downloaded = 0L
        var lastReport = 0L
        conn.inputStream.use { input ->
            FileOutputStream(destFile).use { output ->
                val buf = ByteArray(16 * 1024)
                var read = input.read(buf)
                while (read >= 0) {
                    output.write(buf, 0, read)
                    downloaded += read
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 250) {
                        lastReport = now
                        val mbDone = "%.1f".format(downloaded / (1024.0 * 1024.0))
                        if (total > 0) {
                            val pct = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            val mbTot = "%.1f".format(total / (1024.0 * 1024.0))
                            onProgress("$label indiriliyor ($mbDone / $mbTot MB)", pct)
                        } else {
                            onProgress("$label indiriliyor ($mbDone MB)", null)
                        }
                    }
                    read = input.read(buf)
                }
            }
        }
        onProgress("$label indirildi ✓", 1f)
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(8192)
            var read = fis.read(buf)
            while (read > 0) {
                digest.update(buf, 0, read)
                read = fis.read(buf)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun renderRoundedIconPng(context: Context, uri: Uri): ByteArray {
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: error("Seçilen ikon okunamadı")
        val size = 192
        val minDim = minOf(raw.width, raw.height)
        val x = (raw.width - minDim) / 2
        val y = (raw.height - minDim) / 2
        val square = Bitmap.createBitmap(raw, x, y, minDim, minDim)
        val scaled = Bitmap.createScaledBitmap(square, size, size, true)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 44f, 44f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        return ByteArrayOutputStream().apply {
            out.compress(Bitmap.CompressFormat.PNG, 100, this)
        }.toByteArray()
    }

    fun patchAndSign(
        context: Context,
        officialApk: File,
        patchBundleZip: File,
        outSignedApk: File,
        config: PatchConfig,
        onProgress: (String) -> Unit
    ) {
        val unsignedApk = File(context.cacheDir, "sinewix_plus_unsigned.apk")

        onProgress("Yama paketi hazırlanıyor...")
        val replacements = mutableMapOf<String, ByteArray>()
        ZipInputStream(BufferedInputStream(FileInputStream(patchBundleZip))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    replacements[entry.name] = zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }

        onProgress("Sinewix PLUS yerel olarak derleniyor...")
        val cos = CountingOutputStream(BufferedOutputStream(FileOutputStream(unsignedApk)))
        ZipOutputStream(cos).use { zos ->
            zos.setLevel(Deflater.BEST_COMPRESSION)
            ZipInputStream(BufferedInputStream(FileInputStream(officialApk))).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val skipSignature = name.startsWith("META-INF/") &&
                        (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".MF"))
                    val skipAbi = config.slimUnusedAbis && name.startsWith("lib/") && run {
                        val abi = name.removePrefix("lib/").substringBefore("/")
                        abi.isNotEmpty() && abi !in android.os.Build.SUPPORTED_ABIS
                    }
                    val skipSlim = config.slimUnusedAbis && (skipAbi || EXCLUDED_PREFIXES.any { name.startsWith(it) })

                    if (!skipSignature && !skipSlim) {
                        val replacement = replacements[name]
                        val customIcon = if (config.customIconPngBytes != null && name in LAUNCHER_ICON_ENTRIES) {
                            config.customIconPngBytes
                        } else null

                        val payload = replacement ?: customIcon
                        val storedAlignment = when {
                            name == "resources.arsc" -> 4
                            name.endsWith(".so") -> 4096
                            else -> 0
                        }
                        if (storedAlignment > 0) {
                            val rawBytes = payload ?: zis.readBytes()
                            writeStoredAligned(zos, cos, name, rawBytes, storedAlignment)
                        } else if (payload != null) {
                            zos.putNextEntry(ZipEntry(name))
                            zos.write(payload)
                            zos.closeEntry()
                        } else {
                            zos.putNextEntry(ZipEntry(name))
                            zis.copyTo(zos)
                            zos.closeEntry()
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }

        onProgress("APK yerel olarak imzalanıyor...")
        val ks = KeyStore.getInstance("PKCS12")
        context.assets.open("sepwoo_signer.p12").use { ks.load(it, "sepwoo123".toCharArray()) }
        val alias = ks.aliases().nextElement()
        val key = ks.getKey(alias, "sepwoo123".toCharArray()) as PrivateKey
        val cert = ks.getCertificate(alias) as X509Certificate

        val signerConfig = ApkSigner.SignerConfig.Builder("sepwoo", key, listOf(cert)).build()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(outSignedApk)
            .setMinSdkVersion(26)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()

        unsignedApk.delete()
    }

    fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    fun reportError(
        context: Context,
        stage: String,
        error: Throwable,
        logs: List<String>
    ): Boolean = runCatching {
        val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val payload = JSONObject().apply {
            put("managerVersion", pkgInfo.versionName ?: "1.0.1")
            put("stage", stage)
            put("errorClass", error.javaClass.name)
            put("errorMessage", error.localizedMessage ?: error.message ?: "")
            put("stackTrace", android.util.Log.getStackTraceString(error).take(8000))
            put("device", JSONObject().apply {
                put("manufacturer", android.os.Build.MANUFACTURER)
                put("model", android.os.Build.MODEL)
                put("brand", android.os.Build.BRAND)
                put("device", android.os.Build.DEVICE)
                put("androidVersion", android.os.Build.VERSION.RELEASE)
                put("sdkInt", android.os.Build.VERSION.SDK_INT)
                put("abis", org.json.JSONArray(android.os.Build.SUPPORTED_ABIS.toList()))
            })
            put("recentLogs", org.json.JSONArray(logs.takeLast(15)))
        }
        val conn = (URL("https://cronus.best/sepwoo/api/report").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        conn.responseCode in 200..299
    }.getOrDefault(false)
}
