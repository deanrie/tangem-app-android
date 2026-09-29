package com.tangem.domain.tonconnect.model

/**
 * TON network `global_id` as carried on every `network` field of TON Connect (`-239` mainnet, `-3` testnet).
 *
 * The spec requires wallets to treat the value as an opaque identifier and to enforce exact string equality
 * against the active wallet network — never to special-case unknown values.
 */
@JvmInline
value class TonConnectNetworkId(val value: String) {

    override fun toString(): String = value

    companion object {
        val MAINNET = TonConnectNetworkId("-239")
        val TESTNET = TonConnectNetworkId("-3")
    }
}

/**
 * A TON Connect `client_id`: the 32-byte X25519 public key of one side of a session. On the bridge and in deep
 * links the id travels as 64 lowercase hex characters.
 */
class TonConnectClientId private constructor(val publicKey: ByteArray) {

    val hex: String get() = publicKey.toTonConnectHex()

    override fun equals(other: Any?): Boolean = other is TonConnectClientId && publicKey.contentEquals(other.publicKey)

    override fun hashCode(): Int = publicKey.contentHashCode()

    override fun toString(): String = hex

    companion object {
        const val BYTE_COUNT = 32

        fun fromPublicKey(publicKey: ByteArray): TonConnectClientId {
            if (publicKey.size != BYTE_COUNT) throw TonConnectException.InvalidClientId()
            return TonConnectClientId(publicKey.copyOf())
        }

        /** Parses the 64-character hex form used in `tc://` links and bridge envelopes. */
        fun fromHex(hex: String): TonConnectClientId {
            if (hex.length != BYTE_COUNT * 2) throw TonConnectException.InvalidClientId()
            val bytes = hex.tonConnectHexToBytesOrNull() ?: throw TonConnectException.InvalidClientId()
            return fromPublicKey(bytes)
        }
    }
}

internal fun ByteArray.toTonConnectHex(): String = joinToString(separator = "") { "%02x".format(it) }

/** Strict hex decoding: even length, `[0-9a-fA-F]` only, no `0x` prefix. */
internal fun String.tonConnectHexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val high = Character.digit(this[2 * i], 16)
        val low = Character.digit(this[2 * i + 1], 16)
        if (high < 0 || low < 0) return null
        out[i] = ((high shl 4) or low).toByte()
    }
    return out
}
