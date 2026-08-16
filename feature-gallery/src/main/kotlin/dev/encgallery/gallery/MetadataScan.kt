package dev.encgallery.gallery

import dev.encgallery.logging.EncLog
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class MetaEntry(val name: String, val value: String)

data class MetaGroup(val name: String, val entries: List<MetaEntry>)

data class MetaBlock(val name: String, val bytes: Int)

data class FileMetadata(
    val supported: Boolean,
    val groups: List<MetaGroup> = emptyList(),
    val blocks: List<MetaBlock> = emptyList(),
    val truncated: Boolean = false,
    val xmp: String? = null
) {
    val isEmpty: Boolean
        get() = groups.isEmpty() && blocks.isEmpty() && xmp == null
}

object MetadataScan {

    private const val TAG = "MetadataScan"

    private const val MAX_SEGMENTS = 64
    private const val MAX_CHUNKS = 64
    private const val MAX_IFDS = 8
    private const val MAX_ENTRIES = 256
    private const val MAX_ARRAY = 12
    private const val MAX_TEXT = 200
    private const val MAX_INLINE_BLOB = 16
    private const val MAX_BOXES = 128
    private const val MAX_BOX_DEPTH = 6
    private const val MAX_XMP = 4000

    private val META_CONTAINERS = setOf("udta", "ilst", "meta")

    private const val XMP_MARKER = "http://ns.adobe.com/xap/1.0/"

    private val MEDIA_TEXT_BOXES = mapOf(
        "©nam" to "Title",
        "©ART" to "Artist",
        "©alb" to "Album",
        "©day" to "Year",
        "©cmt" to "Comment",
        "©gen" to "Genre",
        "©too" to "EncodedBy",
        "©wrt" to "Writer",
        "©swr" to "Software",
        "©mak" to "Make",
        "©mod" to "Model",
        "©xyz" to "GPSCoordinates",
        "titl" to "Title",
        "auth" to "Author",
        "dscp" to "Description",
        "desc" to "Description",
        "ldes" to "LongDescription",
        "cprt" to "Copyright",
        "gnre" to "Genre",
        "albm" to "Album",
        "perf" to "Performer",
        "yrrc" to "Year",
        "loci" to "Location",
        "keyw" to "Keywords",
        "name" to "Name"
    )

    private val IPTC_TAGS = mapOf(
        0x05 to "ObjectName",
        0x0F to "Category",
        0x14 to "SupplementalCategory",
        0x19 to "Keywords",
        0x28 to "SpecialInstructions",
        0x37 to "DateCreated",
        0x3C to "TimeCreated",
        0x41 to "OriginatingProgram",
        0x46 to "ProgramVersion",
        0x50 to "Byline",
        0x55 to "BylineTitle",
        0x5A to "City",
        0x5C to "Sublocation",
        0x5F to "State",
        0x65 to "Country",
        0x67 to "TransmissionReference",
        0x69 to "Headline",
        0x6E to "Credit",
        0x73 to "Source",
        0x74 to "Copyright",
        0x78 to "Caption",
        0x7A to "Writer"
    )

    private const val MAX_OFFSET = 64L * 1024 * 1024

    private const val MARKER = 0xFF.toByte()

    fun scan(bytes: ByteArray, mime: String?, tail: ByteArray? = null): FileMetadata = try {
        val result = when {
            mime == "image/jpeg" -> scanJpeg(bytes)
            mime == "image/png" -> scanPng(bytes)
            mime != null && mime.startsWith("video/") -> scanIsoBmff(bytes, tail)
            mime == "image/heic" || mime == "image/avif" -> scanIsoBmff(bytes, tail)
            else -> FileMetadata(supported = false)
        }
        EncLog.d(
            TAG,
            "scan: mime=$mime supported=${result.supported} " +
                "groups=${result.groups.size} blocks=${result.blocks.size}"
        )
        result
    } catch (t: Throwable) {
        EncLog.w(TAG, "scan failed: ${t.javaClass.simpleName}")
        FileMetadata(supported = true)
    }

    fun sanitize(raw: String, limit: Int = MAX_TEXT): String {
        val cleaned = buildString {
            for (ch in raw) {
                if (length >= limit) break
                val drop = ch.isISOControl() ||
                    ch in '\u200B'..'\u200F' ||
                    ch in '\u202A'..'\u202E' ||
                    ch in '\u2066'..'\u2069' ||
                    ch == '\uFEFF'
                if (!drop) append(ch)
            }
        }.trim()
        return if (raw.length > limit) "$cleaned…" else cleaned
    }

    private fun scanJpeg(b: ByteArray): FileMetadata {
        if (b.size < 4 || b[0] != MARKER || b[1] != 0xD8.toByte()) {
            return FileMetadata(supported = true)
        }
        val groups = mutableListOf<MetaGroup>()
        val blocks = mutableListOf<MetaBlock>()
        val xmp = StringBuilder()
        var truncated = false
        var pos = 2
        var seen = 0
        while (seen < MAX_SEGMENTS) {
            if (pos + 4 > b.size) {
                truncated = pos < b.size
                break
            }
            if (b[pos] != MARKER) {
                truncated = true
                break
            }
            var at = pos + 1
            while (at < b.size && b[at] == MARKER) at++
            if (at >= b.size) {
                truncated = true
                break
            }
            val marker = b[at].toInt() and 0xFF
            if (marker == 0xD9 || marker == 0xDA) break
            if (marker in 0xD0..0xD8 || marker == 0x01) {
                pos = at + 1
                continue
            }
            if (at + 3 > b.size) {
                truncated = true
                break
            }
            val length = u16(b, at + 1)
            if (length < 2) {
                truncated = true
                break
            }
            val from = at + 3
            val size = length - 2
            if (from + size > b.size) {
                truncated = true
                break
            }
            readSegment(marker, b, from, size, groups, blocks, xmp)
            pos = from + size
            seen++
        }
        return FileMetadata(
            supported = true,
            groups = groups,
            blocks = blocks.filter { it.bytes > 0 },
            truncated = truncated,
            xmp = xmp.toString().ifEmpty { null }
        )
    }

    private fun readSegment(
        marker: Int,
        b: ByteArray,
        from: Int,
        size: Int,
        groups: MutableList<MetaGroup>,
        blocks: MutableList<MetaBlock>,
        xmp: StringBuilder
    ) {
        val head = ascii(b, from, minOf(size, 32))
        when {
            marker == 0xE1 && head.startsWith("Exif") -> {
                val parsed = if (size > 8) {
                    readTiff(b, from + 6, from + size, blocks)
                } else {
                    emptyList()
                }
                if (parsed.isEmpty()) blocks += MetaBlock("EXIF", size) else groups += parsed
            }

            marker == 0xE1 && head.startsWith("http://ns.adobe.com/xap") -> {
                val at = from + XMP_MARKER.length + 1
                val body = size - XMP_MARKER.length - 1
                val packet = if (body > 0) xmpText(b, at, body) else ""
                if (packet.isEmpty()) {
                    blocks += MetaBlock("XMP", size)
                } else if (xmp.length < MAX_XMP) {
                    if (xmp.isNotEmpty()) xmp.append("\n\n")
                    xmp.append(packet)
                }
            }

            marker == 0xE1 && head.startsWith("http://ns.adobe.com/xmp/extension") ->
                blocks += MetaBlock("XMP (extended)", size)

            marker == 0xE0 -> blocks += MetaBlock("JFIF (APP0)", size)
            marker == 0xE2 && head.startsWith("ICC_PROFILE") -> {
                val at = from + 14
                val parsed = if (size > 20) readIcc(b, at, from + size) else emptyList()
                if (parsed.isEmpty()) {
                    blocks += MetaBlock("ICC", size)
                } else if (groups.none { it.name == "ICC" }) {
                    groups += MetaGroup("ICC", parsed)
                }
            }
            marker == 0xE2 && head.startsWith("MPF") -> blocks += MetaBlock("MPF (APP2)", size)
            marker == 0xED -> {
                val parsed = readPhotoshop(b, from, from + size)
                if (parsed.isEmpty()) {
                    blocks += MetaBlock("IPTC / Photoshop (APP13)", size)
                } else {
                    groups += MetaGroup("IPTC", parsed)
                }
            }
            marker == 0xEE -> blocks += MetaBlock("Adobe (APP14)", size)
            marker == 0xFE -> blocks += MetaBlock("COM", size)
            marker in 0xE0..0xEF -> blocks += MetaBlock("APP${marker - 0xE0}", size)
        }
    }

    private fun scanPng(b: ByteArray): FileMetadata {
        if (b.size < 12) return FileMetadata(supported = true)
        val groups = mutableListOf<MetaGroup>()
        val blocks = mutableListOf<MetaBlock>()
        val header = mutableListOf<MetaEntry>()
        val texts = mutableListOf<MetaEntry>()
        var truncated = false
        var pos = 8
        var seen = 0
        while (seen < MAX_CHUNKS) {
            if (pos + 12 > b.size) {
                truncated = pos < b.size
                break
            }
            val length = u32(b, pos, false)
            if (length > Int.MAX_VALUE - 12) {
                truncated = true
                break
            }
            val type = ascii(b, pos + 4, 4)
            val from = pos + 8
            if (from + length + 4 > b.size) {
                truncated = true
                break
            }
            val size = length.toInt()
            when (type) {
                "IHDR" -> if (size >= 13) {
                    header += MetaEntry("ImageWidth", u32(b, from, false).toString())
                    header += MetaEntry("ImageHeight", u32(b, from + 4, false).toString())
                    header += MetaEntry("BitDepth", (b[from + 8].toInt() and 0xFF).toString())
                    header += MetaEntry("ColorType", (b[from + 9].toInt() and 0xFF).toString())
                    header += MetaEntry("Interlace", (b[from + 12].toInt() and 0xFF).toString())
                }

                "tIME" -> if (size >= 7) {
                    header += MetaEntry(
                        "ModifyTime",
                        String.format(
                            Locale.US,
                            "%04d-%02d-%02d %02d:%02d:%02d",
                            u16(b, from),
                            b[from + 2].toInt() and 0xFF,
                            b[from + 3].toInt() and 0xFF,
                            b[from + 4].toInt() and 0xFF,
                            b[from + 5].toInt() and 0xFF,
                            b[from + 6].toInt() and 0xFF
                        )
                    )
                }

                "pHYs" -> if (size >= 9) {
                    header += MetaEntry(
                        "PixelsPerUnit",
                        "${u32(b, from, false)} x ${u32(b, from + 4, false)}"
                    )
                }

                "eXIf" -> {
                    val parsed = readTiff(b, from, from + size, blocks)
                    if (parsed.isEmpty()) blocks += MetaBlock("eXIf", size) else groups += parsed
                }

                "tEXt" -> pngText(b, from, size)?.let { texts += it }
                "iTXt" -> {
                    val entry = pngInternationalText(b, from, size)
                    if (entry != null) texts += entry else blocks += MetaBlock("iTXt", size)
                }

                "zTXt" -> blocks += MetaBlock("zTXt: ${keyword(b, from, size)}", size)
                "iCCP" -> {
                    val name = keyword(b, from, size)
                    if (name == "\u2014") {
                        blocks += MetaBlock("ICC", size)
                    } else {
                        groups += MetaGroup("ICC", listOf(MetaEntry("ProfileName", name)))
                    }
                }
                "IEND" -> {
                    truncated = false
                    seen = MAX_CHUNKS
                }

                else -> Unit
            }
            pos = from + size + 4
            seen++
        }
        if (header.isNotEmpty()) groups.add(0, MetaGroup("PNG", header))
        if (texts.isNotEmpty()) groups += MetaGroup("PNG text", texts)
        return FileMetadata(true, groups, blocks.filter { it.bytes > 0 }, truncated)
    }

    private fun pngText(b: ByteArray, from: Int, size: Int): MetaEntry? {
        val split = (0 until minOf(size, 80)).firstOrNull { b[from + it] == 0.toByte() } ?: return null
        val key = sanitize(text(b, from, split), 40)
        val bodyAt = from + split + 1
        val bodyLen = size - split - 1
        if (key.isEmpty() || bodyLen <= 0) return null
        val body = sanitize(text(b, bodyAt, bodyLen, stopAtNul = false))
        return if (body.isEmpty()) null else MetaEntry(key, body)
    }

    private fun pngInternationalText(b: ByteArray, from: Int, size: Int): MetaEntry? {
        val split = (0 until minOf(size, 80)).firstOrNull { b[from + it] == 0.toByte() } ?: return null
        if (split + 2 >= size) return null
        val compressed = b[from + split + 1].toInt() != 0
        if (compressed) return null
        val key = sanitize(text(b, from, split), 40)
        if (key.isEmpty()) return null
        var at = from + split + 3
        var skipped = 0
        while (skipped < 2 && at < from + size) {
            if (b[at] == 0.toByte()) skipped++
            at++
        }
        val bodyLen = from + size - at
        if (bodyLen <= 0) return null
        val body = sanitize(text(b, at, bodyLen, stopAtNul = false))
        return if (body.isEmpty()) null else MetaEntry(key, body)
    }

    private fun scanIsoBmff(head: ByteArray, tail: ByteArray?): FileMetadata {
        val entries = mutableListOf<MetaEntry>()
        val blocks = mutableListOf<MetaBlock>()
        var walked = walkBoxes(head, 0, head.size, entries, blocks, 0, "")
        if (!walked && tail != null) {
            val at = findBox(tail, "moov")
            if (at >= 0) walked = walkBoxes(tail, at, tail.size, entries, blocks, 0, "")
        }
        val unique = entries.distinctBy { it.name }
        val groups = if (unique.isEmpty()) emptyList() else listOf(MetaGroup("Media", unique))
        return FileMetadata(true, groups, blocks.filter { it.bytes > 0 }, !walked)
    }

    private fun findBox(b: ByteArray, type: String): Int {
        val marker = type.toByteArray(Charsets.ISO_8859_1)
        var i = 4
        while (i + 4 <= b.size) {
            if (b[i] == marker[0] && b[i + 1] == marker[1] &&
                b[i + 2] == marker[2] && b[i + 3] == marker[3]
            ) {
                return i - 4
            }
            i++
        }
        return -1
    }

    private fun walkBoxes(
        b: ByteArray,
        from: Int,
        limit: Int,
        entries: MutableList<MetaEntry>,
        blocks: MutableList<MetaBlock>,
        depth: Int,
        parent: String
    ): Boolean {
        if (depth > MAX_BOX_DEPTH) return false
        var pos = from
        var seen = 0
        var foundMoov = false
        while (seen < MAX_BOXES && pos + 8 <= limit && pos + 8 <= b.size) {
            var size = u32(b, pos, false)
            val type = ascii(b, pos + 4, 4)
            var body = pos + 8
            if (size == 1L) {
                if (body + 8 > limit) return foundMoov
                size = u64(b, body, false)
                body += 8
            }
            if (size == 0L) size = (limit - pos).toLong()
            if (size < 8 || pos + size > limit) return foundMoov
            val end = (pos + size).toInt()
            when (type) {
                "moov", "trak", "mdia", "udta", "ilst" -> {
                    foundMoov = true
                    walkBoxes(b, body, end, entries, blocks, depth + 1, type)
                }

                "meta" -> {
                    foundMoov = true
                    walkBoxes(b, body + 4, end, entries, blocks, depth + 1, type)
                }

                "ftyp" -> entries += MetaEntry("MajorBrand", sanitize(ascii(b, body, 4).trim(), 8))
                "mvhd" -> readMvhd(b, body, end, entries)
                "tkhd" -> readTkhd(b, body, end, entries)
                "hdlr", "mdhd", "stbl", "minf", "dinf", "stsd", "free", "skip", "mdat", "wide" ->
                    Unit

                else -> {
                    val name = sanitize(type.trim(), 8)
                    if (name.isNotEmpty()) {
                        val value = if (MEDIA_TEXT_BOXES.containsKey(name)) {
                            itemText(b, body, end)
                        } else {
                            null
                        }
                        if (value != null) {
                            entries += MetaEntry(MEDIA_TEXT_BOXES[name] ?: name, value)
                        } else if (parent in META_CONTAINERS) {
                            blocks += MetaBlock(name, (size - 8).toInt().coerceAtLeast(0))
                        }
                    }
                }
            }
            pos = end
            seen++
        }
        return foundMoov
    }

    private fun readMvhd(b: ByteArray, from: Int, limit: Int, entries: MutableList<MetaEntry>) {
        if (from + 4 > limit) return
        val version = b[from].toInt() and 0xFF
        val at = from + 4
        val created: Long
        val scale: Long
        val duration: Long
        if (version == 1) {
            if (at + 28 > limit) return
            created = u64(b, at, false)
            scale = u32(b, at + 16, false)
            duration = u64(b, at + 20, false)
        } else {
            if (at + 16 > limit) return
            created = u32(b, at, false)
            scale = u32(b, at + 8, false)
            duration = u32(b, at + 12, false)
        }
        isoDate(created)?.let { entries += MetaEntry("CreateDate", it) }
        if (scale > 0 && duration > 0) {
            val seconds = duration.toDouble() / scale.toDouble()
            entries += MetaEntry("Duration", formatDuration(seconds))
        }
    }

    private fun readTkhd(b: ByteArray, from: Int, limit: Int, entries: MutableList<MetaEntry>) {
        if (from + 4 > limit) return
        val version = b[from].toInt() and 0xFF
        val base = from + 4 + if (version == 1) 32 else 20
        val matrix = base + 16
        if (matrix + 36 + 8 > limit) return
        val width = u32(b, matrix + 36, false) shr 16
        val height = u32(b, matrix + 40, false) shr 16
        if (width > 0 && height > 0) {
            entries += MetaEntry("ImageSize", "$width x $height")
        }
        val a = signed32(u32(b, matrix, false)) shr 16
        val bb = signed32(u32(b, matrix + 4, false)) shr 16
        val rotation = when {
            a == 0L && bb == 1L -> 90
            a == -1L && bb == 0L -> 180
            a == 0L && bb == -1L -> 270
            else -> 0
        }
        if (rotation != 0) entries += MetaEntry("Rotation", rotation.toString())
    }

    private fun itemText(b: ByteArray, from: Int, limit: Int): String? {
        val length = limit - from
        if (length <= 0 || length > 4096) return null
        val skip = when {
            length > 8 && ascii(b, from + 4, 4) == "data" -> 16
            length > 4 && u16(b, from) == length - 4 -> 4
            else -> 0
        }
        val at = from + skip
        val count = limit - at
        if (count <= 0) return null
        val printable = (0 until minOf(count, 32)).count {
            val c = b[at + it].toInt() and 0xFF
            c >= 0x20 || c == 0x0A
        }
        if (printable < minOf(count, 32)) return null
        return sanitize(text(b, at, count, stopAtNul = false).trim()).ifEmpty { null }
    }

    private fun isoDate(seconds: Long): String? {
        if (seconds <= 0) return null
        val epoch = (seconds - 2_082_844_800L) * 1000L
        if (epoch <= 0) return null
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date(epoch))
    }

    private fun formatDuration(seconds: Double): String {
        val total = seconds.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        } else {
            String.format(Locale.US, "%d:%02d", m, sec)
        }
    }

    private fun xmpText(b: ByteArray, from: Int, size: Int): String {
        val raw = text(b, from, minOf(size, MAX_XMP), stopAtNul = false)
        val trimmed = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        return sanitize(trimmed, MAX_XMP)
    }

    private fun readPhotoshop(b: ByteArray, from: Int, limit: Int): List<MetaEntry> {
        val head = ascii(b, from, minOf(14, limit - from))
        if (!head.startsWith("Photoshop 3.0")) return emptyList()
        var pos = from + 14
        var seen = 0
        while (pos + 12 <= limit && seen < MAX_SEGMENTS) {
            if (ascii(b, pos, 4) != "8BIM") return emptyList()
            val id = u16(b, pos + 4)
            var at = pos + 6
            val nameLen = b[at].toInt() and 0xFF
            at += 1 + nameLen
            if (at % 2 != 0) at++
            if (at + 4 > limit) return emptyList()
            val size = u32(b, at, false).toInt()
            at += 4
            if (size < 0 || at + size > limit) return emptyList()
            if (id == 0x0404) return readIptc(b, at, at + size)
            pos = at + size + (size % 2)
            seen++
        }
        return emptyList()
    }

    private fun readIptc(b: ByteArray, from: Int, limit: Int): List<MetaEntry> {
        val collected = LinkedHashMap<String, MutableList<String>>()
        var pos = from
        var seen = 0
        while (pos + 5 <= limit && seen < MAX_ENTRIES) {
            if ((b[pos].toInt() and 0xFF) != 0x1C) break
            val record = b[pos + 1].toInt() and 0xFF
            val dataset = b[pos + 2].toInt() and 0xFF
            var length = u16(b, pos + 3)
            var at = pos + 5
            if (length and 0x8000 != 0) {
                val extended = length and 0x7FFF
                if (extended != 4 || at + 4 > limit) break
                length = u32(b, at, false).toInt()
                at += 4
            }
            if (length < 0 || at + length > limit) break
            if (record == 2 && length > 0) {
                val name = IPTC_TAGS[dataset]
                if (name != null) {
                    val value = sanitize(text(b, at, length, stopAtNul = false).trim())
                    if (value.isNotEmpty()) {
                        collected.getOrPut(name) { mutableListOf() }.add(value)
                    }
                }
            }
            pos = at + length
            seen++
        }
        return collected.entries.map { (name, values) ->
            MetaEntry(name, sanitize(values.joinToString(", ")))
        }
    }

    private fun readIcc(b: ByteArray, from: Int, limit: Int): List<MetaEntry> {
        if (from + 132 > limit) return emptyList()
        val entries = mutableListOf<MetaEntry>()
        val space = ascii(b, from + 16, 4).trim()
        val count = u32(b, from + 128, false).toInt()
        if (count <= 0 || count > MAX_ENTRIES) return emptyList()
        var found: String? = null
        for (i in 0 until count) {
            val at = from + 132 + i * 12
            if (at + 12 > limit) break
            if (ascii(b, at, 4) != "desc") continue
            val offset = from + u32(b, at + 4, false).toInt()
            val size = u32(b, at + 8, false).toInt()
            if (offset < from || size <= 8 || offset + size > limit) break
            found = iccDescription(b, offset, offset + size)
            break
        }
        found?.takeIf { it.isNotEmpty() }?.let { entries += MetaEntry("ProfileDescription", it) }
        if (space.isNotEmpty()) entries += MetaEntry("ColorSpace", sanitize(space, 8))
        val major = b[from + 8].toInt() and 0xFF
        val minor = (b[from + 9].toInt() and 0xFF) shr 4
        if (major in 1..9) entries += MetaEntry("ProfileVersion", "$major.$minor")
        return entries
    }

    private fun iccDescription(b: ByteArray, from: Int, limit: Int): String? {
        return when (ascii(b, from, 4)) {
            "desc" -> {
                val length = u32(b, from + 8, false).toInt()
                if (length <= 0 || from + 12 + length > limit) return null
                sanitize(text(b, from + 12, length).trim(), 80)
            }

            "mluc" -> {
                if (from + 28 > limit) return null
                val length = u32(b, from + 20, false).toInt()
                val offset = from + u32(b, from + 24, false).toInt()
                if (length <= 0 || offset < from || offset + length > limit) return null
                val slice = b.copyOfRange(offset, offset + length)
                sanitize(String(slice, Charsets.UTF_16BE).trim(), 80)
            }

            else -> null
        }
    }

    private fun keyword(b: ByteArray, from: Int, size: Int): String {
        val end = minOf(from + size, from + 40, b.size)
        val sb = StringBuilder()
        var i = from
        while (i < end && b[i] != 0.toByte()) {
            sb.append((b[i].toInt() and 0xFF).toChar())
            i++
        }
        return sanitize(sb.toString(), 40).ifEmpty { "—" }
    }

    private fun readTiff(
        b: ByteArray,
        start: Int,
        limit: Int,
        blocks: MutableList<MetaBlock>
    ): List<MetaGroup> {
        if (start + 8 > limit || limit > b.size) return emptyList()
        val little = when {
            b[start] == 'I'.code.toByte() && b[start + 1] == 'I'.code.toByte() -> true
            b[start] == 'M'.code.toByte() && b[start + 1] == 'M'.code.toByte() -> false
            else -> return emptyList()
        }
        if (u16(b, start + 2, little) != 42) return emptyList()

        val groups = mutableListOf<MetaGroup>()
        val visited = mutableSetOf<Int>()
        val queue = ArrayDeque<Pair<String, Long>>()
        queue += "IFD0" to u32(b, start + 4, little)

        while (queue.isNotEmpty() && groups.size < MAX_IFDS) {
            val (name, rawOffset) = queue.removeFirst()
            val base = start + rawOffset.toInt()
            if (rawOffset <= 0 || rawOffset > MAX_OFFSET) continue
            if (base < start || base + 2 > limit || !visited.add(base)) continue
            val count = u16(b, base, little)
            if (count <= 0 || count > MAX_ENTRIES) continue
            if (base + 2 + count * 12 > limit) continue

            val entries = mutableListOf<MetaEntry>()
            for (n in 0 until count) {
                val at = base + 2 + n * 12
                val tag = u16(b, at, little)
                val type = u16(b, at + 2, little)
                val items = u32(b, at + 4, little)
                when (tag) {
                    0x8769 -> queue += "SubIFD" to u32(b, at + 8, little)
                    0x8825 -> queue += "GPS" to u32(b, at + 8, little)
                    0xA005 -> queue += "Interop" to u32(b, at + 8, little)
                    0x927C -> blocks += MetaBlock("MakerNote", items.toInt().coerceAtLeast(0))
                    else -> {
                        val value = value(b, start, limit, tag, type, items, at + 8, little)
                        if (value != null) entries += MetaEntry(tagName(name, tag), value)
                    }
                }
            }
            if (entries.isNotEmpty()) groups += MetaGroup(name, entries)

            if (name == "IFD0") {
                val nextAt = base + 2 + count * 12
                if (nextAt + 4 <= limit) {
                    val next = u32(b, nextAt, little)
                    if (next > 0) queue += "IFD1" to next
                }
            }
        }
        return groups
    }

    private fun value(
        b: ByteArray,
        tiff: Int,
        limit: Int,
        tag: Int,
        type: Int,
        items: Long,
        inlineAt: Int,
        little: Boolean
    ): String? {
        if (items <= 0 || items > 1_000_000) return null
        val unit = when (type) {
            1, 2, 6, 7 -> 1
            3, 8 -> 2
            4, 9, 11 -> 4
            5, 10, 12 -> 8
            else -> return null
        }
        val total = unit * items
        if (total > Int.MAX_VALUE) return null
        val at = if (total <= 4) {
            inlineAt
        } else {
            tiff + u32(b, inlineAt, little).toInt()
        }
        if (at < 0 || at + total > limit) return null
        val count = items.toInt()

        return when (type) {
            2 -> sanitize(text(b, at, count).trim())
            7 -> when {
                tag == 0x9286 && count > 8 -> comment(b, at, count)
                else -> {
                    val printable = (0 until minOf(count, MAX_INLINE_BLOB)).all {
                        val c = b[at + it].toInt() and 0xFF
                        c in 0x20..0x7E || c == 0
                    }
                    if (printable && count <= MAX_INLINE_BLOB) {
                        sanitize(ascii(b, at, count).trim()).ifEmpty { "<$count>" }
                    } else {
                        "<$count>"
                    }
                }
            }

            5, 10 -> (0 until minOf(count, MAX_ARRAY)).joinToString(", ") { i ->
                val num = u32(b, at + i * 8, little)
                val den = u32(b, at + i * 8 + 4, little)
                rational(if (type == 10) signed32(num) else num, if (type == 10) signed32(den) else den)
            } + if (count > MAX_ARRAY) ", …" else ""

            11 -> (0 until minOf(count, MAX_ARRAY)).joinToString(", ") { i ->
                decimal(Float.fromBits(u32(b, at + i * 4, little).toInt()).toDouble())
            }

            12 -> (0 until minOf(count, MAX_ARRAY)).joinToString(", ") { i ->
                decimal(Double.fromBits(u64(b, at + i * 8, little)))
            }

            else -> (0 until minOf(count, MAX_ARRAY)).joinToString(", ") { i ->
                when (type) {
                    1 -> (b[at + i].toInt() and 0xFF).toString()
                    6 -> b[at + i].toInt().toString()
                    3 -> u16(b, at + i * 2, little).toString()
                    8 -> u16(b, at + i * 2, little).toShort().toString()
                    9 -> signed32(u32(b, at + i * 4, little)).toString()
                    else -> u32(b, at + i * 4, little).toString()
                }
            } + if (count > MAX_ARRAY) ", …" else ""
        }.takeIf { it.isNotEmpty() }
    }

    private fun comment(b: ByteArray, at: Int, count: Int): String {
        val marker = ascii(b, at, 8).trim().trimEnd('\u0000')
        val body = count - 8
        return when {
            marker.startsWith("ASCII") -> sanitize(text(b, at + 8, body).trim())
            marker.startsWith("UNICODE") -> {
                val slice = b.copyOfRange(at + 8, at + 8 + body)
                sanitize(String(slice, Charsets.UTF_16BE).trim().trimEnd('\u0000'))
            }
            else -> "<$count>"
        }.ifEmpty { "<$count>" }
    }

    private fun rational(num: Long, den: Long): String = when {
        den == 0L -> "$num/0"
        num % den == 0L -> (num / den).toString()
        else -> decimal(num.toDouble() / den.toDouble())
    }

    private fun decimal(v: Double): String {
        val s = String.format(java.util.Locale.US, "%.4f", v)
        return s.trimEnd('0').trimEnd('.')
    }

    private fun tagName(group: String, tag: Int): String {
        val known = if (group == "GPS") GPS_TAGS[tag] else EXIF_TAGS[tag]
        return known ?: String.format(java.util.Locale.US, "Tag 0x%04X", tag)
    }

    private fun text(b: ByteArray, from: Int, count: Int, stopAtNul: Boolean = true): String {
        if (from < 0 || count <= 0 || from + count > b.size) return ""
        val end = if (stopAtNul) {
            (0 until count).firstOrNull { b[from + it] == 0.toByte() } ?: count
        } else {
            count
        }
        if (end <= 0) return ""
        val slice = b.copyOfRange(from, from + end)
        strict(slice, Charsets.UTF_8)?.let { return it }
        cyrillic?.let { cp ->
            strict(slice, cp)?.let { decoded ->
                if (looksCyrillic(decoded)) return decoded
            }
        }
        return String(slice, Charsets.ISO_8859_1)
    }

    private fun strict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (t: CharacterCodingException) {
        null
    }

    private fun looksCyrillic(value: String): Boolean {
        val letters = value.count { it.isLetter() }
        if (letters == 0) return false
        val cyr = value.count { it in '\u0400'..'\u04FF' }
        return cyr * 2 >= letters
    }

    private val cyrillic: Charset? = try {
        Charset.forName("windows-1251")
    } catch (t: Throwable) {
        null
    }

    private fun ascii(b: ByteArray, from: Int, count: Int): String {
        if (from < 0 || count <= 0 || from + count > b.size) return ""
        return String(b, from, count, Charsets.ISO_8859_1)
    }

    private fun u16(b: ByteArray, at: Int, little: Boolean = false): Int {
        if (at < 0 || at + 2 > b.size) return 0
        val a = b[at].toInt() and 0xFF
        val c = b[at + 1].toInt() and 0xFF
        return if (little) (c shl 8) or a else (a shl 8) or c
    }

    private fun u32(b: ByteArray, at: Int, little: Boolean = false): Long {
        if (at < 0 || at + 4 > b.size) return 0
        val bytes = IntArray(4) { b[at + it].toInt() and 0xFF }
        return if (little) {
            (bytes[3].toLong() shl 24) or (bytes[2].toLong() shl 16) or
                (bytes[1].toLong() shl 8) or bytes[0].toLong()
        } else {
            (bytes[0].toLong() shl 24) or (bytes[1].toLong() shl 16) or
                (bytes[2].toLong() shl 8) or bytes[3].toLong()
        }
    }

    private fun u64(b: ByteArray, at: Int, little: Boolean): Long {
        if (at < 0 || at + 8 > b.size) return 0
        val high = u32(b, if (little) at + 4 else at, little)
        val low = u32(b, if (little) at else at + 4, little)
        return (high shl 32) or low
    }

    private fun signed32(v: Long): Long = v.toInt().toLong()

    private val EXIF_TAGS = mapOf(
        0x0100 to "ImageWidth",
        0x0101 to "ImageLength",
        0x0102 to "BitsPerSample",
        0x0103 to "Compression",
        0x0106 to "PhotometricInterpretation",
        0x010E to "ImageDescription",
        0x010F to "Make",
        0x0110 to "Model",
        0x0112 to "Orientation",
        0x0115 to "SamplesPerPixel",
        0x011A to "XResolution",
        0x011B to "YResolution",
        0x011C to "PlanarConfiguration",
        0x0128 to "ResolutionUnit",
        0x0131 to "Software",
        0x0132 to "DateTime",
        0x013B to "Artist",
        0x0201 to "ThumbnailOffset",
        0x0202 to "ThumbnailLength",
        0x0213 to "YCbCrPositioning",
        0x8298 to "Copyright",
        0x829A to "ExposureTime",
        0x829D to "FNumber",
        0x8822 to "ExposureProgram",
        0x8824 to "SpectralSensitivity",
        0x8827 to "ISOSpeedRatings",
        0x8830 to "SensitivityType",
        0x8832 to "RecommendedExposureIndex",
        0x9000 to "ExifVersion",
        0x9003 to "DateTimeOriginal",
        0x9004 to "DateTimeDigitized",
        0x9010 to "OffsetTime",
        0x9011 to "OffsetTimeOriginal",
        0x9012 to "OffsetTimeDigitized",
        0x9101 to "ComponentsConfiguration",
        0x9102 to "CompressedBitsPerPixel",
        0x9201 to "ShutterSpeedValue",
        0x9202 to "ApertureValue",
        0x9203 to "BrightnessValue",
        0x9204 to "ExposureBiasValue",
        0x9205 to "MaxApertureValue",
        0x9206 to "SubjectDistance",
        0x9207 to "MeteringMode",
        0x9208 to "LightSource",
        0x9209 to "Flash",
        0x920A to "FocalLength",
        0x9214 to "SubjectArea",
        0x9286 to "UserComment",
        0x9290 to "SubSecTime",
        0x9291 to "SubSecTimeOriginal",
        0x9292 to "SubSecTimeDigitized",
        0xA000 to "FlashpixVersion",
        0xA001 to "ColorSpace",
        0xA002 to "PixelXDimension",
        0xA003 to "PixelYDimension",
        0xA004 to "RelatedSoundFile",
        0xA20E to "FocalPlaneXResolution",
        0xA20F to "FocalPlaneYResolution",
        0xA210 to "FocalPlaneResolutionUnit",
        0xA215 to "ExposureIndex",
        0xA217 to "SensingMethod",
        0xA300 to "FileSource",
        0xA301 to "SceneType",
        0xA401 to "CustomRendered",
        0xA402 to "ExposureMode",
        0xA403 to "WhiteBalance",
        0xA404 to "DigitalZoomRatio",
        0xA405 to "FocalLengthIn35mmFilm",
        0xA406 to "SceneCaptureType",
        0xA407 to "GainControl",
        0xA408 to "Contrast",
        0xA409 to "Saturation",
        0xA40A to "Sharpness",
        0xA40C to "SubjectDistanceRange",
        0xA420 to "ImageUniqueID",
        0xA430 to "CameraOwnerName",
        0xA431 to "BodySerialNumber",
        0xA432 to "LensSpecification",
        0xA433 to "LensMake",
        0xA434 to "LensModel",
        0xA435 to "LensSerialNumber"
    )

    private val GPS_TAGS = mapOf(
        0x0000 to "GPSVersionID",
        0x0001 to "GPSLatitudeRef",
        0x0002 to "GPSLatitude",
        0x0003 to "GPSLongitudeRef",
        0x0004 to "GPSLongitude",
        0x0005 to "GPSAltitudeRef",
        0x0006 to "GPSAltitude",
        0x0007 to "GPSTimeStamp",
        0x0008 to "GPSSatellites",
        0x0009 to "GPSStatus",
        0x000A to "GPSMeasureMode",
        0x000B to "GPSDOP",
        0x000C to "GPSSpeedRef",
        0x000D to "GPSSpeed",
        0x000E to "GPSTrackRef",
        0x000F to "GPSTrack",
        0x0010 to "GPSImgDirectionRef",
        0x0011 to "GPSImgDirection",
        0x0012 to "GPSMapDatum",
        0x001D to "GPSDateStamp",
        0x001F to "GPSHPositioningError"
    )
}
