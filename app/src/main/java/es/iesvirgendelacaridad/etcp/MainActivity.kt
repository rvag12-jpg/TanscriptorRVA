package es.iesvirgendelacaridad.etcp

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import es.iesvirgendelacaridad.etcp.audio.AudioSegmenter
import es.iesvirgendelacaridad.etcp.network.OpenAiClient
import es.iesvirgendelacaridad.etcp.pdf.PdfExporter
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { MaterialTheme { EtcpApp() } } }
 @OptIn(ExperimentalMaterial3Api::class)
 @Composable private fun EtcpApp() {
  var apiKey by remember { mutableStateOf("") }; var audio by remember { mutableStateOf<Uri?>(null) }; var transcript by remember { mutableStateOf("") }; var summary by remember { mutableStateOf("") }; var status by remember { mutableStateOf("Preparado") }; var meetingDate by remember { mutableStateOf(LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))) }; val scope=rememberCoroutineScope()
  val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);audio=uri;status="Audio seleccionado"}}
  Scaffold(topBar={TopAppBar(title={Text("TanscriptorRVA")})}){pad->Column(Modifier.padding(pad).padding(16.dp).fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)){
   OutlinedTextField(meetingDate,{meetingDate=it},label={Text("Fecha de la reunión (dd/MM/aaaa)")},singleLine=true,modifier=Modifier.fillMaxWidth()); OutlinedTextField(apiKey,{apiKey=it},label={Text("API key de OpenAI")},singleLine=true,modifier=Modifier.fillMaxWidth()); Button(onClick={picker.launch(arrayOf("audio/mp4","audio/x-m4a","audio/*"))}){Text("Seleccionar audio M4A")}; Text(status)
   Button(enabled=audio!=null&&apiKey.isNotBlank(),onClick={scope.launch{runCatching{status="Segmentando audio…";val parts=AudioSegmenter.segment(this@MainActivity,audio!!);val client=OpenAiClient(apiKey);val sb=StringBuilder();parts.forEachIndexed{i,f->status="Transcribiendo ${i+1}/${parts.size}…";sb.append(client.transcribeFile(f)).append("\n")};transcript=sb.toString();status="Analizando reunión…";summary=client.summarizeEtcp(transcript);status="Resumen generado";parts.forEach{it.delete()}}.onFailure{status="Error: ${it.message}"}}}){Text("Transcribir y analizar")}
   if(transcript.isNotBlank())OutlinedTextField(transcript,{transcript=it},label={Text("Transcripción")},modifier=Modifier.fillMaxWidth().weight(1f)); if(summary.isNotBlank()){OutlinedTextField(summary,{summary=it},label={Text("Resumen JSON editable")},modifier=Modifier.fillMaxWidth().weight(1f));Button(onClick={runCatching{val f=PdfExporter.create(this@MainActivity,"Reunión ETCP",meetingDate,summary);status="PDF: ${f.absolutePath}"}.onFailure{status="Error PDF: ${it.message}"}}){Text("Generar PDF")}}
  }}
 }
}
