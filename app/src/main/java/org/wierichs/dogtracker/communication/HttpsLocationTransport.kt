package org.wierichs.dogtracker.communication

import java.net.URL
import javax.net.ssl.HttpsURLConnection

class HttpsLocationTransport(private val bearerToken: String) : MessageTransport {
    override fun send(message: CommunicationMessage): Int {
        val connection = URL(message.destination).openConnection() as? HttpsURLConnection
            ?: throw IllegalArgumentException("Only HTTPS endpoints are supported")

        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            connection.setFixedLengthStreamingMode(message.payload.size)
            connection.outputStream.use { it.write(message.payload) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }
}