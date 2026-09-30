package com.tangem.data.walletconnect.network.tron

import com.tangem.blockchain.blockchains.ethereum.EthereumUtils.toKeccak
import com.tangem.common.extensions.hexToBytes
import com.tangem.common.extensions.toHexString
import com.tangem.domain.walletconnect.model.WcTronTransactionDetails
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Protobuf vectors are hand-encoded `protocol.Transaction.raw` messages (ref block 7803/16138f9255a1db91,
 * expiration 1756201572000, timestamp 1756201512720); txIDs are their sha256. The contract-call vector reproduces
 * the `TriggerSmartContract` example from the Reown Tron RPC reference. Same vectors as the iOS test suite.
 */
internal class WcTronTransactionParserTest {

    // region Transfer

    @Test
    fun `parses transfer and derives txID from raw data`() {
        val signable = WcTronTransactionParser.makeSignableTransaction(
            rawDataHex = TRANSFER_RAW,
            txId = TRANSFER_TX_ID,
            expectedOwnerAddress = OWNER,
        )

        assertEquals(TRANSFER_TX_ID, signable.txId)
        assertTrue(TRANSFER_RAW.hexToBytes().contentEquals(signable.rawData))
        assertEquals(
            WcTronTransactionDetails.Transfer(
                ownerAddress = OWNER,
                destinationAddress = RECIPIENT,
                amountSun = 1_500_000,
                memo = "hi",
            ),
            signable.details,
        )
    }

    @Test
    fun `parses contract call with fee limit and calldata`() {
        val signable = WcTronTransactionParser.makeSignableTransaction(
            rawDataHex = "0x$CALL_RAW",
            txId = null,
            expectedOwnerAddress = OWNER,
        )

        assertEquals(CALL_TX_ID, signable.txId)
        assertEquals(
            WcTronTransactionDetails.ContractCall(
                ownerAddress = OWNER,
                contractAddress = USDT_CONTRACT,
                callValueSun = 0,
                callDataHex = CALL_DATA,
                feeLimitSun = 200_000_000,
                memo = null,
            ),
            signable.details,
        )
    }

    // endregion

    // region Rejections

    @Test
    fun `rejects txID that does not match raw data`() {
        assertThrows(WcTronTransactionParser.ParseError.TxIdMismatch::class.java) {
            WcTronTransactionParser.makeSignableTransaction(
                rawDataHex = TRANSFER_RAW,
                txId = CALL_TX_ID,
                expectedOwnerAddress = OWNER,
            )
        }
    }

    @Test
    fun `rejects transaction owned by another account`() {
        assertThrows(WcTronTransactionParser.ParseError.OwnerMismatch::class.java) {
            WcTronTransactionParser.makeSignableTransaction(
                rawDataHex = FOREIGN_OWNER_RAW,
                txId = null,
                expectedOwnerAddress = OWNER,
            )
        }
        // ...and accepts it for the account that actually owns it.
        val signable = WcTronTransactionParser.makeSignableTransaction(
            rawDataHex = FOREIGN_OWNER_RAW,
            txId = null,
            expectedOwnerAddress = RECIPIENT,
        )
        assertEquals(RECIPIENT, signable.details.ownerAddress)
    }

    @Test
    fun `rejects unsupported contract types and malformed bytes`() {
        assertThrows(WcTronTransactionParser.ParseError.UnsupportedTransaction::class.java) {
            WcTronTransactionParser.makeSignableTransaction(FREEZE_RAW, null, OWNER)
        }
        assertThrows(WcTronTransactionParser.ParseError.InvalidRawData::class.java) {
            WcTronTransactionParser.makeSignableTransaction("", null, OWNER)
        }
        assertThrows(WcTronTransactionParser.ParseError.InvalidRawData::class.java) {
            WcTronTransactionParser.makeSignableTransaction("zz", null, OWNER)
        }
        // Valid protobuf, but no contract at all.
        assertThrows(WcTronTransactionParser.ParseError.UnsupportedTransaction::class.java) {
            WcTronTransactionParser.makeSignableTransaction("0a027803", null, OWNER)
        }
        assertThrows(WcTronTransactionParser.ParseError.InvalidRawData::class.java) {
            val oversized = "00".repeat(WcTronTransactionParser.MAX_RAW_DATA_BYTE_COUNT + 1)
            WcTronTransactionParser.makeSignableTransaction(oversized, null, OWNER)
        }
    }

    // endregion

    // region Params

    @Test
    fun `decodes legacy nested and v1 request layouts keeping raw_data verbatim`() {
        val v1 = """{"address":"$OWNER","transaction":{"visible":false,"txID":"$CALL_TX_ID",""" +
            """"raw_data":{"contract":[],"timestamp":1756201512720},"raw_data_hex":"$CALL_RAW"}}"""
        val legacy = """{"address":"$OWNER","transaction":{"transaction":{"raw_data":{"contract":[]},""" +
            """"raw_data_hex":"$CALL_RAW"}}}"""

        val v1Parsed = requireNotNull(WcTronSignTransactionParams.parse(v1))
        val legacyParsed = requireNotNull(WcTronSignTransactionParams.parse(legacy))

        assertEquals(CALL_RAW, v1Parsed.rawDataHex)
        assertEquals(CALL_TX_ID, v1Parsed.txId)
        assertEquals("""{"contract":[],"timestamp":1756201512720}""", v1Parsed.rawDataJson)
        assertEquals(CALL_RAW, legacyParsed.rawDataHex)
        assertNull(legacyParsed.txId)
        assertEquals(OWNER, legacyParsed.address)

        assertNull(WcTronSignTransactionParams.parse("""{"address":"$OWNER","transaction":{"visible":true}}"""))
        assertNull(WcTronSignTransactionParams.parse("not json"))
    }

    // endregion

    // region Message

    @Test
    fun `message digest follows signMessageV2 layout`() {
        // keccak256("\x19TRON Signed Message:\n" ‖ "39" ‖ message), computed with a reference Keccak.
        assertEquals(
            "aa8faa6427ddbbcbcdd441df0adec9ddebc1188e0d4cce7a43a3d4bf9496acac",
            WcTronTransactionParser.messageBytesToHash("This is a message to be signed for Tron").toKeccak()
                .toHexString().lowercase(),
        )
        // Length prefix counts UTF-8 bytes (12), not characters (6).
        assertEquals(
            "86914ed657b597fe28295a8b16336b018243e570a49091fae83f22f71c800e4a",
            WcTronTransactionParser.messageBytesToHash("Привет").toKeccak().toHexString().lowercase(),
        )
    }

    @Test
    fun `signature formatting rebases recovery byte for transactions only`() {
        val rs = ByteArray(64) { 0xAB.toByte() }
        val rsHex = rs.toHexString().lowercase()

        assertEquals(rsHex + "00", WcTronTransactionParser.transactionSignatureHex(rs + byteArrayOf(27)))
        assertEquals(rsHex + "01", WcTronTransactionParser.transactionSignatureHex(rs + byteArrayOf(28)))
        assertEquals("0x" + rsHex + "1c", WcTronTransactionParser.messageSignatureHex(rs + byteArrayOf(28)))

        assertThrows(IllegalArgumentException::class.java) { WcTronTransactionParser.transactionSignatureHex(rs) }
        assertThrows(IllegalArgumentException::class.java) {
            WcTronTransactionParser.transactionSignatureHex(rs + byteArrayOf(0))
        }
    }

    // endregion

    private companion object {
        const val OWNER = "TKZRPqoV7WLFvjhT4cEyBLv27Rvv1RNWGj"
        const val RECIPIENT = "TCaucrAr4itZrmpjK1Pg4CVS7bym2QiFG4"
        const val USDT_CONTRACT = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"

        /** TransferContract owner → recipient, 1.5 TRX, memo "hi". */
        const val TRANSFER_RAW =
            "0a027803220816138f9255a1db9140a0ad95ae8e33520268695a67080112630a2d747970652e676f" +
            "6f676c65617069732e636f6d2f70726f746f636f6c2e5472616e73666572436f6e74726163741232" +
            "0a154169319ea845b1c35a1f7b0e1429f4f303e8f791331215411cb0b7348eded93b8d0816bbeb81" +
            "9fc1d7a51f3118e0c65b7090de91ae8e33"
        const val TRANSFER_TX_ID = "c29f0e70467cf63193df2a66fbd26b618e4227fcd9805d4dbf77bef3bb255b7c"

        /** TriggerSmartContract owner → USDT `approve(recipient, 0)`, fee_limit 200 TRX. */
        const val CALL_RAW =
            "0a027803220816138f9255a1db9140a0ad95ae8e335aae01081f12a9010a31747970652e676f6f67" +
            "6c65617069732e636f6d2f70726f746f636f6c2e54726967676572536d617274436f6e7472616374" +
            "12740a154169319ea845b1c35a1f7b0e1429f4f303e8f79133121541a614f803b6fd780986a42c78" +
            "ec9c7f77e6ded13c2244095ea7b30000000000000000000000001cb0b7348eded93b8d0816bbeb81" +
            "9fc1d7a51f3100000000000000000000000000000000000000000000000000000000000000007090" +
            "de91ae8e3390018084af5f"
        const val CALL_TX_ID = "96508271130eb1790770b5a5661f5604b96db5a1e9c456eb1e58856e1724e0e3"
        const val CALL_DATA =
            "095ea7b30000000000000000000000001cb0b7348eded93b8d0816bbeb819fc1d7a51f3100000000" +
            "00000000000000000000000000000000000000000000000000000000"

        /** TransferContract whose owner is `RECIPIENT`, not the connected account. */
        const val FOREIGN_OWNER_RAW =
            "0a027803220816138f9255a1db9140a0ad95ae8e335a65080112610a2d747970652e676f6f676c65" +
            "617069732e636f6d2f70726f746f636f6c2e5472616e73666572436f6e747261637412300a15411c" +
            "b0b7348eded93b8d0816bbeb819fc1d7a51f3112154169319ea845b1c35a1f7b0e1429f4f303e8f7" +
            "913318017090de91ae8e33"

        /** FreezeBalanceV2Contract — not a transfer or a call. */
        const val FREEZE_RAW =
            "0a027803220816138f9255a1db9140a0ad95ae8e335a530836124f0a34747970652e676f6f676c65" +
            "617069732e636f6d2f70726f746f636f6c2e467265657a6542616c616e63655632436f6e74726163" +
            "7412170a154169319ea845b1c35a1f7b0e1429f4f303e8f791337090de91ae8e33"
    }
}
