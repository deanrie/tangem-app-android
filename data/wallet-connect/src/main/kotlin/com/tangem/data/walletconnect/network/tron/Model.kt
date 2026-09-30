package com.tangem.data.walletconnect.network.tron

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonReader
import okio.Buffer

@JsonClass(generateAdapter = true)
internal data class WcTronSignMessageRequest(
    @Json(name = "address") val address: String,
    @Json(name = "message") val message: String,
)

@JsonClass(generateAdapter = true)
internal data class WcTronSignMessageResponse(
    @Json(name = "signature") val signature: String,
)

/**
 * Hand-rolled reader for `tron_signTransaction` params.
 *
 * Two layouts exist in the wild — the legacy one nests the unsigned transaction under `transaction.transaction`,
 * the `tron_method_version: "v1"` one puts it directly under `transaction`. `raw_data` is kept as verbatim JSON text
 * so it can be echoed back byte-for-byte: round-tripping it through a generic `Map` would rewrite integers such as
 * `timestamp` as doubles.
 */
internal object WcTronSignTransactionParams {

    private const val KEY_ADDRESS = "address"
    private const val KEY_TRANSACTION = "transaction"
    private const val KEY_RAW_DATA_HEX = "raw_data_hex"
    private const val KEY_RAW_DATA = "raw_data"
    private const val KEY_TX_ID = "txID"
    private const val KEY_VISIBLE = "visible"

    /** The request as sent; the transaction bytes are not decoded yet. */
    data class Parsed(
        val address: String,
        val rawDataHex: String,
        val txId: String?,
        val isVisible: Boolean,
        val rawDataJson: String?,
    )

    fun parse(paramsJson: String): Parsed? = runCatching {
        JsonReader.of(Buffer().writeUtf8(paramsJson)).use { reader ->
            var address: String? = null
            var unsigned: UnsignedTransaction? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    KEY_ADDRESS -> address = reader.nextString()
                    KEY_TRANSACTION -> unsigned = readTransaction(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            val tx = unsigned ?: return@use null
            Parsed(
                address = address ?: return@use null,
                rawDataHex = tx.rawDataHex ?: return@use null,
                txId = tx.txId,
                isVisible = tx.isVisible,
                rawDataJson = tx.rawDataJson,
            )
        }
    }.getOrNull()

    private class UnsignedTransaction {
        var rawDataHex: String? = null
        var txId: String? = null
        var isVisible: Boolean = false
        var rawDataJson: String? = null
        var nested: UnsignedTransaction? = null
    }

    private fun readTransaction(reader: JsonReader): UnsignedTransaction {
        val tx = UnsignedTransaction()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                KEY_RAW_DATA_HEX -> tx.rawDataHex = reader.nextString()
                KEY_TX_ID -> tx.txId = reader.nextString()
                KEY_VISIBLE -> tx.isVisible = reader.nextBoolean()
                KEY_RAW_DATA -> tx.rawDataJson = reader.nextSource().use { it.readUtf8() }
                KEY_TRANSACTION -> tx.nested = readTransaction(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        // Legacy layout: the real transaction is one level down.
        return tx.nested?.takeIf { tx.rawDataHex == null } ?: tx
    }
}
