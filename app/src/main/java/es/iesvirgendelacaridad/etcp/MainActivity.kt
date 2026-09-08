package es.iesvirgendelacaridad.etcp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import es.iesvirgendelacaridad.etcp.audio.AudioSegmenter
import es.iesvirgendelacaridad.etcp.pdf.PdfExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { EtcpApp() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun EtcpApp() {
        var audio by remember { mutableStateOf<Uri?>(null) }
        var audioName by remember { mutableStateOf("") }
        var segments by remember { mutableStateOf<List<Uri>>(emptyList()) }
        var selectedPart by remember { mutableIntStateOf(0) }
        var busy by remember { mutableStateOf(false) }
        var transcript by remember { mutableStateOf("") }
        var summary by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("Preparado. No se necesita API key.") }
        val scope = rememberCoroutineScope()
        var meetingDate by remember { mutableStateOf(LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))) }

        fun displayName(uri: Uri): String {
            var name = uri.lastPathSegment ?: "audio"
            runCatching {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: name
                }
            }
            return name
        }

        fun share(uri: Uri, part: Int, total: Int) {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newUri(contentResolver, "audio", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                startActivity(Intent.createChooser(sendIntent, "Enviar parte $part de $total a Sider"))
                status = "Parte $part de $total preparada. Selecciona Sider en Compartir."
            }.onFailure { status = "No se pudo compartir: ${it.message}" }
        }

        // Use */* deliberately: Samsung My Files, Downloads, Drive and some recorders
        // expose .m4a as application/octet-stream rather than audio/mp4. Filtering only
        // audio/* makes a valid M4A visible but disabled/unselectable.
        val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                if (segments.isNotEmpty()) AudioSegmenter.deleteSegments(this@MainActivity, segments)
                audio = uri
                audioName = displayName(uri)
                segments = emptyList()
                selectedPart = 0
                status = "Audio cargado: $audioName. Pulsa «Preparar para Sider»."
            }
        }

        val transcriptPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("No se pudo leer el archivo")
                }.onSuccess {
                    transcript = it
                    status = "Transcripción importada. Puedes revisarla y generar el PDF."
                }.onFailure { status = "Error al importar: ${it.message}" }
            }
        }

        Scaffold(topBar = { TopAppBar(title = { Text("TanscriptorRVA 1.1.1") }) }) { padding ->
            Column(Modifier.padding(padding).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = meetingDate, onValueChange = { meetingDate = it }, label = { Text("Fecha de la reunión (dd/MM/aaaa)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Sin API: selecciona cualquier archivo desde Descargas, Mis archivos, Drive u otro proveedor. La app comprobará el audio al prepararlo.")

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { audioPicker.launch(arrayOf("*/*")) }) { Text("Cargar audio") }
                    Button(enabled = audio != null && !busy, onClick = {
                        val source = audio ?: return@Button
                        busy = true
                        status = "Comprobando y preparando fragmentos…"
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { AudioSegmenter.segment(this@MainActivity, source, 50) } }
                                .onSuccess { segments = it; selectedPart = 0; status = "Audio preparado en ${it.size} parte(s). Pulsa «Enviar parte»." }
                                .onFailure { status = "No se pudo procesar «$audioName»: ${it.message}. Selecciona un archivo M4A/MP4 de audio válido." }
                            busy = false
                        }
                    }) { Text(if (busy) "Procesando…" else "Preparar para Sider") }
                }

                if (audio != null) Text("Archivo seleccionado: $audioName")

                if (segments.isNotEmpty()) {
                    Text("Fragmentos: ${segments.size} · Seleccionado: ${selectedPart + 1}/${segments.size}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = selectedPart > 0, onClick = { selectedPart-- }) { Text("Anterior") }
                        Button(onClick = { share(segments[selectedPart], selectedPart + 1, segments.size) }) { Text("Enviar parte ${selectedPart + 1}") }
                        OutlinedButton(enabled = selectedPart < segments.lastIndex, onClick = { selectedPart++ }) { Text("Siguiente") }
                    }
                    OutlinedButton(onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "audio/*"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(segments))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { startActivity(Intent.createChooser(sendIntent, "Enviar ${segments.size} partes")) }
                            .onFailure { status = "No se pudieron compartir todas las partes: ${it.message}" }
                    }) { Text("Compartir todas las partes") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { transcriptPicker.launch(arrayOf("text/plain", "text/*", "application/json", "*/*")) }) { Text("Importar transcripción") }
                    OutlinedButton(onClick = {
                        val launchIntent = packageManager.getLaunchIntentForPackage("com.sider.ai")
                        if (launchIntent != null) startActivity(launchIntent)
                        else status = "Sider no está instalado. Usa «Enviar parte» y selecciona una app compatible en Compartir."
                    }) { Text("Abrir Sider") }
                }

                Text(status)
                OutlinedTextField(value = transcript, onValueChange = { transcript = it }, label = { Text("Transcripción") }, placeholder = { Text("Importa un TXT o pega aquí las transcripciones") }, modifier = Modifier.fillMaxWidth().weight(1f))
                OutlinedTextField(value = summary, onValueChange = { summary = it }, label = { Text("Resumen ETCP editable") }, placeholder = { Text("Pega o redacta aquí el resumen final") }, modifier = Modifier.fillMaxWidth().weight(1f))
                Button(enabled = summary.isNotBlank() || transcript.isNotBlank(), onClick = {
                    val content = summary.ifBlank { transcript }
                    runCatching { PdfExporter.create(this@MainActivity, "Reunión ETCP", meetingDate, content) }
                        .onSuccess { status = "PDF generado: ${it.absolutePath}" }
                        .onFailure { status = "Error al generar el PDF: ${it.message}" }
                }, modifier = Modifier.fillMaxWidth()) { Text("Generar PDF") }
            }
        }
    }
}
