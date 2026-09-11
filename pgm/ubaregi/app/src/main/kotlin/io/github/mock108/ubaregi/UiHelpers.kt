package io.github.mock108.ubaregi

import io.github.mock108.ubaregi.data.MAX_AMOUNT_YEN

internal fun normalizeDigits(value: String): String = value.trim().map { character ->
    if (character in '０'..'９') {
        ('0'.code + (character.code - '０'.code)).toChar()
    } else {
        character
    }
}.joinToString("")

internal fun parseMoneyInput(
    value: String,
    fieldName: String,
    allowZero: Boolean,
): Long? {
    val normalized = normalizeDigits(value)
    val parsed = normalized.takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }
        ?.toLongOrNull()
        ?: return null
    val minimum = if (allowZero) 0 else 1
    return parsed.takeIf { it in minimum..MAX_AMOUNT_YEN }
}

internal fun formatYen(value: Long?): String = value?.let { "${"%,d".format(it)}円" } ?: "—"

internal fun formatDateTime(value: Long?): String = value?.let {
    java.time.Instant.ofEpochMilli(it)
        .atZone(java.time.ZoneId.of("Asia/Tokyo"))
        .toLocalDateTime()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))
} ?: "—"
