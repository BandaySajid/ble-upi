package com.bleupi.protocol

import java.io.ByteArrayOutputStream

sealed class CborValue {
    data class Bytes(val data: ByteArray) : CborValue()
    data class Text(val text: String) : CborValue()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CborValue) return false
        return when (this) {
            is Bytes -> other is Bytes && data.contentEquals(other.data)
            is Text -> other is Text && text == other.text
        }
    }

    override fun hashCode(): Int = when (this) {
        is Bytes -> 31 * data.contentHashCode()
        is Text -> 31 * text.hashCode()
    }
}

object CborCodec {
    private const val MAJOR_BYTES = 0x40
    private const val MAJOR_TEXT = 0x60

    fun encodeMap(map: Map<Int, CborValue>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xA0 or (map.size and 0x1F))
        for ((key, value) in map) {
            out.write(key)
            when (value) {
                is CborValue.Bytes -> {
                    encodeHead(out, MAJOR_BYTES, value.data.size)
                    out.write(value.data)
                }
                is CborValue.Text -> {
                    val utf8 = value.text.toByteArray(Charsets.UTF_8)
                    encodeHead(out, MAJOR_TEXT, utf8.size)
                    out.write(utf8)
                }
            }
        }
        return out.toByteArray()
    }

    fun decodeMap(data: ByteArray, offset: Int = 0): Pair<Map<Int, CborValue>, Int> {
        var pos = offset
        val header = data[pos++].toInt() and 0xFF
        val majorType = header shr 5
        require(majorType == 5) { "Not a CBOR map" }

        // Map header length: <24 inlined, 24 means 1-byte length follows, 25 means 2-byte.
        val mapSize = when (val info = header and 0x1F) {
            in 0..23 -> info
            24 -> data[pos++].toInt() and 0xFF
            25 -> {
                val hi = data[pos++].toInt() and 0xFF
                val lo = data[pos++].toInt() and 0xFF
                (hi shl 8) or lo
            }
            else -> throw IllegalArgumentException("Unsupported CBOR map length token: $info")
        }

        val map = LinkedHashMap<Int, CborValue>()
        repeat(mapSize) {
            val key = data[pos++].toInt() and 0xFF
            val valueHeader = data[pos++].toInt() and 0xFF
            val valueMajor = valueHeader shr 5
            val valueLen = decodeHeadLength(data, valueHeader, pos)
            pos = advanceAfterLength(data, valueHeader, pos)
            when (valueMajor) {
                2 -> {
                    val bytes = data.copyOfRange(pos - valueLen, pos)
                    map[key] = CborValue.Bytes(bytes)
                }
                3 -> {
                    val bytes = data.copyOfRange(pos - valueLen, pos)
                    map[key] = CborValue.Text(String(bytes, Charsets.UTF_8))
                }
                else -> throw IllegalArgumentException("Unsupported CBOR major type: $valueMajor")
            }
        }
        return Pair(map, pos)
    }

    /**
     * Reads the length field encoded in CBOR's length token. Returns how many
     * payload bytes the value occupies — independent of where [pos] currently
     * is. Caller MUST have already validated [pos] + extra-bytes are within
     * bounds before invoking.
     */
    private fun decodeHeadLength(data: ByteArray, valueHeader: Int, pos: Int): Int {
        val info = valueHeader and 0x1F
        return when (info) {
            in 0..23 -> info
            24 -> data[pos].toInt() and 0xFF
            25 -> {
                val hi = data[pos].toInt() and 0xFF
                val lo = data[pos + 1].toInt() and 0xFF
                (hi shl 8) or lo
            }
            else -> throw IllegalArgumentException("Unsupported CBOR length token: $info")
        }
    }

    /**
     * Returns [pos] advanced past both the length-extension bytes and the
     * payload itself.
     */
    private fun advanceAfterLength(data: ByteArray, valueHeader: Int, pos: Int): Int {
        val info = valueHeader and 0x1F
        val lengthBytesConsumed = when (info) {
            in 0..23 -> 0
            24 -> 1
            25 -> 2
            else -> throw IllegalArgumentException("Unsupported CBOR length token: $info")
        }
        val payload = decodeHeadLength(data, valueHeader, pos)
        return pos + lengthBytesConsumed + payload
    }

    private fun encodeHead(out: ByteArrayOutputStream, major: Int, len: Int) {
        if (len < 24) {
            out.write(major or len)
        } else if (len < 256) {
            out.write(major or 24)
            out.write(len)
        } else {
            out.write(major or 25)
            out.write((len shr 8) and 0xFF)
            out.write(len and 0xFF)
        }
    }
}

fun encodeCborMap(map: Map<Int, CborValue>): ByteArray = CborCodec.encodeMap(map)

fun decodeCborMap(data: ByteArray, offset: Int = 0): Pair<Map<Int, CborValue>, Int> = CborCodec.decodeMap(data, offset)
