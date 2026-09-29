package com.tangem.domain.tonconnect

import com.tangem.domain.tonconnect.crypto.TonConnectSigner
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

internal fun String.hexToBytes(): ByteArray {
    check(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** In-memory Ed25519 signer standing in for the card. Records every digest it was asked to sign. */
internal class FakeTonConnectSigner(
    private val privateKey: Ed25519PrivateKeyParameters = Ed25519PrivateKeyParameters(SecureRandom()),
) : TonConnectSigner {

    val signedDigests = mutableListOf<ByteArray>()

    val publicKey: ByteArray get() = privateKey.generatePublicKey().encoded

    override suspend fun sign(digest: ByteArray): ByteArray {
        signedDigests += digest
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(digest, 0, digest.size)
        return signer.generateSignature()
    }

    fun verify(signature: ByteArray, digest: ByteArray): Boolean {
        val verifier = Ed25519Signer()
        verifier.init(false, privateKey.generatePublicKey())
        verifier.update(digest, 0, digest.size)
        return verifier.verifySignature(signature)
    }
}

/** Signer that returns a malformed signature. */
internal object BrokenTonConnectSigner : TonConnectSigner {
    override suspend fun sign(digest: ByteArray): ByteArray = ByteArray(10)
}

/**
 * Cross-platform vectors produced by the iOS `TangemTonConnect` module (TonSwift) and libsodium for the same inputs.
 * Both implementations must agree byte for byte on everything that ends up signed or sent to a dApp.
 */
internal object Vectors {
    // NaCl box, deterministic keys
    const val ALICE_SK = "1111111111111111111111111111111111111111111111111111111111111111"
    const val ALICE_PK = "7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13"
    const val BOB_SK = "2222222222222222222222222222222222222222222222222222222222222222"
    const val BOB_PK = "0faa684ed28867b97f4a6a2dee5df8ce974e76b7018e3f22a1c4cf2678570f20"
    const val BEFORENM = "8abe7c8ae6800b76d4f5986b04b77f6e9b4f332403c90df02428336e8bd9b525"
    const val NONCE = "333333333333333333333333333333333333333333333333"
    const val PLAINTEXT_JSON = """{"method":"sendTransaction","params":["{}"],"id":"1"}"""
    const val BOX_CIPHERTEXT =
        "b263a1f455c411c7731e3702b6ad8e0d4d3ea3fcca81823882883950fc9aa2118f765fa99065a5cc07afacfbbd639d258bd623d1e0a87a38ba275832eaf0302ff7e03d9275"
    const val BOX_CIPHERTEXT_EMPTY = "7fcfa7601b47b86ee82dbf94b68d8fab"

    // Wallet v4R2 for the public key from BlockchainSdk's TONAddressTests
    const val WALLET_PUBLIC_KEY = "e7287a82bdcd3a5c2d0ee2150ccbc80d6a00991411fb44cd4d13cef46618aadb"
    const val WALLET_ADDRESS_RAW = "0:6aa21d29ab2eb3224b0664530b76a57943647baf39533b40eeaf3e8e9d480aad"
    const val WALLET_ADDRESS_NON_BOUNCEABLE = "UQBqoh0pqy6zIksGZFMLdqV5Q2R7rzlTO0Durz6OnUgKrdpr"
    const val WALLET_STATE_INIT_BOC =
        "te6cckECFgEAAwQAAgE0AgEAUQAAAAApqaMX5yh6gr3NOlwtDuIVDMvIDWoAmRQR+0TNTRPO9GYYqttAART/APSkE/S88sgLAwIBIAkEBPjygwjXGCDTH9Mf0x8C+CO78mTtRNDTH9Mf0//0BNFRQ7ryoVFRuvKiBfkBVBBk+RDyo/gAJKTIyx9SQMsfUjDL/1IQ9ADJ7VT4DwHTByHAAJ9sUZMg10qW0wfUAvsA6DDgIcAB4wAhwALjAAHAA5Ew4w0DpMjLHxLLH8v/CAcGBQAK9ADJ7VQAbIEBCNcY+gDTPzBSJIEBCPRZ8qeCEGRzdHJwdIAYyMsFywJQBc8WUAP6AhPLassfEss/yXP7AABwgQEI1xj6ANM/yFQgR4EBCPRR8qeCEG5vdGVwdIAYyMsFywJQBs8WUAT6AhTLahLLH8s/yXP7AAIAbtIH+gDU1CL5AAXIygcVy//J0Hd0gBjIywXLAiLPFlAF+gIUy2sSzMzJc/sAyEAUgQEI9FHypwICAUgTCgIBIAwLAFm9JCtvaiaECAoGuQ+gIYRw1AgIR6STfSmRDOaQPp/5g3gSgBt4EBSJhxWfMYQCASAODQARuMl+1E0NcLH4AgFYEg8CASAREAAZrx32omhAEGuQ64WPwAAZrc52omhAIGuQ64X/wAA9sp37UTQgQFA1yH0BDACyMoHy//J0AGBAQj0Cm+hMYALm0AHQ0wMhcbCSXwTgItdJwSCSXwTgAtMfIYIQcGx1Z70ighBkc3RyvbCSXwXgA/pAMCD6RAHIygfL/8nQ7UTQgQFA1yH0BDBcgQEI9ApvoTGzkl8H4AXTP8glghBwbHVnupI4MOMNA4IQZHN0crqSXwbjDRUUAIpQBIEBCPRZMO1E0IEBQNcgyAHPFvQAye1UAXKwjiOCEGRzdHKDHrFwgBhQBcsFUAPPFiP6AhPLassfyz/JgED7AJJfA+IAeAH6APQEMPgnbyIwUAqhIb7y4FCCEHBsdWeDHrFwgBhQBMsFJs8WWPoCGfQAy2kXyx9SYMs/IMmAQPsABtoNw/Q="

    // sendTransaction: now = 1764424242, valid_until = 1764424302, two messages, seqno 7 / 0, signature = 0x44 * 64
    const val DESTINATION_BOUNCEABLE = "EQBm--PFwDv1yCeS-QTJ-L8oiUpqo9IT1BwgVptlSq3ts90Q"
    const val DESTINATION_NON_BOUNCEABLE = "UQBm--PFwDv1yCeS-QTJ-L8oiUpqo9IT1BwgVptlSq3ts4DV"
    const val DESTINATION_RAW = "0:66fbe3c5c03bf5c82792f904c9f8bf28894a6aa3d213d41c20569b654aadedb3"
    const val NOW: Long = 1_764_424_242
    const val VALID_UNTIL: Long = 1_764_424_302
    const val COMMENT_PAYLOAD_BOC = "te6cckEBAQEADQAAFgAAAABjb21tZW5053+evA=="
    const val SIGNING_MESSAGE_HASH_SEQNO7 = "5494af2299ac662eefb3e76f0ae6be3891d1afb2e5720777ab7086cb939fee48"
    const val EXTERNAL_BOC_SEQNO7 =
        "te6cckECAwEAAOoAAuOIANVEOlNWXWZElgzIphbtSvKGyPdecqZ2gd1efR06kBVaAiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiFNTRi7SVfTcAAAADgAGBwCAQBiQgAzffHi4B365BPJfIJk/F+URKU1UekJ6g4QK02ypVb22YgIAAAAAAAAAAAAAAAAAAB+YgAzffHi4B365BPJfIJk/F+URKU1UekJ6g4QK02ypVb22aAvrwgAAAAAAAAAAAAAAAAAAAAAAABjb21tZW50c7QF8Q=="
    const val SIGNING_MESSAGE_HASH_SEQNO0 = "39bcf1dd0c0b535aaa3e87fddbacdc94608657d33847dd19275eba79ef1aba65"
    const val EXTERNAL_BOC_SEQNO0 =
        "te6cckECGAEAA+wABOWIANVEOlNWXWZElgzIphbtSvKGyPdecqZ2gd1efR06kBVaEYiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiIiFNTRi7SVfTcAAAAAAAGBwAhYDAQBiQgAzffHi4B365BPJfIJk/F+URKU1UekJ6g4QK02ypVb22YgIAAAAAAAAAAAAAAAAAAEU/wD0pBP0vPLICwQAfmIAM33x4uAd+uQTyXyCZPxflESlNVHpCeoOECtNsqVW9tmgL68IAAAAAAAAAAAAAAAAAAAAAAAAY29tbWVudAIBIAkFBPjygwjXGCDTH9Mf0x8C+CO78mTtRNDTH9Mf0//0BNFRQ7ryoVFRuvKiBfkBVBBk+RDyo/gAJKTIyx9SQMsfUjDL/1IQ9ADJ7VT4DwHTByHAAJ9sUZMg10qW0wfUAvsA6DDgIcAB4wAhwALjAAHAA5Ew4w0DpMjLHxLLH8v/DggHBgAK9ADJ7VQAbIEBCNcY+gDTPzBSJIEBCPRZ8qeCEGRzdHJwdIAYyMsFywJQBc8WUAP6AhPLassfEss/yXP7AABwgQEI1xj6ANM/yFQgR4EBCPRR8qeCEG5vdGVwdIAYyMsFywJQBs8WUAT6AhTLahLLH8s/yXP7AAICAUgMCgIBIA8LAFm9JCtvaiaECAoGuQ+gIYRw1AgIR6STfSmRDOaQPp/5g3gSgBt4EBSJhxWfMYQC5tAB0NMDIXGwkl8E4CLXScEgkl8E4ALTHyGCEHBsdWe9IoIQZHN0cr2wkl8F4AP6QDAg+kQByMoHy//J0O1E0IEBQNch9AQwXIEBCPQKb6Exs5JfB+AF0z/IJYIQcGx1Z7qSODDjDQOCEGRzdHK6kl8G4w0XDQCKUASBAQj0WTDtRNCBAUDXIMgBzxb0AMntVAFysI4jghBkc3Rygx6xcIAYUAXLBVADzxYj+gITy2rLH8s/yYBA+wCSXwPiAG7SB/oA1NQi+QAFyMoHFcv/ydB3dIAYyMsFywIizxZQBfoCFMtrEszMyXP7AMhAFIEBCPRR8qcCAgEgERAAEbjJftRNDXCx+AIBWBUSAgEgFBMAGa8d9qJoQBBrkOuFj8AAGa3OdqJoQCBrkOuF/8AAPbKd+1E0IEBQNch9AQwAsjKB8v/ydABgQEI9ApvoTGAAUQAAAAApqaMX5yh6gr3NOlwtDuIVDMvIDWoAmRQR+0TNTRPO9GYYqttAAHgB+gD0BDD4J28iMFAKoSG+8uBQghBwbHVngx6xcIAYUATLBSbPFlj6Ahn0AMtpF8sfUmDLPyDJgED7AAZRXkSf"

    // Digests for the wallet address above, domain "ton-connect.github.io", timestamp 1764424242
    const val APP_DOMAIN = "ton-connect.github.io"
    const val PROOF_DIGEST_NONCE = "8bc3a42365841f7debe8bfd79f6b24b9aacf36c95101cee69cbae7f7ff5c4917"
    const val SIGN_DATA_TEXT_HI_DIGEST = "ea97763cb0c5c600916c5b7de7272b81a7823324adf5bbcee829d77f164c5b0f"
    const val SIGN_DATA_BINARY_DEADBEEF_DIGEST = "85b2c51c9c0e492ebcb4135d337ad0233245c6ce1956a8050e0b3d3bb2195aa6"
    const val SIGN_DATA_CELL_SCHEMA = "message#1234 value:uint32 = Message;"
    const val SIGN_DATA_CELL_PAYLOAD_BOC = "te6cckEBAQEABgAACAAAACoFpvBE"
    const val SIGN_DATA_CELL_DIGEST = "3b524e9d6f0ee57c768ab7776958a532b412e44e09b8eca71c9b2c49ca75f5a7"
}
