package es.iesvirgendelacaridad.etcp.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

object AudioSegmenter {
    fun segment(context: Context, uri: Uri, maxMinutes: Int = 50): List<File> {
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
        val outputDir = File(context.cacheDir, "sider_segments").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }

        val parts = mutableListOf<File>()
        var startUs = 0L
        var index = 1
        while (startUs < durationUs) {
            val endUs = minOf(durationUs, startUs + segmentUs)
            val file = File(outputDir, "ETCP_parte_${index.toString().padStart(2, '0')}.m4a")
            copyRange(context, uri, file, startUs, endUs)
            parts += file
            startUs = endUs
            index++
        }
        return parts
    }

    private fun copyRange(context: Context, uri: Uri, outFile: File, startUs: Long, endUs: Long) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
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

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxTrack = muxer.addTrack(extractor.getTrackFormat(track))
        muxer.start()

        val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var firstPts = -1L

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

        muxer.stop()
        muxer.release()
        extractor.release()
    }
}
