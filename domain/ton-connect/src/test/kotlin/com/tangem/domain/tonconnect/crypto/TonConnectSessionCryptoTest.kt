package com.tangem.domain.tonconnect.crypto

import com.google.common.truth.Truth.assertThat
import com.tangem.domain.tonconnect.Vectors
import com.tangem.domain.tonconnect.hexToBytes
import com.tangem.domain.tonconnect.model.TonConnectClientId
import com.tangem.domain.tonconnect.model.TonConnectException
import com.tangem.domain.tonconnect.toHex
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class TonConnectSessionCryptoTest {

    private val crypto = TonConnectSessionCrypto()
    private val alice = TonConnectSessionKeyPair.fromSecretKey(Vectors.ALICE_SK.hexToBytes())
    private val bob = TonConnectSessionKeyPair.fromSecretKey(Vectors.BOB_SK.hexToBytes())

    @Test
    fun `GIVEN two generated key pairs WHEN compared THEN keys are 32 bytes and distinct`() {
        val first = crypto.generateKeyPair()
        val second = crypto.generateKeyPair()

        assertThat(first.publicKey).hasLength(32)
        assertThat(first.secretKey).hasLength(32)
        assertThat(first.publicKey.toHex()).isNotEqualTo(second.publicKey.toHex())
        assertThat(first.clientId.hex).isEqualTo(first.publicKey.toHex())
    }

    @Test
    fun `GIVEN dApp and wallet keys WHEN encrypting then decrypting THEN plaintext round-trips`() {
        val plaintext = Vectors.PLAINTEXT_JSON.toByteArray()

        val envelope = crypto.encrypt(plaintext, bob.clientId, alice)
        val opened = crypto.decrypt(envelope, alice.clientId, bob)

        assertThat(opened).isEqualTo(plaintext)
        assertThat(envelope).hasLength(24 + 16 + plaintext.size)
    }

    @Test
    fun `GIVEN libsodium nonce WHEN encrypting THEN envelope is nonce followed by libsodium ciphertext`() {
        val envelope = crypto.encrypt(Vectors.PLAINTEXT_JSON.toByteArray(), Vectors.NONCE.hexToBytes(), bob.clientId, alice)

        assertThat(envelope.toHex()).isEqualTo(Vectors.NONCE + Vectors.BOX_CIPHERTEXT)
    }

    @Test
    fun `GIVEN same plaintext WHEN encrypted twice THEN nonces differ`() {
        val plaintext = "same".toByteArray()

        val first = crypto.encrypt(plaintext, bob.clientId, alice)
        val second = crypto.encrypt(plaintext, bob.clientId, alice)

        assertThat(first.copyOf(24).toHex()).isNotEqualTo(second.copyOf(24).toHex())
    }

    @Test
    fun `GIVEN tampered truncated or foreign envelope WHEN decrypting THEN DecryptionFailed is thrown`() {
        // Arrange
        val envelope = crypto.encrypt("secret".toByteArray(), bob.clientId, alice)
        val stranger = crypto.generateKeyPair()
        val tampered = envelope.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte() }

        // Act & Assert
        assertThrows<TonConnectException.DecryptionFailed> { crypto.decrypt(tampered, alice.clientId, bob) }
        assertThrows<TonConnectException.DecryptionFailed> { crypto.decrypt(envelope.copyOf(30), alice.clientId, bob) }
        assertThrows<TonConnectException.DecryptionFailed> { crypto.decrypt(ByteArray(24), alice.clientId, bob) }
        assertThrows<TonConnectException.DecryptionFailed> { crypto.decrypt(envelope, stranger.clientId, bob) }
        assertThrows<TonConnectException.DecryptionFailed> { crypto.decrypt(envelope, alice.clientId, stranger) }
    }

    @Test
    fun `GIVEN wrong key lengths WHEN constructing key pair or client id THEN they are rejected`() {
        assertThrows<TonConnectException.CryptoFailure> { TonConnectSessionKeyPair(ByteArray(31), ByteArray(32)) }
        assertThrows<TonConnectException.InvalidClientId> { TonConnectClientId.fromPublicKey(ByteArray(33)) }
        assertThrows<TonConnectException.InvalidClientId> { TonConnectClientId.fromHex("zz".repeat(32)) }
        assertThrows<TonConnectException.InvalidClientId> { TonConnectClientId.fromHex("ab".repeat(31)) }
    }
}
