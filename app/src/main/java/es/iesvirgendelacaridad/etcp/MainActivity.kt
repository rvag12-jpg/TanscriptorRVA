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
import es.iesvirgendelacaridad.etcp.pdf.PdfExporter
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
        var transcript by remember { mutableStateOf("") }
        var summary by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("Preparado. No se necesita API key.") }
        var meetingDate by remember {
            mutableStateOf(LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")))
        }

        val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                audio = uri
                status = "Audio seleccionado. Pulsa «Enviar audio a Sider»."
            }
        }

        val transcriptPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("No se pudo leer el archivo")
                }.onSuccess {
                    transcript = it
                    status = "Transcripción importada. Revisa o pega el resumen de Sider."
                }.onFailure {
                    status = "Error al importar: ${it.message}"
                }
            }
        }

        Scaffold(topBar = { TopAppBar(title = { Text("TanscriptorRVA") }) }) { padding ->
            Column(
                Modifier.padding(padding).padding(16.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = meetingDate,
                    onValueChange = { meetingDate = it },
                    label = { Text("Fecha de la reunión (dd/MM/aaaa)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Flujo sin API: selecciona el audio, compártelo con Sider, exporta o copia la transcripción y vuelve para generar el PDF.")

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { audioPicker.launch(arrayOf("audio/mp4", "audio/x-m4a", "audio/*")) }) {
                        Text("Seleccionar audio")
                    }
                    Button(
                        enabled = audio != null,
                        onClick = {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "audio/*"
                                putExtra(Intent.EXTRA_STREAM, audio)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching {
                                startActivity(Intent.createChooser(sendIntent, "Enviar audio a Sider"))
                                status = "Selecciona Sider en el menú Compartir."
                            }.onFailure {
                                status = "No se pudo abrir el menú Compartir: ${it.message}"
                            }
                        }
                    ) { Text("Enviar a Sider") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { transcriptPicker.launch(arrayOf("text/plain", "text/*", "application/json")) }) {
                        Text("Importar transcripción")
                    }
                    OutlinedButton(onClick = {
                        val launchIntent = packageManager.getLaunchIntentForPackage("com.sider.ai")
                        if (launchIntent != null) {
                            startActivity(launchIntent)
                        } else {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://sider.ai/")))
                        }
                    }) { Text("Abrir Sider") }
                }

                Text(status)

                OutlinedTextField(
                    value = transcript,
                    onValueChange = { transcript = it },
                    label = { Text("Transcripción de Sider") },
                    placeholder = { Text("Importa un TXT o pega aquí la transcripción") },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )

                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("Resumen ETCP editable") },
                    placeholder = { Text("Pega aquí el resumen generado por Sider o redacta el resumen final") },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )

                Button(
                    enabled = summary.isNotBlank() || transcript.isNotBlank(),
                    onClick = {
                        val content = summary.ifBlank { transcript }
                        runCatching {
                            val file = PdfExporter.create(
                                this@MainActivity,
                                "Reunión ETCP",
                                meetingDate,
                                content
                            )
                            status = "PDF generado: ${file.absolutePath}"
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
