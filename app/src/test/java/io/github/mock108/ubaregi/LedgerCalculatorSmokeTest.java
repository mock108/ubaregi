package io.github.mock108.ubaregi;

import java.util.Collections;
import org.junit.Assert;
import org.junit.Test;

public class LedgerCalculatorSmokeTest {
    @Test
    public void paymentSummaryMatchesStageTwoExample() {
        RegisterSessionEntity session = new RegisterSessionEntity(
                "s1", 1L, "OPEN", 1, 1L, null, 10_000L,
                null, null, 1L, 1L, 1L, null);
        CashEntryEntity payment = new CashEntryEntity(
                "p1", "s1", 1L, "PAYMENT", 1L,
                1_500L, 2_000L, null, false, 1L, 1L, 1L);

        RegisterSummary summary = LedgerCalculator.INSTANCE.summary(session, Collections.singletonList(payment));

        Assert.assertEquals(1_500L, summary.getPaymentCollectedYen());
        Assert.assertEquals(2_000L, summary.getCashInYen());
        Assert.assertEquals(500L, summary.getCashOutYen());
        Assert.assertEquals(11_500L, summary.getExpectedCashYen());
    }
}
