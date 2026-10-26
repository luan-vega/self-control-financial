package com.selfcontrol.app.importing;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Importação de extratos. Sempre age como o usuário logado (via RLS).
 * Fluxo: {@link #preview} lê o arquivo e guarda o resultado; {@link #confirm} manda as linhas
 * escolhidas para a caixa de entrada, tudo ou nada.
 */
public interface ImportClient {

    /** Lê o arquivo (OFX ou CSV), classifica cada linha e guarda um import em PREVIEW. */
    ImportBatch preview(String fileName, byte[] content);

    ImportBatch get(UUID batchId);

    List<ImportRow> rows(UUID batchId);

    /** Leva as linhas escolhidas para a caixa de entrada numa só transação. Recusa tudo se alguma for inválida. */
    ImportBatch confirm(UUID batchId, Collection<UUID> rowIds);

    void cancel(UUID batchId);

    /** Imports mais recentes primeiro. */
    List<ImportBatch> history();
}
