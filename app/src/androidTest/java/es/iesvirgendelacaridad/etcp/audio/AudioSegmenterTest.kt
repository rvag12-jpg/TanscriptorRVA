package es.iesvirgendelacaridad.etcp.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class AudioSegmenterTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun withFixture(name: String, test: (Uri, File) -> Unit) {
        val file = File(context.filesDir, name)
        instrumentation.context.assets.open(name).use { source -> file.outputStream().use { source.copyTo(it) } }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        try { test(uri, file) } finally { file.delete() }
    }

    private fun packets(uris: List<Uri>): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        for (uri in uris) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                val track = (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                extractor.selectTrack(track)
                val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
                while (extractor.sampleTime >= 0) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    assertTrue(size > 0)
                    digest.update(ByteBuffer.allocate(4).putInt(size).array())
                    digest.update(buffer.array(), 0, size)
                    count++
                    extractor.advance()
                }
            } finally { extractor.release() }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } to count
    }

    private fun checkFixture(name: String, expectedParts: Int, expectedDurationMs: Long, maxMinutes: Int = 45) {
        withFixture(name) { uri, source ->
            val sourceHash = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
            val before = packets(listOf(uri))
            val parts = AudioSegmenter.segment(context, uri, maxMinutes)
            try {
                assertEquals(expectedParts, parts.size)
                assertTrue(parts.all { it.durationMs in 1..maxMinutes * 60_000L })
                assertTrue("Parts must have similar durations", parts.maxOf { it.durationMs } - parts.minOf { it.durationMs } <= 150L)
                assertEquals("All packets must appear exactly once, in order", before, packets(parts.map { it.uri }))
                assertArrayEquals(sourceHash, MessageDigest.getInstance("SHA-256").digest(source.readBytes()))
                assertTrue("Total duration changed", kotlin.math.abs(parts.sumOf { it.durationMs } - expectedDurationMs) <= 150)
                assertEquals(parts.size, parts.map { it.uri }.distinct().size)
            } finally { AudioSegmenter.deleteSegments(context, parts.map { it.uri }) }
        }
    }

    @Test fun shortAudio() = checkFixture("short.m4a", 1, 10_000L)
    @Test fun underFortyFiveMinutes() = checkFixture("under_forty_five.m4a", 1, 2_699_000L)
    // FFmpeg's AAC encoder adds a 64 ms packet: it also counts towards the cap.
    @Test fun encoderPaddingCountsTowardsMaximum() = checkFixture("forty_five.m4a", 2, 2_700_000L)
    @Test fun exceedsFortyFiveMinutes() = checkFixture("over_forty_five.m4a", 2, 2_701_000L)
    @Test fun oneHour() = checkFixture("one_hour.m4a", 2, 3_600_000L)
    @Test fun twoHours() = checkFixture("two_hours.m4a", 3, 7_200_000L)
    @Test fun acceptsSmallerLimit() = checkFixture("one_hour.m4a", 2, 3_600_000L, 31)

    @Test fun rejectsUnsupportedAudioWithoutRemovingSource() {
        withFixture("unsupported.wav") { uri, source ->
            try { AudioSegmenter.segment(context, uri); fail("Accepted PCM in an MP4 muxer") }
            catch (_: IllegalArgumentException) { assertTrue(source.exists()) }
        }
    }
}
