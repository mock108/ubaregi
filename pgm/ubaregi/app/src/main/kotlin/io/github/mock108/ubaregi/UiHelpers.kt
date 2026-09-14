package io.github.mock108.ubaregi

import io.github.mock108.ubaregi.data.MAX_AMOUNT_YEN
import io.github.mock108.ubaregi.data.CashEntryKind
import io.github.mock108.ubaregi.data.RegisterStatus

internal const val STARTING_CHANGE_LABEL = "開始時の釣銭"
internal const val COUNTED_CASH_LABEL = "実際に数えた手元現金"
internal const val NEXT_CHANGE_LABEL = "次回に残す釣銭"
internal const val EXPECTED_CASH_LABEL = "計算上の手元現金"
internal const val DIFFERENCE_LABEL = "計算との差額"

internal fun RegisterStatus.displayLabel(): String = when (this) {
    RegisterStatus.OPEN -> "稼働中"
    RegisterStatus.CLOSED -> "終了済み"
}

internal fun CashEntryKind.displayLabel(): String = when (this) {
    CashEntryKind.PAYMENT -> "受け渡し"
    CashEntryKind.CASH_IN -> "釣銭補充"
    CashEntryKind.CASH_OUT -> "現金取出し"
}

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
