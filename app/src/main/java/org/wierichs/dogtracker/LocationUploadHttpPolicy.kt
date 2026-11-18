package org.wierichs.dogtracker

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

internal const val MIN_SERVER_RETRY_DELAY_MILLIS = 30_000L
internal const val MAX_SERVER_RETRY_DELAY_MILLIS = 24 * 60 * 60 * 1000L

internal enum class LocationUploadDisposition {
    ACCEPTED,
    INVALID_CREDENTIALS,
    HUNTING_MODE_DISABLED,
    RETRY,
    PERMANENT_REJECTION
}

internal fun locationUploadDisposition(statusCode: Int): LocationUploadDisposition = when {
    statusCode in 200..299 -> LocationUploadDisposition.ACCEPTED
    statusCode == 401 -> LocationUploadDisposition.INVALID_CREDENTIALS
    statusCode == 409 -> LocationUploadDisposition.HUNTING_MODE_DISABLED
    statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode !in 400..499 ->
        LocationUploadDisposition.RETRY
    else -> LocationUploadDisposition.PERMANENT_REJECTION
}

internal fun retryAfterDelayMillis(
    headerValue: String?,
    nowEpochMillis: Long = System.currentTimeMillis()
): Long? {
    val value = headerValue?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val delayMillis = if (value.all { it in '0'..'9' }) {
        val seconds = value.toLongOrNull() ?: return null
        TimeUnit.SECONDS.toMillis(seconds)
    } else {
        val retryAt = try {
            Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value)).toEpochMilli()
        } catch (_: DateTimeParseException) {
            return null
        } catch (_: ArithmeticException) {
            return null
        }
        try {
            Math.subtractExact(retryAt, nowEpochMillis).coerceAtLeast(0)
        } catch (_: ArithmeticException) {
            return null
        }
    }

    return delayMillis.coerceIn(MIN_SERVER_RETRY_DELAY_MILLIS, MAX_SERVER_RETRY_DELAY_MILLIS)
}
