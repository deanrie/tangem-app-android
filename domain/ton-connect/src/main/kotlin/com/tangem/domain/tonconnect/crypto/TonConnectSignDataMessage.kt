package com.tangem.domain.tonconnect.crypto

import com.tangem.domain.tonconnect.model.TonAddress
import com.tangem.domain.tonconnect.model.TonConnectException
import com.tangem.domain.tonconnect.model.TonConnectResponseResult
import com.tangem.domain.tonconnect.model.TonConnectSignDataPayload
import com.tangem.domain.tonconnect.model.toBigEndianBytes
import com.tangem.domain.tonconnect.transaction.TonConnectBoc
import com.tangem.domain.tonconnect.transaction.TonCells
import org.ton.cell.Cell
import org.ton.cell.CellBuilder
import java.util.zip.CRC32

/**
 * Digests for the `signData` method (`spec/rpc.md` § signData).
 *
 * `text` / `binary`:
 * ```
 * message = 0xffff ‖ "ton-connect/sign-data/" ‖ workchain:int32be ‖ hash:32
 *           ‖ len(domain):uint32be ‖ domain ‖ timestamp:uint64be
 *           ‖ ("txt" | "bin") ‖ len(data):uint32be ‖ data
 * digest  = sha256(message)
 * ```
 * All multi-byte integers are big-endian here (unlike `ton_proof`); this matches the reference verifier published
 * with the protocol.
 *
 * `cell`:
 * ```
 * message#75569022 schema_hash:uint32 timestamp:uint64 user_address:MsgAddress app_domain:^SnakeData payload:^Cell
 * digest = message.hash()
 * ```
 * where `app_domain` is the TEP-81 DNS wire form of the domain (`io\0github\0ton-connect\0`).
 */
object TonConnectSignDataMessage {

    const val CELL_MAGIC: Long = 0x75569022L

    private val FLAT_PREFIX = byteArrayOf(0xFF.toByte(), 0xFF.toByte()) + "ton-connect/sign-data/".toByteArray(Charsets.UTF_8)
    private val TEXT_PAYLOAD_PREFIX = "txt".toByteArray(Charsets.UTF_8)
    private val BINARY_PAYLOAD_PREFIX = "bin".toByteArray(Charsets.UTF_8)

    /** The unhashed message for `text` and `binary` payloads. */
    fun flatMessage(
        address: TonAddress,
        appDomain: String,
        timestamp: Long,
        content: TonConnectSignDataPayload.Content,
    ): ByteArray {
        val (payloadPrefix, payloadData) = when (content) {
            is TonConnectSignDataPayload.Content.Text -> TEXT_PAYLOAD_PREFIX to content.text.toByteArray(Charsets.UTF_8)
            is TonConnectSignDataPayload.Content.Binary -> BINARY_PAYLOAD_PREFIX to content.bytes
            is TonConnectSignDataPayload.Content.Cell ->
                throw TonConnectException.BadRequest("cell payloads are hashed as cells, not as flat messages")
        }
        val domain = appDomain.toByteArray(Charsets.UTF_8)
        return FLAT_PREFIX +
            address.toProofBytes() +
            domain.size.toBigEndianBytes() +
            domain +
            timestamp.toBigEndianBytes() +
            payloadPrefix +
            payloadData.size.toBigEndianBytes() +
            payloadData
    }

    /** Builds the cell that is hashed for a `cell` payload. */
    fun cellMessage(address: TonAddress, appDomain: String, timestamp: Long, schema: String, payload: Cell): Cell {
        val builder = CellBuilder.beginCell()
            .storeUInt(CELL_MAGIC, 32)
            .storeUInt(crc32(schema.toByteArray(Charsets.UTF_8)), 32)
            .storeUInt(timestamp, 64)
        address.storeTo(builder)
        return builder
            .storeRef(TonCells.snakeData(dnsWireFormat(appDomain)))
            .storeRef(payload)
            .endCell()
    }

    /** The 32-byte digest handed to the Ed25519 signer, for any payload variant. */
    fun digest(address: TonAddress, appDomain: String, timestamp: Long, payload: TonConnectSignDataPayload): ByteArray {
        return when (val content = payload.content) {
            is TonConnectSignDataPayload.Content.Text,
            is TonConnectSignDataPayload.Content.Binary,
            -> sha256(flatMessage(address, appDomain, timestamp, content))
            is TonConnectSignDataPayload.Content.Cell -> {
                val cell = TonConnectBoc.singleRootCell(content.cellBoc, field = "cell")
                TonCells.hash(cellMessage(address, appDomain, timestamp, content.schema, cell))
            }
        }
    }

    /** Signs [payload] and packages the result for the `signData` response. */
    suspend fun sign(
        payload: TonConnectSignDataPayload,
        address: TonAddress,
        appDomain: String,
        timestamp: Long,
        signer: TonConnectSigner,
    ): TonConnectResponseResult.SignData {
        val signature = signer.sign(digest(address, appDomain, timestamp, payload)).requireSignatureLength()
        return TonConnectResponseResult.SignData(
            signature = signature,
            address = address.toRaw(),
            timestamp = timestamp,
            domain = appDomain,
            payload = payload,
        )
    }

    /** TEP-81 DNS wire format: labels in reverse order, each terminated by `\0`. */
    internal fun dnsWireFormat(domain: String): ByteArray {
        var result = ByteArray(0)
        for (label in domain.split('.').reversed()) {
            result += label.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        }
        return result
    }

    /** CRC-32 (IEEE 802.3, the `zlib` / `crc-32` npm variant) used for the schema hash of `cell` payloads. */
    internal fun crc32(data: ByteArray): Long = CRC32().apply { update(data) }.value
}
