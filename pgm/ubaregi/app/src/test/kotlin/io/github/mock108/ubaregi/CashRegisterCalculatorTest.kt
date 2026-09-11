package io.github.mock108.ubaregi

import io.github.mock108.ubaregi.data.CashEntry
import io.github.mock108.ubaregi.data.CashEntryKind
import io.github.mock108.ubaregi.data.RegisterSession
import io.github.mock108.ubaregi.data.RegisterStatus
import io.github.mock108.ubaregi.domain.calculateAmounts
import io.github.mock108.ubaregi.domain.calculateRegisterSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class CashRegisterCalculatorTest {
    @Test
    fun paymentUsesReceivedForInAndChangeForOut() {
        val amounts = payment(product = 1_500, received = 2_000).calculateAmounts()

        assertEquals(500L, amounts.changeYen)
        assertEquals(2_000L, amounts.cashInYen)
        assertEquals(500L, amounts.cashOutYen)
        assertEquals(1_500L, amounts.netCashYen)
    }

    @Test
    fun cashAdjustmentsUseTheirOwnDirection() {
        assertEquals(
            2_000L,
            cashEntry(CashEntryKind.CASH_IN, amount = 2_000).calculateAmounts().netCashYen,
        )
        assertEquals(
            -1_000L,
            cashEntry(CashEntryKind.CASH_OUT, amount = 1_000).calculateAmounts().netCashYen,
        )
    }

    @Test
    fun registerSummaryExcludesVoidedEntriesAndDoesNotAddClosingWithdrawal() {
        val session = openSession(openingFloat = 10_000)
        val summary = calculateRegisterSummary(
            session,
            listOf(
                payment(product = 1_500, received = 2_000),
                cashEntry(CashEntryKind.CASH_IN, amount = 2_000),
                cashEntry(CashEntryKind.CASH_OUT, amount = 1_000),
                payment(product = 500, received = 500).copy(isVoided = true),
            ),
        )

        assertEquals(1_500L, summary.paymentCollectedYen)
        assertEquals(4_000L, summary.cashInYen)
        assertEquals(1_500L, summary.cashOutYen)
        assertEquals(2_500L, summary.netCashYen)
        assertEquals(12_500L, summary.expectedCashYen)
        assertEquals(null, summary.withdrawalYen)
    }

    @Test
    fun closedRegisterSummaryCalculatesDifferenceActualChangeAndWithdrawal() {
        val session = openSession(openingFloat = 10_000).copy(
            status = RegisterStatus.CLOSED,
            openSlot = null,
            closedAt = 2_000,
            actualCashYen = 11_400,
            nextFloatYen = 10_000,
            closeRevision = 3,
        )
        val summary = calculateRegisterSummary(
            session,
            listOf(payment(product = 1_500, received = 2_000)),
        )

        assertEquals(11_500L, summary.expectedCashYen)
        assertEquals(-100L, summary.differenceYen)
        assertEquals(1_400L, summary.actualChangeYen)
        assertEquals(1_400L, summary.withdrawalYen)
    }

    private fun openSession(openingFloat: Long) = RegisterSession(
        id = "11111111-1111-4111-8111-111111111111",
        sequence = 1,
        status = RegisterStatus.OPEN,
        openSlot = 1,
        openedAt = 1_000,
        closedAt = null,
        openingFloatYen = openingFloat,
        actualCashYen = null,
        nextFloatYen = null,
        createdAt = 1_000,
        updatedAt = 1_000,
        revision = 1,
        closeRevision = null,
    )

    private fun payment(product: Long, received: Long) = CashEntry(
        id = "22222222-2222-4222-8222-222222222222",
        sessionId = "11111111-1111-4111-8111-111111111111",
        sequence = 1,
        kind = CashEntryKind.PAYMENT,
        occurredAt = 1_000,
        productAmountYen = product,
        receivedAmountYen = received,
        amountYen = null,
        createdAt = 1_000,
        updatedAt = 1_000,
    )

    private fun cashEntry(kind: CashEntryKind, amount: Long) = payment(1, 1).copy(
        id = "33333333-3333-4333-8333-333333333333",
        kind = kind,
        productAmountYen = null,
        receivedAmountYen = null,
        amountYen = amount,
    )
}
