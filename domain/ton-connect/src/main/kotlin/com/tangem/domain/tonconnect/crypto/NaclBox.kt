package com.tangem.domain.tonconnect.crypto

import org.bouncycastle.crypto.engines.Salsa20Engine
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.util.Pack
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * NaCl `crypto_box` (curve25519-xsalsa20-poly1305) assembled from BouncyCastle primitives.
 *
 * ```
 * k          = HSalsa20(X25519(sk, pk), 0¹⁶)                 -- crypto_box_beforenm
 * stream     = XSalsa20(k, nonce)
 * ciphertext = stream[32..] ⊕ plaintext
 * tag        = Poly1305(key = stream[0..32], ciphertext)
 * box        = tag ‖ ciphertext                              -- crypto_box_afternm
 * ```
 *
 * None of the four Android repositories ships a NaCl implementation, and adding one just for this is heavier
 * than the ~60 lines below; the construction is pinned against libsodium output in `NaclBoxTest`.
 */
internal object NaclBox {

    const val KEY_BYTES = 32
    const val NONCE_BYTES = 24
    const val MAC_BYTES = 16

    fun generateSecretKey(random: SecureRandom = SecureRandom()): ByteArray {
        val secretKey = ByteArray(KEY_BYTES)
        X25519.generatePrivateKey(random, secretKey)
        return secretKey
    }

    fun publicKey(secretKey: ByteArray): ByteArray {
        require(secretKey.size == KEY_BYTES)
        val publicKey = ByteArray(KEY_BYTES)
        X25519.scalarMultBase(secretKey, 0, publicKey, 0)
        return publicKey
    }

    /** `crypto_box_beforenm`: the shared symmetric key for a (sender, recipient) pair. */
    fun beforeNm(peerPublicKey: ByteArray, ownSecretKey: ByteArray): ByteArray {
        require(peerPublicKey.size == KEY_BYTES && ownSecretKey.size == KEY_BYTES)
        val shared = ByteArray(KEY_BYTES)
        X25519.scalarMult(ownSecretKey, 0, peerPublicKey, 0, shared, 0)
        return hsalsa20(shared, ByteArray(16))
    }

    /** `crypto_box`: returns `tag ‖ ciphertext` (without the nonce). */
    fun seal(plaintext: ByteArray, nonce: ByteArray, peerPublicKey: ByteArray, ownSecretKey: ByteArray): ByteArray {
        require(nonce.size == NONCE_BYTES) { "nonce must be 24 bytes" }
        val (polyKey, stream) = keystream(beforeNm(peerPublicKey, ownSecretKey), nonce, plaintext.size)
        val ciphertext = ByteArray(plaintext.size) { i -> (plaintext[i].toInt() xor stream[i].toInt()).toByte() }
        return poly1305(polyKey, ciphertext) + ciphertext
    }

    /** `crypto_box_open`: verifies the tag in constant time and returns the plaintext, or `null`. */
    fun open(box: ByteArray, nonce: ByteArray, peerPublicKey: ByteArray, ownSecretKey: ByteArray): ByteArray? {
        if (nonce.size != NONCE_BYTES || box.size < MAC_BYTES) return null
        val ciphertext = box.copyOfRange(MAC_BYTES, box.size)
        val (polyKey, stream) = keystream(beforeNm(peerPublicKey, ownSecretKey), nonce, ciphertext.size)
        val expectedTag = poly1305(polyKey, ciphertext)
        if (!MessageDigest.isEqual(expectedTag, box.copyOfRange(0, MAC_BYTES))) return null
        return ByteArray(ciphertext.size) { i -> (ciphertext[i].toInt() xor stream[i].toInt()).toByte() }
    }

    /** First 32 keystream bytes become the one-time Poly1305 key; the rest encrypts the message. */
    private fun keystream(key: ByteArray, nonce: ByteArray, length: Int): Pair<ByteArray, ByteArray> {
        val engine = XSalsa20Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        val zeros = ByteArray(KEY_BYTES + length)
        val out = ByteArray(zeros.size)
        engine.processBytes(zeros, 0, zeros.size, out, 0)
        return out.copyOfRange(0, KEY_BYTES) to out.copyOfRange(KEY_BYTES, out.size)
    }

    private fun poly1305(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Poly1305()
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        val tag = ByteArray(MAC_BYTES)
        mac.doFinal(tag, 0)
        return tag
    }

    /**
     * HSalsa20: the Salsa20 core without the final feed-forward addition, keyed by [key] on the 16-byte [input],
     * returning words 0, 5, 10, 15, 6, 7, 8, 9. BouncyCastle's `salsaCore` includes the addition, so it is undone.
     */
    internal fun hsalsa20(key: ByteArray, input: ByteArray): ByteArray {
        require(key.size == KEY_BYTES && input.size == 16)
        val state = IntArray(16)
        state[0] = SIGMA[0]
        state[5] = SIGMA[1]
        state[10] = SIGMA[2]
        state[15] = SIGMA[3]
        Pack.littleEndianToInt(key, 0, state, 1, 4)
        Pack.littleEndianToInt(key, 16, state, 11, 4)
        Pack.littleEndianToInt(input, 0, state, 6, 4)

        val mixed = IntArray(16)
        Salsa20Engine.salsaCore(20, state, mixed)

        val out = ByteArray(KEY_BYTES)
        intArrayOf(0, 5, 10, 15, 6, 7, 8, 9).forEachIndexed { index, word ->
            Pack.intToLittleEndian(mixed[word] - state[word], out, index * 4)
        }
        return out
    }

    /** "expand 32-byte k" */
    private val SIGMA = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574)
}
