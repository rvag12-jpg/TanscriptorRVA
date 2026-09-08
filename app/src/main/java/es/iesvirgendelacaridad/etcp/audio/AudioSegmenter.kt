package es.iesvirgendelacaridad.etcp.audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import java.nio.ByteBuffer

object AudioSegmenter {
    fun segment(context: Context, uri: Uri, maxMinutes: Int = 50): List<Uri> {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var audioTrack = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                audioTrack = i
                break
            }
        }
        require(audioTrack >= 0) { "No se encontró pista de audio" }
        extractor.selectTrack(audioTrack)
        val format = extractor.getTrackFormat(audioTrack)
        val durationUs = format.getLong(MediaFormat.KEY_DURATION)
        extractor.release()

        val segmentUs = maxMinutes * 60L * 1_000_000L
        val result = mutableListOf<Uri>()
        var startUs = 0L
        var index = 1
        while (startUs < durationUs) {
            val endUs = minOf(durationUs, startUs + segmentUs)
            val outUri = createOutputUri(context, index)
            try {
                copyRange(context, uri, outUri, startUs, endUs)
                result += outUri
            } catch (t: Throwable) {
                context.contentResolver.delete(outUri, null, null)
                result.forEach { context.contentResolver.delete(it, null, null) }
                throw t
            }
            startUs = endUs
            index++
        }
        return result
    }

    private fun createOutputUri(context: Context, index: Int): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "ETCP_parte_${index.toString().padStart(2, '0')}.m4a")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/TanscriptorRVA")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        return requireNotNull(
            context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        ) { "No se pudo crear el fragmento de audio" }
    }

    private fun copyRange(context: Context, sourceUri: Uri, outputUri: Uri, startUs: Long, endUs: Long) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, sourceUri, null)
        var track = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i
                break
            }
        }
        require(track >= 0) { "No se encontró pista de audio" }
        extractor.selectTrack(track)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val pfd = requireNotNull(context.contentResolver.openFileDescriptor(outputUri, "rw"))
        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxTrack = muxer.addTrack(extractor.getTrackFormat(track))
        muxer.start()

        val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var firstPts = -1L

        try {
            while (true) {
                val pts = extractor.sampleTime
                if (pts < 0 || pts >= endUs) break
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                if (firstPts < 0) firstPts = pts
                info.offset = 0
                info.size = size
                info.presentationTimeUs = pts - firstPts
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(muxTrack, buffer, info)
                extractor.advance()
            }
        } finally {
            muxer.stop()
            muxer.release()
            pfd.close()
            extractor.release()
        }

        val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
        context.contentResolver.update(outputUri, values, null, null)
    }

    fun deleteSegments(context: Context, uris: List<Uri>) {
        uris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }
}
