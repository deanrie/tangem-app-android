package com.tangem.domain.tonconnect.session

import com.google.common.truth.Truth.assertThat
import com.tangem.domain.tonconnect.model.TonConnectClientId
import com.tangem.domain.tonconnect.model.TonConnectException
import com.tangem.domain.tonconnect.model.TonConnectManifest
import com.tangem.domain.tonconnect.model.TonConnectNetworkId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class TonConnectSessionTest {

    private val session = TonConnectSession(
        id = "session-1",
        dAppClientId = TonConnectClientId.fromHex("aa".repeat(32)),
        walletClientId = TonConnectClientId.fromHex("bb".repeat(32)),
        bridgeUrl = "https://connect.ton.org/bridge",
        manifest = TonConnectManifest("https://app.example.com", "Example", "https://app.example.com/i.png"),
        appDomain = "app.example.com",
        account = TonConnectSession.Account("0:" + "00".repeat(32), TonConnectNetworkId.MAINNET, "cc".repeat(32)),
        createdAtEpochSeconds = 1_700_000_000,
    )

    @Test
    fun `GIVEN first request id WHEN accepting THEN it becomes the baseline and later ids must strictly increase`() {
        // Act
        val afterFirst = session.acceptingRequest("1700000000500")
        val afterSecond = afterFirst.acceptingRequest("1700000000501")

        // Assert
        assertThat(afterFirst.lastRequestId).isEqualTo("1700000000500")
        assertThat(afterSecond.lastRequestId).isEqualTo("1700000000501")
        assertThrows<TonConnectException.RequestIdNotIncreasing> { afterSecond.acceptingRequest("1700000000501") }
        assertThat(assertThrows<TonConnectException.RequestIdNotIncreasing> { afterSecond.acceptingRequest("5") }.protocolMessage)
            .isEqualTo("Request id 5 is not greater than the last processed id 1700000000501")
        // Compared numerically, not lexicographically.
        assertThat(afterSecond.acceptingRequest("10000000000000").lastRequestId).isEqualTo("10000000000000")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "abc", "-1", "1.0", "0x10", "+1", "99999999999999999999999999999999999999999999999999999999999999999"])
    fun `GIVEN non-integer request id WHEN accepting THEN BadRequest is thrown`(id: String) {
        assertThrows<TonConnectException.BadRequest> { session.acceptingRequest(id) }
    }

    @Test
    fun `GIVEN fresh session WHEN allocating event ids THEN they increase from zero`() {
        val (first, afterFirst) = session.allocatingEventId()
        val (second, afterSecond) = afterFirst.allocatingEventId()

        assertThat(first).isEqualTo(0)
        assertThat(second).isEqualTo(1)
        assertThat(afterSecond.nextEventId).isEqualTo(2)
    }
}
