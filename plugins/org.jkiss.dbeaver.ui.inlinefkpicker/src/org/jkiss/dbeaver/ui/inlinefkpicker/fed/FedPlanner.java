/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ui.inlinefkpicker.fed;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * dbeaver-mm K119: federe sorgu planı.
 * <p>
 * Tek bir SELECT'i metin düzeyinde ayrıştırır; FROM/JOIN tablolarını, ON eşitliklerini,
 * tablo başına itilebilen WHERE terimlerini ve SELECT listesini çıkarır. Alt sorgu, UNION,
 * CTE, DML ve eşitlik dışı ON koşulları federe sayılmaz ve {@code null} döner.
 * dbeaver-mm K133: OR içeren ya da çok tablolu WHERE terimleri planda kalır ve bellekte süzülür.
 * JSQLParser'a bağımlı değildir; SQL metni tarayıcıyla çözülür.
 */
public final class FedPlanner {

    private static final Set<String> BOUNDARIES = Set.of(
        "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "FETCH", "FOR"
    );
    private static final Set<String> JOIN_STARTERS = Set.of(
        "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS"
    );
    private static final Set<String> TABLE_KEYWORDS = Set.of(
        "AS", "ON", "USING", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET",
        "FETCH", "FOR", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS"
    );
    private static final Set<String> AGGREGATE_FUNCTIONS = Set.of("COUNT", "SUM", "MIN", "MAX", "AVG");
    private static final int WORD = 1;
    private static final int IDENT = 2;
    private static final int STRING = 3;
    private static final int SYMBOL = 4;

    private FedPlanner() {
    }

    /**
     * Federe plan: tablo listesi, join listesi, kalan WHERE, seçim listesi ve
     * dbeaver-mm K125 ile ORDER BY/LIMIT/OFFSET/DISTINCT bilgileri.
     * dbeaver-mm K127: GROUP BY sütunları ve seçim listesindeki toplamalar da taşınır.
     * dbeaver-mm K132: HAVING metni ve bellekte uygulanacak toplama karşılaştırmaları taşınır.
     */
    public record FedPlan(
        List<Table> tables,
        List<Join> joins,
        String where,
        List<String> selection,
        List<Order> orderBy,
        Long limit,
        Long offset,
        boolean distinct,
        List<String> groupColumns,
        List<Aggregate> aggregates,
        String havingText,
        List<Having> havingFilters
    ) {
        public FedPlan(
            List<Table> tables,
            List<Join> joins,
            String where,
            List<String> selection,
            List<Order> orderBy
        ) {
            this(
                tables,
                joins,
                where,
                selection,
                orderBy,
                null,
                null,
                false,
                List.of(),
                List.of(),
                "",
                List.of()
            );
        }
    }

    /** dbeaver-mm K127: seçim listesindeki bir toplama; sütun COUNT(*) için null. */
    public record Aggregate(String function, String column, int selectionIndex) {
    }

    /** dbeaver-mm K132: HAVING içindeki toplama karşılaştırması; sütun COUNT(*) için null. */
    public record Having(String function, String column, String operator, String literal) {
    }

    /** Bir FROM/JOIN tablosu: kod (şema), ad, alias ve o tabloya itilen WHERE. */
    public record Table(String code, String name, String alias, String pushedWhere) {
    }

    /** Bir join: tür ve eşitlik anahtar çiftleri. */
    public record Join(String type, List<Key> keys) {
    }

    /** {@code alias.kolon = alias.kolon} eşitliğinin iki tarafı. */
    public record Key(String left, String right) {
    }

    /** dbeaver-mm K125: ORDER BY öğesi; sütun ve azalan sıralama yönü. */
    public record Order(String column, boolean descending) {
    }

    /**
     * SQL metnini federe plana çevirir.
     *
     * @return federe plan ya da federe olmayan/çözülemeyen sorgu için {@code null}
     */
    public static FedPlan parse(String sql) {
        if (sql == null) {
            return null;
        }
        String text = sql.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        List<Token> tokens = tokenize(text);
        if (tokens.isEmpty() || !isWord(tokens.get(0), "SELECT")) {
            return null;
        }
        for (int i = 1; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (isWord(token, "SELECT") || isWord(token, "UNION")
                || isWord(token, "INTERSECT") || isWord(token, "EXCEPT")) {
                return null;
            }
        }

        int[] depths = depths(tokens);
        // dbeaver-mm K132: HAVING desteklenen toplama karşılaştırmalarıysa planda saklanır
        int havingStart = findWord(tokens, depths, "HAVING", 1);
        int from = findWord(tokens, depths, "FROM", 1);
        if (from < 0) {
            return null;
        }
        int boundary = firstBoundary(tokens, depths, from + 1);
        int whereStart = boundary < tokens.size() && isWord(tokens.get(boundary), "WHERE") ? boundary : -1;
        int fromEnd = whereStart >= 0 ? whereStart : boundary;
        int whereEnd = whereStart >= 0 ? firstBoundary(tokens, depths, whereStart + 1) : -1;

        for (int i = from + 1; i < fromEnd; i++) {
            if (tokens.get(i).text.equals("(")) {
                // FROM içinde alt sorgu veya fonksiyon: federe değil
                return null;
            }
        }

        // dbeaver-mm K126: DISTINCT anahtar sözcüğü seçim listesinin ilk öğesinden ayıklanır
        boolean distinct = tokens.size() > 1 && isWord(tokens.get(1), "DISTINCT");
        List<String> selection = splitItems(text, tokens, depths, distinct ? 2 : 1, from);
        if (selection.isEmpty()) {
            return null;
        }

        // dbeaver-mm K127: GROUP BY sütunları ve seçim listesindeki toplamalar okunur
        List<String> groupColumns = List.of();
        int groupStart = findWord(tokens, depths, "GROUP", from + 1);
        if (groupStart >= 0) {
            if (groupStart + 1 >= tokens.size() || !isWord(tokens.get(groupStart + 1), "BY")) {
                return null;
            }
            int groupEnd = firstBoundary(tokens, depths, groupStart + 2);
            List<String> items = splitItems(text, tokens, depths, groupStart + 2, groupEnd);
            groupColumns = parseGroupColumns(items);
            if (groupColumns == null) {
                return null;
            }
        }
        List<Aggregate> aggregates = parseAggregates(selection);
        if (aggregates == null) {
            return null;
        }

        Parser parser = new Parser(text, tokens, depths, from + 1, fromEnd);
        if (!parser.parseFrom()) {
            return null;
        }
        for (String groupColumn : groupColumns) {
            if (!parser.aliases.containsKey(aliasOfColumn(groupColumn))) {
                return null;
            }
        }
        for (Aggregate aggregate : aggregates) {
            if (aggregate.column() != null && !parser.aliases.containsKey(aliasOfColumn(aggregate.column()))) {
                return null;
            }
        }
        if (!groupColumns.isEmpty() || !aggregates.isEmpty()) {
            if (!validGroupedSelection(selection, groupColumns, aggregates)) {
                return null;
            }
        }
        // dbeaver-mm K132: HAVING yalnız toplama <karşılaştırma> sabit ve AND birleşimleriyle desteklenir
        String havingText = "";
        List<Having> havingFilters = List.of();
        if (havingStart >= 0) {
            if (havingStart + 1 >= tokens.size()) {
                return null;
            }
            int havingEnd = firstBoundary(tokens, depths, havingStart + 1);
            if (havingStart + 1 >= havingEnd) {
                return null;
            }
            havingText = text.substring(
                tokens.get(havingStart + 1).start,
                tokens.get(havingEnd - 1).end
            ).trim();
            havingFilters = parser.parseHaving(havingStart + 1, havingEnd);
            if (havingFilters == null) {
                return null;
            }
        }
        // dbeaver-mm K133: dış join'in null tarafındaki terimler kaynağa itilmez
        Set<String> nullableAliases = nullableAliases(parser.tables, parser.joins);
        String remaining = "";
        if (whereStart >= 0) {
            remaining = parser.applyWhere(whereStart + 1, whereEnd, nullableAliases);
            if (remaining == null) {
                return null;
            }
        }
        // dbeaver-mm K125: ORDER BY / LIMIT / OFFSET / DISTINCT plana okunur
        List<Order> orderBy = List.of();
        int orderStart = findWord(tokens, depths, "ORDER", from + 1);
        if (orderStart >= 0) {
            if (orderStart + 1 >= tokens.size() || !isWord(tokens.get(orderStart + 1), "BY")) {
                return null;
            }
            int orderEnd = firstBoundary(tokens, depths, orderStart + 2);
            List<String> items = splitItems(text, tokens, depths, orderStart + 2, orderEnd);
            if (items.isEmpty()) {
                return null;
            }
            orderBy = parseOrder(items, parser.aliases);
            if (orderBy == null) {
                return null;
            }
        }
        Long limit = null;
        int limitStart = findWord(tokens, depths, "LIMIT", from + 1);
        if (limitStart >= 0) {
            limit = parseCount(tokens, limitStart + 1);
            if (limit == null) {
                return null;
            }
        }
        Long offset = null;
        int offsetStart = findWord(tokens, depths, "OFFSET", from + 1);
        if (offsetStart >= 0) {
            offset = parseCount(tokens, offsetStart + 1);
            if (offset == null) {
                return null;
            }
        }
        return new FedPlan(
            List.copyOf(parser.tables),
            List.copyOf(parser.joins),
            remaining,
            selection,
            List.copyOf(orderBy),
            limit,
            offset,
            distinct,
            List.copyOf(groupColumns),
            List.copyOf(aggregates),
            havingText,
            List.copyOf(havingFilters)
        );
    }

    // dbeaver-mm K133: dış join'in null tarafı olan aliaslar
    private static Set<String> nullableAliases(List<Table> tables, List<Join> joins) {
        Set<String> result = new LinkedHashSet<>();
        for (int j = 0; j < joins.size(); j++) {
            Join join = joins.get(j);
            String type = join.type();
            boolean left = "LEFT".equalsIgnoreCase(type);
            boolean outer = left || "RIGHT".equalsIgnoreCase(type);
            if (join.keys().isEmpty() || !outer || j + 1 >= tables.size()) {
                continue;
            }
            String newAlias = tables.get(j + 1).alias();
            Key key = join.keys().get(0);
            String leftAlias = aliasOfColumn(key.left());
            String rightAlias = aliasOfColumn(key.right());
            boolean newIsRight = rightAlias.equalsIgnoreCase(newAlias);
            result.add(left == newIsRight ? rightAlias : leftAlias);
        }
        return result;
    }

    private static List<String> parseGroupColumns(List<String> items) {
        if (items.isEmpty()) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (String item : items) {
            String column = item.trim();
            int dot = column.indexOf('.');
            if (dot <= 0 || dot != column.lastIndexOf('.') || dot == column.length() - 1) {
                return null;
            }
            String alias = unquote(column.substring(0, dot).trim());
            String name = unquote(column.substring(dot + 1).trim());
            if (alias.isEmpty() || name.isEmpty()) {
                return null;
            }
            result.add(alias + "." + name);
        }
        return result;
    }

    private static List<Aggregate> parseAggregates(List<String> selection) {
        List<Aggregate> result = new ArrayList<>();
        for (int i = 0; i < selection.size(); i++) {
            String base = selectionBase(selection.get(i));
            int open = base.indexOf('(');
            if (open < 0) {
                continue;
            }
            int close = base.lastIndexOf(')');
            if (open == 0 || close != base.length() - 1) {
                return null;
            }
            String function = upper(base.substring(0, open).trim());
            if (!AGGREGATE_FUNCTIONS.contains(function)) {
                return null;
            }
            String argument = base.substring(open + 1, close).trim();
            if (argument.equals("*")) {
                if (!function.equals("COUNT")) {
                    return null;
                }
                result.add(new Aggregate("COUNT_STAR", null, i));
                continue;
            }
            int dot = argument.indexOf('.');
            if (dot <= 0 || dot != argument.lastIndexOf('.') || dot == argument.length() - 1) {
                return null;
            }
            result.add(new Aggregate(function, argument, i));
        }
        return result;
    }

    private static boolean validGroupedSelection(
        List<String> selection,
        List<String> groupColumns,
        List<Aggregate> aggregates
    ) {
        for (int i = 0; i < selection.size(); i++) {
            boolean aggregate = false;
            for (Aggregate item : aggregates) {
                if (item.selectionIndex() == i) {
                    aggregate = true;
                    break;
                }
            }
            if (aggregate) {
                continue;
            }
            String base = selectionBase(selection.get(i));
            boolean grouped = false;
            for (String groupColumn : groupColumns) {
                if (groupColumn.equalsIgnoreCase(base)) {
                    grouped = true;
                    break;
                }
            }
            if (!grouped) {
                return false;
            }
        }
        return true;
    }

    private static String selectionBase(String selection) {
        String text = selection.trim();
        int space = text.indexOf(' ');
        return space < 0 ? text : text.substring(0, space).trim();
    }

    private static String aliasOfColumn(String column) {
        int dot = column.indexOf('.');
        return dot > 0 ? column.substring(0, dot) : column;
    }

    private static List<Order> parseOrder(List<String> items, Map<String, Integer> aliases) {
        List<Order> result = new ArrayList<>();
        for (String item : items) {
            String text = item.trim();
            boolean descending = false;
            int space = lastSpace(text);
            if (space > 0) {
                String direction = text.substring(space + 1);
                if (direction.equalsIgnoreCase("DESC")) {
                    descending = true;
                    text = text.substring(0, space).trim();
                } else if (direction.equalsIgnoreCase("ASC")) {
                    text = text.substring(0, space).trim();
                }
            }
            int dot = text.indexOf('.');
            if (dot <= 0 || dot == text.length() - 1) {
                return null;
            }
            String alias = unquote(text.substring(0, dot).trim());
            if (!aliases.containsKey(alias)) {
                return null;
            }
            String column = unquote(text.substring(dot + 1).trim());
            result.add(new Order(alias + "." + column, descending));
        }
        return result;
    }

    private static int lastSpace(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static Long parseCount(List<Token> tokens, int index) {
        if (index >= tokens.size()) {
            return null;
        }
        try {
            return Long.parseLong(tokens.get(index).text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int firstBoundary(List<Token> tokens, int[] depths, int start) {
        for (int i = start; i < tokens.size(); i++) {
            if (depths[i] == 0 && tokens.get(i).type == WORD
                && BOUNDARIES.contains(upper(tokens.get(i).text))) {
                return i;
            }
        }
        return tokens.size();
    }

    private static int findWord(List<Token> tokens, int[] depths, String word, int start) {
        for (int i = start; i < tokens.size(); i++) {
            if (depths[i] == 0 && isWord(tokens.get(i), word)) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> splitItems(String text, List<Token> tokens, int[] depths, int start, int end) {
        List<String> items = new ArrayList<>();
        int itemStart = -1;
        for (int i = start; i <= end; i++) {
            boolean atEnd = i == end;
            if (!atEnd && (depths[i] != 0 || !tokens.get(i).text.equals(","))) {
                if (itemStart < 0) {
                    itemStart = i;
                }
                continue;
            }
            if (itemStart >= 0) {
                String item = text.substring(tokens.get(itemStart).start, tokens.get(i - 1).end).trim();
                if (!item.isEmpty()) {
                    items.add(item);
                }
            }
            itemStart = -1;
        }
        return items;
    }

    private static int[] depths(List<Token> tokens) {
        int[] result = new int[tokens.size()];
        int current = 0;
        for (int i = 0; i < tokens.size(); i++) {
            result[i] = current;
            if (tokens.get(i).text.equals("(")) {
                current++;
            } else if (tokens.get(i).text.equals(")")) {
                current = Math.max(0, current - 1);
            }
        }
        return result;
    }

    private static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                continue;
            }
            int start = i;
            if (c == '\'') {
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                tokens.add(new Token(STRING, start, i, sql.substring(start, i)));
                continue;
            }
            if (c == '"' || c == '`') {
                char quote = c;
                i++;
                while (i < n) {
                    if (sql.charAt(i) == quote) {
                        if (i + 1 < n && sql.charAt(i + 1) == quote) {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                tokens.add(new Token(IDENT, start, i, sql.substring(start, i)));
                continue;
            }
            if (isWordChar(c)) {
                i++;
                while (i < n && isWordChar(sql.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(WORD, start, i, sql.substring(start, i)));
                continue;
            }
            String two = i + 1 < n ? sql.substring(i, i + 2) : "";
            if (two.equals(">=") || two.equals("<=") || two.equals("<>") || two.equals("!=")) {
                i += 2;
                tokens.add(new Token(SYMBOL, start, i, two));
                continue;
            }
            i++;
            tokens.add(new Token(SYMBOL, start, i, String.valueOf(c)));
        }
        return tokens;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '@';
    }

    private static boolean isWord(Token token, String word) {
        return token.type == WORD && token.text.equalsIgnoreCase(word);
    }

    private static boolean isName(Token token) {
        return token.type == WORD || token.type == IDENT;
    }

    private static boolean isColumnQualifier(Token token) {
        if (token.type == IDENT) {
            return true;
        }
        return token.type == WORD && !Character.isDigit(token.text.charAt(0));
    }

    private static String upper(String value) {
        return value.toUpperCase(Locale.ROOT);
    }

    // dbeaver-mm K132: HAVING karşılaştırma işleçlerini tek biçime getirir
    private static String comparison(String text) {
        return switch (text) {
            case "=", "<>", "<", ">", "<=", ">=" -> text;
            case "!=" -> "<>";
            default -> null;
        };
    }

    private static String unquote(String value) {
        if (value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' || first == '`') && first == last) {
            String inner = value.substring(1, value.length() - 1);
            return inner.replace(String.valueOf(first) + first, String.valueOf(first));
        }
        return value;
    }

    private static final class Token {
        private final int type;
        private final int start;
        private final int end;
        private final String text;

        private Token(int type, int start, int end, String text) {
            this.type = type;
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    private static final class Parser {
        private final String sql;
        private final List<Token> tokens;
        private final int[] depths;
        private final int end;
        private final List<Table> tables = new ArrayList<>();
        private final Map<String, Integer> aliases = new LinkedHashMap<>();
        private final List<Join> joins = new ArrayList<>();
        private int index;

        private Parser(String sql, List<Token> tokens, int[] depths, int start, int end) {
            this.sql = sql;
            this.tokens = tokens;
            this.depths = depths;
            this.index = start;
            this.end = end;
        }

        // dbeaver-mm K132: HAVING ifadelerini AND ile ayırıp her birini doğrular
        private List<Having> parseHaving(int start, int endExclusive) {
            List<int[]> terms = splitTerms(start, endExclusive, "AND", 0);
            if (terms.isEmpty()) {
                return null;
            }
            List<Having> result = new ArrayList<>();
            for (int[] term : terms) {
                Having having = parseHavingTerm(term[0], term[1]);
                if (having == null) {
                    return null;
                }
                result.add(having);
            }
            return result;
        }

        private Having parseHavingTerm(int start, int endExclusive) {
            int operatorIndex = -1;
            String operator = null;
            for (int i = start; i < endExclusive; i++) {
                if (depths[i] != 0) {
                    continue;
                }
                Token token = tokens.get(i);
                if (isWord(token, "OR")) {
                    return null;
                }
                String found = comparison(token.text);
                if (found != null) {
                    if (operatorIndex >= 0) {
                        return null;
                    }
                    operatorIndex = i;
                    operator = found;
                }
            }
            if (operatorIndex <= start || operatorIndex + 1 >= endExclusive) {
                return null;
            }
            Token functionToken = tokens.get(start);
            if (functionToken.type != WORD) {
                return null;
            }
            String function = upper(functionToken.text);
            if (!AGGREGATE_FUNCTIONS.contains(function)) {
                return null;
            }
            if (start + 1 >= operatorIndex || !tokens.get(start + 1).text.equals("(")) {
                return null;
            }
            if (!tokens.get(operatorIndex - 1).text.equals(")")) {
                return null;
            }
            int argStart = start + 2;
            int argEnd = operatorIndex - 1;
            String column = null;
            if (argEnd - argStart == 1 && tokens.get(argStart).text.equals("*")) {
                if (!function.equals("COUNT")) {
                    return null;
                }
                function = "COUNT_STAR";
            } else if (argEnd - argStart == 3
                && isName(tokens.get(argStart))
                && tokens.get(argStart + 1).text.equals(".")
                && isName(tokens.get(argStart + 2))) {
                String alias = unquote(tokens.get(argStart).text);
                if (!aliases.containsKey(alias)) {
                    return null;
                }
                column = alias + "." + unquote(tokens.get(argStart + 2).text);
            } else {
                return null;
            }
            String rightText = sql.substring(
                tokens.get(operatorIndex + 1).start,
                tokens.get(endExclusive - 1).end
            ).trim();
            String literal;
            if (rightText.length() >= 2 && rightText.charAt(0) == '\''
                && rightText.charAt(rightText.length() - 1) == '\'') {
                literal = rightText;
            } else {
                try {
                    new BigDecimal(rightText);
                } catch (NumberFormatException e) {
                    return null;
                }
                literal = rightText;
            }
            return new Having(function, column, operator, literal);
        }

        private boolean parseFrom() {
            Table first = parseTable();
            if (first == null) {
                return false;
            }
            tables.add(first);
            while (index < end) {
                if (tokens.get(index).text.equals(",")) {
                    index++;
                    Table table = parseTable();
                    if (table == null) {
                        return false;
                    }
                    tables.add(table);
                    joins.add(new Join("CROSS", List.of()));
                    continue;
                }
                String type = parseJoinType();
                if (type == null) {
                    return false;
                }
                Table table = parseTable();
                if (table == null) {
                    return false;
                }
                tables.add(table);
                List<Key> keys = new ArrayList<>();
                if (!type.equals("CROSS")) {
                    if (index >= end || !isWord(tokens.get(index), "ON")) {
                        return false;
                    }
                    index++;
                    int onEnd = findJoinEnd(index);
                    List<int[]> terms = splitTerms(index, onEnd, "AND", depths[index < end ? index : onEnd]);
                    if (terms.isEmpty()) {
                        return false;
                    }
                    for (int[] term : terms) {
                        Key key = parseKey(term[0], term[1]);
                        if (key == null) {
                            return false;
                        }
                        keys.add(key);
                    }
                    index = onEnd;
                }
                joins.add(new Join(type, keys));
            }
            return true;
        }

        private String parseJoinType() {
            if (index >= end) {
                return null;
            }
            Token token = tokens.get(index);
            if (isWord(token, "JOIN")) {
                index++;
                return "INNER";
            }
            if (isWord(token, "INNER")) {
                index++;
                return expectJoin() ? "INNER" : null;
            }
            if (isWord(token, "LEFT") || isWord(token, "RIGHT") || isWord(token, "FULL")) {
                String type = upper(token.text);
                index++;
                if (index < end && isWord(tokens.get(index), "OUTER")) {
                    index++;
                }
                return expectJoin() ? type : null;
            }
            if (isWord(token, "CROSS")) {
                index++;
                return expectJoin() ? "CROSS" : null;
            }
            return null;
        }

        private boolean expectJoin() {
            if (index < end && isWord(tokens.get(index), "JOIN")) {
                index++;
                return true;
            }
            return false;
        }

        private int findJoinEnd(int start) {
            int base = start < end ? depths[start] : 0;
            for (int i = start; i < end; i++) {
                if (depths[i] == base) {
                    Token token = tokens.get(i);
                    if (token.text.equals(",") || (token.type == WORD
                        && JOIN_STARTERS.contains(upper(token.text)))) {
                        return i;
                    }
                }
            }
            return end;
        }

        private Key parseKey(int start, int endExclusive) {
            int base = start < endExclusive ? depths[start] : 0;
            int equals = -1;
            for (int i = start; i < endExclusive; i++) {
                if (depths[i] != base) {
                    continue;
                }
                Token token = tokens.get(i);
                if (token.text.equals("=")) {
                    if (equals >= 0) {
                        return null;
                    }
                    equals = i;
                } else if (token.text.equals(">") || token.text.equals("<")
                    || token.text.equals(">=") || token.text.equals("<=")
                    || token.text.equals("<>") || token.text.equals("!=")
                    || isWord(token, "OR")) {
                    return null;
                }
            }
            if (equals < 0) {
                return null;
            }
            String left = reference(start, equals);
            String right = reference(equals + 1, endExclusive);
            if (left == null || right == null) {
                return null;
            }
            return new Key(left, right);
        }

        private String reference(int start, int endExclusive) {
            if (endExclusive - start != 3) {
                return null;
            }
            Token alias = tokens.get(start);
            Token dot = tokens.get(start + 1);
            Token column = tokens.get(start + 2);
            if (!isName(alias) || !dot.text.equals(".") || !isName(column)) {
                return null;
            }
            String name = unquote(alias.text);
            if (!aliases.containsKey(name)) {
                return null;
            }
            return name + "." + unquote(column.text);
        }

        private Table parseTable() {
            List<String> parts = readName();
            if (parts == null) {
                return null;
            }
            int last = parts.size() - 1;
            String name = parts.get(last);
            String code = parts.size() > 1 ? String.join(".", parts.subList(0, last)) : null;
            String alias = name;
            if (index < end) {
                Token token = tokens.get(index);
                if (isWord(token, "AS")) {
                    index++;
                    if (index >= end || !isName(tokens.get(index))) {
                        return null;
                    }
                    alias = unquote(tokens.get(index).text);
                    index++;
                } else if (isName(token)
                    && !(token.type == WORD && TABLE_KEYWORDS.contains(upper(token.text)))) {
                    alias = unquote(token.text);
                    index++;
                }
            }
            int tableIndex = tables.size();
            Table table = new Table(code, name, alias, "");
            aliases.put(alias, tableIndex);
            return table;
        }

        private List<String> readName() {
            if (index >= end || !isName(tokens.get(index))) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            parts.add(unquote(tokens.get(index).text));
            index++;
            while (index + 1 < end && tokens.get(index).text.equals(".")
                && isName(tokens.get(index + 1))) {
                parts.add(unquote(tokens.get(index + 1).text));
                index += 2;
            }
            return parts;
        }

        // dbeaver-mm K133: OR içeren WHERE bütünüyle kalır; null taraf terimi de itilmez
        private String applyWhere(int start, int endExclusive, Set<String> nullableAliases) {
            if (start >= endExclusive) {
                return "";
            }
            for (int i = start; i < endExclusive; i++) {
                if (depths[i] == 0 && isWord(tokens.get(i), "OR")) {
                    return sql.substring(tokens.get(start).start, tokens.get(endExclusive - 1).end).trim();
                }
            }
            // dbeaver-mm K133: BETWEEN ... AND ... tek terimdir; AND ayırıcı sayılmaz
            List<int[]> terms = splitWhereTerms(start, endExclusive);
            StringBuilder remaining = new StringBuilder();
            for (int[] term : terms) {
                String text = sql.substring(tokens.get(term[0]).start, tokens.get(term[1] - 1).end).trim();
                if (text.isEmpty()) {
                    continue;
                }
                Set<String> referenced = referencedAliases(term[0], term[1]);
                if (referenced.size() == 1) {
                    String alias = referenced.iterator().next();
                    Integer tableIndex = aliases.get(alias);
                    if (tableIndex != null && !nullableAliases.contains(alias)) {
                        pushWhere(tableIndex, text);
                        continue;
                    }
                }
                if (remaining.length() > 0) {
                    remaining.append(" AND ");
                }
                remaining.append(text);
            }
            return remaining.toString();
        }

        private Set<String> referencedAliases(int start, int endExclusive) {
            Set<String> result = new LinkedHashSet<>();
            for (int i = start; i + 2 < endExclusive; i++) {
                Token alias = tokens.get(i);
                Token dot = tokens.get(i + 1);
                Token column = tokens.get(i + 2);
                if (isColumnQualifier(alias) && dot.text.equals(".") && isName(column)) {
                    result.add(unquote(alias.text));
                }
            }
            return result;
        }

        private void pushWhere(int tableIndex, String text) {
            Table table = tables.get(tableIndex);
            String pushed = table.pushedWhere().isEmpty()
                ? text
                : table.pushedWhere() + " AND " + text;
            tables.set(tableIndex, new Table(table.code(), table.name(), table.alias(), pushed));
        }

        private List<int[]> splitTerms(int start, int endExclusive, String keyword, int base) {
            List<int[]> terms = new ArrayList<>();
            int termStart = start;
            for (int i = start; i < endExclusive; i++) {
                if (depths[i] == base && isWord(tokens.get(i), keyword)) {
                    if (i > termStart) {
                        terms.add(new int[]{termStart, i});
                    }
                    termStart = i + 1;
                }
            }
            if (endExclusive > termStart) {
                terms.add(new int[]{termStart, endExclusive});
            }
            return terms;
        }

        // dbeaver-mm K133: WHERE terimlerini böler; BETWEEN'in AND'i ayırıcı sayılmaz
        private List<int[]> splitWhereTerms(int start, int endExclusive) {
            List<int[]> terms = new ArrayList<>();
            int termStart = start;
            int pendingBetween = 0;
            for (int i = start; i < endExclusive; i++) {
                if (depths[i] != 0) {
                    continue;
                }
                if (isWord(tokens.get(i), "BETWEEN")) {
                    pendingBetween++;
                } else if (isWord(tokens.get(i), "AND")) {
                    if (pendingBetween > 0) {
                        pendingBetween--;
                        continue;
                    }
                    if (i > termStart) {
                        terms.add(new int[]{termStart, i});
                    }
                    termStart = i + 1;
                }
            }
            if (endExclusive > termStart) {
                terms.add(new int[]{termStart, endExclusive});
            }
            return terms;
        }
    }
}
