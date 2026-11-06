package com.nekohome.translate.diag

/**
 * Just enough of the protobuf wire format to read a tombstone without a protobuf dependency:
 * varint (0), 64-bit (1), length-delimited (2) and 32-bit (5) fields. Groups (3 / 4) are
 * deprecated and never used by tombstone.proto, so they are treated as malformed input.
 *
 * Pure JVM. Every read is bounds-checked and throws [ProtoException] on truncated or malformed
 * input, so a corrupt file never produces an out-of-bounds read.
 */
class ProtoException(message: String) : Exception(message)

class ProtoReader(private val buf: ByteArray, start: Int = 0, private val end: Int = buf.size) {

    var pos: Int = start
        private set

    init {
        if (start < 0 || end > buf.size || start > end) throw ProtoException("bad range $start..$end of ${buf.size}")
    }

    val hasMore: Boolean get() = pos < end

    /** Next field key: (field number, wire type). Call only while [hasMore]. */
    fun readTag(): Pair<Int, Int> {
        val key = readVarint()
        val field = (key ushr 3).toInt()
        val wire = (key and 7).toInt()
        if (field <= 0) throw ProtoException("bad field number $field at $pos")
        return field to wire
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= end) throw ProtoException("truncated varint")
            val b = buf[pos++].toInt() and 0xff
            if (shift < 64) result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift >= 70) throw ProtoException("varint too long")
        }
    }

    fun readFixed64(): Long {
        need(8)
        var v = 0L
        for (i in 0 until 8) v = v or ((buf[pos + i].toLong() and 0xff) shl (8 * i))
        pos += 8
        return v
    }

    fun readFixed32(): Int {
        need(4)
        var v = 0
        for (i in 0 until 4) v = v or ((buf[pos + i].toInt() and 0xff) shl (8 * i))
        pos += 4
        return v
    }

    /** A length-delimited field as a sub-reader over the same buffer (no copy). */
    fun readMessage(): ProtoReader {
        val len = readLength()
        val r = ProtoReader(buf, pos, pos + len)
        pos += len
        return r
    }

    fun readString(): String {
        val len = readLength()
        val s = String(buf, pos, len, Charsets.UTF_8)
        pos += len
        return s
    }

    fun skip(wire: Int) {
        when (wire) {
            0 -> readVarint()
            1 -> { need(8); pos += 8 }
            2 -> { val len = readLength(); pos += len }
            5 -> { need(4); pos += 4 }
            else -> throw ProtoException("unsupported wire type $wire at $pos")
        }
    }

    private fun readLength(): Int {
        val len = readVarint()
        if (len < 0 || len > (end - pos).toLong()) throw ProtoException("length $len exceeds remaining ${end - pos}")
        return len.toInt()
    }

    private fun need(n: Int) {
        if (end - pos < n) throw ProtoException("truncated: need $n, have ${end - pos}")
    }
}
