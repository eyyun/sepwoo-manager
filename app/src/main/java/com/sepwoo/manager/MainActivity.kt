package com.sepwoo.manager

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sepwoo Manager — Revenge-style Material 3 UI for Sinewix PLUS.
 * Supports GitHub self-updates, cronus.best manifest/patch fetching,
 * on-device APK patching & apksig signing, and automatic crash reporting.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SepwooManagerTheme {
                SepwooManagerHome()
            }
        }
    }
}

private val BgDark = Color(0xFF09090B)
private val CardDark = Color(0xFF16161A)
private val CardBorder = Color(0xFF26262C)
private val AccentCrimson = Color(0xFFFF3B5C)
private val MutedText = Color(0xFF9E9EA8)

@Composable
fun SepwooManagerTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = AccentCrimson,
        background = BgDark,
        surface = CardDark,
        onPrimary = Color.White,
        onBackground = Color.White,
        onSurface = Color.White
    )
    MaterialTheme(colorScheme = darkColors, content = content)
}

@Composable
fun SepwooManagerHome() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val managerVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.2" }
            .getOrDefault("1.0.2")
    }
    val installedSinewixVersion = remember {
        runCatching { context.packageManager.getPackageInfo("com.wxsmash.game", 0).versionName }.getOrNull()
    }

    var manifest by remember { mutableStateOf<LocalApkPatcher.ManifestInfo?>(null) }
    var managerUpdate by remember { mutableStateOf<LocalApkPatcher.ManagerUpdateInfo?>(null) }
    var selectedApkUri by remember { mutableStateOf<Uri?>(null) }
    var customIconUri by remember { mutableStateOf<Uri?>(null) }
    var customAppName by remember { mutableStateOf("Sinewix PLUS") }
    var statusText by remember { mutableStateOf("cronus.best üzerinden yama manifesti kontrol ediliyor...") }
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

    LaunchedEffect(Unit) {
        launch(Dispatchers.IO) {
            runCatching { LocalApkPatcher.fetchManifest() }.onSuccess { info ->
                withContext(Dispatchers.Main) {
                    manifest = info
                    statusText = "Hazır • Güncel yama: v${info.patchVersion}"
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    statusText = "Manifest alınamadı: ${err.localizedMessage ?: "Bağlantı hatası"}"
                }
            }
        }
        launch(Dispatchers.IO) {
            val upd = LocalApkPatcher.checkManagerUpdate(context)
            withContext(Dispatchers.Main) { managerUpdate = upd }
        }
    }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Header (Revenge-style)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Sepwoo Manager", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Sinewix PLUS • Yerel APK Yamalayıcı", fontSize = 13.sp, color = MutedText)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = CardDark,
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, CardBorder),
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/eyyun/sepwoo-manager"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                ) {
                    Text(
                        text = "GitHub • v$managerVersion",
                        fontSize = 12.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Manager Self-Update Banner
        val upd = managerUpdate
        if (upd != null) {
            Surface(
                color = AccentCrimson.copy(alpha = 0.14f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, AccentCrimson.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Manager Güncellemesi (${upd.latestTag})", fontWeight = FontWeight.Bold, color = Color.White)
                        Text("GitHub üzerinde yeni Sepwoo Manager sürümü hazır.", fontSize = 12.sp, color = Color(0xFFE4E4E7))
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        enabled = !isWorking,
                        onClick = {
                            if (!ensureInstallPermission()) return@Button
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
                                    withContext(Dispatchers.IO) {
                                        LocalApkPatcher.reportError(context, "manager_self_update", err, processLogs.toList())
                                    }
                                }
                                isWorking = false
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Güncelle")
                    }
                }
            }
        }

        // Target App Status Card (Revenge-style)
        Surface(
            color = CardDark,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, CardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(manifest?.appName ?: "Sinewix PLUS", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            text = if (installedSinewixVersion != null) "Kurulu sürüm: v$installedSinewixVersion" else "Kurulu değil • Hedef: v${manifest?.targetVersionName ?: "2.6.3"}",
                            fontSize = 12.sp,
                            color = MutedText
                        )
                    }
                    Surface(
                        color = AccentCrimson.copy(alpha = 0.16f),
                        shape = CircleShape
                    ) {
                        Text(
                            text = "v${manifest?.patchVersion ?: "2.6.3-plus.1"}",
                            color = AccentCrimson,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
                HorizontalDivider(color = CardBorder)
                Text("Kaynak: ${LocalApkPatcher.DEFAULT_MANIFEST_URL}", fontSize = 12.sp, color = MutedText)
                Text(statusText, fontSize = 13.sp, color = AccentCrimson, fontWeight = FontWeight.Medium)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { apkPicker.launch(arrayOf("application/vnd.android.package-archive")) },
                        enabled = !isWorking,
                        border = BorderStroke(1.dp, CardBorder),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Text(if (selectedApkUri == null) "Resmi APK Seç" else "APK Seçildi ✓")
                    }
                    Button(
                        enabled = !isWorking,
                        onClick = {
                            if (!ensureInstallPermission()) return@Button
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
                                    if (sent) {
                                        pushLog("Hata raporu cronus.best sunucusuna iletildi ✓")
                                    }
                                }
                                isWorking = false
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Text(if (selectedApkUri == null) "İndir & Yamala" else "Yamala & Kur")
                    }
                }
            }
        }

        // Options & Customization Card
        Surface(
            color = CardDark,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, CardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Yama & Özelleştirme Ayarları", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Boyut Optimizasyonu", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text("Gereksiz ABI & reklam varlıklarını temizle (~78.3 MB)", fontSize = 12.sp, color = MutedText)
                    }
                    Switch(checked = slimAbis, onCheckedChange = { slimAbis = it }, enabled = !isWorking)
                }
                HorizontalDivider(color = CardBorder)
                OutlinedTextField(
                    value = customAppName,
                    onValueChange = { customAppName = it },
                    label = { Text("Uygulama Adı") },
                    singleLine = true,
                    enabled = !isWorking,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { iconPicker.launch(arrayOf("image/png", "image/jpeg")) },
                    enabled = !isWorking,
                    border = BorderStroke(1.dp, CardBorder),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (customIconUri == null) "Özel İkon Seç (Varsayılan: Sinewix PLUS)" else "Özel İkon Seçildi ✓")
                }
            }
        }

        // Changelog Card
        Surface(
            color = CardDark,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, CardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Sürüm Notları (v${manifest?.patchVersion ?: "2.6.3-plus.1"})", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                val notes = manifest?.changelog?.takeIf { it.isNotEmpty() } ?: listOf(
                    "Uygulama adı ve ikonu Sinewix PLUS olarak güncellendi",
                    "VIP kilidi, tüm reklam ağları & sınırsız indirme",
                    "Harici Oynatıcı (VLC / MX) & PiP desteği"
                )
                notes.forEach { note ->
                    Text("• $note", fontSize = 13.sp, color = Color(0xFFB5B5BC))
                }
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
            color = CardDark,
            border = BorderStroke(1.dp, CardBorder),
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
                    border = BorderStroke(1.dp, CardBorder),
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
