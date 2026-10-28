package org.wierichs.dogtracker.communication

fun interface MessageTransport {
    fun send(message: CommunicationMessage): Int
}