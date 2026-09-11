package io.github.mock108.ubaregi

import org.junit.Assert.assertEquals
import org.junit.Test

class ChangeCalculatorTest {
    @Test
    fun calculatesChange() {
        assertEquals(
            ChangeResult.Success(change = 500),
            ChangeCalculator.calculate(productText = "1500", receivedText = "2000"),
        )
    }

    @Test
    fun reportsShortage() {
        assertEquals(
            ChangeResult.Shortage(amount = 500),
            ChangeCalculator.calculate(productText = "1500", receivedText = "1000"),
        )
    }

    @Test
    fun acceptsFullWidthDigits() {
        assertEquals(
            ChangeResult.Success(change = 500),
            ChangeCalculator.calculate(productText = "１５００", receivedText = "２０００"),
        )
    }

    @Test
    fun rejectsZeroProductAmount() {
        assertEquals(
            ChangeResult.Invalid("商品金額は1円以上で入力してください"),
            ChangeCalculator.calculate(productText = "0", receivedText = "500"),
        )
    }
}
