package io.github.mock108.ubaregi.domain

import io.github.mock108.ubaregi.data.CashEntry
import io.github.mock108.ubaregi.data.CashEntryKind
import io.github.mock108.ubaregi.data.RegisterSession
import io.github.mock108.ubaregi.data.RegisterStatus

data class EntryAmounts(
    val changeYen: Long?,
    val cashInYen: Long,
    val cashOutYen: Long,
    val netCashYen: Long,
)

data class RegisterSummary(
    val paymentCollectedYen: Long,
    val cashInYen: Long,
    val cashOutYen: Long,
    val netCashYen: Long,
    val expectedCashYen: Long,
    val differenceYen: Long?,
    val actualChangeYen: Long?,
    val withdrawalYen: Long?,
)

/** Pure conversion of one saved entry into its calculated cash movement. */
fun CashEntry.calculateAmounts(): EntryAmounts = when (kind) {
    CashEntryKind.PAYMENT -> {
        val product = requireNotNull(productAmountYen) { "PAYMENT.productAmountYen is required" }
        val received = requireNotNull(receivedAmountYen) { "PAYMENT.receivedAmountYen is required" }
        val change = Math.subtractExact(received, product)
        EntryAmounts(
            changeYen = change,
            cashInYen = received,
            cashOutYen = change,
            netCashYen = product,
        )
    }

    CashEntryKind.CASH_IN -> {
        val amount = requireNotNull(amountYen) { "CASH_IN.amountYen is required" }
        EntryAmounts(changeYen = null, cashInYen = amount, cashOutYen = 0, netCashYen = amount)
    }

    CashEntryKind.CASH_OUT -> {
        val amount = requireNotNull(amountYen) { "CASH_OUT.amountYen is required" }
        EntryAmounts(
            changeYen = null,
            cashInYen = 0,
            cashOutYen = amount,
            netCashYen = Math.negateExact(amount),
        )
    }
}

/**
 * Calculates a register from persisted input values only. It does not read a database and
 * deliberately does not add the closing withdrawal as another CASH_OUT entry.
 */
fun calculateRegisterSummary(session: RegisterSession, entries: Iterable<CashEntry>): RegisterSummary {
    var paymentCollected = 0L
    var cashIn = 0L
    var cashOut = 0L

    entries.filterNot { it.isVoided }.forEach { entry ->
        val amounts = entry.calculateAmounts()
        if (entry.kind == CashEntryKind.PAYMENT) {
            paymentCollected = Math.addExact(
                paymentCollected,
                requireNotNull(entry.productAmountYen),
            )
        }
        cashIn = Math.addExact(cashIn, amounts.cashInYen)
        cashOut = Math.addExact(cashOut, amounts.cashOutYen)
    }

    val netCash = Math.subtractExact(cashIn, cashOut)
    val expectedCash = Math.addExact(session.openingFloatYen, netCash)

    if (session.status == RegisterStatus.OPEN) {
        check(session.actualCashYen == null && session.nextFloatYen == null) {
            "OPEN register must not have closing values"
        }
        return RegisterSummary(
            paymentCollectedYen = paymentCollected,
            cashInYen = cashIn,
            cashOutYen = cashOut,
            netCashYen = netCash,
            expectedCashYen = expectedCash,
            differenceYen = null,
            actualChangeYen = null,
            withdrawalYen = null,
        )
    }

    val actualCash = requireNotNull(session.actualCashYen) { "CLOSED.actualCashYen is required" }
    val nextFloat = requireNotNull(session.nextFloatYen) { "CLOSED.nextFloatYen is required" }
    return RegisterSummary(
        paymentCollectedYen = paymentCollected,
        cashInYen = cashIn,
        cashOutYen = cashOut,
        netCashYen = netCash,
        expectedCashYen = expectedCash,
        differenceYen = Math.subtractExact(actualCash, expectedCash),
        actualChangeYen = Math.subtractExact(actualCash, session.openingFloatYen),
        withdrawalYen = Math.subtractExact(actualCash, nextFloat),
    )
}
