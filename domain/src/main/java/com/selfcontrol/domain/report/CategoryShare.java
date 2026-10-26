package com.selfcontrol.domain.report;

import com.selfcontrol.domain.money.Money;

import java.util.UUID;

/** Fatia de uma categoria no total do seu tipo. percent vai de 0 a 100. */
public record CategoryShare(UUID categoryId, String name, String color, Money total, int percent) {
}
