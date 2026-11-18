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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * dbeaver-mm K133: kalan WHERE ifadesini birleşik satırlar üzerinde bellekte süzen saf filtre.
 * <p>
 * Yalnız {@code java.*} kullanır. Dil: AND/OR/NOT, parantez, {@code = <> != < > <= >=},
 * LIKE (% ve _), IN (sabit liste), IS [NOT] NULL ve BETWEEN. Operandlar {@code alias.kolon},
 * sayı veya {@code 'metin'} olabilir; karşılaştırmada sayısal değer önce denenir,
 * NULL karşılaştırması false döner.
 */
public final class FedFilter {

    private FedFilter() {
    }

    /**
     * WHERE metnini satır filtresine derler.
     *
     * @param where WHERE metni (boşsa her satırı geçen filtre)
     * @param positionOfColumn {@code alias.kolon} için birleşik satırdaki konum
     * @return filtre; metin çözülemezse veya bilinmeyen kolon varsa {@code null}
     */
    public static Predicate<Object[]> compile(String where, Function<String, Integer> positionOfColumn) {
        if (where == null || where.isBlank()) {
            return row -> true;
        }
        try {
            Parser parser = new Parser(where, positionOfColumn);
            Predicate<Object[]> result = parser.parseOr();
            parser.skipWhitespace();
            if (!parser.atEnd()) {
                return null;
            }
            return result;
        } catch (ParseException e) {
            return null;
        }
    }

    /**
     * dbeaver-mm K135: federe sonucun bellek satırlarına grid filtresi (WHERE metni) uygular.
     * <p>
     * Kolonlar görünen adlarıyla eşleşir (büyük/küçük harf duyarsız); çıplak ya da tırnaklı
     * kolon adı kabul edilir. Boş kriter tüm satırları döner.
     *
     * @param columns satır sütunlarının adları, satır dizisiyle aynı sırada
     * @param rows süzülecek satırlar
     * @param criteria WHERE metni
     * @return süzülmüş satırlar; bilinmeyen kolon ya da derlenemeyen kriterde {@code null}
     */
    public static List<Object[]> apply(List<String> columns, List<Object[]> rows, String criteria) {
        if (criteria == null || criteria.isBlank()) {
            return new ArrayList<>(rows);
        }
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            positions.putIfAbsent(columns.get(i).toLowerCase(Locale.ROOT), i);
        }
        Predicate<Object[]> filter = compile(criteria, positions::get);
        if (filter == null) {
            return null;
        }
        List<Object[]> result = new ArrayList<>();
        for (Object[] row : rows) {
            if (filter.test(row)) {
                result.add(row);
            }
        }
        return result;
    }

    /** Satırdaki bir değer; kolon konumu ya da derleme zamanı sabiti. */
    private interface Value {
        Object get(Object[] row);
    }

    private static Value column(int index) {
        return row -> row[index];
    }

    private static Value constant(Object value) {
        return row -> value;
    }

    private static Predicate<Object[]> and(Predicate<Object[]> left, Predicate<Object[]> right) {
        return row -> left.test(row) && right.test(row);
    }

    private static Predicate<Object[]> or(Predicate<Object[]> left, Predicate<Object[]> right) {
        return row -> left.test(row) || right.test(row);
    }

    private static Predicate<Object[]> not(Predicate<Object[]> inner) {
        return row -> !inner.test(row);
    }

    private static Predicate<Object[]> comparison(Value left, Value right, String operator) {
        return row -> {
            Integer result = compare(left.get(row), right.get(row));
            if (result == null) {
                return false;
            }
            return switch (operator) {
                case "=" -> result == 0;
                case "<>" -> result != 0;
                case "<" -> result < 0;
                case ">" -> result > 0;
                case "<=" -> result <= 0;
                case ">=" -> result >= 0;
                default -> false;
            };
        };
    }

    private static Predicate<Object[]> isNull(Value value, boolean negated) {
        return row -> (value.get(row) == null) != negated;
    }

    private static Predicate<Object[]> between(Value value, Value low, Value high) {
        return row -> {
            Integer from = compare(value.get(row), low.get(row));
            Integer to = compare(value.get(row), high.get(row));
            return from != null && to != null && from >= 0 && to <= 0;
        };
    }

    private static Predicate<Object[]> in(Value value, List<Value> options) {
        return row -> {
            Object candidate = value.get(row);
            if (candidate == null) {
                return false;
            }
            for (Value option : options) {
                Integer result = compare(candidate, option.get(row));
                if (result != null && result == 0) {
                    return true;
                }
            }
            return false;
        };
    }

    private static Predicate<Object[]> like(Value value, Value pattern) {
        return row -> {
            Object text = value.get(row);
            Object mask = pattern.get(row);
            if (text == null || mask == null) {
                return false;
            }
            return matches(String.valueOf(text), String.valueOf(mask));
        };
    }

    /** LIKE maskesini % ve _ için düzenli ifadeye çevirir. */
    private static boolean matches(String text, String mask) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < mask.length(); i++) {
            char c = mask.charAt(i);
            if (c == '%') {
                regex.append(".*");
            } else if (c == '_') {
                regex.append('.');
            } else if ("\\.[]{}()*+?^$|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(text).matches();
    }

    /**
     * İki değeri karşılaştırır; sayısal olarak çözülebilirlerse sayısal, yoksa metinsel.
     * Herhangi bir taraf NULL ise {@code null} döner.
     */
    private static Integer compare(Object left, Object right) {
        if (left == null || right == null) {
            return null;
        }
        BigDecimal leftNumber = number(left);
        BigDecimal rightNumber = number(right);
        if (leftNumber != null && rightNumber != null) {
            return leftNumber.compareTo(rightNumber);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private static BigDecimal number(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number) {
            try {
                return new BigDecimal(value.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class Parser {
        private final String text;
        private final Function<String, Integer> positions;
        private int index;

        private Parser(String text, Function<String, Integer> positions) {
            this.text = text;
            this.positions = positions;
        }

        private Predicate<Object[]> parseOr() {
            Predicate<Object[]> result = parseAnd();
            while (matchWord("OR")) {
                result = or(result, parseAnd());
            }
            return result;
        }

        private Predicate<Object[]> parseAnd() {
            Predicate<Object[]> result = parseNot();
            while (matchWord("AND")) {
                result = and(result, parseNot());
            }
            return result;
        }

        private Predicate<Object[]> parseNot() {
            if (matchWord("NOT")) {
                return not(parseNot());
            }
            return parsePrimary();
        }

        private Predicate<Object[]> parsePrimary() {
            skipWhitespace();
            if (!atEnd() && text.charAt(index) == '(') {
                index++;
                Predicate<Object[]> inner = parseOr();
                expect(')');
                return inner;
            }
            return parsePredicate();
        }

        private Predicate<Object[]> parsePredicate() {
            Value left = parseOperand();
            if (matchWord("IS")) {
                boolean negated = matchWord("NOT");
                expectWord("NULL");
                return isNull(left, negated);
            }
            if (matchWord("BETWEEN")) {
                Value low = parseOperand();
                expectWord("AND");
                Value high = parseOperand();
                return between(left, low, high);
            }
            if (matchWord("IN")) {
                expect('(');
                List<Value> options = new ArrayList<>();
                if (!peekClose()) {
                    options.add(parseOperand());
                    while (match(',')) {
                        options.add(parseOperand());
                    }
                }
                expect(')');
                return in(left, options);
            }
            if (matchWord("LIKE")) {
                return like(left, parseOperand());
            }
            String operator = matchComparison();
            if (operator == null) {
                throw new ParseException();
            }
            return comparison(left, parseOperand(), operator);
        }

        private Value parseOperand() {
            skipWhitespace();
            if (atEnd()) {
                throw new ParseException();
            }
            char c = text.charAt(index);
            if (c == '\'') {
                return constant(readString());
            }
            if (c == '-' || c == '+' || Character.isDigit(c)) {
                return constant(readNumber());
            }
            // dbeaver-mm K135: tırnaklı kolon adı (grid filtresi "kolon" biçiminde üretir)
            if (c == '"' || c == '`') {
                return column(lookup(readQuotedName(c)));
            }
            if (Character.isLetter(c) || c == '_') {
                String alias = readIdentifier();
                skipWhitespace();
                if (index < text.length() && text.charAt(index) == '.') {
                    index++;
                    skipWhitespace();
                    String column = readIdentifier();
                    return column(lookup(alias + "." + column));
                }
                // dbeaver-mm K135: çıplak kolon adı; yalnız sütun listesinde varsa geçerli
                return column(lookup(alias));
            }
            throw new ParseException();
        }

        // dbeaver-mm K135: kolon adını konuma çevirir; bulunamazsa ayrıştırma hatası
        private int lookup(String name) {
            Integer position = positions.apply(name.toLowerCase(Locale.ROOT));
            if (position == null) {
                throw new ParseException();
            }
            return position;
        }

        // dbeaver-mm K135: tırnaklı tanımlayıcıyı okur; kapanmazsa hata
        private String readQuotedName(char quote) {
            index++;
            int start = index;
            while (index < text.length() && text.charAt(index) != quote) {
                index++;
            }
            if (index >= text.length()) {
                throw new ParseException();
            }
            String name = text.substring(start, index);
            index++;
            return name;
        }

        private String readIdentifier() {
            skipWhitespace();
            if (atEnd() || !isIdentifierStart(text.charAt(index))) {
                throw new ParseException();
            }
            int start = index;
            while (index < text.length() && isIdentifierPart(text.charAt(index))) {
                index++;
            }
            return text.substring(start, index);
        }

        private String readString() {
            index++;
            StringBuilder result = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == '\'') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '\'') {
                        result.append('\'');
                        index += 2;
                        continue;
                    }
                    index++;
                    return result.toString();
                }
                result.append(c);
                index++;
            }
            throw new ParseException();
        }

        private BigDecimal readNumber() {
            int start = index;
            if (text.charAt(index) == '-' || text.charAt(index) == '+') {
                index++;
            }
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            if (index < text.length() && text.charAt(index) == '.') {
                index++;
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                int mark = index;
                index++;
                if (index < text.length() && (text.charAt(index) == '-' || text.charAt(index) == '+')) {
                    index++;
                }
                if (index < text.length() && Character.isDigit(text.charAt(index))) {
                    while (index < text.length() && Character.isDigit(text.charAt(index))) {
                        index++;
                    }
                } else {
                    index = mark;
                }
            }
            try {
                return new BigDecimal(text.substring(start, index));
            } catch (NumberFormatException e) {
                throw new ParseException();
            }
        }

        private String matchComparison() {
            skipWhitespace();
            if (atEnd()) {
                return null;
            }
            String two = index + 1 < text.length() ? text.substring(index, index + 2) : "";
            switch (two) {
                case "<=":
                case ">=":
                case "<>":
                case "!=":
                    index += 2;
                    return two.equals("!=") ? "<>" : two;
                default:
                    break;
            }
            char c = text.charAt(index);
            if (c == '=' || c == '<' || c == '>') {
                index++;
                return String.valueOf(c);
            }
            return null;
        }

        private boolean matchWord(String word) {
            skipWhitespace();
            int mark = index;
            if (!atEnd() && isIdentifierStart(text.charAt(index))) {
                int start = index;
                while (index < text.length() && isIdentifierPart(text.charAt(index))) {
                    index++;
                }
                if (text.substring(start, index).equalsIgnoreCase(word)) {
                    return true;
                }
            }
            index = mark;
            return false;
        }

        private void expectWord(String word) {
            if (!matchWord(word)) {
                throw new ParseException();
            }
        }

        private boolean match(char c) {
            skipWhitespace();
            if (!atEnd() && text.charAt(index) == c) {
                index++;
                return true;
            }
            return false;
        }

        private void expect(char c) {
            skipWhitespace();
            if (atEnd() || text.charAt(index) != c) {
                throw new ParseException();
            }
            index++;
        }

        private boolean peekClose() {
            skipWhitespace();
            return !atEnd() && text.charAt(index) == ')';
        }

        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private boolean atEnd() {
            return index >= text.length();
        }

        private static boolean isIdentifierStart(char c) {
            return Character.isLetter(c) || c == '_';
        }

        private static boolean isIdentifierPart(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '$';
        }
    }

    private static final class ParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
