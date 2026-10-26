package com.selfcontrol.domain.importing;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.selfcontrol.domain.transaction.TransactionType.EXPENSE;
import static com.selfcontrol.domain.transaction.TransactionType.INCOME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class DuplicateDetectorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);

    @Test
    void matchesByDateAmountAndNormalizedDescription() {
        var detector = new DuplicateDetector(List.of(new Fingerprint(DAY, EXPENSE, 1250, "BRL", "Padaria São João")));

        assertThat(detector.isDuplicate(new Fingerprint(DAY, EXPENSE, 1250, "BRL", "  PADARIA   sao joao "))).isTrue();
    }

    @Test
    void differentDateAmountTypeCurrencyOrDescriptionIsNew() {
        var existing = new Fingerprint(DAY, EXPENSE, 1250, "BRL", "Padaria");
        List<Fingerprint> candidates = List.of(
                new Fingerprint(DAY.plusDays(1), EXPENSE, 1250, "BRL", "Padaria"),
                new Fingerprint(DAY, EXPENSE, 1251, "BRL", "Padaria"),
                new Fingerprint(DAY, INCOME, 1250, "BRL", "Padaria"),
                new Fingerprint(DAY, EXPENSE, 1250, "USD", "Padaria"),
                new Fingerprint(DAY, EXPENSE, 1250, "BRL", "Padaria 2"));

        for (Fingerprint candidate : candidates) {
            assertThat(new DuplicateDetector(List.of(existing)).isDuplicate(candidate)).as(candidate.toString()).isFalse();
        }
    }

    @Test
    void countsOccurrencesSoRepeatedPurchasesAreKept() {
        Fingerprint coffee = new Fingerprint(DAY, EXPENSE, 700, "BRL", "Café");
        var detector = new DuplicateDetector(List.of(coffee));

        assertThat(detector.classify(List.of(coffee, coffee, coffee))).containsExactly(true, false, false);
    }

    @Test
    void twoExistingCopiesAbsorbTwoImportedOnes() {
        Fingerprint coffee = new Fingerprint(DAY, EXPENSE, 700, "BRL", "Café");
        var detector = new DuplicateDetector(List.of(coffee, coffee));

        assertThat(detector.classify(List.of(coffee, coffee, coffee))).containsExactly(true, true, false);
    }

    @Test
    void nullDescriptionIsEmptyAndRequiredFieldsAreChecked() {
        assertThat(new Fingerprint(DAY, EXPENSE, 1, "BRL ", null))
                .isEqualTo(new Fingerprint(DAY, EXPENSE, 1, "BRL", "  "));
        assertThatNullPointerException().isThrownBy(() -> new Fingerprint(null, EXPENSE, 1, "BRL", "x"));
        assertThat(Descriptions.key(null)).isEmpty();
    }
}
