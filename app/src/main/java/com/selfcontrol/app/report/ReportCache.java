package com.selfcontrol.app.report;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cache em memória (Caffeine) dos relatórios e do saldo, por usuário. A chave sempre começa pelo
 * usuário e inclui a moeda e os filtros, então trocar a moeda padrão não precisa invalidar nada.
 * Qualquer mudança em lançamentos ou categorias do usuário apaga as entradas dele depois do commit
 * (antes do commit, uma leitura concorrente poderia guardar de novo o valor antigo).
 * <p>
 * Vale por instância: com várias réplicas, cada uma tem o seu cache, e uma réplica pode mostrar
 * um relatório desatualizado por até {@code app.reports.cache.ttl}. Ver docs/deployment.md.
 */
@Component
public class ReportCache {

    /** Identifica uma consulta: o primeiro campo é sempre o dono. */
    record Key(UUID userId, Object... parts) {

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && userId.equals(k.userId) && java.util.Arrays.equals(parts, k.parts);
        }

        @Override
        public int hashCode() {
            return 31 * userId.hashCode() + java.util.Arrays.hashCode(parts);
        }
    }

    private final Cache<Key, Object> cache;
    private final boolean enabled;

    ReportCache(@Value("${app.reports.cache.enabled:true}") boolean enabled,
                @Value("${app.reports.cache.ttl:10m}") Duration ttl,
                @Value("${app.reports.cache.max-entries:10000}") long maxEntries) {
        this.enabled = enabled;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxEntries)
                .recordStats()
                .build();
    }

    @SuppressWarnings("unchecked")
    <T> T get(Key key, Supplier<T> loader) {
        if (!enabled) {
            return loader.get();
        }
        return (T) cache.get(key, k -> loader.get());
    }

    /** Apaga o cache do usuário. Dentro de uma transação, só depois do commit. */
    public void invalidate(UUID userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(userId);
                }
            });
        } else {
            evict(userId);
        }
    }

    long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

    Optional<Object> peek(Key key) {
        return Optional.ofNullable(cache.getIfPresent(key));
    }

    private void evict(UUID userId) {
        cache.asMap().keySet().removeIf(k -> k.userId().equals(userId));
    }
}
