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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * dbeaver-mm K120: tablo→bağlantı eşlemesinin saf (DBeaver'siz) kısmı.
 * <p>
 * Aday bağlantı listelerini mümkün olduğunca tek bağlantıya indirger. Belirsiz kalan
 * tablolar kayda alınmaz; kararı çağıran (kullanıcıya sorarak) verir.
 */
public final class FedResolve {

    private FedResolve() {
    }

    /**
     * Her tablo için tek adayı, çok aday varsa hatırlananı seçer.
     *
     * @param candidates tablo → aday bağlantı kimlikleri
     * @param remembered tablo → daha önce seçilmiş bağlantı kimliği
     * @return yalnız çözülen tabloların tablo → bağlantı eşlemesi (boş olabilir, null olmaz)
     */
    public static Map<String, String> pick(Map<String, List<String>> candidates, Map<String, String> remembered) {
        Map<String, String> result = new LinkedHashMap<>();
        if (candidates == null || candidates.isEmpty()) {
            return result;
        }
        Map<String, String> memory = remembered == null ? Map.of() : remembered;
        for (Map.Entry<String, List<String>> entry : candidates.entrySet()) {
            List<String> list = entry.getValue();
            if (list == null || list.isEmpty()) {
                continue;
            }
            if (list.size() == 1) {
                result.put(entry.getKey(), list.get(0));
                continue;
            }
            String chosen = memory.get(entry.getKey());
            if (chosen != null && list.contains(chosen)) {
                result.put(entry.getKey(), chosen);
            }
        }
        return result;
    }
}
