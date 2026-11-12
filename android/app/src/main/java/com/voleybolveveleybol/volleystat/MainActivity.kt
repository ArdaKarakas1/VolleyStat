package com.voleybolveveleybol.volleystat

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Palette from docs/design.md (the supplied Voleybol Ve Veleybol branding).
private val Paper = Color(0xFFFCF7EB)
private val Ink = Color(0xFF162340)
private val Accent = Color(0xFF9E491B)
private val BrandColors = lightColorScheme(
    primary = Ink, onPrimary = Color.White,
    secondary = Accent, onSecondary = Color.White,
    background = Paper, onBackground = Ink,
    surface = Paper, onSurface = Ink,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = BrandColors) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { VolleyStatHome() }
            }
        }
    }
}

@Composable
private fun VolleyStatHome() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var home by remember { mutableStateOf("") }
    var away by remember { mutableStateOf("") }
    var selectedVideo by remember { mutableStateOf<Uri?>(null) }
    var mediaName by remember { mutableStateOf("") }
    var mediaSize by remember { mutableLongStateOf(0L) }
    var rightsApproved by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf("") }
    var pendingCapture by remember { mutableStateOf<Uri?>(null) }

    fun applyVideo(uri: Uri?) {
        selectedVideo = uri
        val info = uri?.let { readVideoInfo(context.contentResolver, it) }
        mediaName = info?.first ?: ""
        mediaSize = info?.second ?: 0L
        progress = 0f
        result = if (uri != null && info == null) "Seçilen videonun adı ya da boyutu okunamadı." else ""
    }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        applyVideo(uri)
    }
    val captureVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { didRecord ->
        if (didRecord) applyVideo(pendingCapture) else result = "Kamera kaydı iptal edildi."
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Image(painterResource(R.drawable.brand_logo), contentDescription = "Voleybol Ve Veleybol logosu", modifier = Modifier.size(72.dp))
            Column {
                Text("VolleyStat", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Voleybol Ve Veleybol", style = MaterialTheme.typography.labelLarge)
            }
        }
        Text("SALON VOLEYBOLU / 6 × 6", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Accent)
        Text("Maç videosunu seç ya da kaydet. Uygulama videoyu yükler ve sunucu dosyayı kontrol eder. Otomatik analiz modeli henüz bağlı değil. Bu sürüm istatistik üretmez.")
        HorizontalDivider()
        OutlinedTextField(value = home, onValueChange = { home = it }, label = { Text("Ev sahibi takım") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = away, onValueChange = { away = it }, label = { Text("Rakip takım") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
            enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text("Galeriden maç videosu seç") }
        OutlinedButton(
            onClick = {
                val folder = File(context.cacheDir, "camera").apply { mkdirs() }
                val file = File(folder, "match-${System.currentTimeMillis()}.mp4")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                pendingCapture = uri
                runCatching { captureVideo.launch(uri) }.onFailure { result = "Bu cihazda video kaydeden bir kamera uygulaması bulunamadı." }
            },
            enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text("Kamerayla maç kaydet") }
        if (selectedVideo != null && mediaSize > 0) {
            Text("Seçilen: $mediaName · ${mediaSize / 1024 / 1024} MiB")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = rightsApproved, onCheckedChange = { rightsApproved = it }, enabled = !busy)
            Text("Bu videoyu analiz için yükleme hakkım ve iznim var. Model eğitimi için izin vermiyorum.", modifier = Modifier.weight(1f))
        }
        val ready = home.trim().length >= 2 && away.trim().length >= 2 && !home.trim().equals(away.trim(), ignoreCase = true) &&
            selectedVideo != null && mediaSize > 0 && rightsApproved && BuildConfig.API_BASE_URL.isNotBlank()
        Button(
            onClick = {
                val uri = selectedVideo ?: return@Button
                busy = true
                progress = 0f
                result = "Video yerel geliştirme sunucusuna yükleniyor."
                scope.launch {
                    try {
                        val reply = withContext(Dispatchers.IO) {
                            LocalUploadClient(context.contentResolver, BuildConfig.API_BASE_URL).upload(
                                home.trim(), away.trim(), uri, mediaName, mediaSize
                            ) { fraction -> progress = fraction }
                        }
                        result = describeUpload(reply)
                    } catch (e: Exception) {
                        result = "Yükleme başarısız: ${e.message ?: "Bilinmeyen hata"}"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = ready && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text(if (busy) "Yükleniyor" else "Yerel sunucuya yükle") }
        if (busy) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        Text("Güvenlik sınırı", fontWeight = FontWeight.SemiBold)
        Text("Bu sürüm, hesap sistemi olmayan yerel bir test sunucusuna yükler. Gerçek oyuncuların videolarını ve telifli yayınları yüklemeyin.", style = MaterialTheme.typography.bodySmall)
    }
}
