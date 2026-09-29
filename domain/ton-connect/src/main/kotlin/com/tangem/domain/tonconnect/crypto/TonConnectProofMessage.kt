package com.tangem.domain.tonconnect.crypto

import com.tangem.domain.tonconnect.model.TonAddress
import com.tangem.domain.tonconnect.model.TonConnectProof
import com.tangem.domain.tonconnect.model.toLittleEndianBytes
import java.security.MessageDigest

/**
 * Byte layout and digest of the `ton_proof` connect item (`spec/connect.md` § Address proof signature).
 *
 * ```
 * message   = "ton-proof-item-v2/" ‖ workchain:int32be ‖ hash:32 ‖ len(domain):uint32le ‖ domain ‖ timestamp:uint64le ‖ payload
 * signature = Ed25519Sign(sha256(0xffff ‖ "ton-connect" ‖ sha256(message)))
 * ```
 *
 * Note the mixed endianness — it is part of the wire format and differs from `signData`.
 */
object TonConnectProofMessage {

    private val ITEM_PREFIX = "ton-proof-item-v2/".toByteArray(Charsets.UTF_8)
    private val SIGNATURE_PREFIX = byteArrayOf(0xFF.toByte(), 0xFF.toByte()) + "ton-connect".toByteArray(Charsets.UTF_8)

    /** The unhashed proof message. */
    fun message(address: TonAddress, appDomain: String, timestamp: Long, payload: String): ByteArray {
        val domain = appDomain.toByteArray(Charsets.UTF_8)
        return ITEM_PREFIX +
            address.toProofBytes() +
            domain.size.toLittleEndianBytes() +
            domain +
            timestamp.toLittleEndianBytes() +
            payload.toByteArray(Charsets.UTF_8)
    }

    /** The 32-byte digest handed to the Ed25519 signer. */
    fun digest(address: TonAddress, appDomain: String, timestamp: Long, payload: String): ByteArray {
        val inner = sha256(message(address, appDomain, timestamp, payload))
        return sha256(SIGNATURE_PREFIX + inner)
    }

    /** Signs the proof for [address] and packages it as the `ton_proof` reply item. */
    suspend fun makeProof(
        address: TonAddress,
        appDomain: String,
        payload: String,
        timestamp: Long,
        signer: TonConnectSigner,
    ): TonConnectProof {
        val signature = signer.sign(digest(address, appDomain, timestamp, payload)).requireSignatureLength()
        return TonConnectProof(timestamp = timestamp, domain = appDomain, signature = signature, payload = payload)
    }
}

internal fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
