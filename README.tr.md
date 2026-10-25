# dbeaver-mm

🇬🇧 **[English README](README.md)**

![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)
![Version](https://img.shields.io/badge/version-0.3.0-green.svg)
![Java](https://img.shields.io/badge/Java-21-orange.svg)
![Based on](https://img.shields.io/badge/based%20on-DBeaver%20Community-lightgrey.svg)

> **dbeaver-mm: her ID'nin anlamını gör, değeri yazmak yerine seç.**

---

## 📖 Nasıl çalışır? Kullanım rehberi

ID'nin anlamı ID'nin yanında görünür; değer yazman gereken her yerde adıyla arayıp seçersin:

![Tabloda FK açıklamaları: status_id ve channel_id'nin yanında adlar](docs/rehber/02-fk-etiketleri.png)

![Hücre içi seçici: "kullan" yazınca Kullanımdan kalktı bulunuyor](docs/rehber/04-hucre-secici.png)

Her özelliğin adım adım, ekran görüntülü anlatımı: **[Kullanım Rehberi → docs/KULLANIM.md](docs/KULLANIM.md)**

| Ne yapmak istiyorsun? | Rehberde |
| --- | --- |
| Windows'a kurmak | [Başlarken](docs/KULLANIM.md#başlarken) |
| ID'lerin ne anlama geldiğini görmek | [Tabloda FK açıklamaları](docs/KULLANIM.md#tabloda-fk-açıklamaları) |
| Hücreye değeri adıyla seçmek | [Hücreden değer seçme](docs/KULLANIM.md#hücreden-değer-seçme) |
| Sonucu adla filtrelemek | [Filtre kutusu](docs/KULLANIM.md#filtre-kutusunda-adla-filtreleme) |
| SQL'de değer seçmek, parametre formu | [SQL editörü araçları](docs/KULLANIM.md#sql-editörü-araçları) |
| Yeni satırlara API'den ID almak | [ID getirme](docs/KULLANIM.md#yeni-satırlara-id-getirme) |
| Değişiklikleri task betiğinde toplamak | [Conf paketleri](docs/KULLANIM.md#conf-paketleri) |
| Hata bildirmek | [Oturum kaydı ve sorun raporu](docs/KULLANIM.md#oturum-kaydı-ve-sorun-raporu) |

## 📌 Proje Hakkında ve Problem

Normalize bir veritabanında kolonların çoğu ID taşır: `order_type_id = 2`, `service_id = 14`. Bir ID'nin ne anlama
geldiğini öğrenmek için analistin ya join yazması ya da sözlük tablosunu başka bir sekmede açması gerekir. Bir değere
göre filtrelemek ya da sorgu yazmak için ID'yi başka bir yerde bulup elle yazar. Veri birden fazla bağlantıya
dağılmışsa (örneğin test verisi bir veritabanında, konfigürasyon başka birinde) iş daha da zorlaşır. Standart DBeaver
referans verilen değeri yalnızca ayrı editörlerde gösterebilir ve SQL editörü aynı anda tek bir bağlantıya bağlıdır.

dbeaver-mm, [DBeaver Community](https://github.com/dbeaver/dbeaver)'nin bir fork'udur. ID'nin anlamını ID'nin
göründüğü yerde (tablo, filtre kutusu, SQL editörü) gösterir ve ID yazılması gereken her yerde aranabilir bir seçici
açar. Bunların hepsi bağlantılar arasında da çalışır. Her şey istemci tarafında, DBeaver'ın kendi modeli üzerine
kuruludur: fiziksel FK'lar, sanal (virtual) FK'lar ve sanal modeldeki açıklama kolonu. Sunucu bileşeni, PRO özelliği
ya da ayrı bir kural motoru gerekmez. Böylece fork, upstream DBeaver'ın üzerinde ince bir katman olarak kalır.

## ✨ Temel Özellikler

* **Tabloda FK açıklamaları:** FK hücresinde ID'nin yanında referans tablodaki açıklama görünür (`2 | Aktif`).
  Kolon başlığındaki buton hangi kolonun açıklama olacağını seçer, seçim sanal modele kaydedilir. *Başka bir
  bağlantıya* giden sanal FK üzerinden gelen açıklamalar turkuaz renktedir.
* **Her yerde değer seçici:** Sözlük FK hücrelerinde, boş (`[NULL]`) olanlar dahil, aranabilir bir Değer / Açıklama
  penceresi açılır. Filtre kutusuna `kolon =`, SQL editörüne `fk_kolon =` ya da `kolon IN (` yazınca değerler
  açıklamalarıyla listelenir. Aramalar veritabanında yapılır, büyük/küçük harf ve Türkçe karakter farkı
  gözetilmez (`müşteri` araması `Musteri` kaydını bulur).
* **Çok bağlantılı SQL editörü:** Bağlantı çubuğu, betiğin hangi bağlantılarda çalışabileceğini seçer. Editör,
  sorgudaki tabloların bulunduğu bağlantıya kendisi geçer. Tablo adları bağlantılar arasında tamamlanır
  (`Tablo | Bağlantı`). Bağlantı kısa kodları (`<kod>.`) listeyi tek bir bağlantıyla sınırlar.
* **Betik araçları:** Son SQL betikleri paneli son 10 betiği WHERE koşullarıyla birlikte kartlar halinde gösterir,
  favoriler en üstte durur. Parametre formu (`Ctrl+Alt+P`) betikteki tüm `kolon = değer` koşullarını tek yerden
  düzenletir.
* **Yeni satırlara ID:** *Edit › Fetch IDs for new rows*, anahtarı boş yeni satırlar kadar ID'yi ID API'sinden
  alır ve kaydetmeden tabloya yazar. Domain her bağlantı için bir kez seçilir.
* **Conf paketleri:** *Edit › Add changes to conf package ...*, tablodaki kaydedilmemiş değişiklikleri SQL'e
  çevirir (*Generate SQL* ile aynı betik) ve ilk satırı task açıklaması olan `Scripts/conf/<task id>.sql`
  dosyasının sonuna ekler. Değişiklikler hemen atılabilir, böylece sekme veritabanına bir şey kaydetmeden
  kapanır. Hücre içi FK seçicisi, yalnızca conf paketlerinde olan satırları `[conf <task id>]` işaretiyle en
  üstte gösterir. *Window › Conf Packages* alt paneli açar: paketler en yeni üstte; seçilenin INSERT'leri
  tablo başına satır ızgarası, tüm SQL'i ikinci sekmede. Paket değişince kendini yeniler. FK kolonları
  grid'deki gibi `değer | etiket` gösterir; sağ tık ya da Ctrl+C hücre değerini, *Copy row* tüm satırı kopyalar.
  Bir satırın FK değeri ile aynı paketteki gösterdiği satır aynı arka plan rengini alır. Sağ tık ›
  *Delete row ...* satırı, altındaki satırları (seçilen seviyeye kadar işaretli) ve üstündeki satırları (işaretsiz)
  tabloda gösterir; pencere açıkken panel silinecekleri kırmızı, onlara bağlı kalanları turuncu gösterir. Birden
  fazla şema varsa panel bunları çizgi ve başlıkla ayırır (ör. *PCM confs*). Kopyala butonları paketin betiğini panoya alır: yazdığı her
  şema için bir buton (ör. *Copy pcm*, *Copy domain_config*) ve *Copy all*; açıklama satırı hariç.
  Kopyalanan betik FK hatası vermeden çalışır: DELETE'ler önce alt tablolar, INSERT'ler önce üst satırlar, sonra UPDATE'ler.
* **Daha kolay okuma:** Satırlar bir kolona göre iki renkle gruplanabilir. Gezgin tabloları doğrudan gösterir;
  view, index ve diğer nesneler tek bir *Other objects* düğümünde toplanır.
* **Donma kaydedici:** Pencere bir saniye ya da daha uzun cevap vermezse ne kadar ve nerede takıldığı `~/.dbeaver-mm/donma.log` dosyasına yazılır; 2 sn ve üstü donmalar ardından bildirim de gösterir (*Preferences › User Interface › Notifications › UI freeze* ile kapanır). Bir saniyeden uzun süren arka plan işi sağ altta küçük bir *Working: …* etiketiyle görünür; bekleme artık donma sanılmaz. *Plus › Create Problem Report* sürüm bilgisini, donma kaydını ve son oturumların hata log'larını masaüstüne zip olarak koyar; sorunu inceleyecek kişiye gönderilir, hiçbir şey kendiliğinden gönderilmez.
* **Oturum kaydı:** Tablo açma, tablo içinde arama/filtre, DML/DDL, conf paketi değişiklikleri ve ID getirme `~/.dbeaver-mm/kayit/surekli.jsonl` dosyasına yazılır (2 MB'ta döner). *Plus › Start session...* bir isim sorar ve durdurulana kadar her adımı (komutlar, sekmeler, navigator, Ctrl+F, SQL) ayrı bir dosyaya kaydeder; kayıt sürerken kırmızı *REC* etiketi görünür. Yazma arka plan iş parçacığında yapılır, arayüzde asla; hiçbir şey dışarı gönderilmez. Yalnızca onayla ve rumuzla: ilk açılışta sorulur, *Plus Settings › Automatic recording...* ile değiştirilir.

## 🛠 Teknolojik Altyapı

* **Dil / platform:** Java 21 (`JavaSE-21`), Eclipse RCP / OSGi bundle'ları
* **Arayüz:** SWT ve JFace. Veri tablosu DBeaver'ın `LightGrid` bileşenidir (`SpreadsheetPresentation`). SQL
  düzenleme Eclipse metin altyapısını kullanır.
* **Veri erişimi:** DBeaver modeli üzerinden JDBC (`DBSEntityAssociation`, sanal model `DBVEntity`, sözlük sorguları)
* **Derleme:** Apache Maven + Eclipse Tycho. OSGi bağımlılıkları Eclipse P2 depolarından gelir.
* **Temel:** DBeaver Community (`devel`) ve `project.deps` dosyasındaki repolar (`dbeaver-common`, `datadam-api`)
* **AI / ML:** Fork'un özelliklerinde yok (upstream DBeaver'ın kendi AI özellikleri değişmeden duruyor)

## 🏗 Sistem Mimarisi ve Çalışma Mantığı

Fork, upstream'deki birkaç bundle'ı değiştirir ve iki yeni bundle ekler:

| Bundle | Fork'taki rolü |
|---|---|
| `org.jkiss.dbeaver.ui.editors.data` | Tablodaki FK açıklamaları, başlık butonu, hücre içi seçici, filtre kutusu değerleri, satır gruplama |
| `org.jkiss.dbeaver.ui.inlinefkpicker` (yeni) | SQL FK seçicisi, bağlantı çubuğu mantığı, otomatik bağlantı, tablo tamamlama, kısa kodlar, parametre formu |
| `org.jkiss.dbeaver.ui.recentscripts` (yeni) | Son SQL betikleri paneli |
| `org.jkiss.dbeaver.ui.editors.sql` | SQL editörünün üstündeki bağlantı çubuğu |
| `org.jkiss.dbeaver.ui.navigator` | *Other objects* düğümü |

Upstream kodundaki fork değişiklikleri `// dbeaver-mm` yorumuyla işaretlidir.

Bir FK açıklamasının hücreden ekrana yolu:

```mermaid
flowchart LR
    A[Hücre değeri] --> B{Kolonun fiziksel ya da<br/>sanal FK'sı var mı?}
    B -- hayır --> Z[Düz değer]
    B -- evet --> C[Referans tablo<br/>başka bağlantıda olabilir]
    C --> D[Sanal modeldeki<br/>açıklama kolonu]
    D --> E[O tablonun bağlantısında<br/>sözlük sorgusu]
    E --> F[(Açıklama önbelleği)]
    F --> G["2 | Aktif"<br/>bağlantılar arasıysa turkuaz]
```

Seçiciler aynı yolu kullanır. Filtre kutusu, hücre içi pencere ve SQL seçicisi, "referans tablonun bu metne uyan
değerleri, açıklamalarıyla" isteğini ortak bir yardımcı üzerinden yapar (`FkDictionaryLabels`). Arama doğru
bağlantıda bir veritabanı sorgusu olarak çalışır, sonuçlar harf ve aksan farkı gözetmeden eşleştirilir. SQL
editöründe imleç çözümleyici (`SqlCaretAnalyzer`), kolonun tablosunu bulmak için sorgunun `FROM` listesini ve
alias'larını okur. Bağlantı seçici sonra bu tabloyu aday bağlantılarda arar. Bunu yaparken yalnızca önbellekteki tablo
listelerini okur, veriye dokunmaz.

Bulunamayan açıklama önbelleğe alınmaz. Metadata henüz yüklenmemişse hücre düz değeri gösterir, metadata gelince
açıklama kendiliğinden görünür.

## 🚀 Hızlı Başlangıç ve Kurulum

**Windows, derlemeden:** [Releases](https://github.com/miracmenekse/dbeaver-mm/releases) sayfasından `dbeaver-mm-windows-x64.zip` dosyasını indir, çıkar ve `dbeaver\dbeaver.exe` dosyasına çift tıkla. Java içinde gelir. SmartScreen uyarırsa *Ek bilgi → Yine de çalıştır* de.

**Kaynaktan derleme:**

**Gereksinimler:** JDK 21, Apache Maven 3.9+, Git, yaklaşık 4 GB boş disk. Derlemeden önce açık olan DBeaver'ı kapat.

```bash
# Tüm repolar aynı üst klasörde olmalı
mkdir -p ~/dbeaver-dev && cd ~/dbeaver-dev
git clone https://github.com/dbeaver/dbeaver-common.git
git clone https://github.com/dbeaver/datadam-api.git
git clone https://github.com/miracmenekse/dbeaver-mm.git

# Ürünün tamamını derle (Community sürümü)
cd dbeaver-mm
mvn package -f product/aggregate/pom.xml -T1C -Pproduct-dbeaver-ce

# Derlenen ürün (her platform için ayrı klasör) burada:
ls product/community/target/products/
```

Ortam değişkeni gerekmez. Linux'ta koyu GTK teması kullanıyorsan ve tablo bozuk görünüyorsa
`GTK_THEME=Adwaita:light` ile başlat.

**Demo verisi:** Repo kökünde iki SQLite betiği var. DBeaver'da `Alt+X` ile çalıştır, sonra bağlantıda `F5`'e bas.
- `bsn_flow_spec_setup.sql`: üç sözlük tablosuna FK ile bağlı bir tablo kurar.
- `cross_connection_fk_setup.sql`: bağlantılar arası sanal FK'yı denemek için ikinci bağlantının verisini kurar.

## 💡 Kullanım Senaryoları

**1. Sonuç tablosunu okumak** (`bsn_flow_spec_setup.sql` demo verisi)

```sql
SELECT id, label, status_id, priority_id FROM bsn_flow_spec;
```
```text
id  | label  | status_id      | priority_id
----+--------+----------------+-------------
100 | akis A | 2 | Aktif      | 3 | Yuksek
101 | akis B | 1 | Yeni       | 1 | Dusuk
102 | akis C | 3 | Tamamlandi | 2 | Orta
```

**2. ID yerine açıklamayla filtrelemek**

```text
Filtre kutusuna yazılan:  status_id = tamam
Öneri:                    3 | Tamamlandi
Seçince:                  status_id = 3
```

**3. Bağlantılar arası SQL yazmak** (`cross_connection_fk_setup.sql` demo verisi;
`cfg`, `mm_config_db` bağlantısına verilen kısa kod)

```text
Yazılan:   select * from cfg.serv
Öneri:     service_spec | mm_config_db
Seçince:   select * from service_spec      (editör mm_config_db'ye geçer)

Yazılan:   select * from bsn_flow_spec where service_id =
Öneriler:  10 | Faturalama
           20 | Musteri Yonetimi
           30 | Siparis Yonetimi
Seçince:   ... where service_id = 20       (açıklamalar mm_config_db'den okunur)
```

## 🗺 Yol Haritası

- [x] v0.1: FK açıklamaları, son SQL betikleri paneli, satır içi FK seçici, parametre formu
- [x] Bağlantılar arası FK açıklamaları ve seçiciler (K1–K14)
- [x] Filtre kutusunda veritabanı tarafında, aksan duyarsız değer araması (K15–K20)
- [ ] Her FK kolonu için bağımsız açıklama kolonu (şu an seçim referans tablo başına ortak)
- [ ] `INSERT` / `UPDATE ... SET` konumlarında değer seçici
- [ ] Ekran görüntüleri ve kısa bir demo videosu
- [ ] Hazır derlenmiş sürümler

## 📄 Lisans ve Katkıda Bulunma

dbeaver-mm, DBeaver Community gibi [Apache License 2.0](LICENSE.md) ile lisanslıdır. Her Java dosyası
`docs/license_header.txt` içindeki Apache 2.0 başlığıyla başlar.

Issue ve pull request'ler açıktır. Lütfen:
- Upstream dosyalardaki fork değişikliklerini `// dbeaver-mm` yorumuyla işaretle.
- Paralel mekanizma eklemek yerine DBeaver'ın mevcut modelinin (FK'lar, sanal model) üzerine kur.
- Commit'leri `K<n>: <ne değişti>` biçiminde numarala ve aşağıdaki gelişim geçmişine bir satır ekle.

Upstream DBeaver dokümantasyonu, sürücüler ve indirmeler için: [dbeaver/dbeaver](https://github.com/dbeaver/dbeaver).

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
| K23 | 2026-10-07 | Hücre içi FK açılır listesi **boş (`[NULL]`) hücrelerde** de çalışır; eksik değer, *Can't navigate to NULL value* hatası yerine referans tablodan seçilebilir. |
| K24 | 2026-10-08 | *Fetch IDs for new rows*, tablodaki yeni satırların anahtarını ID API'sinden doldurur; ID domain'i her bağlantı için bir kez sorulur. |
| K25 | 2026-10-08 | *Add changes to conf package ...*, tablodaki kaydedilmemiş değişiklikleri SQL olarak `Scripts/conf/<task id>.sql` dosyasına ekler ve istenirse tablodan atar. |
| K26 | 2026-10-08 | Hücre içi FK seçicisi conf paketlerine eklenen satırları en üstte gösterir; yeni üst kayıt veritabanında yokken seçilebilir. |
| K27 | 2026-10-10 | *Conf Packages* alt paneli paketleri listeler; seçilenin eklenen satırlarını tablo başına ve SQL'ini gösterir, paketler değiştikçe yenilenir. |
| K28 | 2026-10-10 | Conf Packages paneli değerlerin yanında FK etiketlerini gösterir (fiziksel ya da sanal FK, yalnızca pakette olan satırlar dahil); sağ tık / Ctrl+C ile hücre ya da satır kopyalanır. |
| K29 | 2026-10-10 | Conf Packages paneli ilişkili satırları renklendirir: FK değeri ile aynı paketteki gösterdiği anahtar, ilişki başına tek renk paylaşır. |
| K30 | 2026-10-10 | Conf Packages panelinde *Delete row ...* satırı paketten tek başına ya da seçilen seviyeye kadar bağlı satırlarıyla siler, ona bağlı kalan satırlar için uyarır. |
| K31 | 2026-10-10 | Conf Packages paneline kopyala butonları: tüm betik ya da tek bir şemanın (pcm, domain_config, ...) ifadeleri; her şema ayrı gönderildiği için. |
| K32 | 2026-10-10 | Kopyalanan conf betikleri FK'ya uygun sırada: DELETE'ler önce alt tablolar, INSERT'ler önce gösterilen satırlar (aynı tablo içinde de), sonra diğerleri; üst satırları tutan şema önce gelir. |
| K33 | 2026-10-10 | Conf paketinden silme pencere yerine panelde yapılır: silinecek satırlar kırmızı, geride kalanlar turuncu boyanır, seviye satırların üstündeki çubuktan seçilir. |
| K34 | 2026-10-10 | Silme yeniden sade, tablolu pencerede; üstteki satırlar da (işaretsiz) listelenir. Conf paketi penceresi son 2 task id'yi buton olarak sunar; panel şemaları çizgi ve başlıkla ayırır. |
| K35 | 2026-10-10 | Conf Packages panelinde tek seçim kalır: bir tablodaki satıra tıklamak diğer tablolardaki seçimi kaldırır. |
| K36 | 2026-10-10 | Conf Packages panelinde hücreye çift tıklayınca yerinde düzenlenir; Enter değeri paketteki INSERT'e yazar (`NULL` = null), Esc vazgeçer. |
| K37 | 2026-10-10 | Window ile Help arasında **Plus** ana menüsü (Conf Packages, Recent SQL Scripts, Focus Mode); tablo editöründe Properties / Data / Diagram sekmelerinin yanında *Fetch IDs* ve *Add to conf package* butonları. |
| K38 | 2026-10-12 | **Windows indirmesi**: her `v*` etiketi, Java'sı içinde hazır bir Windows zip'ini GitHub Releases'e koyar. |
| K39 | 2026-10-12 | **Windows'ta ID'leri getir**, herkes kendi girişiyle. |
| K40 | 2026-10-12 | **Betiksiz ID'leri getir**: DBeaver ID API'sini kendisi çağırır, bash ya da PowerShell gerekmez. K39'un yerini alır. |
| K41 | 2026-10-12 | İlk Windows indirmesi yayında: [v0.2.3](https://github.com/miracmenekse/dbeaver-mm/releases/tag/v0.2.3) (sürüm derlemesi ürün klasörünü paketler, yan repoları fork'un upstream tabanına sabitler). |
| K43 | 2026-10-12 | **Donma kaydedici**: 1 sn ve üstü arayüz donmaları nedeniyle birlikte `~/.dbeaver-mm/donma.log`'a yazılır, 2 sn ve üstü bildirim de verir; yavaş arka plan işleri *Working: …* etiketiyle görünür. Donma test düzeni `test/perf/` altında (1 GB PostgreSQL test verisi, SSH benzeri gecikme aracı, JFR özeti, rapor `RAPOR.md`). |
| K44 | 2026-10-12 | **Yavaş bağlantıda FK etiketleri yüzünden donma bitti**: çizim anında eksik olan etiketler (ör. karşılıksız FK değerleri) artık arayüzde hücre hücre sorgulanmıyor, arka planda toplu çekiliyor; "etiket yok" sonucu da hatırlanıyor. 30 ms gecikmede karşılıksız değerli tabloda fare gezdirmek 4,2 sn arayüz beklemesinden 0'a indi. |
| K45 | 2026-10-12 | **Sorun raporu**: *Plus › Create Problem Report* sürüm bilgisi, donma kaydı ve hata log'larını içeren bir zip'i hata bildirimiyle gönderilmek üzere masaüstüne koyar. |
| K46 | 2026-10-12 | **Conf Packages tabloları her şemada buluyor**: paket tabloları yalnızca bağlantının varsayılan şemasında aranıyordu; başka şemanın paketleri (ör. varsayılan `public` iken `pcm`) FK etiketi ve renk almıyor, FK sırası yerine dosya sırasıyla kopyalanıyordu. Artık INSERT'in kendi şeması kullanılıyor, şemasız tablolar tüm şemalarda aranıyor. |
| K47 | 2026-10-12 | **Oturum kaydı**: tablo açma, arama, DML ve conf paketi değişiklikleri her zaman hafifçe kaydedilir; isimli oturumlar (*Plus › Start session...*) her adımı kaydeder. Amaç her iş türünün hangi konfigürasyon adımlarını gerektirdiğini öğrenmek. Ölçüm: arayüz iş parçacığında 0 dosya yazması. |
| K48 | 2026-10-13 | **Kullanım rehberi** `docs/KULLANIM.md`: her özellik adım adım, 17 ekran görüntüsüyle; README'nin başında *Nasıl çalışır?* bölümü. |
| K49 | 2026-10-13 | **Kayıt yalnızca onayla ve anonim**: ilk açılışta dbeaver-mm neden kayıt aldığını (veri biriktirip bu sistemleri ileride otomasyonla yönetmek) anlatır ve bir rumuz sorar (Yüzüklerin Efendisi: Yüzük Kardeşliği'nden 20 karakter ya da kendi rumuzun). Evet denmeden hiçbir şey kaydedilmez; kayıtlarda yalnızca rumuz bulunur. *Plus Settings › Automatic recording...* kaydı açıp kapatır ve rumuzu değiştirir. |
| K50 | 2026-10-13 | **Oturum dışa aktarma**: oturum durdurulunca kaydı ve aynı adlı conf paketi (`conf/<görev>.sql`) masaüstüne zip olarak konabilir. Yazma arayüz iş parçacığında yapılmaz. |
| K51 | 2026-10-13 | **Oturum analizi**: `tools/session-analysis/analiz.py` (yalnızca stdlib) oturum kayıtlarını ya da K50 zip'lerini okur; her oturumun adımlarını rumuza göre ve oturumların çoğunda ortak adımları süreç iskeleti olarak yazar. |
| K52 | 2026-10-13 | **ID API hesap penceresi**: kullanıcı adı ve şifre elle dosya düzenlemek yerine bir pencereden girilir. |
| K53 | 2026-10-13 | **Plus Settings menüsü**: ikinci bir üst menü tüm Plus ayarlarını toplar (ID API hesabı, otomatik kayıt, donma bildirimleri); *Plus*'ta yalnızca özellikler kalır. Fetch IDs ayrıca verilen bir dosyayla kurulur; kullanıcı yalnızca kullanıcı adını ve şifresini girer. |
| K56 | 2026-10-14 | ID'leri getir, Java'nın kendi koyduğu başlıkları (`Connection`, `Host` ...) atlar; tarayıcıdan kopyalanan başlıklar `id-api.properties` içinde artık hata vermez. |
| K57 | 2026-10-14 | **Daha güvenli kaydetme uyarısı**: kaydedilmemiş değişiklikle nesne editörü kapatılınca "Write to database?" penceresi bağlantıyı (veri değişikliği onayı isteyen bağlantı tipinde kırmızı), nesneyi ve değişiklikleri, ayrıca conf paketi bilgi notunu gösterir. Varsayılan düğme *Keep editing*; nesne adı yazılmadan *Write to DB* etkinleşmez. |
| K58 | 2026-10-15 | ID'leri getir, `id-api.properties` içinde `token.field` gibi kısa bir anahtar varken "Range [13, 11) out of bounds" hatası vermez; başlık anahtarlarının öneki kesilmeden önce kontrol edilir. |
| K61 | 2026-10-19 | Filtre penceresindeki değer seçici artık varsayılan olarak büyük/küçük harfe duyarsız arar ("case insensitive" seçeneği elle açılana kadar kapalıydı); `b2b` yazınca `B2B` bulunur. |
| K62 | 2026-10-19 | `kolon =` değer önerileri artık açıklama kolonunun yanında anahtar kolonunda da arar; `shrt_code =` sonrası `b2b` yazınca içinde geçen tüm `shrt_code` değerleri gelir (önceden yalnızca açıklama kolonunda, örn. `cuser_name`, arıyordu). |
| K67 | 2026-10-22 | Conf paketleri: yalnızca DELETE edilen (INSERT olmayan) tabloların FK'ları da okunur; bu tabloların DELETE'leri de önce alt tablo olacak şekilde sıralanır. |
| K68 | 2026-10-23 | `data_tp` / `row_id` etiketleri için altyapı: sütun çifti tanınır, tablo → bağlantı eşlemesi `~/.dbeaver-mm/data-tp.properties` dosyasında tutulur (henüz görünür değişiklik yok). |
| K69 | 2026-10-23 | `data_tp` hücresi id'nin yanında `etiya_type_value`'dan gelen hedef tablo adını gösterir (`14 \| CUST_CONTR`); bağlantı başına bir kez okunur, FK etiketleriyle aynı soluk biçimde. |
| K70 | 2026-10-23 | `row_id` hücresi gösterdiği satırın açıklamasını yazar: tabloyu `data_tp` verir, tablo açık bağlantılarda aranır (yalnız birinde varsa kendiliğinden eşlenip hatırlanır), etiket anahtar kolonundan gelir; başka bağlantıdan gelen etiket dış veri rengindedir. |
| K71 | 2026-10-23 | `row_id` sütun başlığındaki `…` düğmesi, sonuçtaki tüm hedef tablolar için tek pencere açar: eşlenmemiş tablonun bağlantısı seçilir, tablo başına bir ya da birden çok etiket kolonu seçilir, canlı önizleme görünür; etiketler sorgu yeniden çalışmadan yenilenir. |
| K72 | 2026-10-23 | Tenant başına `data_tp` eşlemesi için altyapı: tenant = bağlantı adının ilk kelimesi, eşleme `~/.dbeaver-mm/data-tp/<TENANT>.properties` dosyasında (henüz görünür değişiklik yok). |
| K73 | 2026-10-23 | **Plus Settings › data_tp mapping...**: tenant başına `etiya_type_value`'nun bağlantısı ve şeması seçilir; *Build mapping* onu bir kez okur ve her hedef tabloyu tenant'ın açık bağlantılarında bulur (✓ tek yer, ⚠ birden çok – biri seçilir, ✕ yok); *Save* tenant'ın eşleme dosyasını yazar. |
| K74 | 2026-10-23 | `data_tp` / `row_id` etiketleri yalnızca tenant'ın data_tp eşlemesinden (K73) gelir: gezinirken `etiya_type_value` ya da bağlantılar arası tablo araması yapılmaz; iki tenant'taki aynı tablo kendi etiketlerini alır; `…` penceresi eşlenmiş bağlantıyı salt okunur gösterir, eşlenmemiş tipte Plus Settings'e yönlendirir. |
| K75 | 2026-10-26 | Grid değişiklikleri için geri alma betiği altyapısı: ters SQL'i üretir (silinen satırları geri ekler, eski değerleri geri yazar, eklenen satırları siler) ve betikleri 30 gün `~/.dbeaver-mm/geri-al/` altında tutar (henüz görünür değişiklik yok). |
