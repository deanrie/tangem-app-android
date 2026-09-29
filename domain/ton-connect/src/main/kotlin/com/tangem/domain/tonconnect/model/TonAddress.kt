package com.tangem.domain.tonconnect.model

import org.ton.cell.CellBuilder
import java.util.Base64

/**
 * A TON `addr_std` account address: workchain + 256-bit account id.
 *
 * Supports the two textual forms TON Connect uses — raw `wc:hex` and the user-friendly base64(url) form of
 * TEP-2, whose tag byte carries the bounceable / test-only flags the wallet must honour.
 */
class TonAddress(val workchain: Int, val hash: ByteArray) {

    init {
        require(hash.size == HASH_BYTE_COUNT) { "address hash must be 32 bytes" }
        require(workchain in Byte.MIN_VALUE..Byte.MAX_VALUE) { "workchain must fit in int8" }
    }

    fun toRaw(): String = "$workchain:${hash.toTonConnectHex()}"

    fun toFriendly(bounceable: Boolean, testOnly: Boolean = false, urlSafe: Boolean = true): String {
        var tag = if (bounceable) BOUNCEABLE_TAG else NON_BOUNCEABLE_TAG
        if (testOnly) tag = tag or TEST_FLAG
        val body = ByteArray(FRIENDLY_BYTE_COUNT - 2)
        body[0] = tag.toByte()
        body[1] = workchain.toByte()
        hash.copyInto(body, destinationOffset = 2)
        val crc = crc16(body)
        val full = body + byteArrayOf((crc shr 8).toByte(), crc.toByte())
        val encoder = if (urlSafe) Base64.getUrlEncoder() else Base64.getEncoder()
        return encoder.encodeToString(full)
    }

    /** `workchain` as a 32-bit signed big-endian integer followed by the 256-bit account hash (proof encoding). */
    fun toProofBytes(): ByteArray = workchain.toBigEndianBytes() + hash

    /** Stores `addr_std$10 anycast:nothing workchain_id:int8 address:bits256`. */
    fun storeTo(builder: CellBuilder): CellBuilder = builder
        .storeBits(true, false) // addr_std$10
        .storeBit(false) // anycast: nothing
        .storeInt(workchain, 8)
        .storeBytes(hash)

    override fun equals(other: Any?): Boolean =
        other is TonAddress && other.workchain == workchain && other.hash.contentEquals(hash)

    override fun hashCode(): Int = 31 * workchain + hash.contentHashCode()

    override fun toString(): String = toRaw()

    /** User-friendly form together with the flags encoded in its tag byte. */
    class Friendly(val address: TonAddress, val isBounceable: Boolean, val isTestOnly: Boolean)

    companion object {
        const val HASH_BYTE_COUNT = 32
        private const val FRIENDLY_BYTE_COUNT = 36
        private const val BOUNCEABLE_TAG = 0x11
        private const val NON_BOUNCEABLE_TAG = 0x51
        private const val TEST_FLAG = 0x80

        /** Parses either the raw or the user-friendly form. */
        fun parse(source: String): TonAddress =
            if (source.contains(':')) parseRaw(source) else parseFriendly(source).address

        fun parseRaw(raw: String): TonAddress {
            val parts = raw.split(':')
            require(parts.size == 2) { "raw address must be <workchain>:<hex>" }
            val workchain = parts[0].toIntOrNull() ?: throw IllegalArgumentException("workchain is not an integer")
            val hash = parts[1].tonConnectHexToBytesOrNull() ?: throw IllegalArgumentException("hash is not hex")
            require(hash.size == HASH_BYTE_COUNT) { "hash must be 32 bytes" }
            return TonAddress(workchain, hash)
        }

        fun parseFriendly(friendly: String): Friendly {
            val normalized = friendly.replace('-', '+').replace('_', '/')
            val bytes = try {
                Base64.getDecoder().decode(normalized)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("address is not valid base64", e)
            }
            require(bytes.size == FRIENDLY_BYTE_COUNT) { "friendly address must decode to 36 bytes" }

            val crc = crc16(bytes.copyOfRange(0, FRIENDLY_BYTE_COUNT - 2))
            require(bytes[34] == (crc shr 8).toByte() && bytes[35] == crc.toByte()) { "address checksum mismatch" }

            var tag = bytes[0].toInt() and 0xFF
            val testOnly = tag and TEST_FLAG != 0
            if (testOnly) tag = tag xor TEST_FLAG
            require(tag == BOUNCEABLE_TAG || tag == NON_BOUNCEABLE_TAG) { "unknown address tag" }

            val workchain = bytes[1].toInt() // sign-extended: 0xff → -1 (masterchain)
            return Friendly(
                address = TonAddress(workchain, bytes.copyOfRange(2, 34)),
                isBounceable = tag == BOUNCEABLE_TAG,
                isTestOnly = testOnly,
            )
        }

        /** CRC-16/XMODEM as used by TEP-2. */
        internal fun crc16(data: ByteArray): Int {
            var reg = 0
            for (byte in data + byteArrayOf(0, 0)) {
                var mask = 0x80
                while (mask > 0) {
                    reg = reg shl 1
                    if (byte.toInt() and mask != 0) reg = reg or 1
                    mask = mask shr 1
                    if (reg > 0xFFFF) {
                        reg = reg and 0xFFFF
                        reg = reg xor 0x1021
                    }
                }
            }
            return reg and 0xFFFF
        }
    }
}

internal fun Int.toBigEndianBytes(): ByteArray = byteArrayOf(
    (this ushr 24).toByte(),
    (this ushr 16).toByte(),
    (this ushr 8).toByte(),
    this.toByte(),
)

internal fun Long.toBigEndianBytes(): ByteArray = ByteArray(8) { i -> (this ushr (56 - 8 * i)).toByte() }

internal fun Int.toLittleEndianBytes(): ByteArray = ByteArray(4) { i -> (this ushr (8 * i)).toByte() }

internal fun Long.toLittleEndianBytes(): ByteArray = ByteArray(8) { i -> (this ushr (8 * i)).toByte() }
