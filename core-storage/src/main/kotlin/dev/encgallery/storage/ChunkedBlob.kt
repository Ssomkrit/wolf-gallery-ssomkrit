package dev.encgallery.storage

import dev.encgallery.nativec.NativeCrypto
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ChunkedBlob {

    const val VERSION_3: Byte = 0x03

    const val HEADER_BYTES = 32

    const val TAG_BYTES = 16

    private const val SALT_BYTES = 16
    private const val NONCE_PREFIX_BYTES = 7
    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val DEFAULT_CHUNK_LOG2 = 20

    private val MAGIC = byteArrayOf(0x45, 0x47, 0x42, 0x4C)
    private val INFO = "EGBL-v3-chunk".toByteArray(Charsets.US_ASCII)

    class Header(
        val bytes: ByteArray,
        val salt: ByteArray,
        val noncePrefix: ByteArray,
        val chunkLog2: Int
    ) {
        val chunkPlainBytes: Int get() = 1 shl chunkLog2
        val chunkDiskBytes: Int get() = chunkPlainBytes + TAG_BYTES
    }

    fun newHeader(chunkLog2: Int = DEFAULT_CHUNK_LOG2): Header {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { rnd.nextBytes(it) }
        val prefix = ByteArray(NONCE_PREFIX_BYTES).also { rnd.nextBytes(it) }
        val bytes = ByteArray(HEADER_BYTES)
        System.arraycopy(MAGIC, 0, bytes, 0, 4)
        bytes[4] = VERSION_3
        System.arraycopy(salt, 0, bytes, 8, SALT_BYTES)
        System.arraycopy(prefix, 0, bytes, 24, NONCE_PREFIX_BYTES)
        bytes[31] = chunkLog2.toByte()
        return Header(bytes, salt, prefix, chunkLog2)
    }

    fun parseHeader(bytes: ByteArray, name: String): Header {
        if (bytes.size != HEADER_BYTES) {
            throw EncryptedFileBlob.BlobFormatException("blob $name truncated header")
        }
        for (i in 0 until 4) {
            if (bytes[i] != MAGIC[i]) {
                throw EncryptedFileBlob.BlobFormatException("blob $name bad magic")
            }
        }
        if (bytes[4] != VERSION_3) {
            throw EncryptedFileBlob.BlobFormatException("blob $name is not v3")
        }
        if (bytes[5] != 0.toByte() || bytes[6] != 0.toByte() || bytes[7] != 0.toByte()) {
            throw EncryptedFileBlob.BlobFormatException(
                "blob $name reserved bytes are non-zero — possibly written by future build"
            )
        }
        val chunkLog2 = bytes[31].toInt()
        if (chunkLog2 !in 12..26) {
            throw EncryptedFileBlob.BlobFormatException(
                "blob $name has unsupported chunk size 2^$chunkLog2"
            )
        }
        return Header(
            bytes = bytes.copyOf(),
            salt = bytes.copyOfRange(8, 8 + SALT_BYTES),
            noncePrefix = bytes.copyOfRange(24, 24 + NONCE_PREFIX_BYTES),
            chunkLog2 = chunkLog2
        )
    }

    fun deriveKey(dek: SecretKeySpec, header: Header): SecretKeySpec {
        val raw = dek.encoded ?: error("vault key has no encoded form")
        try {
            val prk = hmac(header.salt, raw)
            try {
                val block = hmac(prk, INFO + byteArrayOf(0x01))
                return SecretKeySpec(block, "AES")
            } finally {
                NativeCrypto.secureZero(prk)
            }
        } finally {
            NativeCrypto.secureZero(raw)
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun nonce(header: Header, index: Long, last: Boolean): ByteArray {
        if (index > 0xFFFFFFFFL) throw IOException("blob too large for chunk counter")
        val n = ByteArray(NONCE_BYTES)
        System.arraycopy(header.noncePrefix, 0, n, 0, NONCE_PREFIX_BYTES)
        n[7] = ((index ushr 24) and 0xFF).toByte()
        n[8] = ((index ushr 16) and 0xFF).toByte()
        n[9] = ((index ushr 8) and 0xFF).toByte()
        n[10] = (index and 0xFF).toByte()
        n[11] = if (last) 1 else 0
        return n
    }

    private fun cipherFor(
        mode: Int,
        key: SecretKeySpec,
        header: Header,
        index: Long,
        last: Boolean
    ): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(mode, key, GCMParameterSpec(GCM_TAG_BITS, nonce(header, index, last)))
        cipher.updateAAD(header.bytes)
        return cipher
    }

    fun encrypt(
        input: InputStream,
        out: OutputStream,
        dek: SecretKeySpec,
        chunkLog2: Int = DEFAULT_CHUNK_LOG2
    ) {
        val header = newHeader(chunkLog2)
        val key = deriveKey(dek, header)
        out.write(header.bytes)

        val plain = ByteArray(header.chunkPlainBytes)
        var index = 0L
        var pending = readFully(input, plain)
        do {
            val next = if (pending == plain.size) {
                val lookahead = ByteArray(header.chunkPlainBytes)
                val n = readFully(input, lookahead)
                if (n > 0) lookahead to n else null
            } else {
                null
            }
            val isLast = next == null
            val cipher = cipherFor(Cipher.ENCRYPT_MODE, key, header, index, isLast)
            out.write(cipher.doFinal(plain, 0, pending))
            index++
            if (next != null) {
                System.arraycopy(next.first, 0, plain, 0, next.second)
                pending = next.second
                NativeCrypto.secureZero(next.first)
            } else {
                pending = 0
            }
        } while (!isLast)
        NativeCrypto.secureZero(plain)
    }

    fun decryptInto(file: File, sink: OutputStream, dek: SecretKeySpec) {
        file.inputStream().use { raw ->
            val headerBytes = ByteArray(HEADER_BYTES)
            if (readFully(raw, headerBytes) != HEADER_BYTES) {
                throw EncryptedFileBlob.BlobFormatException("blob ${file.name} truncated header")
            }
            val header = parseHeader(headerBytes, file.name)
            val key = deriveKey(dek, header)
            val layout = layoutOf(file.length(), header, file.name)

            val disk = ByteArray(header.chunkDiskBytes)
            for (index in 0 until layout.chunkCount) {
                val last = index == layout.chunkCount - 1
                val want = if (last) layout.lastChunkDiskBytes else header.chunkDiskBytes
                if (readFully(raw, disk, want) != want) {
                    throw EncryptedFileBlob.BlobFormatException(
                        "blob ${file.name} truncated at chunk $index"
                    )
                }
                val plain = openChunk(key, header, index, last, disk, want, file.name)
                sink.write(plain)
                NativeCrypto.secureZero(plain)
            }
        }
    }

    private fun openChunk(
        key: SecretKeySpec,
        header: Header,
        index: Long,
        last: Boolean,
        disk: ByteArray,
        diskLen: Int,
        name: String
    ): ByteArray = try {
        cipherFor(Cipher.DECRYPT_MODE, key, header, index, last).doFinal(disk, 0, diskLen)
    } catch (e: AEADBadTagException) {
        throw IOException("auth tag verification failed for $name chunk $index", e)
    } catch (e: BadPaddingException) {
        throw IOException("auth tag verification failed for $name chunk $index", e)
    }

    class Layout(
        val chunkCount: Long,
        val lastChunkDiskBytes: Int,
        val plaintextSize: Long
    )

    fun layoutOf(fileLength: Long, header: Header, name: String): Layout {
        val dataLen = fileLength - HEADER_BYTES
        if (dataLen < TAG_BYTES) {
            throw EncryptedFileBlob.BlobFormatException("blob $name has no complete chunk")
        }
        val diskChunk = header.chunkDiskBytes.toLong()
        val count = (dataLen + diskChunk - 1) / diskChunk
        val lastDisk = dataLen - (count - 1) * diskChunk
        if (lastDisk < TAG_BYTES || lastDisk > diskChunk) {
            throw EncryptedFileBlob.BlobFormatException("blob $name has a malformed final chunk")
        }
        val plaintext = (count - 1) * header.chunkPlainBytes + (lastDisk - TAG_BYTES)
        return Layout(count, lastDisk.toInt(), plaintext)
    }

    fun open(file: File, dek: SecretKeySpec): Reader {
        val raf = RandomAccessFile(file, "r")
        try {
            val headerBytes = ByteArray(HEADER_BYTES)
            raf.readFully(headerBytes)
            val header = parseHeader(headerBytes, file.name)
            return Reader(raf, header, deriveKey(dek, header), layoutOf(file.length(), header, file.name), file.name)
        } catch (t: Throwable) {
            try { raf.close() } catch (_: Throwable) {}
            throw t
        }
    }

    class Reader(
        private val raf: RandomAccessFile,
        private val header: Header,
        private val key: SecretKeySpec,
        private val layout: Layout,
        private val name: String
    ) : Closeable {

        val plaintextSize: Long get() = layout.plaintextSize

        private var cachedIndex = -1L
        private var cachedPlain: ByteArray? = null
        private val diskBuf = ByteArray(header.chunkDiskBytes)

        @Synchronized
        fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0 || position >= layout.plaintextSize) return -1
            var copied = 0
            var pos = position
            while (copied < size && pos < layout.plaintextSize) {
                val index = pos / header.chunkPlainBytes
                val chunk = chunkAt(index)
                val within = (pos - index * header.chunkPlainBytes).toInt()
                val available = chunk.size - within
                if (available <= 0) break
                val take = minOf(available, size - copied)
                System.arraycopy(chunk, within, buffer, offset + copied, take)
                copied += take
                pos += take
            }
            return if (copied == 0) -1 else copied
        }

        private fun chunkAt(index: Long): ByteArray {
            cachedPlain?.let { if (cachedIndex == index) return it }
            val last = index == layout.chunkCount - 1
            val want = if (last) layout.lastChunkDiskBytes else header.chunkDiskBytes
            raf.seek(HEADER_BYTES + index * header.chunkDiskBytes)
            raf.readFully(diskBuf, 0, want)
            val plain = openChunk(key, header, index, last, diskBuf, want, name)
            cachedPlain?.let { NativeCrypto.secureZero(it) }
            cachedPlain = plain
            cachedIndex = index
            return plain
        }

        @Synchronized
        override fun close() {
            cachedPlain?.let { NativeCrypto.secureZero(it) }
            cachedPlain = null
            cachedIndex = -1
            NativeCrypto.secureZero(diskBuf)
            try { raf.close() } catch (_: Throwable) {}
        }
    }

    fun runSelfTest(tempDir: File, log: (String) -> Unit): Boolean {
        val chunkLog2 = 12
        val chunkPlain = 1 shl chunkLog2
        val plainSize = chunkPlain * 3 + 777
        val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        val plain = ByteArray(plainSize).also { SecureRandom().nextBytes(it) }
        val encFile = File(tempDir, "chunked_selftest.bin")
        var pass = true

        fun rejects(label: String, mutate: (RandomAccessFile) -> Unit): Boolean {
            val copy = File(tempDir, "chunked_selftest_$label.bin")
            encFile.copyTo(copy, overwrite = true)
            return try {
                RandomAccessFile(copy, "rw").use(mutate)
                val sink = java.io.ByteArrayOutputStream()
                decryptInto(copy, sink, key)
                log("($label) FAIL — modified blob was accepted")
                false
            } catch (e: Exception) {
                log("($label) rejected: ${e.javaClass.simpleName}")
                true
            } finally {
                copy.delete()
            }
        }

        try {
            encFile.outputStream().use { out ->
                plain.inputStream().use { src -> encrypt(src, out, key, chunkLog2) }
            }
            val header = parseHeader(
                encFile.inputStream().use { ins ->
                    ByteArray(HEADER_BYTES).also { readFully(ins, it) }
                },
                encFile.name
            )
            val layout = layoutOf(encFile.length(), header, encFile.name)
            val sizeOk = layout.plaintextSize == plainSize.toLong() && layout.chunkCount == 4L
            log("(7) chunked: ${layout.chunkCount} chunks, plaintext size ${layout.plaintextSize} of $plainSize, ok=$sizeOk")
            if (!sizeOk) pass = false

            val sink = java.io.ByteArrayOutputStream()
            decryptInto(encFile, sink, key)
            val roundTripOk = sink.toByteArray().contentEquals(plain)
            log("(8) chunked round-trip byte-exact: $roundTripOk")
            if (!roundTripOk) pass = false

            val reader = open(encFile, key)
            val randomOk = try {
                val at = chunkPlain - 100L
                val buf = ByteArray(400)
                var got = 0
                while (got < buf.size) {
                    val n = reader.readAt(at + got, buf, got, buf.size - got)
                    if (n <= 0) break
                    got += n
                }
                got == buf.size &&
                    buf.contentEquals(plain.copyOfRange(at.toInt(), at.toInt() + buf.size)) &&
                    reader.readAt(layout.plaintextSize, buf, 0, 1) == -1
            } finally {
                reader.close()
            }
            log("(9) random access across a chunk boundary + read past end: $randomOk")
            if (!randomOk) pass = false

            if (!rejects("10") { raf ->
                    val at = (HEADER_BYTES + chunkPlain + 32).toLong()
                    raf.seek(at)
                    val b = raf.read()
                    raf.seek(at)
                    raf.write(b xor 0x01)
                }
            ) pass = false

            if (!rejects("11") { raf ->
                    val diskChunk = header.chunkDiskBytes
                    val first = ByteArray(diskChunk)
                    val second = ByteArray(diskChunk)
                    raf.seek(HEADER_BYTES.toLong())
                    raf.readFully(first)
                    raf.readFully(second)
                    raf.seek(HEADER_BYTES.toLong())
                    raf.write(second)
                    raf.write(first)
                }
            ) pass = false

            if (!rejects("12") { raf ->
                    raf.setLength(raf.length() - layout.lastChunkDiskBytes)
                }
            ) pass = false

            if (!rejects("13") { raf ->
                    raf.seek(8)
                    val b = raf.read()
                    raf.seek(8)
                    raf.write(b xor 0x01)
                }
            ) pass = false
        } catch (t: Throwable) {
            log("chunked self-test EXCEPTION: ${t.javaClass.simpleName}: ${t.message}")
            pass = false
        } finally {
            NativeCrypto.secureZero(plain)
            encFile.delete()
        }
        return pass
    }

    private fun readFully(input: InputStream, buf: ByteArray, limit: Int = buf.size): Int {
        var off = 0
        while (off < limit) {
            val n = try {
                input.read(buf, off, limit - off)
            } catch (e: EOFException) {
                -1
            }
            if (n < 0) break
            off += n
        }
        return off
    }
}
