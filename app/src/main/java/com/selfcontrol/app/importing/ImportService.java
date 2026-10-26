package com.selfcontrol.app.importing;

import com.selfcontrol.app.category.CategoryClient;
import com.selfcontrol.app.importing.parser.Statement;
import com.selfcontrol.app.importing.parser.StatementFormat;
import com.selfcontrol.app.importing.parser.StatementFormatException;
import com.selfcontrol.app.importing.parser.StatementLine;
import com.selfcontrol.app.importing.parser.TextDecoding;
import com.selfcontrol.app.shared.error.ConflictException;
import com.selfcontrol.app.shared.error.InvalidInputException;
import com.selfcontrol.app.shared.error.NotFoundException;
import com.selfcontrol.app.shared.security.UserContext;
import com.selfcontrol.domain.category.Category;
import com.selfcontrol.domain.importing.DuplicateDetector;
import com.selfcontrol.domain.importing.Fingerprint;
import com.selfcontrol.domain.transaction.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lê o arquivo de forma síncrona: com o limite de {@value #MAX_ROWS} linhas e 5 MB, o parse e a
 * gravação levam menos de um segundo, então ainda não vale a pena um job assíncrono.
 */
@Service
@Transactional
class ImportService implements ImportClient {

    static final int MAX_ROWS = 5000;
    static final int HISTORY_SIZE = 50;

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final ImportRepository repository;
    private final CategoryMatching matching;
    private final CategoryClient categories;
    private final UserContext users;

    ImportService(ImportRepository repository, CategoryMatching matching, CategoryClient categories, UserContext users) {
        this.repository = repository;
        this.matching = matching;
        this.categories = categories;
        this.users = users;
    }

    @Override
    public ImportBatch preview(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw new InvalidInputException("file", "Escolha um arquivo OFX ou CSV");
        }
        String name = fileName(fileName);
        String text = TextDecoding.decode(content);
        StatementFormat format = StatementFormat.detect(name, text);
        Statement statement;
        try {
            statement = format.parse(text);
        } catch (StatementFormatException e) {
            throw new InvalidInputException("file", e.getMessage());
        }
        if (statement.lines().size() > MAX_ROWS) {
            throw new InvalidInputException("file", "O arquivo tem mais de " + MAX_ROWS + " lançamentos. Divida em partes menores.");
        }

        Currency currency = currency(statement.currency(), users.current().defaultCurrency());
        CategoryMatcher matcher = matching.load();
        List<ImportRepository.NewRow> rows = new ArrayList<>(statement.lines().size());
        for (StatementLine line : statement.lines()) {
            rows.add(classify(line, currency, matcher));
        }
        rows = markDuplicates(rows);

        UUID batchId = UUID.randomUUID();
        Map<RowStatus, Long> counts = rows.stream().collect(Collectors.groupingBy(ImportRepository.NewRow::status, Collectors.counting()));
        repository.insertBatch(batchId, ImportSource.valueOf(format.name()), name, rows.size(),
                counts.getOrDefault(RowStatus.NEW, 0L).intValue(),
                counts.getOrDefault(RowStatus.DUPLICATE, 0L).intValue(),
                counts.getOrDefault(RowStatus.ERROR, 0L).intValue());
        repository.insertRows(batchId, rows);
        log.info("Import {} ({}): {} linhas, {}", batchId, format, rows.size(), counts);
        return get(batchId);
    }

    @Override
    @Transactional(readOnly = true)
    public ImportBatch get(UUID batchId) {
        return repository.findBatch(batchId).orElseThrow(() -> new NotFoundException("Import não encontrado"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImportRow> rows(UUID batchId) {
        get(batchId);
        Map<UUID, Category> byId = categories.list().stream().collect(Collectors.toMap(Category::id, Function.identity()));
        return repository.rows(batchId).stream()
                .map(r -> new ImportRow(r.id(), r.lineNo(), r.status(), r.type(), r.amount(), r.occurredOn(),
                        r.description(), r.categoryId() == null ? null : byId.get(r.categoryId()), r.externalId(), r.message()))
                .toList();
    }

    @Override
    public ImportBatch confirm(UUID batchId, Collection<UUID> rowIds) {
        ImportBatch batch = get(batchId);
        if (!batch.inPreview()) {
            throw new ConflictException("Este import já foi " + (batch.status() == ImportStatus.IMPORTED ? "confirmado" : "cancelado"));
        }
        Set<UUID> ids = new LinkedHashSet<>(rowIds == null ? List.of() : rowIds);
        ids.remove(null);
        if (ids.isEmpty()) {
            throw new InvalidInputException("rows", "Selecione ao menos um lançamento para importar");
        }
        // Tudo ou nada: se alguma linha pedida não existe ou tem erro, nada é importado.
        if (repository.countSelectable(batchId, ids) != ids.size()) {
            throw new InvalidInputException("rows", "A seleção tem linhas com erro ou de outro import. Nada foi importado.");
        }
        // Trava o import antes de copiar, para um clique duplo não importar duas vezes.
        if (!repository.finish(batchId, ImportStatus.IMPORTED, ids.size())) {
            throw new ConflictException("Este import já foi confirmado");
        }
        int moved = repository.moveToInbox(batchId, batch.source(), ids);
        if (moved != ids.size()) {
            throw new IllegalStateException("Esperava mover " + ids.size() + " linhas, movi " + moved);
        }
        log.info("Import {} confirmado: {} lançamentos na caixa de entrada", batchId, moved);
        return get(batchId);
    }

    @Override
    public void cancel(UUID batchId) {
        ImportBatch batch = get(batchId);
        if (!batch.inPreview() || !repository.finish(batchId, ImportStatus.CANCELLED, 0)) {
            throw new ConflictException("Só dá para cancelar um import que ainda não foi confirmado");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImportBatch> history() {
        return repository.recentBatches(HISTORY_SIZE);
    }

    private static ImportRepository.NewRow classify(StatementLine line, Currency currency, CategoryMatcher matcher) {
        if (!line.valid()) {
            return error(line, line.error());
        }
        if (line.date().isBefore(Transaction.MIN_DATE) || line.date().isAfter(Transaction.MAX_DATE)) {
            return error(line, "Data fora do intervalo aceito: " + line.date());
        }
        Long cents = cents(line.amount(), currency);
        if (cents == null) {
            return error(line, "Valor com mais de " + currency.getDefaultFractionDigits() + " casas decimais: " + line.amount());
        }
        CategoryMatcher.Match match = matcher.resolve(line.category(), line.type(), line.description());
        return new ImportRepository.NewRow(UUID.randomUUID(), line.lineNo(), RowStatus.NEW, match.type(), cents,
                currency.getCurrencyCode(), line.date(), line.description(),
                match.category() == null ? null : match.category().id(), line.externalId(), match.note());
    }

    /** Compara com o que já existe no período do arquivo (lançamentos e caixa de entrada). */
    private List<ImportRepository.NewRow> markDuplicates(List<ImportRepository.NewRow> rows) {
        List<ImportRepository.NewRow> valid = rows.stream().filter(r -> r.status() == RowStatus.NEW).toList();
        if (valid.isEmpty()) {
            return rows;
        }
        LocalDate from = valid.stream().map(ImportRepository.NewRow::occurredOn).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate to = valid.stream().map(ImportRepository.NewRow::occurredOn).max(Comparator.naturalOrder()).orElseThrow();
        DuplicateDetector detector = new DuplicateDetector(repository.fingerprints(from, to));
        return rows.stream()
                .map(r -> r.status() == RowStatus.NEW && detector.isDuplicate(fingerprint(r))
                        ? new ImportRepository.NewRow(r.id(), r.lineNo(), RowStatus.DUPLICATE, r.type(), r.amountCents(),
                                r.currency(), r.occurredOn(), r.description(), r.categoryId(), r.externalId(),
                                "Já existe um lançamento igual (mesma data, valor e descrição)")
                        : r)
                .toList();
    }

    private static Fingerprint fingerprint(ImportRepository.NewRow r) {
        return new Fingerprint(r.occurredOn(), r.type(), r.amountCents(), r.currency(), r.description());
    }

    private static ImportRepository.NewRow error(StatementLine line, String message) {
        return new ImportRepository.NewRow(UUID.randomUUID(), line.lineNo(), RowStatus.ERROR, null, null, null, null,
                Objects.requireNonNullElse(line.description(), ""), null, line.externalId(), message);
    }

    private static Long cents(BigDecimal amount, Currency currency) {
        try {
            return amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY)
                    .unscaledValue().longValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private static Currency currency(String code, Currency fallback) {
        if (code == null) {
            return fallback;
        }
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Só o nome do arquivo, sem caminho (alguns navegadores antigos mandam o caminho inteiro). */
    private static String fileName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isEmpty()) {
            return "arquivo";
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }
}
