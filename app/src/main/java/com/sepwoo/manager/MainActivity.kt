package com.sepwoo.manager

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sepwoo Manager — Revenge Manager inspired Material 3 UI for Sinewix PLUS.
 * Includes GitHub self-updater, cronus.best patcher, popup progress dialog, and crash reporting.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SepwooManagerTheme {
                SepwooManagerApp()
            }
        }
    }
}

private val BgDark = Color(0xFF0E0E11)
private val SurfaceL1 = Color(0xFF18181D)
private val SurfaceL2 = Color(0xFF222229)
private val OutlineSubtle = Color(0xFF2E2E38)
private val AccentCrimson = Color(0xFFFF3B5C)
private val MutedText = Color(0xFFA0A0AC)

@Composable
fun SepwooManagerTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = AccentCrimson,
        secondaryContainer = SurfaceL2,
        onSecondaryContainer = Color.White,
        background = BgDark,
        surface = SurfaceL1,
        onPrimary = Color.White,
        onBackground = Color.White,
        onSurface = Color.White
    )
    MaterialTheme(colorScheme = darkColors, content = content)
}

@Composable
fun SepwooManagerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val managerVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.3" }
            .getOrDefault("1.0.3")
    }
    var installedSinewixVersion by remember {
        mutableStateOf(
            runCatching { context.packageManager.getPackageInfo("com.wxsmash.game", 0).versionName }.getOrNull()
        )
    }

    var showSettings by remember { mutableStateOf(false) }
    var manifest by remember { mutableStateOf<LocalApkPatcher.ManifestInfo?>(null) }
    var managerUpdate by remember { mutableStateOf<LocalApkPatcher.ManagerUpdateInfo?>(null) }
    var selectedApkUri by remember { mutableStateOf<Uri?>(null) }
    var customIconUri by remember { mutableStateOf<Uri?>(null) }
    var customAppName by remember { mutableStateOf("Sinewix PLUS") }
    var statusText by remember { mutableStateOf("cronus.best kontrol ediliyor...") }
    var slimAbis by remember { mutableStateOf(true) }

    var showProcessDialog by remember { mutableStateOf(false) }
    var processTitle by remember { mutableStateOf("Yama İşlemi") }
    var isWorking by remember { mutableStateOf(false) }
    var currentStep by remember { mutableIntStateOf(0) }
    var stepProgress by remember { mutableStateOf<Float?>(null) }
    val processLogs = remember { mutableStateListOf<String>() }

    fun pushLog(line: String) {
        if (processLogs.lastOrNull() != line) {
            processLogs.add(line)
            if (processLogs.size > 30) processLogs.removeAt(0)
        }
    }

    fun refreshAll() {
        installedSinewixVersion = runCatching {
            context.packageManager.getPackageInfo("com.wxsmash.game", 0).versionName
        }.getOrNull()
        statusText = "Yenileniyor..."
        scope.launch(Dispatchers.IO) {
            runCatching { LocalApkPatcher.fetchManifest() }.onSuccess { info ->
                withContext(Dispatchers.Main) {
                    manifest = info
                    statusText = "Hazır • v${info.patchVersion}"
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    statusText = "Bağlantı hatası: ${err.localizedMessage ?: "Bilinmeyen"}"
                }
            }
            val upd = LocalApkPatcher.checkManagerUpdate(context)
            withContext(Dispatchers.Main) { managerUpdate = upd }
        }
    }

    fun ensureInstallPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            statusText = "Bilinmeyen uygulama yükleme izni gerekli — Ayarlar açılıyor..."
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        return true
    }

    fun startSelfUpdate(upd: LocalApkPatcher.ManagerUpdateInfo) {
        if (!ensureInstallPermission()) return
        scope.launch {
            isWorking = true
            processTitle = "Manager Güncelleniyor"
            showProcessDialog = true
            currentStep = 1
            stepProgress = null
            processLogs.clear()
            pushLog("Yeni sürüm (${upd.latestTag}) indiriliyor...")
            runCatching {
                withContext(Dispatchers.IO) {
                    val outApk = File(context.filesDir, "Sepwoo-Manager-${upd.latestTag}.apk")
                    LocalApkPatcher.downloadFile(upd.apkUrl, outApk, "Sepwoo Manager ${upd.latestTag}") { msg, pct ->
                        statusText = msg
                        stepProgress = pct
                        if (pct == null || pct >= 1f) pushLog(msg)
                    }
                    currentStep = 5
                    stepProgress = 1f
                    withContext(Dispatchers.Main) {
                        LocalApkPatcher.installApk(context, outApk)
                    }
                }
            }.onFailure { err ->
                val msg = "Güncelleme hatası: ${err.localizedMessage ?: err.javaClass.simpleName}"
                statusText = msg
                pushLog(msg)
                val sent = withContext(Dispatchers.IO) {
                    LocalApkPatcher.reportError(context, "manager_self_update", err, processLogs.toList())
                }
                if (sent) pushLog("Hata raporu cronus.best sunucusuna iletildi ✓")
            }
            isWorking = false
        }
    }

    fun startPatchAndInstall() {
        if (!ensureInstallPermission()) return
        scope.launch {
            isWorking = true
            processTitle = "Sinewix PLUS Kurulumu"
            showProcessDialog = true
            currentStep = 1
            stepProgress = null
            processLogs.clear()
            pushLog("İşlem başlatıldı...")

            runCatching {
                withContext(Dispatchers.IO) {
                    val info = manifest ?: LocalApkPatcher.fetchManifest().also { manifest = it }
                    val officialApk = File(context.cacheDir, "official_sinewix.apk")
                    val bundleZip = File(context.cacheDir, "sepwoo_bundle.zip")
                    val signedApk = File(context.filesDir, "Sinewix_PLUS_signed.apk")

                    currentStep = 1
                    val pickedUri = selectedApkUri
                    if (pickedUri != null) {
                        statusText = "Seçilen resmi APK kopyalanıyor..."
                        pushLog("Seçilen yerel APK kopyalanıyor...")
                        context.contentResolver.openInputStream(pickedUri)?.use { input ->
                            officialApk.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("Seçilen APK açılamadı")
                        pushLog("Resmi APK hazır ✓")
                    } else {
                        LocalApkPatcher.downloadFile(info.officialApkUrl, officialApk, "Resmi SineWix APK") { msg, pct ->
                            statusText = msg
                            stepProgress = pct
                            if (pct == null || pct >= 1f) pushLog(msg)
                        }
                    }

                    currentStep = 2
                    stepProgress = null
                    LocalApkPatcher.downloadFile(info.patchBundleUrl, bundleZip, "cronus.best yama paketi") { msg, pct ->
                        statusText = msg
                        stepProgress = pct
                        if (pct == null || pct >= 1f) pushLog(msg)
                    }

                    currentStep = 3
                    stepProgress = null
                    if (info.patchBundleSha256.isNotBlank()) {
                        pushLog("SHA-256 bütünlüğü doğrulanıyor...")
                        val actual = LocalApkPatcher.sha256Hex(bundleZip)
                        require(actual.equals(info.patchBundleSha256, ignoreCase = true)) {
                            "Yama SHA-256 doğrulaması başarısız!"
                        }
                        pushLog("SHA-256 doğrulandı ✓")
                    }

                    currentStep = 4
                    val iconBytes = customIconUri?.let {
                        pushLog("Özel ikon yuvarlatılıyor...")
                        LocalApkPatcher.renderRoundedIconPng(context, it)
                    }
                    LocalApkPatcher.patchAndSign(
                        context = context,
                        officialApk = officialApk,
                        patchBundleZip = bundleZip,
                        outSignedApk = signedApk,
                        config = LocalApkPatcher.PatchConfig(
                            customAppName = customAppName,
                            customIconPngBytes = iconBytes,
                            slimUnusedAbis = slimAbis
                        )
                    ) { msg ->
                        statusText = msg
                        pushLog(msg)
                        if (msg.contains("imzalanıyor")) currentStep = 5
                    }

                    currentStep = 5
                    stepProgress = 1f
                    pushLog("Tamamlandı! Android yükleyicisi açılıyor...")
                    withContext(Dispatchers.Main) {
                        statusText = "Hazır! Android yükleyicisi açılıyor..."
                        LocalApkPatcher.installApk(context, signedApk)
                    }
                }
            }.onFailure { err ->
                val msg = "Hata: ${err.localizedMessage ?: err.javaClass.simpleName}"
                statusText = msg
                pushLog(msg)
                val sent = withContext(Dispatchers.IO) {
                    LocalApkPatcher.reportError(context, "patch_step_$currentStep", err, processLogs.toList())
                }
                if (sent) pushLog("Hata raporu cronus.best sunucusuna iletildi ✓")
            }
            isWorking = false
        }
    }

    LaunchedEffect(Unit) { refreshAll() }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedApkUri = uri
            statusText = "Yerel resmi APK seçildi, yamalamaya hazır."
        }
    }

    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            customIconUri = uri
            statusText = "Özel uygulama ikonu seçildi."
        }
    }

    BackHandler(enabled = showSettings) {
        showSettings = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .statusBarsPadding()
    ) {
        // Revenge-style Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showSettings) {
                    IconButton(onClick = { showSettings = false }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Geri", tint = Color.White)
                    }
                }
                Text(
                    text = if (showSettings) "Ayarlar" else "Ana Sayfa",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }
            if (!showSettings) {
                Row {
                    IconButton(onClick = { refreshAll() }, enabled = !isWorking) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Yenile", tint = MutedText)
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Ayarlar", tint = MutedText)
                    }
                }
            }
        }

        if (showSettings) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = SurfaceL1,
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, OutlineSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Yama & Özelleştirme", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = AccentCrimson)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Boyut Optimizasyonu", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                                Text("Gereksiz ABI & reklam varlıklarını temizle", fontSize = 12.sp, color = MutedText)
                            }
                            Switch(checked = slimAbis, onCheckedChange = { slimAbis = it }, enabled = !isWorking)
                        }
                        HorizontalDivider(color = OutlineSubtle)
                        OutlinedTextField(
                            value = customAppName,
                            onValueChange = { customAppName = it },
                            label = { Text("Uygulama Adı") },
                            singleLine = true,
                            enabled = !isWorking,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FilledTonalButton(
                            onClick = { iconPicker.launch(arrayOf("image/png", "image/jpeg")) },
                            enabled = !isWorking,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (customIconUri == null) "Özel Uygulama İkonu Seç" else "Özel İkon Seçildi ✓")
                        }
                    }
                }

                Surface(
                    color = SurfaceL1,
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, OutlineSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Sepwoo Manager", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = AccentCrimson)
                        Text("Sürüm: v$managerVersion", fontSize = 14.sp, color = Color.White)
                        Text("Yama Kaynağı: cronus.best/sepwoo", fontSize = 12.sp, color = MutedText)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            FilledTonalButton(
                                onClick = { refreshAll() },
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.weight(1f).height(46.dp)
                            ) {
                                Text("Güncelle Tara", maxLines = 1, softWrap = false, fontSize = 13.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/eyyun/sepwoo-manager"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                },
                                border = BorderStroke(1.dp, OutlineSubtle),
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.weight(1f).height(46.dp)
                            ) {
                                Text("GitHub", maxLines = 1, softWrap = false, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        } else {
            // Revenge-style Home Screen
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(10.dp))

                // Centered Hero Icon (Revenge style)
                Surface(
                    shape = CircleShape,
                    color = AccentCrimson.copy(alpha = 0.16f),
                    modifier = Modifier.size(84.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Image(
                            painter = painterResource(id = R.drawable.sinewix_plus_logo),
                            contentDescription = "Sinewix PLUS",
                            modifier = Modifier.size(64.dp).clip(CircleShape)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = manifest?.appName ?: "Sinewix PLUS",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (installedSinewixVersion != null) {
                        "Kurulu: v$installedSinewixVersion • Yama: v${manifest?.patchVersion ?: "2.6.3-plus.1"}"
                    } else {
                        "Kurulu değil • Yama: v${manifest?.patchVersion ?: "2.6.3-plus.1"}"
                    },
                    fontSize = 13.sp,
                    color = MutedText,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(statusText, fontSize = 12.sp, color = AccentCrimson, textAlign = TextAlign.Center)

                Spacer(Modifier.height(18.dp))

                // Centered Action Pills (Revenge style)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { startPatchAndInstall() },
                        enabled = !isWorking,
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)
                    ) {
                        Text(if (selectedApkUri == null) "İndir & Yamala" else "Yamala & Kur", fontWeight = FontWeight.SemiBold)
                    }
                    FilledTonalButton(
                        onClick = { apkPicker.launch(arrayOf("application/vnd.android.package-archive")) },
                        enabled = !isWorking,
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
                    ) {
                        Text(if (selectedApkUri == null) "APK Seç" else "Seçildi ✓")
                    }
                }

                // Self-Update Pill Card if GitHub has newer version
                val upd = managerUpdate
                if (upd != null) {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        color = AccentCrimson.copy(alpha = 0.14f),
                        contentColor = Color.White,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, AccentCrimson.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !isWorking) { startSelfUpdate(upd) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Manager Güncellemesi (${upd.latestTag})", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("GitHub üzerinden otomatik güncellemek için dokun", fontSize = 12.sp, color = MutedText)
                            }
                            Text("Güncelle", color = AccentCrimson, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))

                // Revenge-style Changelog List Card
                Surface(
                    color = SurfaceL1,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            text = "Değişiklikler & Sürüm Notları",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MutedText,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                        )
                        val notes = manifest?.changelog?.takeIf { it.isNotEmpty() } ?: listOf(
                            "Uygulama adı ve ikonu Sinewix PLUS olarak güncellendi",
                            "VIP kilidi, tüm reklam ağları & sınırsız indirme",
                            "Harici Oynatıcı (VLC / MX) & PiP desteği"
                        )
                        notes.forEachIndexed { idx, note ->
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("sepwoo • v${manifest?.patchVersion ?: "2.6.3-plus.1"}", fontSize = 12.sp, color = MutedText)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(note, fontSize = 14.sp, color = Color.White)
                            }
                            if (idx < notes.lastIndex) {
                                HorizontalDivider(color = OutlineSubtle, modifier = Modifier.padding(horizontal = 18.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showProcessDialog) {
        ProcessStatusDialog(
            title = processTitle,
            isWorking = isWorking,
            currentStep = currentStep,
            stepProgress = stepProgress,
            statusText = statusText,
            logs = processLogs,
            onDismiss = { if (!isWorking) showProcessDialog = false }
        )
    }
}

@Composable
private fun ProcessStatusDialog(
    title: String,
    isWorking: Boolean,
    currentStep: Int,
    stepProgress: Float?,
    statusText: String,
    logs: List<String>,
    onDismiss: () -> Unit
) {
    val steps = listOf(
        "1. Resmi APK / Dosya hazırlığı",
        "2. Paket indirme",
        "3. SHA-256 güvenlik doğrulaması",
        "4. Yerel derleme & özelleştirme",
        "5. APK imzalama & kurulum"
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = !isWorking, dismissOnClickOutside = !isWorking)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SurfaceL1,
            border = BorderStroke(1.dp, OutlineSubtle),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Surface(
                        color = AccentCrimson.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (isWorking) "Adım $currentStep/5" else "Bitti",
                            color = AccentCrimson,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                if (stepProgress != null) {
                    LinearProgressIndicator(
                        progress = { stepProgress },
                        color = AccentCrimson,
                        trackColor = Color(0xFF28282D),
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                } else if (isWorking) {
                    LinearProgressIndicator(
                        color = AccentCrimson,
                        trackColor = Color(0xFF28282D),
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                }

                Text(statusText, fontSize = 13.sp, color = AccentCrimson, fontWeight = FontWeight.Medium)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    steps.forEachIndexed { idx, label ->
                        val stepNum = idx + 1
                        val prefix = when {
                            currentStep > stepNum || (!isWorking && currentStep == 5 && stepNum == 5 && !statusText.startsWith("Hata")) -> "✓"
                            currentStep == stepNum && isWorking -> "▶"
                            else -> "•"
                        }
                        val color = when {
                            prefix == "✓" -> Color(0xFF4ADE80)
                            prefix == "▶" -> Color.White
                            else -> Color(0xFF7A7A82)
                        }
                        Text("$prefix $label", fontSize = 13.sp, color = color)
                    }
                }

                Surface(
                    color = BgDark,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, OutlineSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        logs.takeLast(5).forEach { log ->
                            Text(log, fontSize = 11.sp, color = Color(0xFFA1A1AA))
                        }
                    }
                }

                if (!isWorking) {
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Kapat")
                    }
                }
            }
        }
    }
}
