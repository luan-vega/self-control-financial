package com.selfcontrol.app.importing;

import com.jayway.jsonpath.JsonPath;
import com.selfcontrol.app.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Upload → preview → confirmação → caixa de entrada, com Postgres real e RLS valendo. */
class ImportTests extends IntegrationTest {

    static byte[] resource(String name) throws IOException {
        try (InputStream in = ImportTests.class.getResourceAsStream("/imports/" + name)) {
            return in.readAllBytes();
        }
    }

    @Test
    void realOfxGoesThroughPreviewIntoTheInbox() throws Exception {
        TestUser user = newUser();
        category(user, "Moradia", "EXPENSE");

        UUID batchId = upload(user, "itau.ofx", resource("itau-102-cp1252.ofx"));

        List<ImportRow> rows = as(user, () -> imports().rows(batchId));
        assertThat(rows).hasSize(7);
        assertThat(rows).extracting(ImportRow::status)
                .containsExactly(RowStatus.NEW, RowStatus.NEW, RowStatus.NEW, RowStatus.NEW, RowStatus.NEW,
                        RowStatus.ERROR, RowStatus.ERROR);
        ImportBatch batch = as(user, () -> imports().get(batchId));
        assertThat(batch).extracting(ImportBatch::status, ImportBatch::totalRows, ImportBatch::newRows, ImportBatch::errorRows)
                .containsExactly(ImportStatus.PREVIEW, 7, 5, 2);

        // Preview na tela: linhas, valores e erros
        mvc.perform(get("/imports/{id}", batchId).with(user.login()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("PIX ENVIADO - José Aluguel"),
                        containsString("2.200,00"), containsString("Data inválida"), containsString("Enviar para a caixa de entrada"))));

        List<UUID> valid = rows.stream().filter(ImportRow::selectable).map(ImportRow::id).toList();
        confirm(user, batchId, valid)
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Redirect", "/inbox?imported=5"));

        assertThat(count(user, "SELECT count(*) FROM inbox_item WHERE status = 'PENDING'")).isEqualTo(5);
        assertThat(count(user, "SELECT count(*) FROM financial_transaction")).as("nada lançado antes de revisar").isZero();
        assertThat(as(user, () -> imports().get(batchId)).status()).isEqualTo(ImportStatus.IMPORTED);
        mvc.perform(get("/inbox").param("imported", "5").with(user.login()))
                .andExpect(content().string(allOf(containsString("5 lançamentos importados"),
                        containsString("SALARIO EMPRESA X"), containsString("itau.ofx"))));
    }

    @Test
    void reimportingTheSameFileMarksEverythingAsDuplicate() throws Exception {
        TestUser user = newUser();
        UUID first = upload(user, "itau.ofx", resource("itau-102-cp1252.ofx"));
        confirm(user, first, selectable(user, first)).andExpect(status().isOk());

        UUID second = upload(user, "itau.ofx", resource("itau-102-cp1252.ofx"));

        ImportBatch batch = as(user, () -> imports().get(second));
        assertThat(batch.newRows()).isZero();
        assertThat(batch.duplicateRows()).isEqualTo(5);
        assertThat(as(user, () -> imports().rows(second))).filteredOn(r -> r.status() == RowStatus.DUPLICATE)
                .allSatisfy(r -> assertThat(r.selectedByDefault()).isFalse());
    }

    @Test
    void duplicatesAreDetectedAgainstConfirmedTransactionsCountingRepeats() throws Exception {
        TestUser user = newUser();
        UUID cafe = category(user, "Café", "EXPENSE");
        // Já lançado: um café de 7,00 em 09/10. O extrato tem dois.
        insertTransaction(user, cafe, 700, "2026-10-09", "Cafe");

        UUID batchId = upload(user, "itau.ofx", resource("itau-102-cp1252.ofx"));

        List<ImportRow> coffees = as(user, () -> imports().rows(batchId)).stream()
                .filter(r -> "CAFE".equals(r.description())).toList();
        assertThat(coffees).extracting(ImportRow::status).containsExactly(RowStatus.DUPLICATE, RowStatus.NEW);
        // Mapeamento automático pelo histórico: mesma descrição → mesma categoria
        assertThat(coffees).allSatisfy(r -> assertThat(r.category().id()).isEqualTo(cafe));
    }

    @Test
    void csvMapsCategoriesByNameAndResolvesTypeFromTheCategory() throws Exception {
        TestUser user = newUser();
        UUID salary = category(user, "Salário", "INCOME");
        UUID food = category(user, "Mercado", "EXPENSE");
        String csv = """
                Data;Descrição;Valor;Categoria
                05/10/2026;Pagamento;8.500,00;salario
                09/10/2026;Supermercado;643,90;MERCADO
                10/10/2026;Farmácia;35,00;Saúde
                11/10/2026;Valor com centavos demais;1,234;Mercado
                """;

        UUID batchId = upload(user, "planilha.csv", csv.getBytes(StandardCharsets.UTF_8));

        List<ImportRow> rows = as(user, () -> imports().rows(batchId));
        assertThat(rows.get(0).type().name()).isEqualTo("INCOME");
        assertThat(rows.get(0).category().id()).isEqualTo(salary);
        assertThat(rows.get(1).type().name()).isEqualTo("EXPENSE");
        assertThat(rows.get(1).category().id()).isEqualTo(food);
        assertThat(rows.get(2).category()).isNull();
        assertThat(rows.get(2).message()).contains("não encontrada");
        assertThat(rows.get(3).status()).isEqualTo(RowStatus.ERROR);
        assertThat(rows.get(3).message()).contains("casas decimais");
    }

    @Test
    void confirmationIsAllOrNothing() throws Exception {
        TestUser user = newUser();
        UUID batchId = upload(user, "itau.ofx", resource("itau-102-cp1252.ofx"));
        List<ImportRow> rows = as(user, () -> imports().rows(batchId));
        UUID errorRow = rows.stream().filter(r -> !r.selectable()).findFirst().orElseThrow().id();
        List<UUID> withError = new java.util.ArrayList<>(selectable(user, batchId));
        withError.add(errorRow);

        confirm(user, batchId, withError)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nada foi importado")));
        confirm(user, batchId, List.of(UUID.randomUUID()))
                .andExpect(content().string(containsString("Nada foi importado")));
        confirm(user, batchId, List.of())
                .andExpect(content().string(containsString("Selecione ao menos um")));

        assertThat(count(user, "SELECT count(*) FROM inbox_item")).isZero();
        assertThat(as(user, () -> imports().get(batchId)).status()).isEqualTo(ImportStatus.PREVIEW);
    }

    @Test
    void cannotConfirmTwiceOrAfterCancelling() throws Exception {
        TestUser user = newUser();
        UUID batchId = upload(user, "itau.ofx", resource("itau-102-cp1252.ofx"));
        List<UUID> ids = selectable(user, batchId);
        confirm(user, batchId, ids).andExpect(header().exists("HX-Redirect"));

        confirm(user, batchId, ids).andExpect(content().string(containsString("já foi confirmado")));
        assertThat(count(user, "SELECT count(*) FROM inbox_item")).isEqualTo(5);

        UUID other = upload(user, "itau.ofx", resource("itau-102-cp1252.ofx"));
        mvc.perform(post("/imports/{id}/cancel", other).header("HX-Request", "true").with(user.login()).with(csrf()))
                .andExpect(header().string("HX-Redirect", "/imports/new"));
        assertThat(as(user, () -> imports().get(other)).status()).isEqualTo(ImportStatus.CANCELLED);
        mvc.perform(post("/imports/{id}/cancel", other).header("HX-Request", "true").with(user.login()).with(csrf()))
                .andExpect(content().string(containsString("Só dá para cancelar")));
        confirm(user, other, selectable(user, other)).andExpect(content().string(containsString("já foi cancelado")));
    }

    @Test
    void badFilesComeBackAsMessages() throws Exception {
        TestUser user = newUser();

        mvc.perform(multipart("/imports").file(new MockMultipartFile("file", "x.ofx", "application/x-ofx", "nada".getBytes()))
                        .header("HX-Request", "true").with(user.login()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("não parece ser um OFX")));
        mvc.perform(multipart("/imports").file(new MockMultipartFile("file", "x.csv", "text/csv", new byte[0]))
                        .header("HX-Request", "true").with(user.login()).with(csrf()))
                .andExpect(content().string(containsString("Escolha um arquivo")));
        mvc.perform(multipart("/imports").header("HX-Request", "true").with(user.login()).with(csrf()))
                .andExpect(content().string(containsString("Escolha um arquivo")));
        // Sem HTMX, volta para a tela de upload com o erro
        mvc.perform(multipart("/imports").file(new MockMultipartFile("file", "x.csv", "text/csv", "descricao;categoria\nPão;Mercado\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .with(user.login()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("Importar extrato"), containsString("data e valor"))));
    }

    @Test
    void tooManyRowsIsRejected() throws Exception {
        TestUser user = newUser();
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i <= ImportService.MAX_ROWS; i++) {
            csv.append("10/10/2026,Linha ").append(i).append(",1\n");
        }

        mvc.perform(multipart("/imports").file(new MockMultipartFile("file", "big.csv", "text/csv", csv.toString().getBytes()))
                        .header("HX-Request", "true").with(user.login()).with(csrf()))
                .andExpect(content().string(containsString("mais de 5000")));
    }

    @Test
    void uploadWithoutHtmxRedirectsToThePreviewPage() throws Exception {
        TestUser user = newUser();

        MvcResult result = mvc.perform(multipart("/imports")
                        .file(new MockMultipartFile("file", "a.csv", "text/csv", "10/10/2026,Pão,5\n".getBytes()))
                        .with(user.login()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl()).startsWith("/imports/");
    }

    @Test
    void historyAndUploadPagesRender() throws Exception {
        TestUser user = newUser();
        mvc.perform(get("/imports").with(user.login()))
                .andExpect(content().string(containsString("Nenhum arquivo importado")));
        upload(user, "extrato-outubro.ofx", resource("itau-102-cp1252.ofx"));

        mvc.perform(get("/imports").with(user.login()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("extrato-outubro.ofx"), containsString("Aguardando"),
                        containsString("7 linhas · 5 novas · 0 duplicadas · 2 com erro"))));
        mvc.perform(get("/imports/new").with(user.login()))
                .andExpect(content().string(allOf(containsString("Arraste o arquivo aqui"), containsString("data-dropzone"))));
    }

    @Test
    void importsAreIsolatedPerUser() throws Exception {
        TestUser alice = newUser();
        TestUser bob = newUser();
        UUID batchId = upload(alice, "alice.ofx", resource("itau-102-cp1252.ofx"));
        List<UUID> aliceRows = selectable(alice, batchId);

        mvc.perform(get("/imports/{id}", batchId).with(bob.login())).andExpect(status().isNotFound());
        confirm(bob, batchId, aliceRows).andExpect(status().isNotFound());
        mvc.perform(get("/imports").with(bob.login())).andExpect(content().string(containsString("Nenhum arquivo importado")));
        assertThat(count(bob, "SELECT count(*) FROM import_row")).isZero();

        // Bob com o próprio import não consegue puxar linhas do import da Alice
        UUID bobBatch = upload(bob, "bob.csv", "10/10/2026,Pão,5\n".getBytes());
        confirm(bob, bobBatch, aliceRows).andExpect(content().string(containsString("Nada foi importado")));
        assertThat(count(alice, "SELECT count(*) FROM inbox_item") + count(bob, "SELECT count(*) FROM inbox_item")).isZero();
    }

    private UUID upload(TestUser user, String name, byte[] content) throws Exception {
        MvcResult result = mvc.perform(multipart("/imports")
                        .file(new MockMultipartFile("file", name, "application/octet-stream", content))
                        .header("HX-Request", "true").with(user.login()).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        String html = result.getResponse().getContentAsString();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/imports/([0-9a-f-]{36})/confirm").matcher(html);
        assertThat(m.find()).as("preview com botão de confirmar: " + html).isTrue();
        return UUID.fromString(m.group(1));
    }

    private org.springframework.test.web.servlet.ResultActions confirm(TestUser user, UUID batchId, List<UUID> rowIds) throws Exception {
        var request = post("/imports/{id}/confirm", batchId).header("HX-Request", "true").with(user.login()).with(csrf());
        rowIds.forEach(id -> request.param("rowIds", id.toString()));
        return mvc.perform(request);
    }

    private List<UUID> selectable(TestUser user, UUID batchId) {
        return as(user, () -> imports().rows(batchId)).stream()
                .filter(ImportRow::selectedByDefault).map(ImportRow::id).toList();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private ImportClient importClient;

    private ImportClient imports() {
        return importClient;
    }

    private int count(TestUser user, String sql) {
        return as(user, () -> jdbc.sql(sql).query(Integer.class).single());
    }

    private UUID category(TestUser user, String name, String type) throws Exception {
        String body = mvc.perform(json(post("/api/categories"), user,
                        "{\"name\": \"%s\", \"type\": \"%s\"}".formatted(name, type)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private void insertTransaction(TestUser user, UUID category, long cents, String date, String description) {
        as(user, () -> jdbc.sql("""
                        INSERT INTO financial_transaction (category_id, type, amount_cents, currency, occurred_on, description)
                        VALUES (:category, 'EXPENSE', :cents, 'BRL', :date::date, :description)
                        """)
                .param("category", category).param("cents", cents).param("date", date).param("description", description)
                .update());
    }
}
