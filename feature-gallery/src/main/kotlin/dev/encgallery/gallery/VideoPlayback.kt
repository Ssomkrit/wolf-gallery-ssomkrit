package dev.encgallery.gallery

import android.content.Context
import android.media.MediaDataSource
import dev.encgallery.crypto.KeystoreAesGcm
import dev.encgallery.logging.EncLog
import dev.encgallery.nativec.NativeCrypto
import dev.encgallery.storage.EncryptedFileBlob
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

internal const val VIDEO_RAM_CAP_BYTES: Long = 96L * 1024 * 1024

private const val BLOB_OVERHEAD_BYTES: Long = 36L

private fun playbackTempDir(context: Context): File =
    File(context.cacheDir, "vid_playback").apply { mkdirs() }

internal fun secureDeleteFile(file: File) {
    if (!file.exists()) return
    try {
        RandomAccessFile(file, "rw").use { raf ->
            val len = raf.length()
            val zeros = ByteArray(256 * 1024)
            var written = 0L
            raf.seek(0)
            while (written < len) {
                val n = minOf(zeros.size.toLong(), len - written).toInt()
                raf.write(zeros, 0, n)
                written += n
            }
            raf.fd.sync()
        }
    } catch (t: Throwable) {
        EncLog.w("VideoPlayback", "zero-overwrite failed for ${file.name}: ${t.javaClass.simpleName}")
    }
    if (!file.delete() && file.exists()) {
        EncLog.w("VideoPlayback", "could not delete playback temp ${file.name}")
    }
}

internal fun sweepPlaybackTemps(context: Context) {
    val dir = File(context.cacheDir, "vid_playback")
    if (dir.isDirectory) dir.listFiles()?.forEach { secureDeleteFile(it) }
}

internal class InMemoryMediaDataSource(private var data: ByteArray?) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val d = data ?: return -1
        if (position >= d.size) return -1

        val start = position.toInt()
        val n = minOf(size, d.size - start)
        System.arraycopy(d, start, buffer, offset, n)
        return n
    }

    override fun getSize(): Long = data?.size?.toLong() ?: 0L

    override fun close() {
        data?.let { NativeCrypto.secureZero(it) }
        data = null
    }
}

internal class ChunkedMediaDataSource(
    private val reader: dev.encgallery.storage.ChunkedBlob.Reader
) : MediaDataSource() {

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        reader.readAt(position, buffer, offset, size)

    override fun getSize(): Long = reader.plaintextSize

    override fun close() {
        reader.close()
    }
}

internal sealed interface VideoSource {
    fun close()

    class Streaming(val dataSource: ChunkedMediaDataSource) : VideoSource {
        override fun close() {
            try { dataSource.close() } catch (_: Throwable) {}
        }
    }

    class InMemory(val dataSource: InMemoryMediaDataSource) : VideoSource {
        override fun close() {
            try { dataSource.close() } catch (_: Throwable) {}
        }
    }

    class TempFile(val file: File) : VideoSource {
        override fun close() = secureDeleteFile(file)
    }
}

private class FixedBufferSink(private val buffer: ByteArray) : OutputStream() {
    var count = 0
        private set

    override fun write(b: Int) {
        if (count >= buffer.size) throw IOException("plaintext exceeds expected size")
        buffer[count++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len > buffer.size - count) throw IOException("plaintext exceeds expected size")
        System.arraycopy(b, off, buffer, count, len)
        count += len
    }
}

private fun decryptExactBytes(blob: EncryptedFileBlob, input: File, size: Int): ByteArray {
    val buffer = ByteArray(size)
    val sink = FixedBufferSink(buffer)
    blob.decryptToStream(input, sink)
    return if (sink.count == buffer.size) buffer else buffer.copyOf(sink.count)
}

internal fun decryptVideoForPlayback(
    context: Context,
    blobFile: File,
    keystore: KeystoreAesGcm,
): VideoSource {
    val blob = EncryptedFileBlob(keystore)
    if (blob.isChunked(blobFile)) {
        return VideoSource.Streaming(ChunkedMediaDataSource(blob.openChunked(blobFile)))
    }
    val plaintextSize = (blobFile.length() - BLOB_OVERHEAD_BYTES).coerceAtLeast(0)
    if (plaintextSize in 1..VIDEO_RAM_CAP_BYTES) {
        val inRam = try {
            decryptExactBytes(blob, blobFile, plaintextSize.toInt())
        } catch (t: Throwable) {
            EncLog.w("VideoPlayback", "in-memory buffer unavailable (${t.javaClass.simpleName}) → temp fallback")
            null
        }
        if (inRam != null) return VideoSource.InMemory(InMemoryMediaDataSource(inRam))
    }
    val out = File(playbackTempDir(context), "play_${blobFile.nameWithoutExtension}.bin")
    blob.decryptToFile(blobFile, out)
    EncLog.i("VideoPlayback", "video playback via temp file (zeroed on close)")
    return VideoSource.TempFile(out)
}
