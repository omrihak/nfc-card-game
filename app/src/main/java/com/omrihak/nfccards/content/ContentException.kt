package com.omrihak.nfccards.content

/** Why a card could not be played. Only ever logged: the toddler UI never shows error details. */
enum class ErrorKind {
    UNKNOWN_CARD,
    INVALID_URL,
    NO_INTERNET,
    METADATA_UNAVAILABLE,
    MALFORMED_METADATA,
    MISSING_MEDIA,
    DOWNLOAD_FAILED,
    UNSUPPORTED,
}

class ContentException(
    val kind: ErrorKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
