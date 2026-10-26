package com.selfcontrol.app.inbox;

import com.selfcontrol.app.importing.ImportSource;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Caixa de entrada: lançamentos importados esperando revisão. Sempre age como o usuário logado (via RLS). */
public interface InboxClient {

    /** Resultado de uma confirmação em lote: os sem categoria (ou com categoria do tipo errado) ficam na caixa. */
    record BulkResult(int confirmed, int skipped) {
    }

    /** Pendentes, mais recentes primeiro. */
    List<InboxEntry> pending();

    int pendingCount();

    InboxEntry get(UUID id);

    /** Cria um pendente (webhook). Sem categoryId, tenta o mapeamento automático pela descrição. */
    InboxEntry add(InboxCommand command, ImportSource source);

    InboxEntry update(UUID id, InboxCommand command);

    /** Vira lançamento de verdade e sai da caixa. Itens sem categoria válida são pulados. */
    BulkResult confirm(Collection<UUID> ids);

    /** Some da caixa, mas fica guardado para reimportar o mesmo extrato não trazer de volta. */
    int discard(Collection<UUID> ids);
}
