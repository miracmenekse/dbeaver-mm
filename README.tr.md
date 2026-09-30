# dbeaver-mm (Türkçe)

🇬🇧 [English README](README.md)

**dbeaver-mm**, [DBeaver Community](https://github.com/dbeaver/dbeaver)'nin, gün boyu normalize veritabanlarında
sorgu yazan ve veri okuyan analistler için geliştirilmiş bir fork'udur.

## Amaç

Normalize bir şemada tablolar çoğunlukla ID taşır: `order_type_id = 2`, `service_id = 14`. Bu ID'nin ne anlama
geldiğini bulmak için ya join yazmak ya da başka bir sekmede sözlük tablosunu açıp bakmak gerekir. Veriler birden
fazla veritabanına dağılmışsa (örneğin test verisi bir bağlantıda, konfigürasyon başka bir bağlantıda) iş daha da
zorlaşır.

dbeaver-mm'in amacı iki şeydir:
1. **ID'nin anlamını olduğu yerde göstermek.** Tabloda, filtrede ve SQL'de ID'nin yanında açıklaması görünür.
2. **Değeri aramak yerine seçtirmek.** Nerede bir ID yazılacaksa orada aranabilir bir liste açılır.

Tümü istemci tarafında, DBeaver'ın kendi modeli üzerine kuruludur: fiziksel FK'lar, sanal (virtual) FK'lar ve sanal
modeldeki "açıklama kolonu" ayarı. Sunucu bileşeni, PRO özelliği ya da ayrı bir kural motoru yoktur. Koddaki fork
değişiklikleri `// dbeaver-mm` yorumuyla işaretlidir.

---

## Özellikler

### 1. Veri tablosunda FK açıklamaları

- **Amaç:** Sonuç tablosunda ID'nin ne olduğunu join yazmadan görmek.
- **Arayüz:** FK kolonundaki hücrede değerin yanında açıklama çıkar: `2 | MAIN_ORDER`. Açıklama gri ipucu rengindedir.
  Başka bir bağlantıdaki tabloya giden FK'larda ise **turkuaz** renktedir. FK kolonunun başlığında, sıralama ve
  filtre oklarının solunda bir **"…" butonu** bulunur.
- **Nasıl çalışır:** Kolonun FK'sı DBeaver metadata'sından bulunur. Referans verilen tablo bir sözlükse açıklamalar
  DBeaver'ın sözlük sorgusuyla çekilir ve önbelleğe alınır. Metadata henüz yüklenmemişse boş sonuç önbelleğe
  alınmaz, böylece metadata gelince etiketler kendiliğinden görünür. "…" butonu referans tablonun kolonlarını
  listeler. Seçilen kolon, o tablonun açıklama kolonu olarak sanal modele kalıcı yazılır.
- **Çıktı:** Her FK hücresi `id | açıklama` biçiminde görünür. Seçilen açıklama kolonu kalıcıdır ve filtre kutusu ile
  SQL seçicisinde de aynı kolon kullanılır.
- **Bilinen sınır:** Seçim referans tablo başına saklanır. Aynı tabloya giden iki FK kolonu aynı açıklamayı kullanır.

### 2. Bağlantılar arası FK

- **Amaç:** Veri bir bağlantıda, sözlük başka bir bağlantıda olduğunda da açıklamayı görmek.
- **Nasıl çalışır:** DBeaver'ın bağlantılar arası sanal FK özelliği kullanılır
  (ör. `mm_test_db.bsn_flow_spec.service_id → mm_config_db.service_spec`). Açıklama diğer bağlantı üzerinden okunur.
- **Çıktı:** Turkuaz renkli etiket. Filtre kutusu ve SQL seçicisi de bu FK'ları izler.

### 3. Hücre içi değer seçici

- **Amaç:** Tabloda veri düzenlerken ID'yi ezberden yazmamak.
- **Arayüz:** Sözlük FK hücrelerinde bir açılır menü butonu bulunur. Tıklayınca küçük bir **Değer / Açıklama**
  penceresi açılır ve bu pencerede arama yapılabilir.
- **Nasıl çalışır:** Arama veritabanında yapılır (50 satır döner). Pencere ekrana sığmazsa küçülür, alt satırlarda
  hücrenin üstüne açılır.
- **Çıktı:** Seçilen değer normal bir hücre düzenlemesi olarak yazılır. Kaydetmek ve geri almak her zamanki gibi çalışır.

### 4. Satırları kolona göre gruplama

- **Arayüz:** Sağ tık menüsündeki **"Group rows by &lt;kolon&gt;"** seçeneğiyle ya da kolon başlığındaki ikonla açılır.
- **Nasıl çalışır:** Satırlar o kolona göre sıralanır. Her grup, iki mat rengin birbirini izlemesiyle boyanır.
- **Çıktı:** Aynı değere sahip satırlar görsel bloklar halinde görünür.

### 5. Sonuç filtre kutusu

- **Amaç:** `WHERE` filtresi yazarken değeri listeden seçmek.
- **Arayüz:** Filtre kutusuna `kolon =` yazınca bir öneri listesi açılır. Listede değerler etiketleriyle birlikte yer
  alır (`2 | MAIN_ORDER`). Filtre butonunun yanındaki bir onay butonu listeyi **sadece sonuçta geçen değerlerle**
  sınırlar.
- **Nasıl çalışır:** Sözlük FK'larında, bağlantılar arası FK'larda ve düz kolonlarda çalışır. Arama veritabanında
  yapılır, yalnızca çekilen ilk satırlarda değil. Büyük/küçük harf ve Türkçe karakter farkı gözetilmez. Tırnak
  içinde boşluk yazılsa da arama devam eder.
- **Çıktı:** Seçilen değer, yazılan arama metninin yerine geçer ve filtre `kolon = değer` olarak tamamlanır.

### 6. SQL editöründe satır içi FK seçici

- **Amaç:** SQL yazarken ID'yi başka yerden bakıp kopyalamamak.
- **Arayüz:** `fk_kolon =` ya da `kolon IN (` yazınca imlecin altında küçük bir liste açılır. Listede referans
  tablonun satırları ID ve etiketle birlikte görünür. Liste başlığındaki bir buton hangi kolonun etiket olarak
  gösterileceğini seçer.
- **Nasıl çalışır:** Hedef tablo, sorgunun `FROM` ve alias listesinden bulunur. Ardından kolonun fiziksel ya da sanal
  FK'sı izlenir, gerekirse başka bir bağlantıya geçilir. Arama sunucu tarafında yapılır.
- **Çıktı:** Seçilen ID imlecin olduğu yere yazılır.

### 7. Bağlantı çubuğu ve otomatik bağlantı

- **Amaç:** Tabloları farklı bağlantılara dağılmış bir ortamda doğru bağlantıyı elle seçmek zorunda kalmamak.
- **Arayüz:** SQL editörünün üstünde bir **"Connections: …"** çubuğu bulunur. Çubuk filtrelenebilir ve birden fazla
  bağlantı seçilebilir. Seçilen bağlantılar kısa kodlarıyla görünür. Gezginde bir bağlantıya
  **"Use SQL auto connection"** etiketi de verilebilir.
- **Nasıl çalışır:** Editör, yazılan sorgudaki tabloların hangi aday bağlantıda olduğuna bakar. Adaylar çubukta
  seçilenlerdir, çubukta seçim yoksa etiketli bağlantılardır. Yalnızca açık bağlantıların önbellekteki tablo
  adları okunur, veri okunmaz. Birden fazla eşleşme olursa imlecin yanında seçim sunulur. Seçim editör ve tablo
  başına hatırlanır. Tablolar zaten mevcut bağlantıdaysa bağlantı değişmez.
- **Çıktı:** Editör, sorgunun tablolarını içeren bağlantıya kendiliğinden geçer.

### 8. Bağlantılar arası tablo tamamlama ve kısa kodlar

- **Arayüz:** `from` / `join` / `into` / `update` yazınca aday bağlantıların tabloları `Tablo | Bağlantı` biçiminde
  listelenir. Gezgindeki **"Set SQL short code…"** ile bir bağlantıya kısa kod verilebilir.
- **Nasıl çalışır:** `<kod>.` yazınca yalnızca o bağlantının tabloları listelenir. Böylece tüm bağlantıları taramak
  gerekmez. Bir tablo seçildiğinde yazılan önek silinir ve tablo adı yazılır. `WHERE` / `AND` / `OR` yazınca da o
  tablonun kolonları önerilir, tablo başka bir bağlantıda olsa bile.
- **Çıktı:** Tablo adı eklenir ve editör o tablonun bağlantısına geçer.

### 9. Son SQL betikleri paneli ve parametre formu

- **Amaç:** Sık kullanılan betikleri hızla bulmak ve farklı değerlerle yeniden çalıştırmak.
- **Arayüz:** Sağa yerleşik bir panel bulunur. Panelde aktif projenin son kaydedilen 10 betiği kartlar halinde
  listelenir. Her kartta dosya adı, betiğin ilk yorum satırı ve `WHERE` koşulları görünür. Favoriler listenin en
  üstüne sabitlenir ve 10 sınırına dahil değildir. Bir karta tek tıklamak betiği açar. Ortada açılan
  **parametre formu**, betikteki her `kolon = değer` koşulunu dolu ve düzenlenebilir olarak listeler.
- **Nasıl çalışır:** Sıralama dosya değiştirilme zamanına göre yapılır. DBeaver'ın "Recent SQL Script" komutu da aynı
  ölçütü kullanır. Parametre formu join koşullarını ve alt sorguları atlar. Form, betik panelden açıldığında
  kendiliğinden gelir ya da herhangi bir SQL editöründe `Ctrl+Alt+P` ile açılır.
- **Çıktı:** Form yalnızca betik metnini günceller, sorguyu çalıştırmaz.

### 10. Arama ve gezgin

- **Aksan ve harf duyarsız arama:** Tüm seçicilerde ve filtre kutusunda geçerlidir. `müşteri` araması `Musteri`
  kaydını da bulur. Veritabanı sonuç döndürmezse daha geniş bir dilim istemci tarafında aynı kuralla taranır.
- **Sade gezgin:** Tablolar doğrudan bağlantının ya da şemanın altında görünür. View, index, sequence, trigger gibi
  nesneler kapalı gelen tek bir **"Other objects"** düğümünde toplanır.

---

## Deneme

Repo kökünde, geliştirme sırasında kullanılan iki SQLite betiği var:
- `bsn_flow_spec_setup.sql`: üç sözlük tablosuna (`flow_status`, `priority_level`, `department`) FK ile bağlı bir tablo kurar.
- `cross_connection_fk_setup.sql`: bağlantılar arası sanal FK'yı denemek için ikinci bağlantının verisini kurar.

DDL çalıştırdıktan sonra gezgini yenilemek için bağlantıda **F5**'e bas. Betiğin tamamını çalıştırmak için **Alt+X**
kullan. Derleme upstream DBeaver ile aynıdır
([Building from sources](https://github.com/dbeaver/dbeaver/wiki/Build-from-sources)). Derlemeden önce açık olan
DBeaver'ı kapat.

---

## Gelişim geçmişi

Fork'un her adımı eskiden yeniye sıralanmıştır. Commit mesajlarında adımlar `K<n>` diye numaralanır.

| Adım | Tarih | Değişiklik |
|---|---|---|
| [PR #1](https://github.com/miracmenekse/dbeaver-mm/pull/1) v0.1 | 2026-08-15 | İlk sürüm. Tabloda **FK açıklamaları** ve açıklama kolonu seçen başlık butonu. **Son SQL betikleri** paneli (favorilerle). SQL editöründe `=` / `IN (` sonrası **satır içi FK seçici** ve **parametre formu** (`Ctrl+Alt+P`). |
| K1–K2 | 2026-09-19 | **Bağlantılar arası sanal FK** üzerinden çözülen açıklamalar (turkuaz). Filtre kutusunda `kolon =` sözlük değerlerini etiketleriyle listeler. SQL seçicisi etiket kolonunu seçebilir, seçim tabloyla ortaktır. |
| K3–K6 | 2026-09-19 | İki renkli **satır gruplama**. *Other objects* düğümlü **sade gezgin**. Seçimi hücre düzenlemesi olarak yazan **hücre içi FK açılır listesi**. SQL editörü, sorgudaki tablolara bakarak **bağlantıyı kendisi seçer**. |
| K7–K10 | 2026-09-20 | Filtre kutusu düz kolonların değerlerini de listeler. Hücre seçici **aranabilir pencereye** dönüşür. **WHERE / AND / OR sonrası kolon tamamlama** bağlantılar arası çalışır. SQL editörünün üstüne **bağlantı çubuğu** eklenir. |
| K11 | 2026-09-20 | `from` / `join` / `into` / `update` sonrası **bağlantılar arası tablo adı tamamlama**. Seçilen tablo bağlantıyı da değiştirir. |
| K12–K13 | 2026-09-20 | **Aksan ve harf duyarsız arama** (Türkçe harfler katlanır). Kolonun etiketi kendisiyse etiket tekrar edilmez. **Bağlantı kısa kodları** eklenir (`<kod>.` yalnızca o bağlantının tablolarını listeler). |
| K14 | 2026-09-22 | SQL FK seçicisi fiziksel ya da sanal FK'yı başka bağlantıya kadar izler. Hücre seçici ekranın alt kenarında taşmaz. |
| K15 | 2026-09-28 | Filtre kutusunda seçilen değer, yazılan arama metninin yerine geçer. |
| K16 | 2026-09-28 | Filtre kutusundaki değer araması ilk 50 satırda değil, **veritabanında** yapılır. |
| K17 | 2026-09-28 | Sözlük etiketlerinde `[NULL]` gösterilmez. Aksan duyarsız sözlük araması veritabanında yapılır. |
| K18 | 2026-09-28 | Açık tırnak içinde boşluk yazıldıktan sonra da değer araması sürer. |
| K19 | 2026-09-28 | Bağlantı çubuğu her bağlantının kısa kodunu gösterir. |
| K20 | 2026-09-30 | Filtre kutusunda listeyi **yalnızca sonuçta geçen değerlerle** sınırlama seçeneği (etiketleriyle). |
