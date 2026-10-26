package com.selfcontrol.app.webhook;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.selfcontrol.app.importing.ImportSource;
import com.selfcontrol.app.importing.parser.Values;
import com.selfcontrol.app.inbox.InboxClient;
import com.selfcontrol.app.inbox.InboxCommand;
import com.selfcontrol.app.inbox.InboxEntry;
import com.selfcontrol.app.shared.error.InvalidInputException;
import com.selfcontrol.domain.transaction.TransactionType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * Captura rápida pelos Atalhos do iOS (ex.: automação da Carteira a cada compra no cartão).
 * Sem sessão nem CSRF: autenticado pelo token secreto ({@link WebhookTokenFilter}). O lançamento
 * entra na caixa de entrada como pendente.
 */
@RestController
public class WebhookController {

    public static final String PATH = "/api/transactions/webhook";

    /**
     * Tudo como texto para aceitar o que o Atalhos mandar: "12,34", "R$ 12,34" ou 12.34;
     * datas ISO ("2026-10-26" ou "2026-10-26T14:05:00-03:00") ou "26/10/2026".
     */
    record WebhookRequest(
            String date,
            String description,
            String amount,
            @JsonProperty("category_id") @JsonAlias("categoryId") String categoryId,
            String type,
            String currency) {
    }

    record WebhookResponse(UUID id, String status, TransactionType type, String amount, long amountCents,
                           String currency, LocalDate occurredOn, String description, UUID categoryId,
                           String categoryName) {

        static WebhookResponse of(InboxEntry e) {
            return new WebhookResponse(e.id(), "PENDING", e.type(), e.amount().toDecimal().toPlainString(),
                    e.amount().minorUnits(), e.amount().currency().getCurrencyCode(), e.occurredOn(), e.description(),
                    e.category() == null ? null : e.category().id(), e.category() == null ? null : e.category().name());
        }
    }

    private final InboxClient inbox;

    WebhookController(InboxClient inbox) {
        this.inbox = inbox;
    }

    @PostMapping(PATH)
    @ResponseStatus(HttpStatus.CREATED)
    WebhookResponse receive(@RequestBody WebhookRequest request) {
        BigDecimal amount = amount(request.amount());
        InboxCommand command = new InboxCommand(type(request.type()), amount.abs(), request.currency(),
                date(request.date()), categoryId(request.categoryId()), request.description());
        try {
            return WebhookResponse.of(inbox.add(command, ImportSource.WEBHOOK));
        } catch (InvalidInputException e) {
            // Devolve o erro com o nome do campo do JSON do webhook.
            throw new InvalidInputException(switch (e.field()) {
                case "occurredOn" -> "date";
                case "categoryId" -> "category_id";
                default -> e.field();
            }, e.getMessage());
        }
    }

    private static BigDecimal amount(String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidInputException("amount", "Informe o valor");
        }
        try {
            return Values.parseAmount(text);
        } catch (NumberFormatException e) {
            throw new InvalidInputException("amount", "Valor inválido: " + text);
        }
    }

    /** Sem tipo é despesa, o caso comum de uma compra no cartão. O sinal do valor é ignorado. */
    private static TransactionType type(String text) {
        if (text == null || text.isBlank()) {
            return TransactionType.EXPENSE;
        }
        String value = text.strip().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "EXPENSE", "DESPESA", "GASTO" -> TransactionType.EXPENSE;
            case "INCOME", "RECEITA" -> TransactionType.INCOME;
            default -> throw new InvalidInputException("type", "Tipo inválido: use EXPENSE ou INCOME");
        };
    }

    private static LocalDate date(String text) {
        if (text == null || text.isBlank()) {
            return LocalDate.now();
        }
        LocalDate date = Values.parseDate(text);
        if (date == null) {
            throw new InvalidInputException("date", "Data inválida: " + text);
        }
        return date;
    }

    private static UUID categoryId(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text.strip());
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("category_id", "category_id inválido");
        }
    }
}
