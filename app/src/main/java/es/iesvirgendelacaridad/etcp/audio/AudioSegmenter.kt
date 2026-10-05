package es.iesvirgendelacaridad.etcp.audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

object AudioSegmenter {
    const val MAX_MINUTES = BalancedSegmentPlan.MAX_MINUTES
    data class Part(val uri: Uri, val startUs: Long, val durationMs: Long)

    /** Plans balanced cuts, then copies every encoded packet exactly once. */
    fun segment(context: Context, uri: Uri, maxMinutes: Int = MAX_MINUTES): List<Part> {
        require(maxMinutes in 1..MAX_MINUTES) { "El límite debe estar entre 1 y 45 minutos" }
        val maxDurationUs = maxMinutes * 60L * 1_000_000L
        val extractor = MediaExtractor()
        val created = mutableListOf<Uri>()
        val parts = mutableListOf<Part>()
        var writer: PartWriter? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No se encontró una pista de audio")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)
            require(mime in setOf("audio/mp4a-latm", "audio/3gpp", "audio/amr-wb")) {
                "Este audio ($mime) necesita convertirse a M4A/AAC antes de dividirlo. Las grabaciones de la aplicación ya usan ese formato."
            }
            val declaredDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else -1L
            var packetDurationUs = when (mime) {
                "audio/3gpp", "audio/amr-wb" -> 20_000L
                else -> {
                    val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    require(sampleRate > 0) { "Frecuencia de muestreo inválida" }
                    (1024L * 1_000_000L + sampleRate - 1L) / sampleRate
                }
            }
            val boundaries = mutableListOf<Long>()
            while (extractor.sampleTime >= 0) {
                boundaries += extractor.sampleTime
                extractor.advance()
            }
            require(boundaries.isNotEmpty()) { "El archivo no contiene muestras de audio" }
            if (boundaries.size > 1) packetDurationUs = boundaries.last() - boundaries[boundaries.lastIndex - 1]
            val finalEnd = if (declaredDurationUs > boundaries.last()) declaredDurationUs else boundaries.last() + packetDurationUs
            boundaries += finalEnd
            val cuts = BalancedSegmentPlan.create(boundaries.toLongArray(), maxMinutes)
            extractor.seekTo(cuts.first(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            check(extractor.sampleTime == cuts.first()) { "No se pudo volver al inicio del audio" }
            var nextCut = 1
            val prefix = sourceName(context, uri) + "_" + UUID.randomUUID().toString().take(8)
            var buffer = ByteBuffer.allocate(64 * 1024)
            while (extractor.sampleTime >= 0) {
                if (Thread.currentThread().isInterrupted) error("División interrumpida")
                val pts = extractor.sampleTime
                val flags = extractor.sampleFlags
                require(flags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "No se admite audio protegido" }
                require(flags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME == 0) { "No se admiten paquetes de audio incompletos" }
                val sizeLong = extractor.sampleSize
                require(sizeLong in 1..16L * 1024 * 1024) { "Tamaño de muestra de audio inválido" }
                val size = sizeLong.toInt()
                if (size > buffer.capacity()) buffer = ByteBuffer.allocate(size)
                buffer.clear()
                check(extractor.readSampleData(buffer, 0) == size) { "No se pudo leer una muestra completa" }
                extractor.advance()
                val nextPts = extractor.sampleTime
                val packetEnd = if (nextPts >= 0) {
                    require(nextPts > pts) { "Marcas temporales de audio no crecientes" }
                    packetDurationUs = nextPts - pts
                    nextPts
                } else {
                    // Respect a shortened final packet when the container declares it.
                    if (declaredDurationUs > pts) declaredDurationUs else pts + packetDurationUs
                }
                if (writer != null && pts == cuts[nextCut]) {
                    val old = requireNotNull(writer)
                    old.finish(pts)
                    parts += Part(old.uri, old.startUs, verifyDuration(context, old.uri, maxDurationUs))
                    writer = null
                    nextCut++
                }
                check(packetEnd <= cuts[nextCut]) { "El audio cambió durante la división" }
                if (writer == null) {
                    val out = createOutputUri(context, prefix, parts.size + 1)
                    created += out
                    writer = PartWriter(context, out, format, pts)
                }
                val codecFlags = if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0
                requireNotNull(writer).write(buffer, size, pts, codecFlags)
            }
            require(writer != null) { "El archivo no contiene muestras de audio" }
            val last = requireNotNull(writer)
            last.finish(finalEnd)
            parts += Part(last.uri, last.startUs, verifyDuration(context, last.uri, maxDurationUs))
            writer = null
            // Publish only after every part passes validation; never alter the source.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                created.forEach { check(context.contentResolver.update(it, values, null, null) == 1) }
            }
            return parts
        } catch (t: Throwable) {
            writer?.abort()
            deleteSegments(context, created)
            throw t
        } finally {
            extractor.release()
        }
    }

    private class PartWriter(context: Context, val uri: Uri, format: MediaFormat, val startUs: Long) {
        private val descriptor: ParcelFileDescriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri, "rw"))
        private val muxer: MediaMuxer
        private val track: Int
        private var closed = false
        init {
            var candidate: MediaMuxer? = null
            try {
                candidate = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                track = candidate.addTrack(format)
                candidate.start()
                muxer = candidate
            } catch (t: Throwable) {
                runCatching { candidate?.release() }
                descriptor.close()
                throw t
            }
        }

        fun write(buffer: ByteBuffer, size: Int, pts: Long, flags: Int) {
            val info = MediaCodec.BufferInfo().apply { set(0, size, pts - startUs, flags) }
            buffer.position(0)
            buffer.limit(size)
            muxer.writeSampleData(track, buffer, info)
        }

        fun finish(endUs: Long) {
            try {
                // MP4 EOS explicitly fixes the final packet's duration.
                val info = MediaCodec.BufferInfo().apply {
                    set(0, 0, endUs - startUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                muxer.writeSampleData(track, ByteBuffer.allocate(0), info)
                muxer.stop()
            } finally {
                abort()
            }
        }

        fun abort() {
            if (closed) return
            closed = true
            runCatching { muxer.release() }
            runCatching { descriptor.close() }
        }
    }

    private fun verifyDuration(context: Context, uri: Uri, maxUs: Long): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("No se pudo comprobar la duración del fragmento")
            check(ms > 0 && ms <= maxUs / 1000) { "La duración del fragmento supera el límite solicitado" }
            return ms
        } finally {
            retriever.release()
        }
    }

    private fun sourceName(context: Context, uri: Uri): String {
        var name = "Reunion"
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0) ?: name
            }
        }
        return name.substringBeforeLast('.', name).replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(80).ifBlank { "Reunion" }
    }

    private fun createOutputUri(context: Context, prefix: String, index: Int): Uri {
        val name = "${prefix}_parte_${index.toString().padStart(3, '0')}.m4a"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val directory = File(context.filesDir, "audio_parts").apply { check(isDirectory || mkdirs()) }
            return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(directory, name))
        }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/TanscriptorRVA")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        return requireNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)) {
            "No se pudo crear el fragmento de audio"
        }
    }

    fun deleteSegments(context: Context, uris: List<Uri>) {
        uris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }
}
