package org.wierichs.dogtracker.communication

data class MessageTransportResponse(
    val statusCode: Int,
    val retryAfter: String?
)

fun interface MessageTransport {
    fun send(message: CommunicationMessage): MessageTransportResponse
}