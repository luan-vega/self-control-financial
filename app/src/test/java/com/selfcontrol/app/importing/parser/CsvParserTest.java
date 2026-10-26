package com.selfcontrol.app.importing.parser;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static com.selfcontrol.domain.transaction.TransactionType.EXPENSE;
import static com.selfcontrol.domain.transaction.TransactionType.INCOME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

class CsvParserTest {

    private static List<StatementLine> parse(String csv) {
        return CsvParser.parse(csv).lines();
    }

    @Test
    void basicFormatWithoutHeader() {
        List<StatementLine> lines = parse("""
                10/10/2026,Supermercado,643.90,Mercado
                2026-10-12,Uber,32.80,Transporte
                """);

        assertThat(lines).extracting(StatementLine::lineNo, StatementLine::date, StatementLine::description,
                        StatementLine::amount, StatementLine::type, StatementLine::category)
                .containsExactly(
                        tuple(1, LocalDate.of(2026, 10, 10), "Supermercado", new BigDecimal("643.90"), null, "Mercado"),
                        tuple(2, LocalDate.of(2026, 10, 12), "Uber", new BigDecimal("32.80"), null, "Transporte"));
    }

    @Test
    void semicolonWithPortugueseHeaderAndCommaDecimals() {
        List<StatementLine> lines = parse("""
                Data;Descrição;Valor (R$);Categoria;Tipo
                05/10/2026;Salário;8.500,00;Salário;Receita
                06/10/2026;"Aluguel; outubro";2.200,00;Moradia;despesa
                """);

        assertThat(lines).extracting(StatementLine::lineNo, StatementLine::description, StatementLine::amount, StatementLine::type)
                .containsExactly(
                        tuple(2, "Salário", new BigDecimal("8500.00"), INCOME),
                        tuple(3, "Aluguel; outubro", new BigDecimal("2200.00"), EXPENSE));
    }

    @Test
    void headerInAnyOrderLikeNubankAccountExport() {
        List<StatementLine> lines = parse("""
                Data,Valor,Identificador,Descrição
                01/10/2026,-45.90,abc-1,Transferência enviada pelo Pix - Fulano
                02/10/2026,1200.00,abc-2,Transferência recebida
                """);

        assertThat(lines).extracting(StatementLine::amount, StatementLine::type, StatementLine::externalId)
                .containsExactly(
                        tuple(new BigDecimal("45.90"), EXPENSE, "abc-1"),
                        tuple(new BigDecimal("1200.00"), INCOME, "abc-2"));
    }

    @Test
    void positiveOnlySpreadsheetLeavesTypeOpen() {
        List<StatementLine> lines = parse("""
                date,title,amount
                2026-10-03,iFood,89.90
                2026-10-04,Netflix,55.90
                """);

        assertThat(lines).extracting(StatementLine::type).containsOnlyNulls();
    }

    @Test
    void tabSeparatedQuotesWithEscapesAndMultilineCells() {
        List<StatementLine> lines = parse("data\tdescricao\tvalor\r\n"
                + "10.10.2026\t\"Loja \"\"Top\"\"\nfilial 2\"\t(10,00)\r\n"
                + "\r\n"
                + "11/10/26\tTarifa\t5,00 D\r\n");

        assertThat(lines).extracting(StatementLine::lineNo, StatementLine::date, StatementLine::description,
                        StatementLine::amount, StatementLine::type)
                .containsExactly(
                        tuple(2, LocalDate.of(2026, 10, 10), "Loja \"Top\" filial 2", new BigDecimal("10.00"), EXPENSE),
                        tuple(5, LocalDate.of(2026, 10, 11), "Tarifa", new BigDecimal("5.00"), EXPENSE));
    }

    @Test
    void lineErrorsKeepTheRestOfTheFile() {
        List<StatementLine> lines = parse("""
                31/02/2026,Data impossível,10.00
                10/10/2026,Valor ruim,abc
                10/10/2026,Zero,0
                10/10/2026
                10/10/2026,Tipo ruim,10,Mercado,talvez
                10/10/2026,Ok,10
                """);

        assertThat(lines).extracting(StatementLine::error)
                .satisfiesExactly(
                        e -> assertThat(e).contains("Data inválida"),
                        e -> assertThat(e).contains("Valor inválido"),
                        e -> assertThat(e).contains("diferente de zero"),
                        e -> assertThat(e).contains("Faltam colunas"),
                        e -> assertThat(e).contains("Tipo inválido"),
                        e -> assertThat(e).isNull());
    }

    @Test
    void windows1252AndBomAreDecoded() {
        byte[] latin = "10/10/2026;Padaria São João;12,50\n".getBytes(Charset.forName("windows-1252"));
        byte[] bom = ("﻿" + "10/10/2026;Café;7,00\n").getBytes(StandardCharsets.UTF_8);

        assertThat(parse(TextDecoding.decode(latin)).getFirst().description()).isEqualTo("Padaria São João");
        assertThat(parse(TextDecoding.decode(bom)).getFirst().date()).isEqualTo(LocalDate.of(2026, 10, 10));
    }

    @Test
    void longDescriptionsAreTruncated() {
        String desc = "x".repeat(300);
        assertThat(parse("10/10/2026," + desc + ",1").getFirst().description()).hasSize(200);
    }

    @Test
    void headerWithoutRequiredColumnsOrEmptyFileIsRejected() {
        assertThatThrownBy(() -> CsvParser.parse("descricao,categoria\nMercado,Mercado"))
                .isInstanceOf(StatementFormatException.class).hasMessageContaining("data e valor");
        assertThatThrownBy(() -> CsvParser.parse("\n\n"))
                .isInstanceOf(StatementFormatException.class).hasMessageContaining("vazio");
    }

    @Test
    void detectsCsvByNameOrContent() {
        assertThat(StatementFormat.detect("x.CSV", "")).isEqualTo(StatementFormat.CSV);
        assertThat(StatementFormat.detect("x.txt", "")).isEqualTo(StatementFormat.CSV);
        assertThat(StatementFormat.detect(null, "10/10/2026,a,1")).isEqualTo(StatementFormat.CSV);
        assertThat(StatementFormat.detect("x.qfx", "")).isEqualTo(StatementFormat.OFX);
    }
}
