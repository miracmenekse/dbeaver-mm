# PoC: FK kolonunda dictionary açıklaması gösterme — Durum

**Repo:** dbeaver-mm (DBeaver Community fork)
**Amaç:** Sonuç grid'inde bir FK kolonunda, hücredeki ID'nin yanına referans (dictionary) tablonun açıklamasını göstermek. Örn. `order_type_id = 2` → `2 | MAIN_ORDER`.

Ek olarak: her FK kolonu için hangi referans-kolonunun açıklama olarak gösterileceğini kullanıcı seçebilsin (kolon başlığındaki bir butonla), seçim kalıcı olsun.

---

## Ana dosya
`plugins/org.jkiss.dbeaver.ui.editors.data/src/org/jkiss/dbeaver/ui/controls/resultset/spreadsheet/SpreadsheetPresentation.java`

## Durum: ÇALIŞIYOR (temel özellik doğrulandı)
`bsn_inter_spec.order_type_id` kolonunda hücreler `2 | MAIN_ORDER`, `3 | RETURN` şeklinde görünüyor. Başlık butonu + kolon seçici eklendi ama çoklu-FK senaryosuyla canlı test devam ediyor.

---

## Bölüm 1 — Temel FK dictionary etiketi (tamamlandı)

`SpreadsheetPresentation.java` içinde:

1. **Cache alanları** (`log` alanının altında):
   - `fkDictCache`: `Map<DBDAttributeBinding, Map<Object, String>>` — attribute+değer başına açıklama.
   - `fkAssocCache`: `Map<DBDAttributeBinding, DBSEntityAssociation>` — kolonun FK-dictionary association'ı.
2. **`formatValue`** son `return` try-bloğu: `getValueDisplayString(...)` sonucuna `getFkDictionaryLabel(attr, value)` varsa ` | <label>` ekleniyor.
3. **`getFkDictionaryLabel(attr, value)`**: FK'yı `DBUtils.getAttributeReferrers(...)` ile DOĞRUDAN buluyor, referans tablo dictionary ise `dictionary.getDictionaryValues(...)` ile açıklamayı çekiyor.
4. **`clearMetaData()`**: `fkDictCache.clear()` + `fkAssocCache.clear()`.

### Kritik öğrenilenler (debug sonucu)
- **`ResultSetUtils.getEnumerableConstraint()` kullanılmadı**: render anında lazy çözümleme yüzünden `null` dönebiliyor. Bunun yerine `getAttributeReferrers` ile FK doğrudan bulunuyor (DBeaver'ın `ReferenceValueEditor.readEnum` mantığıyla aynı).
- **`null` ASLA cache'lenmiyor**: metadata ilk boyamada henüz yüklenmemişken lookup `null` döner; bu kalıcı cache'lenirse grid hep boş kalır. Sadece dolu sonuç cache'leniyor → metadata gelince kendi kendini düzeltiyor. (Asıl "çalışmıyor" sorunu buydu.)

---

## Bölüm 2 — Kolon başlığı butonu + kolon seçici (kod tamam, UI testi sürüyor)

Kullanıcı FK kolonu başlığındaki **"..." butonuna** (sort/filter oklarının solunda) basınca, referans tablonun kolonları bir menüde çıkıyor; seçilen kolon referans tablonun açıklama kolonu olarak **virtual model'e kalıcı** yazılıyor ve grid yenileniyor.

### Değişen 6 dosya (hepsi `plugins/org.jkiss.dbeaver.ui.editors.data/src/.../`)
- `ui/controls/lightgrid/GridColumnRenderer.java` — `IMAGE_FK_DICT` (UIIcon.DOTS_BUTTON) + `getFkDictControlBounds()` + paint bloğu (sort ikonunun solunda çizer).
- `ui/controls/lightgrid/IGridContentProvider.java` — `default boolean isElementSupportsFkDict(...)`.
- `ui/controls/lightgrid/GridColumn.java` — `isOverFkDictButton(x,y)` isabet testi.
- `ui/controls/lightgrid/LightGrid.java` — `columnBeingFkDict`, `hoveringOnColumnFkDict`, `Event_FkDictColumn = 1003`, hover algılama (handleHoverOnColumnHeader) + tıklama dispatch (onMouseUp).
- `ui/controls/resultset/spreadsheet/Spreadsheet.java` — `Event_FkDictColumn` dinleyici + `presentation.handleFkDictColumnClick(...)`.
- `ui/controls/resultset/spreadsheet/SpreadsheetPresentation.java` — `getFkDictAssociation(...)`, `ContentProvider.isElementSupportsFkDict(...)`, `handleFkDictColumnClick(...)`, `applyFkDictColumn(...)`.

### Kalıcılık mekanizması
Seçim, referans tablonun `DBVEntity.setDescriptionColumnNames(col)` + `persistConfiguration()` ile virtual model'e yazılıyor. Mevcut `getDictionaryValues` bu ayarı otomatik okuduğu için ekstra SQL yok.

### Tasarım kararı / bilinen sınır
Seçim **referans tablo** başına saklanıyor (DBeaver'ın native mekanizması). Yani aynı tabloya giden iki farklı FK kolonu aynı açıklama kolonunu paylaşır. Farklı tablolara giden FK'lar bağımsızdır. Katı "her FK-kolonu bağımsız" isteniyorsa: seçim `DBVEntityAttribute.properties`'e yazılıp değer özel sorguyla çekilmeli (henüz yapılmadı).

---

## Açık iş / sıradaki
- Başlık butonunun görünürlüğü ve tıklama isabet geometrisini canlı test et (piksel işi, ortamda doğrulanamadı).
- Çoklu-FK test senaryosu: `bsn_flow_spec_setup.sql` (repo kökünde) — `bsn_flow_spec` 3 ayrı sözlük tablosuna (`flow_status`, `priority_level`, `department`) FK ile bağlı. DB **SQLite**.
- Not: DDL sonrası DBeaver navigator otomatik yenilenmez → connection'a **F5**; tüm scripti çalıştırmak için **Alt+X**.

## Derleme / çalıştırma
1. Eclipse'i disk değişiklikleriyle senkronla (proje → Refresh/F5; "reload" sorulursa onayla).
2. **Project → Build All**, **Problems**'ta hata olmamalı.
3. DBeaver dev instance'ını kapat, **Debug As** ile başlat (Eclipse Application launch config; workspace bundle'ı çalıştığından emin ol).
4. `bsn_inter_spec` / `bsn_flow_spec` → Data sekmesi, gerekiyorsa F5.

## Geçici tanı logları
Kaldırıldı. Debug sırasında `getFkDictionaryLabel` içine `[FK-PoC]` önekli `log.warn` satırları eklenmişti; artık sadece `catch` içinde standart `log.debug` var.
