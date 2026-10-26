package com.selfcontrol.app.importing.parser;

import com.selfcontrol.domain.importing.Descriptions;
import com.selfcontrol.domain.transaction.Transaction;
import com.selfcontrol.domain.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lê CSV de planilhas e bancos. Formato básico, sem cabeçalho: {@code data, descrição, valor, categoria}.
 * <ul>
 *   <li>Separador detectado na primeira linha: {@code ;}, {@code ,} ou tab.</li>
 *   <li>Cabeçalho opcional; com ele as colunas podem vir em qualquer ordem e com outros nomes
 *       (data/date, descrição/histórico/title, valor/amount, categoria, tipo, identificador).</li>
 *   <li>Campos entre aspas, com aspas dobradas e quebras de linha dentro.</li>
 *   <li>Sinal: se algum valor do arquivo é negativo, negativo é despesa e positivo é receita. Se todos
 *       são positivos (planilha de gastos), o tipo vem da coluna tipo ou da categoria; sem nenhum dos dois,
 *       é despesa.</li>
 * </ul>
 */
final class CsvParser {

    private enum Column { DATE, DESCRIPTION, AMOUNT, CATEGORY, TYPE, ID }

    private record Record(int lineNo, List<String> cells) {
    }

    private record Raw(int lineNo, List<String> cells, BigDecimal amount) {
    }

    private CsvParser() {
    }

    static Statement parse(String content) {
        char delimiter = detectDelimiter(content);
        List<Record> records = read(content, delimiter);
        if (records.isEmpty()) {
            throw new StatementFormatException("O arquivo está vazio");
        }

        Map<Column, Integer> columns = header(records.getFirst().cells());
        if (columns == null) {
            columns = Map.of(Column.DATE, 0, Column.DESCRIPTION, 1, Column.AMOUNT, 2, Column.CATEGORY, 3, Column.TYPE, 4);
        } else {
            records = records.subList(1, records.size());
            if (!columns.containsKey(Column.DATE) || !columns.containsKey(Column.AMOUNT)) {
                throw new StatementFormatException("O cabeçalho precisa ter as colunas de data e valor");
            }
        }

        // Primeira passada: valores, para saber se o arquivo usa sinal.
        List<Raw> raws = new ArrayList<>(records.size());
        boolean signed = false;
        for (Record r : records) {
            BigDecimal amount = null;
            try {
                amount = Values.parseAmount(cell(r.cells(), columns.get(Column.AMOUNT)));
                signed |= amount.signum() < 0;
            } catch (NumberFormatException ignored) {
                // vira erro da linha abaixo
            }
            raws.add(new Raw(r.lineNo(), r.cells(), amount));
        }

        List<StatementLine> lines = new ArrayList<>(raws.size());
        for (Raw raw : raws) {
            lines.add(line(raw, columns, signed));
        }
        return new Statement(null, lines);
    }

    private static StatementLine line(Raw raw, Map<Column, Integer> columns, boolean signed) {
        List<String> cells = raw.cells();
        String description = cell(cells, columns.get(Column.DESCRIPTION)).replaceAll("\\s+", " ");
        if (description.length() > Transaction.MAX_DESCRIPTION_LENGTH) {
            description = description.substring(0, Transaction.MAX_DESCRIPTION_LENGTH).strip();
        }
        if (cells.size() <= Math.max(columns.get(Column.DATE), columns.get(Column.AMOUNT))) {
            return StatementLine.invalid(raw.lineNo(), description, "Faltam colunas: esperado data, descrição, valor");
        }
        String rawDate = cell(cells, columns.get(Column.DATE));
        LocalDate date = Values.parseDate(rawDate);
        if (date == null) {
            return StatementLine.invalid(raw.lineNo(), description, "Data inválida: \"" + rawDate + "\"");
        }
        if (raw.amount() == null) {
            return StatementLine.invalid(raw.lineNo(), description,
                    "Valor inválido: \"" + cell(cells, columns.get(Column.AMOUNT)) + "\"");
        }
        if (raw.amount().signum() == 0) {
            return StatementLine.invalid(raw.lineNo(), description, "O valor deve ser diferente de zero");
        }

        String rawType = cell(cells, columns.get(Column.TYPE));
        TransactionType type = parseType(rawType);
        if (!rawType.isEmpty() && type == null) {
            return StatementLine.invalid(raw.lineNo(), description, "Tipo inválido: \"" + rawType + "\" (use receita ou despesa)");
        }
        if (type == null && signed) {
            type = raw.amount().signum() < 0 ? TransactionType.EXPENSE : TransactionType.INCOME;
        } else if (type == null && raw.amount().signum() < 0) {
            type = TransactionType.EXPENSE;
        }
        String category = cell(cells, columns.get(Column.CATEGORY));
        String id = cell(cells, columns.get(Column.ID));
        return new StatementLine(raw.lineNo(), date, description, raw.amount().abs(), type,
                category.isEmpty() ? null : category, id.isEmpty() ? null : id, null);
    }

    static TransactionType parseType(String text) {
        return switch (Descriptions.key(text)) {
            case "receita", "income", "credito", "credit", "c", "entrada", "in" -> TransactionType.INCOME;
            case "despesa", "expense", "debito", "debit", "d", "saida", "out", "gasto" -> TransactionType.EXPENSE;
            default -> null;
        };
    }

    /** Mapa coluna → índice se a linha for um cabeçalho; null se já for dado (tem data ou número). */
    private static Map<Column, Integer> header(List<String> cells) {
        if (cells.stream().anyMatch(c -> Values.parseDate(c) != null || isNumber(c))) {
            return null;
        }
        Map<Column, Integer> columns = new java.util.EnumMap<>(Column.class);
        for (int i = 0; i < cells.size(); i++) {
            Column column = column(Descriptions.key(cells.get(i)));
            if (column != null) {
                columns.putIfAbsent(column, i);
            }
        }
        return columns.isEmpty() ? null : columns;
    }

    private static boolean isNumber(String text) {
        try {
            Values.parseAmount(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Column column(String name) {
        if (name.startsWith("data") || name.equals("date") || name.equals("dt")) {
            return Column.DATE;
        }
        if (name.startsWith("descri") || name.startsWith("historico") || name.equals("title") || name.equals("memo")
                || name.startsWith("estabelecimento") || name.startsWith("lancamento")) {
            return Column.DESCRIPTION;
        }
        if (name.startsWith("valor") || name.startsWith("amount") || name.equals("value") || name.equals("quantia")) {
            return Column.AMOUNT;
        }
        if (name.startsWith("categ")) {
            return Column.CATEGORY;
        }
        if (name.equals("tipo") || name.equals("type") || name.equals("natureza")) {
            return Column.TYPE;
        }
        if (name.equals("identificador") || name.equals("id") || name.equals("fitid")) {
            return Column.ID;
        }
        return null;
    }

    private static String cell(List<String> cells, Integer index) {
        return index == null || index >= cells.size() ? "" : cells.get(index).strip();
    }

    /** O separador que mais aparece fora de aspas na primeira linha com conteúdo. Empate fica com vírgula. */
    private static char detectDelimiter(String content) {
        String first = content.lines().filter(l -> !l.isBlank()).findFirst().orElse("");
        int commas = 0;
        int semicolons = 0;
        int tabs = 0;
        boolean quoted = false;
        for (char c : first.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted) {
                switch (c) {
                    case ',' -> commas++;
                    case ';' -> semicolons++;
                    case '\t' -> tabs++;
                    default -> { }
                }
            }
        }
        if (tabs > commas && tabs > semicolons) {
            return '\t';
        }
        return semicolons > commas ? ';' : ',';
    }

    /** Tokenizador RFC 4180. Linhas em branco são puladas; lineNo é a linha onde o registro começa. */
    private static List<Record> read(String content, char delimiter) {
        List<Record> records = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int recordStart = 1;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    cell.append(c);
                }
            } else if (c == '"' && cell.toString().isBlank()) {
                cell.setLength(0);
                quoted = true;
            } else if (c == delimiter) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                cells.add(cell.toString());
                cell.setLength(0);
                addRecord(records, recordStart, cells);
                cells = new ArrayList<>();
                line++;
                recordStart = line;
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        addRecord(records, recordStart, cells);
        return records;
    }

    private static void addRecord(List<Record> records, int lineNo, List<String> cells) {
        if (cells.stream().anyMatch(c -> !c.isBlank())) {
            records.add(new Record(lineNo, cells));
        }
    }
}
