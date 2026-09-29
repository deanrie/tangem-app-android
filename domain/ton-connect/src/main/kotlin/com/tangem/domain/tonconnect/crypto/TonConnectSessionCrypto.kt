package com.tangem.domain.tonconnect.crypto

import com.tangem.domain.tonconnect.model.TonConnectClientId
import com.tangem.domain.tonconnect.model.TonConnectException
import java.security.SecureRandom

/**
 * X25519 keypair of the wallet side of one session (`spec/session.md`).
 *
 * The secret key must be stored in the Android Keystore-backed storage by the caller; it is the only thing that
 * lets the wallet read the dApp's messages and is discarded when the session ends.
 */
class TonConnectSessionKeyPair(val publicKey: ByteArray, val secretKey: ByteArray) {

    init {
        if (publicKey.size != NaclBox.KEY_BYTES || secretKey.size != NaclBox.KEY_BYTES) {
            throw TonConnectException.CryptoFailure()
        }
    }

    val clientId: TonConnectClientId get() = TonConnectClientId.fromPublicKey(publicKey)

    companion object {
        fun fromSecretKey(secretKey: ByteArray): TonConnectSessionKeyPair =
            TonConnectSessionKeyPair(publicKey = NaclBox.publicKey(secretKey), secretKey = secretKey)
    }
}

/**
 * End-to-end encryption between dApp and wallet on top of the untrusted HTTP bridge.
 *
 * Wire format of every bridge message: `nonce (24 bytes) ‖ nacl.box(plaintext, nonce, peer_pk, own_sk)`.
 */
class TonConnectSessionCrypto(private val random: SecureRandom = SecureRandom()) {

    /** Generates a fresh keypair. One keypair per session; a new keypair means a new session. */
    fun generateKeyPair(): TonConnectSessionKeyPair = TonConnectSessionKeyPair.fromSecretKey(NaclBox.generateSecretKey(random))

    /** Encrypts [plaintext] for [recipient] and returns `nonce ‖ ciphertext` with 24 fresh random nonce bytes. */
    fun encrypt(plaintext: ByteArray, recipient: TonConnectClientId, keyPair: TonConnectSessionKeyPair): ByteArray {
        val nonce = ByteArray(NaclBox.NONCE_BYTES).also(random::nextBytes)
        return encrypt(plaintext, nonce, recipient, keyPair)
    }

    internal fun encrypt(
        plaintext: ByteArray,
        nonce: ByteArray,
        recipient: TonConnectClientId,
        keyPair: TonConnectSessionKeyPair,
    ): ByteArray = nonce + NaclBox.seal(plaintext, nonce, recipient.publicKey, keyPair.secretKey)

    /**
     * Opens `nonce ‖ ciphertext` sent by [sender]. Any failure (wrong key, truncation, tampering) throws and the
     * message must be discarded, never interpreted as plaintext.
     */
    fun decrypt(envelope: ByteArray, sender: TonConnectClientId, keyPair: TonConnectSessionKeyPair): ByteArray {
        if (envelope.size < NaclBox.NONCE_BYTES + NaclBox.MAC_BYTES) throw TonConnectException.DecryptionFailed()
        val nonce = envelope.copyOfRange(0, NaclBox.NONCE_BYTES)
        val box = envelope.copyOfRange(NaclBox.NONCE_BYTES, envelope.size)
        return NaclBox.open(box, nonce, sender.publicKey, keyPair.secretKey) ?: throw TonConnectException.DecryptionFailed()
    }

    companion object {
        const val NONCE_BYTE_COUNT = NaclBox.NONCE_BYTES
    }
}
