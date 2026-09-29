package com.tangem.domain.tonconnect.crypto

import com.google.common.truth.Truth.assertThat
import com.tangem.domain.tonconnect.Vectors
import com.tangem.domain.tonconnect.hexToBytes
import com.tangem.domain.tonconnect.toHex
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Pins the BouncyCastle-assembled `crypto_box` against libsodium output for the same keys, nonce and message. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class NaclBoxTest {

    private val aliceSk = Vectors.ALICE_SK.hexToBytes()
    private val alicePk = Vectors.ALICE_PK.hexToBytes()
    private val bobSk = Vectors.BOB_SK.hexToBytes()
    private val bobPk = Vectors.BOB_PK.hexToBytes()
    private val nonce = Vectors.NONCE.hexToBytes()

    @Test
    fun `GIVEN deterministic secret keys WHEN deriving public keys THEN they match libsodium`() {
        assertThat(NaclBox.publicKey(aliceSk).toHex()).isEqualTo(Vectors.ALICE_PK)
        assertThat(NaclBox.publicKey(bobSk).toHex()).isEqualTo(Vectors.BOB_PK)
    }

    @Test
    fun `GIVEN alice and bob keys WHEN computing beforenm THEN shared key matches libsodium and is symmetric`() {
        assertThat(NaclBox.beforeNm(bobPk, aliceSk).toHex()).isEqualTo(Vectors.BEFORENM)
        assertThat(NaclBox.beforeNm(alicePk, bobSk).toHex()).isEqualTo(Vectors.BEFORENM)
    }

    @Test
    fun `GIVEN fixed nonce WHEN sealing THEN ciphertext matches libsodium byte for byte`() {
        // Arrange
        val plaintext = Vectors.PLAINTEXT_JSON.toByteArray()

        // Act
        val box = NaclBox.seal(plaintext, nonce, bobPk, aliceSk)
        val emptyBox = NaclBox.seal(ByteArray(0), nonce, bobPk, aliceSk)

        // Assert
        assertThat(box.toHex()).isEqualTo(Vectors.BOX_CIPHERTEXT)
        assertThat(emptyBox.toHex()).isEqualTo(Vectors.BOX_CIPHERTEXT_EMPTY)
    }

    @Test
    fun `GIVEN libsodium ciphertext WHEN opening with the recipient key THEN plaintext is recovered`() {
        val opened = NaclBox.open(Vectors.BOX_CIPHERTEXT.hexToBytes(), nonce, alicePk, bobSk)

        assertThat(opened?.toString(Charsets.UTF_8)).isEqualTo(Vectors.PLAINTEXT_JSON)
    }

    @Test
    fun `GIVEN tampered truncated or foreign ciphertext WHEN opening THEN null is returned`() {
        // Arrange
        val box = Vectors.BOX_CIPHERTEXT.hexToBytes()
        val tampered = box.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte() }
        val strangerSk = ByteArray(32) { 0x55 }

        // Act & Assert
        assertThat(NaclBox.open(tampered, nonce, alicePk, bobSk)).isNull()
        assertThat(NaclBox.open(box.copyOf(15), nonce, alicePk, bobSk)).isNull()
        assertThat(NaclBox.open(box, nonce, NaclBox.publicKey(strangerSk), bobSk)).isNull()
        assertThat(NaclBox.open(box, nonce, alicePk, strangerSk)).isNull()
        assertThat(NaclBox.open(box, nonce.copyOf().also { it[0] = 0 }, alicePk, bobSk)).isNull()
    }

    @Test
    fun `GIVEN empty input WHEN computing hsalsa20 THEN matches the NaCl reference vector`() {
        // Reference: HSalsa20 with the 32-byte key "shared secret" from the NaCl paper, zero input.
        val key = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742".hexToBytes()
        val expected = "1b27556473e985d462cd51197a9a46c76009549eac6474f206c4ee0844f68389"

        assertThat(NaclBox.hsalsa20(key, ByteArray(16)).toHex()).isEqualTo(expected)
    }
}
