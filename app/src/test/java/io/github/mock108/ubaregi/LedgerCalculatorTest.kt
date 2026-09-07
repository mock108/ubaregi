package io.github.mock108.ubaregi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerCalculatorTest {
    @Test
    fun paymentUsesProductForCollectionAndReceivedMinusProductForChange() {
        val session = openSession(10_000)
        val payment = payment("p1", 1_500, 2_000)

        val summary = LedgerCalculator.summary(session, listOf(payment))

        assertEquals(1_500, summary.paymentCollectedYen)
        assertEquals(2_000, summary.cashInYen)
        assertEquals(500, summary.cashOutYen)
        assertEquals(1_500, summary.netCashYen)
        assertEquals(11_500, summary.expectedCashYen)
    }

    @Test
    fun cashInAndCashOutDoNotChangePaymentCollection() {
        val session = openSession(10_000)
        val entries = listOf(
            payment("p1", 1_500, 2_000),
            movement("in", KIND_CASH_IN, 2_000),
            movement("out", KIND_CASH_OUT, 1_000),
        )

        val summary = LedgerCalculator.summary(session, entries)

        assertEquals(1_500, summary.paymentCollectedYen)
        assertEquals(12_500, summary.expectedCashYen)
    }

    @Test
    fun voidedEntryRemainsInHistoryButIsExcludedFromSummary() {
        val session = openSession(10_000)
        val voided = payment("p1", 1_500, 2_000).copy(isVoided = true)

        val summary = LedgerCalculator.summary(session, listOf(voided))

        assertEquals(0, summary.paymentCollectedYen)
        assertEquals(10_000, summary.expectedCashYen)
        assertTrue(voided.isVoided)
    }

    @Test
    fun fullWidthDigitsAreNormalizedAndInvalidMoneyIsRejected() {
        assertEquals(10_000, (MoneyInput.parse(" １００００ ", "初期釣銭", true) as MoneyParseResult.Valid).value)
        assertTrue(MoneyInput.parse("1,000", "商品金額", false) is MoneyParseResult.Invalid)
        assertTrue(MoneyInput.parse("0", "商品金額", false) is MoneyParseResult.Invalid)
        assertTrue(MoneyInput.parse("10000000", "初期釣銭", true) is MoneyParseResult.Invalid)
    }

    private fun openSession(float: Long) = RegisterSessionEntity(
        id = "s1", sequence = 1, status = STATUS_OPEN, openSlot = 1,
        openedAt = 1, closedAt = null, openingFloatYen = float,
        actualCashYen = null, nextFloatYen = null, createdAt = 1,
        updatedAt = 1, revision = 1, closeRevision = null,
    )

    private fun payment(id: String, product: Long, received: Long) = CashEntryEntity(
        id = id, sessionId = "s1", sequence = 1, kind = KIND_PAYMENT,
        occurredAt = 1, productAmountYen = product, receivedAmountYen = received,
        amountYen = null, createdAt = 1, updatedAt = 1,
    )

    private fun movement(id: String, kind: String, amount: Long) = CashEntryEntity(
        id = id, sessionId = "s1", sequence = 1, kind = kind,
        occurredAt = 1, productAmountYen = null, receivedAmountYen = null,
        amountYen = amount, createdAt = 1, updatedAt = 1,
    )
}
