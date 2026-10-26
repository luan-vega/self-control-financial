package com.selfcontrol.domain.importing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Marca como duplicatas as linhas importadas que já existem. A comparação conta ocorrências:
 * se o extrato tem dois cafés iguais no mesmo dia e só um já foi lançado, um é duplicata e o
 * outro é novo. Assim reimportar um período sobreposto não duplica nada, e compras repetidas
 * de verdade não somem.
 */
public final class DuplicateDetector {

    private final Map<Fingerprint, Integer> remaining = new HashMap<>();

    public DuplicateDetector(Collection<Fingerprint> existing) {
        existing.forEach(f -> remaining.merge(f, 1, Integer::sum));
    }

    /** Consome uma ocorrência existente, se houver. Chame na ordem das linhas do arquivo. */
    public boolean isDuplicate(Fingerprint candidate) {
        Integer left = remaining.get(candidate);
        if (left == null) {
            return false;
        }
        if (left == 1) {
            remaining.remove(candidate);
        } else {
            remaining.put(candidate, left - 1);
        }
        return true;
    }

    /** Uma flag por candidato, na mesma ordem. */
    public List<Boolean> classify(List<Fingerprint> candidates) {
        List<Boolean> result = new ArrayList<>(candidates.size());
        candidates.forEach(c -> result.add(isDuplicate(c)));
        return result;
    }
}
