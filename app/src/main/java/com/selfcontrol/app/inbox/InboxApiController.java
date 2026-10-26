package com.selfcontrol.app.inbox;

import com.selfcontrol.app.importing.ImportSource;
import com.selfcontrol.domain.transaction.Transaction;
import com.selfcontrol.domain.transaction.TransactionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * API JSON da caixa de entrada: listar pendentes, criar (como o webhook), editar, confirmar e
 * descartar em lote. Em /api/inbox usa a sessão + CSRF; em /api/v1/inbox, chave de API.
 */
@RestController
@RequestMapping({"/api/inbox", "/api/v1/inbox"})
class InboxApiController {

    /** categoryId é opcional: sem ele, tenta a categoria pelo histórico da descrição. */
    record InboxRequest(
            @NotNull TransactionType type,
            @NotNull @Positive BigDecimal amount,
            @Size(min = 3, max = 3) String currency,
            @NotNull LocalDate occurredOn,
            UUID categoryId,
            @Size(max = Transaction.MAX_DESCRIPTION_LENGTH) String description) {

        InboxCommand toCommand() {
            return new InboxCommand(type, amount, currency, occurredOn, categoryId, description);
        }
    }

    record IdsRequest(@NotEmpty @Size(max = 500) List<UUID> ids) {
    }

    record InboxResponse(UUID id, String source, TransactionType type, String amount, long amountCents,
                         String currency, LocalDate occurredOn, String description, UUID categoryId,
                         boolean readyToConfirm, boolean possibleDuplicate) {

        static InboxResponse of(InboxEntry e) {
            return new InboxResponse(e.id(), e.source().name(), e.type(), e.amount().toDecimal().toPlainString(),
                    e.amount().minorUnits(), e.amount().currency().getCurrencyCode(), e.occurredOn(), e.description(),
                    e.category() == null ? null : e.category().id(), e.readyToConfirm(), e.possibleDuplicate());
        }
    }

    record ConfirmResponse(int confirmed, int skipped) {
    }

    record DiscardResponse(int discarded) {
    }

    private final InboxClient inbox;

    InboxApiController(InboxClient inbox) {
        this.inbox = inbox;
    }

    /** Pendentes, mais recentes primeiro. */
    @GetMapping
    List<InboxResponse> pending() {
        return inbox.pending().stream().map(InboxResponse::of).toList();
    }

    @GetMapping("/{id}")
    InboxResponse get(@PathVariable UUID id) {
        return InboxResponse.of(inbox.get(id));
    }

    @PostMapping
    ResponseEntity<InboxResponse> create(@Valid @RequestBody InboxRequest request) {
        InboxEntry created = inbox.add(request.toCommand(), ImportSource.API);
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").build(created.id());
        return ResponseEntity.created(location).body(InboxResponse.of(created));
    }

    @PutMapping("/{id}")
    InboxResponse update(@PathVariable UUID id, @Valid @RequestBody InboxRequest request) {
        return InboxResponse.of(inbox.update(id, request.toCommand()));
    }

    /** Vira lançamento; os sem categoria válida ficam na caixa (contados em skipped). */
    @PostMapping("/confirm")
    ConfirmResponse confirm(@Valid @RequestBody IdsRequest request) {
        InboxClient.BulkResult result = inbox.confirm(request.ids());
        return new ConfirmResponse(result.confirmed(), result.skipped());
    }

    @PostMapping("/discard")
    DiscardResponse discard(@Valid @RequestBody IdsRequest request) {
        return new DiscardResponse(inbox.discard(request.ids()));
    }
}
