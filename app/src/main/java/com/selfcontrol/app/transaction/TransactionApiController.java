package com.selfcontrol.app.transaction;

import com.selfcontrol.domain.report.Period;
import com.selfcontrol.domain.transaction.Transaction;
import com.selfcontrol.domain.transaction.TransactionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * API JSON de lançamentos. Em /api/transactions usa a sessão + CSRF (o próprio app web); em
 * /api/v1/transactions é a API pública, com chave de API (ver SecurityConfig).
 */
@RestController
@RequestMapping({"/api/transactions", "/api/v1/transactions"})
class TransactionApiController {

    record TransactionRequest(
            @NotNull TransactionType type,
            @NotNull @Positive BigDecimal amount,
            @Size(min = 3, max = 3) String currency,
            @NotNull LocalDate occurredOn,
            @NotNull UUID categoryId,
            @Size(max = Transaction.MAX_DESCRIPTION_LENGTH) String description) {

        TransactionCommand toCommand() {
            return new TransactionCommand(type, amount, currency, occurredOn, categoryId, description);
        }
    }

    /** amount vai como texto decimal ("12.34") para nenhum cliente perder precisão com float. */
    record TransactionResponse(
            UUID id,
            TransactionType type,
            String amount,
            long amountCents,
            String currency,
            LocalDate occurredOn,
            UUID categoryId,
            String description) {

        static TransactionResponse of(Transaction t) {
            return new TransactionResponse(t.id(), t.type(), t.amount().toDecimal().toPlainString(),
                    t.amount().minorUnits(), t.amount().currency().getCurrencyCode(), t.occurredOn(),
                    t.categoryId(), t.description());
        }
    }

    private final TransactionClient transactions;

    TransactionApiController(TransactionClient transactions) {
        this.transactions = transactions;
    }

    /** Sem período, devolve o mês atual. */
    @GetMapping
    List<TransactionResponse> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                   @RequestParam(required = false) UUID categoryId) {
        YearMonth now = YearMonth.now();
        Period period = new Period(from != null ? from : now.atDay(1), to != null ? to : now.atEndOfMonth());
        return transactions.list(period, categoryId).stream()
                .map(e -> TransactionResponse.of(e.transaction()))
                .toList();
    }

    @GetMapping("/{id}")
    TransactionResponse get(@PathVariable UUID id) {
        return TransactionResponse.of(transactions.get(id));
    }

    @PostMapping
    ResponseEntity<TransactionResponse> create(@Valid @RequestBody TransactionRequest request) {
        Transaction created = transactions.create(request.toCommand());
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").build(created.id());
        return ResponseEntity.created(location).body(TransactionResponse.of(created));
    }

    @PutMapping("/{id}")
    TransactionResponse update(@PathVariable UUID id, @Valid @RequestBody TransactionRequest request) {
        return TransactionResponse.of(transactions.update(id, request.toCommand()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        transactions.delete(id);
    }
}
