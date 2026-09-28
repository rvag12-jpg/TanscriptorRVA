package es.iesvirgendelacaridad.etcp.pdf

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import org.json.JSONObject
import java.io.File

object PdfExporter {
    fun create(context: Context, title: String, date: String, content: String): File {
        val pdf = PdfDocument()
        val paint = Paint().apply { textSize = 11f }
        val bold = Paint().apply { textSize = 15f; isFakeBoldText = true }
        val w = 595
        val h = 842
        val margin = 45
        var pageNo = 1
        var y = 60f
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())

        fun newPage() {
            pdf.finishPage(page)
            pageNo++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
            y = 55f
        }

        fun line(text: String, heading: Boolean = false) {
            val p = if (heading) bold else paint
            val max = if (heading) 62 else 88
            val normalized = text.ifBlank { "—" }
            normalized.lines().forEach { sourceLine ->
                val chunks = if (sourceLine.isEmpty()) listOf("") else sourceLine.chunked(max)
                chunks.forEach { part ->
                    if (y > h - 55) newPage()
                    page.canvas.drawText(part, margin.toFloat(), y, p)
                    y += if (heading) 23f else 16f
                }
            }
        }

        line("RESUMEN DE REUNIÓN", true)
        line(title)
        line("Fecha: $date")
        y += 8

        val json = runCatching { JSONObject(content.trim()) }.getOrNull()

        if (json == null) {
            line("Contenido", true)
            line(content)
        } else {
            line("1. Síntesis ejecutiva", true)
            line(json.optString("sintesis_ejecutiva"))
            y += 8

            line("2. Temas tratados", true)
            json.optJSONArray("temas")?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    line("• ${o.optString("titulo")} [${o.optString("tipo")}]")
                    line(o.optString("resumen"))
                }
            }
            y += 8

            line("3. Propuestas", true)
            json.optJSONArray("propuestas")?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i)
                    line("• ${o?.optString("texto").orEmpty()}")
                }
            }
            y += 8

            line("4. Acuerdos", true)
            json.optJSONArray("acuerdos")?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    line("• ${o.optString("texto")}")
                    if (o.optString("responsable").isNotBlank()) {
                        line("  Responsable: ${o.optString("responsable")}")
                    }
                    if (o.optString("plazo").isNotBlank()) {
                        line("  Plazo: ${o.optString("plazo")}")
                    }
                }
            }
            y += 8

            line("5. Asuntos pendientes", true)
            json.optJSONArray("pendientes")?.let { a ->
                for (i in 0 until a.length()) line("• ${a.optString(i)}")
            }
            y += 8

            line("6. Observaciones", true)
            json.optJSONArray("observaciones")?.let { a ->
                for (i in 0 until a.length()) line("• ${a.optString(i)}")
            }
        }

        pdf.finishPage(page)
        val file = File(
            context.getExternalFilesDir(null),
            "Resumen_Reunion_${date.replace('/', '-')}.pdf"
        )
        file.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        return file
    }
}
