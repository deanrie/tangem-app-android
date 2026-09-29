package com.tangem.domain.tonconnect.model

/**
 * Error codes shared by connect events and RPC responses (`spec/connect.md`, `spec/rpc.md`).
 */
enum class TonConnectErrorCode(val code: Int) {
    UNKNOWN_ERROR(0),
    BAD_REQUEST(1),
    MANIFEST_NOT_FOUND(2),
    MANIFEST_CONTENT_ERROR(3),
    UNKNOWN_APP(100),
    USER_DECLINED(300),
    METHOD_NOT_SUPPORTED(400),
}

/**
 * Wallet-side failures of the TON Connect core. Every case maps to a protocol error code and message so the
 * caller can answer the dApp without inspecting the case.
 */
sealed class TonConnectException(
    val protocolCode: TonConnectErrorCode,
    val protocolMessage: String,
) : Exception(protocolMessage) {

    class UnsupportedProtocolVersion(version: String?) : TonConnectException(
        protocolCode = TonConnectErrorCode.BAD_REQUEST,
        protocolMessage = "Unsupported TON Connect protocol version: ${version ?: "missing"}",
    )

    class InvalidClientId : TonConnectException(TonConnectErrorCode.BAD_REQUEST, "Invalid client id")

    class MalformedConnectRequest(reason: String) : TonConnectException(TonConnectErrorCode.BAD_REQUEST, reason)

    class ManifestNotFound : TonConnectException(TonConnectErrorCode.MANIFEST_NOT_FOUND, "App manifest not found")

    class ManifestContentError(reason: String) : TonConnectException(
        protocolCode = TonConnectErrorCode.MANIFEST_CONTENT_ERROR,
        protocolMessage = "App manifest content error: $reason",
    )

    class CryptoFailure : TonConnectException(TonConnectErrorCode.UNKNOWN_ERROR, "Cryptographic operation failed")

    class DecryptionFailed : TonConnectException(TonConnectErrorCode.UNKNOWN_ERROR, "Message could not be decrypted")

    class MalformedEnvelope(reason: String) : TonConnectException(TonConnectErrorCode.BAD_REQUEST, reason)

    class RequestIdNotIncreasing(received: String, last: String) : TonConnectException(
        protocolCode = TonConnectErrorCode.BAD_REQUEST,
        protocolMessage = "Request id $received is not greater than the last processed id $last",
    )

    class UnknownSession : TonConnectException(TonConnectErrorCode.UNKNOWN_APP, "Unknown app")

    class MethodNotSupported(method: String) : TonConnectException(
        protocolCode = TonConnectErrorCode.METHOD_NOT_SUPPORTED,
        protocolMessage = "Method not supported: $method",
    )

    class BadRequest(reason: String) : TonConnectException(TonConnectErrorCode.BAD_REQUEST, reason)

    class UserDeclined : TonConnectException(TonConnectErrorCode.USER_DECLINED, "User declined the request")

    class InternalFailure(reason: String) : TonConnectException(TonConnectErrorCode.UNKNOWN_ERROR, reason)
}
