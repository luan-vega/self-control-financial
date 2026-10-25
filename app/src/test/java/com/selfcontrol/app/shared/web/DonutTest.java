package com.selfcontrol.app.shared.web;

import com.selfcontrol.domain.money.Money;
import com.selfcontrol.domain.report.CategoryShare;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DonutTest {

    @Test
    void emptyWithoutData() {
        assertThat(Donut.of(List.of()).isEmpty()).isTrue();
    }

    @Test
    void slicesFollowEachOtherAroundTheCircle() {
        Donut donut = Donut.of(List.of(share("Moradia", 7_500, 75), share("Mercado", 2_500, 25)));

        assertThat(donut.total()).isEqualTo(Money.of(10_000, "BRL"));
        assertThat(donut.slices()).extracting(Donut.Slice::dashArray).containsExactly("75.000 25.000", "25.000 75.000");
        assertThat(donut.slices()).extracting(Donut.Slice::dashOffset).containsExactly("25.000", "-50.000");
    }

    @Test
    void groupsTheTailIntoOthers() {
        List<CategoryShare> shares = IntStream.rangeClosed(1, 7)
                .mapToObj(i -> share("C" + i, 100, 14))
                .toList();

        Donut donut = Donut.of(shares);

        assertThat(donut.slices()).hasSize(Donut.MAX_SLICES);
        Donut.Slice others = donut.slices().getLast();
        assertThat(others.name()).isEqualTo("Outros");
        assertThat(others.total()).isEqualTo(Money.of(300, "BRL"));
        assertThat(others.color()).isEqualTo(Donut.OTHERS_COLOR);
    }

    private static CategoryShare share(String name, long cents, int percent) {
        return new CategoryShare(null, name, "#10b981", Money.of(cents, "BRL"), percent);
    }
}
