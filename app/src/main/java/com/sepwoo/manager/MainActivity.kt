package com.sepwoo.manager

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sepwoo Manager — Material 3 UI for Sinewix PLUS.
 * Fetches manifest & patch bundles from cronus.best and patches SineWix locally on-device.
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

@Composable
fun SepwooManagerTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = Color(0xFFFF3B5C),
        background = Color(0xFF0B0B0B),
        surface = Color(0xFF171818),
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

    var manifest by remember { mutableStateOf<LocalApkPatcher.ManifestInfo?>(null) }
    var selectedApkUri by remember { mutableStateOf<Uri?>(null) }
    var customIconUri by remember { mutableStateOf<Uri?>(null) }
    var customAppName by remember { mutableStateOf("Sinewix PLUS") }
    var statusText by remember { mutableStateOf("cronus.best üzerinden güncel yama manifesti kontrol ediliyor...") }
    var slimAbis by remember { mutableStateOf(true) }
    var isWorking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) { LocalApkPatcher.fetchManifest() }
        }.onSuccess { info ->
            manifest = info
            statusText = "Hazır • Güncel yama: v${info.patchVersion}"
        }.onFailure { err ->
            statusText = "Manifest alınamadı: ${err.localizedMessage ?: "Bağlantı hatası"}"
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
            .background(Color(0xFF0B0B0B))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Sepwoo Manager", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Sinewix PLUS • Yerel APK Yamalayıcı", fontSize = 14.sp, color = Color(0xFF9E9EA4))

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF171818)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Hedef Uygulama: ${manifest?.appName ?: "Sinewix PLUS"}", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("Yama Kaynağı: ${LocalApkPatcher.DEFAULT_MANIFEST_URL}", fontSize = 13.sp, color = Color(0xFFB5B5BC))
                Text("Durum: $statusText", fontSize = 13.sp, color = Color(0xFFFF3B5C))
                if (isWorking) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFFFF3B5C))
                }
            }
        }

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF171818)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Boyut Optimizasyonu", fontWeight = FontWeight.Medium)
                    Text("Kullanılmayan ABI & reklam varlıklarını temizle (~78.2 MB)", fontSize = 12.sp, color = Color(0xFF9E9EA4))
                }
                Switch(checked = slimAbis, onCheckedChange = { slimAbis = it }, enabled = !isWorking)
            }
        }

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF171818)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Uygulama İsim & İkon Değiştirici", fontWeight = FontWeight.SemiBold)
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
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (customIconUri == null) "Özel İkon Seç (Varsayılan: Sinewix PLUS)" else "Özel İkon Seçildi ✓")
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { apkPicker.launch(arrayOf("application/vnd.android.package-archive")) },
                enabled = !isWorking,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).height(50.dp)
            ) {
                Text(if (selectedApkUri == null) "Resmi APK Seç" else "APK Seçildi ✓")
            }
            Button(
                enabled = !isWorking,
                onClick = {
                    scope.launch {
                        isWorking = true
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val info = manifest ?: LocalApkPatcher.fetchManifest().also { manifest = it }
                                val officialApk = File(context.cacheDir, "official_sinewix.apk")
                                val bundleZip = File(context.cacheDir, "sepwoo_bundle.zip")
                                val signedApk = File(context.filesDir, "Sinewix_PLUS_signed.apk")

                                val pickedUri = selectedApkUri
                                if (pickedUri != null) {
                                    statusText = "Seçilen resmi APK kopyalanıyor..."
                                    context.contentResolver.openInputStream(pickedUri)?.use { input ->
                                        officialApk.outputStream().use { output -> input.copyTo(output) }
                                    } ?: error("Seçilen APK açılamadı")
                                } else {
                                    LocalApkPatcher.downloadFile(info.officialApkUrl, officialApk, "Resmi SineWix APK") {
                                        statusText = it
                                    }
                                }

                                LocalApkPatcher.downloadFile(info.patchBundleUrl, bundleZip, "cronus.best yama paketi") {
                                    statusText = it
                                }

                                if (info.patchBundleSha256.isNotBlank()) {
                                    val actual = LocalApkPatcher.sha256Hex(bundleZip)
                                    require(actual.equals(info.patchBundleSha256, ignoreCase = true)) {
                                        "Yama SHA-256 doğrulaması başarısız!"
                                    }
                                }

                                val iconBytes = customIconUri?.let { LocalApkPatcher.renderRoundedIconPng(context, it) }
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
                                ) { statusText = it }

                                withContext(Dispatchers.Main) {
                                    statusText = "Hazır! Android yükleyicisi açılıyor..."
                                    LocalApkPatcher.installApk(context, signedApk)
                                }
                            }
                        }.onFailure { err ->
                            statusText = "Hata: ${err.localizedMessage ?: err.javaClass.simpleName}"
                        }
                        isWorking = false
                    }
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).height(50.dp)
            ) {
                Text(if (selectedApkUri == null) "İndir & Yamala" else "Yamala & Kur")
            }
        }

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF171818)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Sürüm Notları (v${manifest?.patchVersion ?: "2.6.3-plus.1"})", fontWeight = FontWeight.SemiBold)
                val notes = manifest?.changelog?.takeIf { it.isNotEmpty() } ?: listOf(
                    "Uygulama adı ve ikonu Sinewix PLUS olarak güncellendi",
                    "VIP kilidi, tüm reklam ağları & sınırsız indirme",
                    "Harici Oynatıcı (VLC / MX) & PiP desteği"
                )
                notes.forEach { note ->
                    Text("• $note", fontSize = 13.sp, color = Color(0xFFB5B5BC))
                }
                Text("• GitHub: github.com/eyyun/sepwoo-manager", fontSize = 13.sp, color = Color(0xFFFF3B5C))
            }
        }
    }
}
