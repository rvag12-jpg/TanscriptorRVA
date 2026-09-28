package es.iesvirgendelacaridad.etcp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.core.content.ContextCompat
import es.iesvirgendelacaridad.etcp.audio.AudioSegmenter
import es.iesvirgendelacaridad.etcp.audio.MeetingRecordingService
import es.iesvirgendelacaridad.etcp.pdf.PdfExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    private val recordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MeetingRecordingService.ACTION_RECORDING_SAVED) return
            val uriText = intent.getStringExtra(MeetingRecordingService.EXTRA_URI)
            val error = intent.getStringExtra(MeetingRecordingService.EXTRA_ERROR)
            pendingRecordingUri = uriText?.let(Uri::parse)
            pendingRecordingError = error
        }
    }

    private var pendingRecordingUri by mutableStateOf<Uri?>(null)
    private var pendingRecordingError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(
            this,
            recordingReceiver,
            IntentFilter(MeetingRecordingService.ACTION_RECORDING_SAVED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        setContent { MaterialTheme { EtcpApp() } }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(recordingReceiver) }
        super.onDestroy()
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
        var status by remember { mutableStateOf("Preparado. Puedes grabar una reunión o cargar un audio existente.") }
        var recording by remember { mutableStateOf(MeetingRecordingService.isRecording) }
        var recordingSeconds by remember { mutableLongStateOf(0L) }
        val scope = rememberCoroutineScope()
        var meetingDate by remember {
            mutableStateOf(LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")))
        }

        fun displayName(uri: Uri): String {
            var name = uri.lastPathSegment ?: "audio"
            runCatching {
                contentResolver.query(
                    uri,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: name
                }
            }
            return name
        }

        fun useAudio(uri: Uri, message: String) {
            if (segments.isNotEmpty()) AudioSegmenter.deleteSegments(this@MainActivity, segments)
            audio = uri
            audioName = displayName(uri)
            segments = emptyList()
            selectedPart = 0
            status = message
        }

        fun shareAudio(uri: Uri, title: String, prompt: String? = null) {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                prompt?.let { putExtra(Intent.EXTRA_TEXT, it) }
                clipData = ClipData.newUri(contentResolver, "audio", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                startActivity(Intent.createChooser(sendIntent, title))
            }.onFailure {
                status = "No se pudo compartir el audio: ${it.message}"
            }
        }

        fun chatGptPrompt(): String = """
            Transcribe íntegramente esta reunión en español y, después, genera un resumen estructurado.
            No inventes información ni acuerdos. Separa claramente hechos, propuestas y acuerdos.
            Si no puedes identificar a una persona, usa Hablante 1, Hablante 2, etc.
            
            Devuelve al final un JSON válido con esta estructura exacta:
            {
              "sintesis_ejecutiva": "...",
              "temas": [{"titulo":"...","tipo":"información|debate|decisión","resumen":"..."}],
              "propuestas": [{"texto":"..."}],
              "acuerdos": [{"texto":"...","responsable":"","plazo":""}],
              "pendientes": ["..."],
              "observaciones": ["..."]
            }
        """.trimIndent()

        fun copyPrompt() {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Prompt reunión", chatGptPrompt()))
            status = "Instrucciones copiadas. Compártelas junto con el audio en ChatGPT."
        }

        val microphonePermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            val micGranted = grants[Manifest.permission.RECORD_AUDIO] == true ||
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

            if (micGranted) {
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, MeetingRecordingService::class.java).apply {
                        action = MeetingRecordingService.ACTION_START
                    }
                )
                recording = true
                recordingSeconds = 0L
                status = "Grabando reunión. La grabación continúa con la pantalla bloqueada."
            } else {
                status = "Se necesita permiso de micrófono para grabar."
            }
        }

        val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                useAudio(uri, "Audio cargado: ${displayName(uri)}.")
            }
        }

        val transcriptPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("No se pudo leer el archivo")
                }.onSuccess {
                    transcript = it
                    status = "Transcripción importada."
                }.onFailure {
                    status = "Error al importar: ${it.message}"
                }
            }
        }

        LaunchedEffect(recording) {
            while (recording) {
                delay(1000)
                recordingSeconds++
                recording = MeetingRecordingService.isRecording
            }
        }

        LaunchedEffect(pendingRecordingUri, pendingRecordingError) {
            pendingRecordingUri?.let { uri ->
                useAudio(uri, "Grabación guardada: ${displayName(uri)}.")
                pendingRecordingUri = null
                recording = false
            }
            pendingRecordingError?.let {
                status = "Error de grabación: $it"
                pendingRecordingError = null
                recording = false
            }
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text("TanscriptorRVA 1.2.0") }) }
        ) { padding ->
            Column(
                Modifier.padding(padding).padding(16.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = meetingDate,
                    onValueChange = { meetingDate = it },
                    label = { Text("Fecha de la reunión (dd/MM/aaaa)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            if (recording) {
                                val m = recordingSeconds / 60
                                val s = recordingSeconds % 60
                                "Grabando · %02d:%02d".format(m, s)
                            } else {
                                "Grabador de reuniones"
                            }
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !recording && !busy,
                                onClick = {
                                    val permissions = buildList {
                                        add(Manifest.permission.RECORD_AUDIO)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            add(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                    microphonePermissionLauncher.launch(permissions.toTypedArray())
                                }
                            ) { Text("Iniciar grabación") }

                            Button(
                                enabled = recording,
                                onClick = {
                                    startService(
                                        Intent(
                                            this@MainActivity,
                                            MeetingRecordingService::class.java
                                        ).apply {
                                            action = MeetingRecordingService.ACTION_STOP
                                        }
                                    )
                                    status = "Cerrando y guardando la grabación…"
                                }
                            ) { Text("Detener") }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !recording && !busy,
                        onClick = { audioPicker.launch(arrayOf("*/*")) }
                    ) { Text("Cargar audio") }

                    Button(
                        enabled = audio != null && !busy && !recording,
                        onClick = {
                            val source = audio ?: return@Button
                            busy = true
                            status = "Comprobando y preparando fragmentos…"
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        AudioSegmenter.segment(this@MainActivity, source, 45)
                                    }
                                }.onSuccess {
                                    segments = it
                                    selectedPart = 0
                                    status = "Audio preparado en ${it.size} parte(s)."
                                }.onFailure {
                                    status = "No se pudo procesar «$audioName»: ${it.message}"
                                }
                                busy = false
                            }
                        }
                    ) { Text(if (busy) "Procesando…" else "Preparar audio") }
                }

                if (audio != null) {
                    Text("Audio actual: $audioName")

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            copyPrompt()
                            shareAudio(
                                audio!!,
                                "Enviar reunión a ChatGPT",
                                chatGptPrompt()
                            )
                        }) { Text("Enviar a ChatGPT") }

                        OutlinedButton(onClick = { copyPrompt() }) {
                            Text("Copiar instrucciones")
                        }
                    }
                }

                if (segments.isNotEmpty()) {
                    Text("Fragmentos: ${segments.size} · Seleccionado: ${selectedPart + 1}/${segments.size}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = selectedPart > 0,
                            onClick = { selectedPart-- }
                        ) { Text("Anterior") }

                        Button(onClick = {
                            shareAudio(
                                segments[selectedPart],
                                "Enviar parte ${selectedPart + 1} de ${segments.size}",
                                chatGptPrompt()
                            )
                        }) { Text("Enviar parte ${selectedPart + 1}") }

                        OutlinedButton(
                            enabled = selectedPart < segments.lastIndex,
                            onClick = { selectedPart++ }
                        ) { Text("Siguiente") }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        transcriptPicker.launch(
                            arrayOf("text/plain", "text/*", "application/json", "*/*")
                        )
                    }) { Text("Importar transcripción") }

                    OutlinedButton(onClick = {
                        val launchIntent = packageManager.getLaunchIntentForPackage("com.openai.chatgpt")
                        if (launchIntent != null) {
                            startActivity(launchIntent)
                        } else {
                            status = "No se encontró la app de ChatGPT. Usa «Enviar a ChatGPT» y selecciona una app compatible."
                        }
                    }) { Text("Abrir ChatGPT") }
                }

                Text(status)

                OutlinedTextField(
                    value = transcript,
                    onValueChange = { transcript = it },
                    label = { Text("Transcripción") },
                    placeholder = { Text("Importa o pega aquí la transcripción completa") },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )

                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("Resumen / JSON de acta") },
                    placeholder = { Text("Pega aquí el JSON generado por ChatGPT o un resumen en texto") },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )

                Button(
                    enabled = summary.isNotBlank() || transcript.isNotBlank(),
                    onClick = {
                        val content = summary.ifBlank { transcript }
                        runCatching {
                            PdfExporter.create(
                                this@MainActivity,
                                "Reunión",
                                meetingDate,
                                content
                            )
                        }.onSuccess {
                            status = "PDF generado: ${it.absolutePath}"
                        }.onFailure {
                            status = "Error al generar el PDF: ${it.message}"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Generar PDF") }
            }
        }
    }
}
