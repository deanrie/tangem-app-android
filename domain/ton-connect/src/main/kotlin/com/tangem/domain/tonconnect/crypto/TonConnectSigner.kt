package com.tangem.domain.tonconnect.crypto

import com.tangem.domain.tonconnect.model.TonConnectException

/**
 * Ed25519 signer for the connected TON account.
 *
 * Every TON Connect signature (transaction, `ton_proof`, `signData`) is a plain Ed25519 signature over a 32-byte
 * digest, which is exactly what the Tangem card / hot wallet SDK produce for TON today. The app supplies the
 * implementation (card session, hot wallet); the core never sees a private key.
 */
fun interface TonConnectSigner {

    /** Returns the 64-byte Ed25519 signature of [digest] made with the account's key. */
    suspend fun sign(digest: ByteArray): ByteArray
}

internal const val SIGNATURE_BYTE_COUNT = 64

internal fun ByteArray.requireSignatureLength(): ByteArray {
    if (size != SIGNATURE_BYTE_COUNT) {
        throw TonConnectException.InternalFailure("signer returned $size bytes, expected $SIGNATURE_BYTE_COUNT")
    }
    return this
}
