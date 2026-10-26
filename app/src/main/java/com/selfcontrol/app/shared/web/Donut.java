package com.selfcontrol.app.shared.web;

import com.selfcontrol.domain.money.Money;
import com.selfcontrol.domain.report.CategoryShare;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Gráfico de rosca em SVG puro, calculado no servidor (sem biblioteca de gráficos).
 * Usa um círculo de circunferência 100 (r = 15.9155), então dasharray e offset são porcentagens.
 * As maiores categorias aparecem sozinhas e o resto vira "Outros".
 */
public record Donut(List<Slice> slices, Money total) {

    public static final int MAX_SLICES = 5;
    static final String OTHERS_COLOR = "#94a3b8";

    public record Slice(String name, String color, Money total, int percent, String dashArray, String dashOffset) {
    }

    public static Donut of(List<CategoryShare> shares) {
        if (shares.isEmpty()) {
            return new Donut(List.of(), null);
        }
        Money total = shares.stream().map(CategoryShare::total).reduce(Money::plus).orElseThrow();
        List<CategoryShare> visible = new ArrayList<>(shares.subList(0, Math.min(MAX_SLICES, shares.size())));
        if (shares.size() > MAX_SLICES) {
            visible.removeLast();
            List<CategoryShare> rest = shares.subList(MAX_SLICES - 1, shares.size());
            Money restTotal = rest.stream().map(CategoryShare::total).reduce(Money::plus).orElseThrow();
            visible.add(new CategoryShare(null, "Outros", OTHERS_COLOR, restTotal,
                    rest.stream().mapToInt(CategoryShare::percent).sum()));
        }

        List<Slice> slices = new ArrayList<>();
        double start = 0;
        for (CategoryShare share : visible) {
            double length = share.total().minorUnits() * 100.0 / total.minorUnits();
            // O traço começa às 12h: offset 25 gira o início do círculo, que no SVG fica às 3h.
            slices.add(new Slice(share.name(), share.color(), share.total(), share.percent(),
                    format(length) + " " + format(100 - length), format(25 - start)));
            start += length;
        }
        return new Donut(slices, total);
    }

    public boolean isEmpty() {
        return slices.isEmpty();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
