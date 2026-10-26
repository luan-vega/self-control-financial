package com.selfcontrol.app.importing;

import java.time.Instant;
import java.util.UUID;

/** Um arquivo importado (ou em preview) e os contadores do que aconteceu com as linhas. */
public record ImportBatch(
        UUID id,
        ImportSource source,
        String fileName,
        ImportStatus status,
        int totalRows,
        int newRows,
        int duplicateRows,
        int errorRows,
        int importedRows,
        Instant createdAt,
        Instant finishedAt) {

    public boolean inPreview() {
        return status == ImportStatus.PREVIEW;
    }
}
