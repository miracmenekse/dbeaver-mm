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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * dbeaver-mm K135: federe OLMAYAN sorgular için Türkçe neden metni ve satır sınırı açıklaması.
 * <p>
 * Yalnız {@code java.*} kullanır. Metin taraması basittir: önce string literal ve tırnaklı
 * tanımlayıcılar silinir, böylece {@code 'union'} gibi değerler nedene dönüşmez.
 */
public final class FedExplain {

    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern QUOTED_IDENTIFIER = Pattern.compile("\"[^\"]*\"");
    private static final Pattern WITH_START = Pattern.compile("^\\s*WITH\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNION = Pattern.compile("\\bUNION\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBQUERY = Pattern.compile("\\(\\s*SELECT\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FULL_JOIN = Pattern.compile(
        "\\bFULL\\s+(OUTER\\s+)?JOIN\\b", Pattern.CASE_INSENSITIVE);

    private FedExplain() {
    }

    /**
     * Sorgunun federe edilemeyişinin nedenini verir.
     *
     * @param sql SQL metni
     * @return federe OLMAYAN nedenin Türkçe metni, desteklenen ya da boş sorguda {@code null}
     */
    public static String reason(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        String code = STRING_LITERAL.matcher(sql).replaceAll("''");
        code = QUOTED_IDENTIFIER.matcher(code).replaceAll(Matcher.quoteReplacement("\"x\""));
        if (WITH_START.matcher(code).find()) {
            return "CTE desteklenmez: WITH (ortak tablo ifadesi) federe edilemez";
        }
        if (UNION.matcher(code).find()) {
            return "UNION desteklenmez: birleşik sonuç federe edilemez";
        }
        if (SUBQUERY.matcher(code).find()) {
            return "alt sorgu desteklenmez: iç içe SELECT federe edilemez";
        }
        if (FULL_JOIN.matcher(code).find()) {
            return "FULL JOIN desteklenmez: yalnız INNER, LEFT ve RIGHT JOIN federe edilir";
        }
        return null;
    }

    /**
     * Satır sınırı aşıldığında gösterilecek metin.
     *
     * @param table tablo adı
     * @param rows satır sınırı
     * @return sınır ve tablo adını içeren Türkçe metin
     */
    public static String limitReason(String table, long rows) {
        return "satır sınırı: " + table + " en fazla " + rows + " satır federe edilebilir";
    }
}
