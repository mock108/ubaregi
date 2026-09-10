package io.github.mock108.ubaregi

private const val MAX_AMOUNT_YEN = 9_999_999L

sealed interface ChangeResult {
    data object Empty : ChangeResult

    data class Invalid(val message: String) : ChangeResult

    data class Shortage(val amount: Long) : ChangeResult

    data class Success(val change: Long) : ChangeResult
}

object ChangeCalculator {
    fun calculate(productText: String, receivedText: String): ChangeResult {
        if (productText.isBlank() || receivedText.isBlank()) {
            return ChangeResult.Empty
        }

        val productAmount = parseYen(productText)
            ?: return ChangeResult.Invalid("商品金額は1〜9,999,999円で入力してください")
        val receivedAmount = parseYen(receivedText)
            ?: return ChangeResult.Invalid("受取金額は0〜9,999,999円で入力してください")

        if (productAmount == 0L) {
            return ChangeResult.Invalid("商品金額は1円以上で入力してください")
        }

        return if (receivedAmount < productAmount) {
            ChangeResult.Shortage(productAmount - receivedAmount)
        } else {
            ChangeResult.Success(receivedAmount - productAmount)
        }
    }

    private fun parseYen(value: String): Long? {
        val normalized = value.trim().map { character ->
            if (character in '０'..'９') {
                ('0'.code + (character.code - '０'.code)).toChar()
            } else {
                character
            }
        }.joinToString("")

        if (normalized.isEmpty() || normalized.any { it !in '0'..'9' }) {
            return null
        }

        return normalized.toLongOrNull()?.takeIf { it <= MAX_AMOUNT_YEN }
    }
}
