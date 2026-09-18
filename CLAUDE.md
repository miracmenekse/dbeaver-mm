# CLAUDE.md — dbeaver-mm geliştirme rehberi

> Bu dosya proje kökündedir ve Claude Code her oturum başında otomatik okur.
> Amaç: FK dictionary PoC'unda geliştirmeye devam edecek bir Claude Code oturumuna
> gereken tüm bağlamı vermek. Detaylı durum için ayrıca `POC_FK_DICTIONARY_STATUS.md`.

## Proje
DBeaver Community fork'u (Eclipse RCP / PDE tabanlı, Java). Üzerinde çalışılan PoC:
**Sonuç grid'inde bir FK kolonunda, hücredeki ID'nin yanına referans (dictionary)
tablonun açıklamasını göstermek** (`order_type_id = 2` → `2 | MAIN_ORDER`) ve
kullanıcının hangi referans-kolonunun gösterileceğini kolon başlığındaki bir butonla
seçip kalıcı kaydedebilmesi.

## Geliştirme ortamı ve derleme/çalıştırma (ÖNEMLİ)
- IDE: **Eclipse (PDE)**. Proje Eclipse workspace'ine import edilmiş (`dbeaver-mm`, `dbeaver-common`).
- Derleme **komut satırından değil, Eclipse içinden** yapılıyor: `Project → Build All`.
  (Tam Tycho/Maven CLI derlemesi bu akışta kullanılmıyor; sandbox/CLI'dan derlemeye çalışma.)
- Çalıştırma: **Run/Debug Configurations → Eclipse Application** launch config → **Debug As**.
  - Kritik: launch config, `org.jkiss.dbeaver.ui.editors.data` bundle'ının **workspace**
    sürümünü kullanmalı (Target Platform kopyasını değil). "Launch with: all workspace and
    enabled target plug-ins" en güvenlisi. Aksi halde kod değişiklikleri çalışan uygulamaya
    yansımaz (bu tuzağa bir kez düşüldü).
- Dosyayı IDE dışında (ör. bir agent) düzenledikten sonra Eclipse'te proje **Refresh (F5)**
  gerekebilir; "reload externally changed file" sorulursa onayla.
- Import düzenleme (Organize Imports / Ctrl+Shift+O) opsiyonel; PoC kodu bilinçli olarak
  çoğu yerde **tam-nitelikli (fully-qualified) tip adları** kullanıyor, o yüzden import şart değil.

## Ana dosyalar
Hepsi `plugins/org.jkiss.dbeaver.ui.editors.data/src/org/jkiss/dbeaver/ui/controls/` altında:
- `resultset/spreadsheet/SpreadsheetPresentation.java` — PoC'un ana yeri (label + buton mantığı).
- `resultset/spreadsheet/Spreadsheet.java` — LightGrid olaylarını presentation'a bağlar.
- `lightgrid/LightGrid.java` — özel grid widget'ı (başlık çizimi, hover, mouse olayları).
- `lightgrid/GridColumn.java` — kolon başlığı isabet testleri (`isOver...Button`).
- `lightgrid/GridColumnRenderer.java` — başlık ikonlarının çizimi.
- `lightgrid/IGridContentProvider.java` — grid içerik sağlayıcı arayüzü.

## Bu oturumda yapılanlar

### Bölüm 1 — FK dictionary etiketi (ÇALIŞIYOR, doğrulandı)
`SpreadsheetPresentation.java`:
- Alanlar: `fkDictCache` (`Map<DBDAttributeBinding, Map<Object,String>>`),
  `fkAssocCache` (`Map<DBDAttributeBinding, DBSEntityAssociation>`). `log` alanının altında.
- `formatValue(...)`: son `return` try-bloğunda display metnine `getFkDictionaryLabel(attr, value)`
  varsa ` | <label>` ekleniyor.
- `getFkDictionaryLabel(attr, value)`: `getFkDictAssociation(attr)` ile FK'yı bulur, referans tablo
  dictionary ise `dictionary.getDictionaryValues(monitor, [refColumn], [[value]], null,false,true,false)`
  ile açıklamayı çeker. Sadece dolu label cache'lenir.
- `getFkDictAssociation(attr)`: `DBUtils.getAttributeReferrers(monitor, entityAttribute, true)` ile
  FK referrer'ları gezer, `referencedConstraint.getParentObject() instanceof DBSDictionary` olanı döner.
  Sadece dolu sonucu cache'ler.
- `clearMetaData()`: iki cache'i de temizler.

### Bölüm 2 — Kolon başlığı "..." butonu + kolon seçici (kod tamam, canlı UI testi sürüyor)
Filter/sort ikonuyla **birebir aynı desen** kullanıldı:
- `GridColumnRenderer`: `IMAGE_FK_DICT = DBeaverIcons.getImage(UIIcon.DOTS_BUTTON)`,
  `getFkDictControlBounds()`, ve `paint()` içinde sort ikonunun **soluna** çizen blok.
- `IGridContentProvider`: `default boolean isElementSupportsFkDict(IGridColumn)` (tek uygulayıcı
  `SpreadsheetPresentation.ContentProvider`).
- `GridColumn`: `isOverFkDictButton(x,y)` — geometri: sağdan filter (varsa) + sort (varsa) genişliği
  çıkarılıp FK butonuna denk gelir.
- `LightGrid`: `columnBeingFkDict`, `hoveringOnColumnFkDict`, `Event_FkDictColumn = 1003`;
  `handleHoverOnColumnHeader`'da hover algılama; `onMouseUp`'ta `notifyListeners(Event_FkDictColumn, ...)`.
- `Spreadsheet`: `addListener(Event_FkDictColumn, this)` + switch'te `presentation.handleFkDictColumnClick(data)`.
- `SpreadsheetPresentation`:
  - `ContentProvider.isElementSupportsFkDict(el)` → `getFkDictAssociation(binding) != null`.
  - `handleFkDictColumnClick(el)`: referans tablonun kolonlarını SWT `Menu`(RADIO) ile listeler.
  - `applyFkDictColumn(refEntity, colName)`: `DBVUtils.getVirtualEntity(refEntity, true)` →
    `setDescriptionColumnNames(colName)` → `persistConfiguration()`; sonra `fkDictCache.clear()` +
    `spreadsheet.redrawGrid()`.

## Kullanılan kilit DBeaver API'leri
- `org.jkiss.dbeaver.model.DBUtils.getAttributeReferrers(monitor, entityAttribute, includeVirtual)`
- `DBUtils.getReferenceAttribute(monitor, association, tableColumn, false)`
- `DBSEntityAssociation.getReferencedConstraint().getParentObject()` → referans `DBSEntity` (aynı zamanda `DBSDictionary`)
- `DBSDictionary.getDictionaryValues(...)` — açıklama kolonunu referans tablonun virtual model
  ayarından (`DBVUtils.getDictionaryDescriptionColumns`) okur.
- `DBVUtils.getVirtualEntity(DBSEntity, create)`, `DBVEntity.setDescriptionColumnNames(String)` / `persistConfiguration()`
- LightGrid olay deseni: `notifyListeners(Event_X, event)` → `Spreadsheet.handleEvent` → `presentation.*`.
- Referans/çalışan örnek: `ui/data/editors/ReferenceValueEditor.java` (`readEnum` metodu) ve
  `ui/controls/resultset/ResultSetUtils.getEnumerableConstraint`.

## Kritik tuzaklar / öğrenilenler
1. **`ResultSetUtils.getEnumerableConstraint()` render anında `null` dönebilir** (lazy çözümleme +
   `DBSDictionary`/`supportsDictionaryEnumeration` kapısı). Bu yüzden FK doğrudan `getAttributeReferrers`
   ile bulunuyor. `ReferenceValueEditor.readEnum` ile aynı yol.
2. **`null` sonucu ASLA cache'leme.** Metadata ilk boyamada hazır değilken lookup `null` döner;
   kalıcı cache'lenirse grid/buton bir daha güncellenmez. Sadece dolu sonuç cache'lenir → metadata
   gelince kendini düzeltir. (Asıl "çalışmıyor" bug'ı buydu.)
3. **Başlık butonu geometrisi** filter+sort ikonlarına bağlı; `isOverFkDictButton` ile
   `GridColumnRenderer.paint` çizim sırası tutarlı olmalı (sağdan: filter, sort, sonra FK butonu).
   UI/piksel davranışı yalnızca çalışan uygulamada doğrulanabilir.
4. **PDE launch workspace bundle kullanmalı** (yukarıda). Kod çalışmıyorsa önce breakpoint ile
   kodun gerçekten yüklendiğini doğrula.
5. **Navigator önbelleği**: DDL sonrası tablolar görünmüyorsa connection'a **F5**; tüm SQL scriptini
   çalıştırmak için **Alt+X** (Ctrl+Enter sadece tek ifade çalıştırır).

## Bilinen tasarım sınırı
Kolon seçimi **referans tablo** başına saklanıyor (native mekanizma). Aynı tabloya giden iki FK kolonu
aynı açıklama kolonunu paylaşır. Katı "her FK-kolonu bağımsız" gerekiyorsa: seçimi referans tablo yerine
`DBVEntityAttribute.properties` (referencing kolonda) sakla ve değeri, açıklama kolonunu parametreyle
alan özel bir sorguyla çek (henüz yapılmadı — muhtemel sonraki adım).

## Test verisi (DB = SQLite, `mm_test_db`)
- Mevcut: `bsn_inter_spec` (kolon `order_type_id` → `order_type` dictionary; PoC burada doğrulandı).
- Çoklu-FK senaryosu: repo kökünde `bsn_flow_spec_setup.sql` — `bsn_flow_spec` üç ayrı sözlük tablosuna
  (`flow_status`, `priority_level`, `department`) FK ile bağlı. Alt+X ile çalıştır, sonra F5.

## Açık işler / sıradaki adımlar
- [ ] Başlık butonunun görünürlüğü + tıklama isabet geometrisini canlı test et; gerekiyorsa
      `isOverFkDictButton` / `GridColumnRenderer.paint` geometrisini ayarla.
- [ ] Çoklu-FK senaryosunu (`bsn_flow_spec`) uçtan uca test et.
- [ ] (Opsiyonel) Katı per-FK-kolon depolama (`DBVEntityAttribute.properties`) + özel açıklama sorgusu.
- [ ] SWT `Menu` her tıklamada yeniden yaratılıyor; küçük kaynak sızıntısını gidermek için
      `menuHidden`'da dispose eklenebilir (PoC için kritik değil).

---

# Recent SQL Scripts Panel (eklenti: `org.jkiss.dbeaver.ui.recentscripts`)

Kaynak karar notu: Obsidian `Projects/Dbeaver-mm/Recent-SQL-Scripts-Panel.md`.

Ana toolbar'ın en sağındaki simge sağa dock'lu bir View açar; View, aktif projenin **son 10**
`.sql` script'ini en son kaydedilen üstte kart listesi olarak gösterir. Kart = başlık (dosya adı)
+ description (ilk `--` yorum satırı) + 3 satır kod önizlemesi. Tek tık → SQL editöründe açar.

## Kritik bulgu — "recent" nereden geliyor
**DBeaver ayrı bir recent-script listesi TUTMUYOR.** `SQLEditorUtils.findRecentScript` bağlantıya
bağlı scriptler arasından `ResourceUtils.getResourceLastModified()` en büyük olanı seçer. Yani
recency = **dosyanın son değiştirilme zamanı**; bağlantı yalnızca bir filtre. Panel aynı ölçütü
bağlantı filtresi olmadan kullanıyor (`SQLEditorUtils.getScriptsFromProject` + aynı timestamp
çağrısı). Kendi zaman damgamızı tutmuyoruz.

## Dosya haritası
- `core/DBeaverScriptsBridge.java` — **DBeaver API'sine dokunan TEK sınıf.** Upstream değişirse
  sadece burası düzeltilir. Script listeleme, timestamp, boyut ve editörde açma burada.
- `core/ScriptPreviewParser.java` — `.sql` başlık ayrıştırma (saf fonksiyon, Eclipse'siz overload'ı
  test edilebilir). Yorum satırları önizlemeye ASLA girmez; `-----` cetvel satırı atlanır.
- `core/ScriptPreviewCache.java` — `(lastModified, size)` ile doğrulanan LRU; sadece timestamp
  yetmez (bazı FS'lerde 1 sn çözünürlük).
- `core/RecentScriptsService.java` — `limit` kapasiteli PriorityQueue ile sınırlı seçim; kaç bin
  script olursa olsun refresh başına en fazla 10 dosya okunur.
- `RecentScriptsView.java` — ViewPart + `IResourceChangeListener` + Refresh/F5.
- `ui/RecentScriptCard.java` — owner-drawn `Canvas` (focus + tab traversal için; `Label` olmaz).

### Favori (pin) mekanizması
- Her kartın sağ üstünde yıldız: dolu = favori, boş = değil. Tık (ya da odaktayken `F`) toggle.
- Favoriler listenin **üstüne sabitlenir** ve 10-recency sınırına takılmaz; altında ince ayraç,
  sonra "son kullanılanlar". `MAX_FAVORITES=50`.
- Persistence: proje resource property `recent-scripts.favorite=true`
  (`project-metadata.json`), **`DBeaverScriptsBridge.setFavorite/isFavorite` içinde izole**.
  Sidecar yok; `ScriptRef.favorite` bu propertyden okunur (dosya IO yok, in-memory map).
- Yıldızın hit-test'i kart gövdesi tıklamasından ÖNCE kontrol edilir (yıldıza tık açmaz, toggle eder).

## Bu eklentide tekrar düşülmemesi gereken tuzaklar
1. `SQLEditorUtils.getScriptsFromProject` **uzantı filtrelemiyor** — `Scripts` altındaki her dosyayı
   döndürür. `.sql` filtresi service'te ve zorunlu.
2. `SQLEditorHandlerOpenEditor`'ın üst sınıfı `org.jkiss.dbeaver.ui.navigator`'da ve
   `ui.editors.sql` onu **reexport etmiyor** → MANIFEST'te `ui.navigator` da gerekli.
3. `IActionBars` `org.eclipse.ui` paketinde, `org.eclipse.jface.action`'da değil.
4. `Canvas.computeSize` override edilmezse her kart 64x64 kare çizilir.
5. `resourceChanged` UI thread'inde DEĞİL → orada SWT çağrısı yok, `getContents()` yok.
6. `IFile.getContents()` yerine **`getContents(true)`** (bayat ağaçta `OUT_OF_SYNC_LOCAL` atar).
7. `UIStyles.mix/lighten/darken` her çağrıda `new Color` üretir → `PaintListener` içinden asla
   çağrılmaz; `UIUtils.getSharedColor(RGB)` ile intern edilir.
8. `BaseThemeSettings` fontları alanda cache'lenmez (tema değişiminde takas edilip dispose edilir).
9. `perspectiveExtensions` **kalıcılaşmış perspektife uygulanmaz** → mevcut workspace'te panel sağa
   düşmezse `Window → Reset Perspective`.
10. Yeni bundle PDE launch config'e **elle eklenmeli** (Plug-ins sekmesi → işaretle → Add Required
    Plug-ins). Aksi hâlde hata sessiz: toolbar butonu hiç çıkmaz.

## Derleme dışı doğrulama yöntemi (CLI'dan Eclipse build'e dokunmadan)
`javac` ile hızlı sözdizimi/API kontrolü yapılabilir: classpath =
`~/AppData/Local/DBeaver/plugins/*.jar` (org.jkiss.* hariç) + ilgili `plugins/*/target/classes` +
`../dbeaver-common/modules/org.jkiss.utils/target/classes`. JDK: `$JAVA_HOME` (Adoptium 21).
`ScriptPreviewParser.parse(BufferedReader)` Eclipse'siz olduğu için doğrudan çalıştırılıp test edilebilir.

---

## Kod konvansiyonları
- Her dosyada Apache 2.0 lisans başlığı var (yeni dosyada koru).
- Var olan dosyalar `model.*`, `model.struct.*`, `model.data.*` gibi **wildcard import** kullanıyor.
- PoC eklemeleri çakışmayı önlemek için çoğunlukla **fully-qualified** tip adları kullandı; bu bilinçli.
- Türkçe yorumlarda ASCII (Türkçe karakter yok) tercih edildi.

## Kalıcı proje kuralları
- **DBeaver internal API yerine mümkün olduğunca public / Eclipse standart API kullan.** Internal'a
  mecbur kalınırsa tek bir noktada izole et (örnek: `DBeaverScriptsBridge`).
- **Yeni özellikler Community sürümüyle uyumlu kalsın** — PRO/Enterprise API'sine (`com.dbeaver.*`
  bundle'ları) bağımlılık yaratma. `visibleWhen` içine `hasPermission` testi koyma; Community'de
  butonu gizleyen şey odur.
- Yeni bundle üç yere kaydedilir: `plugins/pom.xml` (**desktop profili**), ilgili `features/*/feature.xml`,
  ve geliştiricinin PDE launch config'i. `DBeaver.product` değişmez (`type="features"`).
- `Bundle-Version` (`x.y.z.qualifier`) ile pom `<version>` (`x.y.z-SNAPSHOT`) birebir eşleşmeli.
- Bağımlılıklar `pom.xml`e değil **`MANIFEST.MF` `Require-Bundle`**'a yazılır. Kaynak kökü `src/`
  (`src/main/java` değil).
- **Türkçe locale kuralı (J3) — TÜM kod için, sadece SQL değil.** No-arg `toLowerCase()` /
  `toUpperCase()` YASAK. Karşılaştırma için `toLowerCase(Locale.ROOT)` ya da `equalsIgnoreCase`;
  **arayüzde gösterilen metin** için de aynı (`"ID"` → `"ıd"`, `"INSERT"` → `"ınsert"` olur).
  Kolon başlığı büyütme gibi fikirler ya `Locale.ROOT` kullanır ya hiç yapılmaz. Testler de dahil:
  upstream'in `OpenAIModelsTest`'i bu makinede tam bu yüzden kırılıyordu (`o4-mini` → `O4-MİNİ`).
- **Çizim bütçesi (I1) — tüm eklentiler için:**
  1. `PaintListener` içinde asla `new Color` / `new Font` yok → `UIUtils.getSharedColor(RGB)`.
     (`UIStyles.mix/lighten/darken` da her çağrıda `new Color` üretir, paint içinde kullanılmaz.)
  2. Hover efektleri yalnızca değişen bölgeyi `redraw(x, y, w, h, false)` ile yeniler; tam `redraw()` yok.
  3. `BaseThemeSettings` fontları alanda cache'lenmez (tema değişiminde dispose edilir).
  4. Animasyon yok. Modern görünüm hizalama, boşluk ve kontrastla elde edilir.
- **Kod `javac` ile de derlenmeli.** Eclipse'in derleyicisi (ecj) bazı hataları kabul ediyor, CLI
  derlemesi (Tycho) ise `javac` kullanıyor. Örnek: anonim `LinkedHashMap` alt sınıfı içinde `Entry`
  adı dıştaki `record Entry`'yi değil `Map.Entry`'yi gösterir (ScriptPreviewCache, düzeltildi).
  Eclipse'te derlendi diye bitmiş sayma; commit öncesi CLI build'i çalıştır.
- **`.product` dosyasını Eclipse'in Product editöründe açıp kaydetme.** Editör dosyayı yeniden yazıyor
  (`includeLaunchers="false"` → CLI build `dbeaver` çalıştırılabilirini üretmez) ve
  `ui.app.standalone/plugin.xml`'den `windowImages`, `preferenceCustomization`, `aboutText`
  özelliklerini siliyor. v0.1'de bu oldu, güncel upstream'e taşırken geri alındı.
- **Upstream dosyasına dokunulan her yeri `dbeaver-mm` etiketli bir yorumla işaretle**
  (ör. `// dbeaver-mm G1: ...`), upstream güncellemelerinde çakışmayı bulmak kolaylaşır.

## Linux'ta CLI derleme / çalıştırma (2026-09)
- Depolar: `~/projects/{dbeaver,dbeaver-common,datadam-api}`. Aktif dal: `dbeaver-mm-v0.1-on-devel`
  (güncel upstream `devel` + dbeaver-mm commit'leri), `mm` remote'u = `miracmenekse/dbeaver-mm`.
- Tam derleme (`~/projects` içinden):
  `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./dbeaver-common/mvnw install -Pproduct-dbeaver-ce,product-dbeaver-eclipse-ce,appstore -T 1C -f dbeaver/product/aggregate`
  (`-DskipTests` ile ~40 sn, testlerle ~5 dk). İlk kez `tools/build.sh` kullanılacaksa
  `DATADAM_API_REF=devel` ver: script `main` dalını arıyor ama depoda `main` yok.
- Çıktı: `product/community/target/products/org.jkiss.dbeaver.core.product/linux/gtk/x86_64/dbeaver/dbeaver`.
  Derlemeden önce çalışan DBeaver'ı kapat (derleme aynı klasörün üzerine yazar).
- Perspektif değişikliklerini (B1, C2) görmek için: mevcut workspace'te `Window → Reset Perspective`,
  ya da `./dbeaver -data <boş klasör>` ile temiz bir workspace.
- SQLite bağlantısında yol tam yazılmalı (`/home/mirac/projects/mm_test_db`); `~` açılmaz → `SQLITE_CANTOPEN`.

---

# Inline FK ID Seçici (eklenti: `org.jkiss.dbeaver.ui.inlinefkpicker`)

Karar/handoff notu: repo `docs/inline-fk-id-picker.md` (asıl referans Obsidian
`Projects/Dbeaver-mm/Inline-FK-Id-Picker.md`).

SQL editöründe herhangi bir `<kolon> =` (veya `<kolon> IN (`) yazılınca, o kolonun ait olduğu
tablonun o kolondaki değerlerini küçük bir açılır listede gösterip seçileni tip-duyarlı biçimde
imlece yazan üretkenlik eklentisi.

> **ÖNEMLİ tasarım değişikliği (2026-08-15, canlı test sonrası kullanıcı kararı):** Eski `xxx_id →
> xxx` **isim kuralı KALDIRILDI** (var olmayan kolonda tetikleniyordu). Artık kolonun ait olduğu
> tablo, sorgunun **FROM/UPDATE listesinden ve alias'tan** çözümlenir; anahtar/eklenen değer o
> kolonun **kendi** değeridir (PK değil). Obsidian notu hâlâ eski kuralı anlatıyor olabilir —
> güncellenmesi gerekebilir.

## Kalıcı kurallar (bu özellik için)
- **Edisyon-bağımsız / Community uyumlu**; PRO/Enterprise özel API'sine (`com.dbeaver.*`) bağımlılık
  YOK. `visibleWhen`'e `hasPermission` koyma.
- **Okuma sorguları hafif ve LIMIT'li** (LIMIT 50 + WHERE LIKE + debounce); kullanıcının editördeki
  sorgusunu ASLA değiştirme — ayrı/geçici okuma yap.
- **Internal yerine stabil/public DBeaver API** tercih et; DBeaver API'sine dokunan tek sınıf
  `core/InlineFkService.java` (recentscripts'teki `DBeaverScriptsBridge` gibi izole edilmeli).
- **Tablo çözümleme = FROM/alias** (isim kuralı DEĞİL): `SqlCaretAnalyzer` statement'in FROM/UPDATE
  tablo referanslarını (ad + alias) ayrıştırır; `InlineFkService.resolveTarget` qualifier'a göre
  (alias veya tablo adı) tabloyu, kolonu da o tablonun gerçek kolonu olarak çözer (case-insensitive).
  Kolon hiçbir aday tabloda yoksa popup açılmaz.
- Statement sınırları tetikleyicide `SQLEditorBase.extractQueryAtPos` ile bulunup analyzer'a
  `analyze(text, caret, stmtStart, stmtEnd)` olarak verilir (FROM parsing yalnız aktif statement'ta).
- Öncelikli hedef DB: **PostgreSQL/MySQL** (SQLite ikincil). Dialect farkı DBeaver'ın
  `DBSDictionary.getDictionaryEnumeration` + `getCaseInsensitiveExpressionFormatter` katmanına bırakılır.

## Kilit API kararı
Hedef tablo çoğu JDBC datasource'ta `JDBCTable` → `DBSDictionary`'dir. **`getDictionaryEnumeration(...)`**
karşılaştırılan kolon `keyColumn` olarak verilir; `keyPattern = kullanıcının yazdığı filtre`,
`sortByValue = true` → `SELECT col, desc WHERE col LIKE ? ORDER BY col LIMIT 50` davranışı,
`List<DBDLabelValuePair>` (value=kolon değeri, label=otomatik açıklama kolonu) döner. Literal =
`SQLUtils.convertValueToSQL(dataSource, column, value)` (sayı düz, metin/UUID tırnaklı). `DBSDictionary`
değilse/`supportsDictionaryEnumeration()` false ise jenerik hafif `SELECT col FROM t` fallback'ine düşer.

## Dosya haritası
- `core/InlineFkService.java` — **DBeaver API'sine dokunan TEK sınıf**: `resolveEntity` (aktif şemada
  tablo), `resolveTarget` (FROM/alias → tablo+kolon), `enumerate` (kolon değerleri, LIKE+limit),
  `formatLiteral`. Upstream değişirse sadece burası.
- `core/SqlCaretAnalyzer.java` — saf parser (Eclipse'siz, test edilebilir): metin+caret+statement
  sınırı → `<kolon>`/`alias.<kolon>` + `=`/`IN` modu + FROM/UPDATE tablo referansları.
- `core/FkColumnRef.java` (kolon, qualifier, `TableRef` listesi, mod, offset), `core/FkRow.java` — modeller.
- `ui/FkPickerPopup.java` — kompakt SWT popup (filtre kutusu + Table; ok/scroll, Enter/Tab/Esc,
  IN için çoklu seçim).
- `trigger/OpenFkPickerHandler.java` — manuel command handler (keybinding).
- `trigger/InlineFkStartup.java` — `IStartup`; SQL editörlere `StyledText` dinleyicisi takar
  (`=`/`IN (` sonrası otomatik tetik). Çift-takmaya karşı `widget.setData` işaretiyle korunur.

## Script Parametre Formu (2026-08-15 eklendi) — panel ile ortak özellik
Kayıtlı bir scripti Recent SQL Scripts panelinden açınca **ortada bir form** açılır; statement'in
tüm `<kolon> = <literal|?>` koşulları listelenir, değerler FK picker listesinden seçilir.
Elle tetik: SQL editöründe **Ctrl+Alt+P** (`...inlinefkpicker.whereParams`).
- `core/SqlWhereAnalyzer.java` — saf/Eclipse'siz. Yorum + string literal **maskelenir** (offsetler
  korunur), paren-depth 0'da `=` aranır, `<=`/`>=`/`!=` elenir. Sağ taraf yalnız `?`, sayı veya
  tırnaklı metin ise koşul sayılır → **join predikatları (`a.id = b.id`) otomatik elenir**.
  Alt sorgular (parantez içi) kapsam dışı. `SqlCaretAnalyzer.parseTables` yeniden kullanılır.
- `ui/WhereParamsDialog.java` — Tree ile **WHERE koşulları / JOIN koşulları** diye iki grup
  (JOIN ON'daki `sale_cnl_id = 4` gibi gerçek filtreler gizlenmesin diye). Dolu değerler dolu
  gelir ve değiştirilebilir. Değer listesi `InlineFkService.enumerate` ile (aynı LIMIT 50 + LIKE).
  Çözülemeyen kolonda "Değer" kutusuna elle yazılabilir. **Sorguyu ÇALIŞTIRMAZ.**
- `ui/WhereParamsAction.java` — editör girişi; `extractQueryAtPos` ile **ilk statement**'a sınırlar
  (çok statement'lı scriptte koşullar/FROM karışmasın).
- **Doküman yazımı sağdan sola** (`valueStart` desc) yapılır; yoksa ilk değişiklik sonrakilerin
  offsetlerini kaydırır.
- Panel kartı önizlemesi artık **WHERE koşullarını** gösteriyor (`ScriptPreviewParser.parseText`);
  WHERE'i olmayan scriptlerde eski 3-satır kod önizlemesine düşer, en fazla 6 koşul + "... +N".

## Bu eklentide tuzaklar / notlar
0. **TÜRKÇE LOCALE / I-ı TUZAĞI (yakıldı):** Kullanıcının makinesi Türkçe locale. No-arg
   `String.toLowerCase()`/`toUpperCase()` locale-duyarlıdır → `"JOIN".toLowerCase()` = `"joın"`
   (noktasız ı) olur ve `"join"`'e EŞİT DEĞİLDİR. Bu yüzden `FROM` (I yok) çalışıp `JOIN`/`IN`/
   `UNION` gibi anahtar kelimeler tanınmıyordu; JOIN'li karmaşık sorgularda popup açılmıyordu.
   SQL/anahtar-kelime karşılaştırmalarında DAİMA `toLowerCase(Locale.ROOT)` kullan (veya
   `equalsIgnoreCase` — o locale-bağımsızdır). Bu kural tüm eklentiler için geçerli.
1. Otomatik tetik dinleyicisi UI thread'inde `asyncExec` ile açılır; parse `SqlCaretAnalyzer` ile
   editör metnini DEĞİŞTİRMEDEN yapılır.
2. `getDictionaryEnumeration` metadata hazır değilken boş dönebilir → popup arka plan job'ında
   `DBRProgressMonitor` ile çağrılır (render anında değil), böylece FK PoC pitfall #1'e düşülmez.
3. `Bundle-Version`/pom `<version>` eşleşmesi ve 3 kayıt yeri (plugins/pom.xml desktop profili,
   feature.xml, PDE launch config) diğer eklentilerle aynı kurala tabi.
