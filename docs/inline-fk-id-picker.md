# Inline FK ID Seçici (Referans Id Otomatik Tamamlama)

> Bu dosya Obsidian notunun (`Projects/Dbeaver-mm/Inline-FK-Id-Picker.md`) repo içine
> alınmış köprü kopyasıdır. Asıl referans Obsidian notudur; ikisi aynı içeriktir.

**Durum:** Geliştirildi + yeniden tasarlandı (bundle: `org.jkiss.dbeaver.ui.inlinefkpicker`)
**DBeaver katmanı:** Özel eklenti (Community uyumlu) — SQL Editör / Üretkenlik (lisans katmanı gerektirmez)
**Son güncelleme:** 2026-08-15

> ## ⚠️ Tasarım değişikliği (2026-08-15, canlı test sonrası)
> Eski **isim kuralı (`xxx_id → xxx`) KALDIRILDI**. Sorun: `where bsn_flow_spec_id = 101` gibi bir
> yazımda `bsn_flow_spec_id` tabloda gerçek bir kolon olmadığından SQL hata veriyordu. Yeni davranış:
> **herhangi bir `<kolon> =` / `<kolon> IN (`** yazıldığında, kolonun ait olduğu tablo sorgunun
> **FROM/UPDATE listesinden ve alias'tan** çözülür; popup o kolonun **kendi değerlerini** gösterir,
> yazdıkça o kolona göre filtreler, seçilince o kolonun değerini tip-duyarlı (metin→tırnaklı,
> sayı→düz) biçimde ekler. Kolon hiçbir aday tabloda yoksa popup açılmaz.
> Aşağıdaki "Kararlar" bölümünün eski isim-kuralı maddeleri bu değişiklikle geçersizdir.

## Özet
SQL editöründe bir sorgu yazarken, başka bir tabloya referans veren bir id kolonunun değerini girerken, o referans tablosunu **küçük bir inline widget** olarak açıp satırlar arasında ok tuşları/scroll ile gezip seçim yaparak **id değerini otomatik ekleyen** bir eklenti.

Çözdüğü sorun: Kullanıcı `bsn_flow_spec_id = ` yazacağı zaman aradığı `bsn_flow_spec`'in id'sini ezbere bilmiyor. Şu anki iş akışı: (1) sorguyu bırak, (2) ayrı bir `SELECT ... FROM bsn_flow_spec` sorgusu çalıştır, (3) doğru id'yi gözle bul, (4) geri dön ve id'yi elle sorguya yaz. Bu eklenti bu 4 adımı editörden hiç çıkmadan tek etkileşime indirir: `bsn_flow_spec_id =` yazınca `bsn_flow_spec` tablosunun satırları (id + anlamlı kolon) küçük bir açılır listede görünür, kullanıcı seçer, id yerine yazılır.

## Kararlar (+ gerekçe)
- **Özel DBeaver eklentisi (Eclipse RCP / OSGi bundle), Community uyumlu** — özellik tamamen istemci tarafı SQL editör davranışı + açık bağlantı üzerinden veri okuma; PRO/Enterprise API'sine gerek yok.
- **Hedef tablo çözümleme = isim kuralı (`xxx_id` → `xxx`), her zaman** *(kullanıcı kararı)*. Kolon adından `_id` son eki atılır, kalan doğrudan tablo adı kabul edilir. Gerekçe: Kullanıcının şemasında adlandırma tutarlı; en basit, şemadan bağımsız, düşük maliyetli çözüm. FK metadata parse etme veya zorunlu config gerekmez.
  - *İnce ayar (MVP sonrası, opsiyonel):* Kuralla bulunan tablo şemada yoksa graceful fallback (widget açılmaz / uyarı) ve ileride opsiyonel kullanıcı override eşlemesi. Ama varsayılan davranış her zaman isim kuralı.
- **Tetikleme = otomatik + manuel** *(kullanıcı kararı)*. `_id` ile biten bir kolondan sonra `=` (veya `= ` boşluğu) yazılınca widget otomatik açılır; ayrıca Ctrl+Space / özel kısayol ile imleç uygun konumdayken elle çağrılabilir. Gerekçe: Otomatik akıcı deneyim, manuel kısayol kontrol ve yeniden-çağırma.
- **Gösterim = id + anlamlı kolon + yazarak arama** *(kullanıcı kararı)*. Widget id ve otomatik seçilen anlamlı bir görünen kolonu (name/label/title/code) listeler; gösterilen kolon(lar) yapılandırılabilir. Yazarak sunucu-tarafı filtreleme (WHERE ... LIKE, debounce'lu) — büyük tablolarda şart. Widget kompakt liste olarak "küçük" kalır (grid değil).
- **Kapsam = `x_id =` ve `x_id IN (...)`** *(kullanıcı kararı)*. WHERE'deki eşitlik ve IN konumları desteklenir. `IN (...)` için çoklu seçim (birden fazla id ekleme). Gerekçe: En sık iki durum; INSERT/SET sonraki iterasyona bırakılabilir.
- **Eklenecek değer = seçilen satırın id (PK) değeri, tip-duyarlı** — id kolonu **hedef tablonun PK metadata'sından** alınır; PK bulunamazsa `id` varsayılır *(kullanıcı kararı)*. Sayısal ise düz (`= 42`), metin/UUID ise tırnaklı (`= 'a1b2...'`). Gerekçe: Doğru anahtar kolonu ve tipi DBeaver metadata'sından bilinir; niyet id eklemek.
- **Varsayılan sıralama + limit = anlamlı (görünen) kolona göre alfabetik, ilk ~50 satır** *(kullanıcı kararı)*. Arama yazınca sunucu tarafı filtre uygulanır.
- **Değerlendirme = istemci tarafı, aktif bağlantı üzerinden hafif sorgu** — widget açılırken referans tabloya `SELECT <pk>, <görünen kolon> FROM <tablo> [WHERE <görünen kolon> LIKE '%arama%'] ORDER BY <görünen kolon> LIMIT 50` çalıştırılır. Editördeki kullanıcı sorgusu **değiştirilmez**; ayrı/geçici okuma yapılır.

## Netleşen açık sorular (2026-08-15, kullanıcı kararı)
- **Öncelikli hedef DB:** PostgreSQL / MySQL (SQLite ikincil). Kod DB-agnostik kalır; dialect-özel
  LIKE/ILIKE ve case-insensitive davranış DBeaver'ın `DBSDictionary.getDictionaryEnumeration`
  (`SQLDialect.getCaseInsensitiveExpressionFormatter`) katmanıyla soyutlanır.
- **İlk teslim kapsamı:** Önce tekli `=` senaryosu uçtan uca; ardından `IN (...)` çoklu seçim.

## Uygulama mimarisi (bu repoda)
- **Bundle:** `plugins/org.jkiss.dbeaver.ui.inlinefkpicker` (Community uyumlu, PRO API'sine bağımlı değil).
- **Tablo çözümleme (FROM/alias):** `SqlCaretAnalyzer` aktif statement'in FROM/UPDATE tablo
  referanslarını (ad + alias; JOIN, virgül, `AS`, `schema.tablo` desteklenir) ayrıştırır.
  `InlineFkService.resolveTarget`: qualifier varsa (`alias.kolon`) alias→tablo eşlemesiyle (yoksa
  qualifier'ı tablo adı sayarak) tek adayı; yoksa FROM'daki tabloları sırayla dener ve **kolonu
  gerçekten içeren** tabloyu seçer. Tablo aktif şemadan `DBSObjectContainer.getChild` ile bulunur.
- **Değerlendirme API'si:** Hedef tablo çoğunlukla `JDBCTable` → `DBSDictionary`'dir.
  `getDictionaryEnumeration(monitor, keyColumn=karşılaştırılan kolon, keyPattern=filtre,
  searchText=null, null, caseInsensitive=true, sortAsc=true, sortByValue=true, 0, 50)` →
  `SELECT col, desc WHERE col LIKE ? ORDER BY col LIMIT 50` davranışı, `List<DBDLabelValuePair>`
  (value=kolon değeri, label=otomatik açıklama kolonu). `DBSDictionary` değilse/enumeration
  desteklemiyorsa `SELECT col FROM t LIMIT 50` jenerik fallback'ine düşülür.
- **Literal:** `SQLUtils.convertValueToSQL(dataSource, column, value)` ile tip-duyarlı
  (sayı düz, metin/UUID tırnaklı).
- **Tetik:** Manuel = command + keybinding (`OpenFkPickerHandler`, Ctrl+Alt+Space). Otomatik =
  `IStartup` (`InlineFkStartup`) SQL editör `StyledText`'ine hafif dinleyici takar; `=` veya `(`
  sonrası `SqlCaretAnalyzer` ile bağlam doğrulanınca popup açılır. Statement sınırı
  `extractQueryAtPos` ile bulunur.

## Kenar durumları
- Bağlantı yok / çevrimdışı → widget açılmaz (sessiz).
- Kolon FROM'daki hiçbir tabloda yok (ör. var olmayan `bsn_flow_spec_id`) → açılmaz.
- Statement'te FROM/UPDATE tablosu yok → açılmaz.
- Büyük tablo → LIMIT 50 + sunucu tarafı LIKE + debounce.
- Çok şema / çok tablo → alias öncelikli; alias yoksa kolonu içeren ilk tablo.

## Madde Madde Yapılacaklar
- [x] SQL editör content-assist / completion akışını incele; tetik entegrasyon noktasını sabitle.
- [x] Kolon algılama (`<kolon>` / `alias.<kolon>`) + FROM/UPDATE alias ayrıştırma + aktif statement sınırı.
- [x] FROM/alias → gerçek tablo + kolon çözümleme (kolonu içeren tabloyu seç).
- [x] Kolonun kendi değerlerini okuyan hafif sorgu (LIMIT 50, WHERE LIKE + debounce, ORDER BY kolon).
- [x] Inline widget/popup: kompakt liste + yazarak arama + ok tuşu/scroll + Enter/Esc.
- [x] Seçilen değeri tip-duyarlı biçimde editöre yazma; `IN (...)` için çoklu seçim → virgüllü liste.
- [ ] Canlı uçtan uca test (Eclipse PDE): `where short_code =`, `where bfs.id =`, `... IN (...)`; kenar durumları.
