package com.selfcontrol.app.importing.parser;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.selfcontrol.domain.transaction.TransactionType.EXPENSE;
import static com.selfcontrol.domain.transaction.TransactionType.INCOME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfxParserTest {

    static byte[] resource(String name) throws IOException {
        try (InputStream in = OfxParserTest.class.getResourceAsStream("/imports/" + name)) {
            return in.readAllBytes();
        }
    }

    @Test
    void readsBrazilianSgmlOfxInWindows1252() throws IOException {
        String content = TextDecoding.decode(resource("itau-102-cp1252.ofx"));

        assertThat(StatementFormat.detect("extrato.ofx", content)).isEqualTo(StatementFormat.OFX);
        Statement statement = StatementFormat.OFX.parse(content);
        List<StatementLine> lines = statement.lines();

        assertThat(statement.currency()).isEqualTo("BRL");
        assertThat(lines).hasSize(7);
        assertThat(lines.get(0)).extracting(StatementLine::date, StatementLine::amount, StatementLine::type,
                        StatementLine::description, StatementLine::externalId)
                .containsExactly(LocalDate.of(2026, 10, 5), new BigDecimal("8500.00"), INCOME, "SALARIO EMPRESA X", "20261005001");
        assertThat(lines.get(1)).extracting(StatementLine::amount, StatementLine::type, StatementLine::description)
                .containsExactly(new BigDecimal("2200.00"), EXPENSE, "PIX ENVIADO - José Aluguel");
        assertThat(lines.get(2).description()).isEqualTo("PÃO DE AÇUCAR & CIA");
        assertThat(lines.get(5).valid()).isFalse();
        assertThat(lines.get(5).error()).contains("Data inválida");
        assertThat(lines.get(6).error()).contains("diferente de zero");
    }

    @Test
    void readsXmlOfx2() throws IOException {
        String content = TextDecoding.decode(resource("bank-211.ofx"));

        Statement statement = StatementFormat.detect("download", content).parse(content);

        assertThat(statement.currency()).isEqualTo("USD");
        assertThat(statement.lines()).extracting(StatementLine::date, StatementLine::amount, StatementLine::type, StatementLine::description)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 10, 12), new BigDecimal("12.34"), EXPENSE, "Coffee Shop"),
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 10, 15), new BigDecimal("1000"), INCOME, "Refund – Açaí"));
    }

    @Test
    void rejectsFilesThatAreNotOfxOrHaveNoTransactions() {
        assertThatThrownBy(() -> OfxParser.parse("data;valor"))
                .isInstanceOf(StatementFormatException.class).hasMessageContaining("<OFX>");
        assertThatThrownBy(() -> OfxParser.parse("<OFX><BANKTRANLIST></BANKTRANLIST></OFX>"))
                .isInstanceOf(StatementFormatException.class).hasMessageContaining("nenhuma transação");
    }

    @Test
    void invalidAmountIsALineError() {
        Statement s = OfxParser.parse("<OFX><STMTTRN><DTPOSTED>20261001<TRNAMT>abc<NAME>X</STMTTRN></OFX>");

        assertThat(s.currency()).isNull();
        assertThat(s.lines().getFirst().error()).contains("Valor inválido");
    }
}
