# dbeaver-mm kasma testi raporu (2026-10-12)

## Özet
İki ana kasma kaynağı ölçülerek doğrulandı:

1. **Grid / FK etiketleri:** Etiketi önbellekte olmayan her FK hücresi, **boyanırken UI iş parçacığında
   tek tek SQL sorgusu** çalıştırıyor. Etiketi hiç olmayan değerler (yetim FK, açıklaması NULL satır)
   hiç önbelleğe girmediği için bu sorgu **her repaint'te, her hover'da** tekrarlanıyor. Kaydetme ve
   yenileme ise tüm sekmelerin etiket önbelleğini siliyor. Yerel DB'de fark edilmiyor; uzak DB'de
   sorgu başına ağ gecikmesi kadar donma demek (30 ms RTT'de fare gezdirmek ≈ 1,5 dk donma).
2. **Conf Packages:** INSERT'leri FK sırasına koyan `ConfPackages.parentsFirst` O(n³). UI iş
   parçacığında paket seçilince ve kopyalanınca çalışıyor: **200 satır 1,6–2,2 sn, 500 satır 29 sn,
   1000 satır 4 dk**. Aynı sırayı üreten doğrusal sürüm 5000 satırda 19 ms.

## Ortam
- VM: 8 çekirdek, 16 GB, Ubuntu 22.04, Xvfb 1920x1080 + openbox, Java 25 (çalıştırma), Java 21 (derleme).
- Ürün: main @ K42, `mvnw install -Pproduct-dbeaver-ce -DskipTests` (CI ile aynı).
- DB: PostgreSQL 14 yerel (SSL), `test/perf/perf_db.sql` → mm_main 1,15 GB (4 şema, ~460 tablo,
  `pcm.product_offer` 2,5 M satır / 10 FK, yetim değerler NOT VALID FK'lerle), mm_config 11 MB
  (mm_main'den sanal FK hedefi), mm_archive 37 MB.
- Conf paketleri: 50 × 10 satır, 5 × 200, 1 × 2000, 1 × 5000 (`Scripts/conf/`).

## Yöntem
- Her senaryo için ayrı JFR kaydı (`jcmd JFR.start`, profil = `profile` + `jdk.SocketRead/Write` eşiği **0 ms**).
- Ana metrik: **UI iş parçacığındaki (`main`) soket işlemleri** = UI'de yapılan DB gidiş-dönüşleri.
  Yerel DB'de her biri <0,1 ms olduğundan burada donma kısa görünür. Uzak DB'de
  **tahmini donma ≈ (soket işlemi / 2) × RTT** (bir sorgu ≈ bir yazma + bir okuma). Örnek RTT: 30 ms.
- İkincil metrik: UI iş parçacığında ≥100 ms kesintisiz meşgul örnek dizileri (Execution + NativeMethod).
- Araçlar (`test/perf/`, çıktılar `~/dbeaver-perf/`): `olc.py` JFR özeti, `kayit.sh` kayıt aç/kapa,
  `x.sh` xdotool, `baslat.sh` başlatma, `bench/Bench.java` sıralama ölçümü.

## Sonuçlar

| # | Senaryo | UI'de soket işlemi | ≥100 ms meşgul | 30 ms RTT'de tahmini donma |
|---|---|---|---|---|
| G1 | Büyük tabloda kaydırma (temiz FK verisi) | 0 | 2 / 0,3 sn | yok |
| G2 | Yetim / NULL açıklamalı FK hücreleri üzerinde fare + kaydırma (~40 sn) | **6 570** | 12 / 2,2 sn | **~98 sn** |
| G3 | Sanal FK hedefi (mm_config) **bağlı değilken** sorgu + fare | **1 582** | 2 / 0,25 sn | ~24 sn + **bağlantı kurma UI'de** |
| G4 | Tek FK hücresini değiştirip kaydetme | **955** | 1 / 0,1 sn | ~14 sn |
| G5 | Filtre kutusunda FK değeri arama (50 bin satırlık sözlük, Türkçe) | 207 | 0 | ~3 sn |
| G6 | Navigator'da 300+ tablolu şemayı açma + F5 | 0 | 0 | yok (*) |
| G7 | Hücre içi FK seçicisini açma (çapraz bağlantı) | **1 362** | 2 / 0,3 sn | ~20 sn |
| C1 | Conf Packages panelini açma (5000 satırlık paket otomatik seçildi) | 33 | 4 / 0,7 sn | ~0,5 sn |
| C3 | 200 satırlık paketi seçme + kopyalama (FK bilgisi yüklenemedi, bkz. B5) | 0 | 0 | — |
| G2L | G2'nin aynısı, **gerçek 30 ms RTT** (`gecikme.py`), 2 sn fare gezdirme | 402 (**4,2 sn** sokette) | 7 / 1,3 sn | ölçüldü: donma kaydedici 2,7 sn + 1,7 sn donma yazdı |
| B | `parentsFirst` tek başına (gerçek koddan birebir kopya) | — | 200: 2,2 sn · 500: 29 sn · 1000: 244 sn | DB'den bağımsız |

(*) Tablo listesi önceden SQL tamamlama tarafından önbelleğe alınmış olabilir; soğuk açılışta tekrar ölçülmeli.

G5 ve G7'deki sorguların tamamı açılan liste grid'i yeniden boyattığı için (B1); filtre/seçici sorgusunun
kendisi arka planda.

## Bulgular ve öneriler (öncelik sırasıyla)

### B1 — FK etiketi: önbellek ıskası boyamada UI'de SQL çalıştırıyor (kritik, G2/G4/G5/G7)
- Yer: `ui/data/hints/FkDictionaryLabels.java:262-297` (`getLabel`), çağıran
  `FkDictionaryHintProvider.getCellHints:68` ← `GridCellRenderer.renderHints:291`, `isOverHintAction:404`,
  `getCellToolTip`. Ölçümde UI soket işlemlerinin **%98'i** bu zincirden.
- Neden: `prefetch` yalnızca bulunan etiketleri koyuyor; bulunamayan değer için `getLabel`
  `getDictionaryValues` ile tek satırlık sorguya düşüyor ve `null` sonucu saklamıyor
  (`if (label != null) columnLabels.put(...)`).
- Öneri:
  1. `prefetch` sorduğu **her** değeri yazsın; bulunamayanlar için boş işaretçi (`""` veya sabit `MISSING`)
     → `getLabel` işaretçiyi görünce `null` döner, sorgu yok.
  2. `getLabel` UI iş parçacığında **asla** sorgu atmasın: ıska olursa değeri bir kümeye ekleyip
     tek bir arka plan işi (debounce ~100 ms) `prefetch` ile toplu çeksin, bitince `redrawGrid()`.
     Grid'de düzenlenen değerin etiketi böylece bir an sonra gelir.
  Not: CLAUDE.md'deki "null sonucu ASLA cache'leme" kuralı metadata (association) içindir; etiket için
  "sorgulandı, yok" bilgisi güvenle saklanabilir (yeniden çekme zaten `invalidateLabels` ile).

### B2 — Kaydet/yenile tüm sekmelerin etiket önbelleğini siliyor (yüksek, G4)
- Yer: `FkDictionaryHintProvider.cacheRequiredData:103-104` → `FkDictionaryLabels.invalidateLabels()`
  (`LABELS.clear()`, global). `ResultSetPersister.java:401` her kaydetmede `cleanupCache=true` çağırıyor.
- Etkisi: tek hücre kaydetmek, açık **tüm** grid'lerin etiketlerini siler; B1 ile birleşince ekrandaki her
  FK hücresi yeniden UI'de sorgulanır (G4: 955 işlem).
- Öneri: `cleanupCache`'te yalnızca bu çağrının `attributes` anahtarlarını temizle
  (`LABELS.keySet().removeAll(attributes)`), sonra mevcut döngü zaten yeniden `prefetch` ediyor.

### B3 — Sanal FK hedefi her boyamada çözülüyor; hedef bağlı değilse UI'de bağlanıyor (yüksek, G3)
- Yer: `model/virtual/DBVEntityForeignKey.java:89-91,170` (`getReferencedConstraint` →
  `getNodeByPath(VoidProgressMonitor)` → `DBUtils.initDataSource`), çağıran
  `FkDictionaryLabels.isExternal:106` ve `getAssociation:83` (hücre başına).
- Ölçüm: G3'te `DBUtils.initDataSource` UI zincirinde; mm_config SSL bağlantısı UI'de kuruldu.
  G2'de de `getRealReferenceConstraint:170` meşgul örneklerin önde gelenlerinden (önbelleksiz yol çözümü).
- Öneri: `FkDictionaryLabels` içinde attr → `(refConstraint, external)` önbelleği (association ile aynı
  `WeakHashMap`), böylece boyamada `getReferencedConstraint` bir kez çağrılır. Hedef bağlantı kapalıysa
  boyamada bağlanma: `container.isConnected()` false ise etiket gösterme, bağlantıyı arka plan işiyle aç
  (veya hiç açma) ve bitince yeniden çiz.

### B4 — Conf Packages: FK sıralaması O(n³) ve UI iş parçacığında (düşük: gerçek paketler 5–20 satır, 50 satır = 86 ms)
- Yer: `ui/controls/resultset/conf/ConfPackages.java:324-358` (`parentsFirst`, `references`),
  çağıran `scriptsBySchema:290` ← `ConfPackagesView.java:313` (paket seçimi, FK bağlantıları gelince
  yeniden kurulum, kopyala düğmeleri). Kodda zaten `ponytail: O(n^3) ... tens of rows` notu var;
  gerçek paketler bu sınırı aşıyor.
- Ölçüm (`bench/Bench.java`, kodun birebir kopyası, çocuk satırlar önce yazılmış paket):
  50 → 86 ms, 200 → 2,2 sn, 500 → 29 sn, 1000 → 244 sn.
- Öneri (aynı çıktı, test edildi): ebeveynleri `tablo.kolon=değer` anahtarıyla indeksle, her satırın
  paket içi ebeveyn sayısını çıkar, "hazır" satırları dosya sırasıyla (`TreeSet`) seç; döngü varsa kalan
  ilk satırı al (mevcut davranış). `Bench.parentsFirstFast` = n=500'de 9 ms, n=5000'de 19 ms; mevcut
  sürümle **aynı sırayı** verdiği assert ile doğrulandı. Ayrıca `scriptsBySchema`'yı link'ler değişmedikçe
  yeniden hesaplamamak için sonucu `currentSql + currentLinks` ile önbelleğe al.
- `referencing` (K30 silme) da her satır için tüm paketi tarıyor; silme seviyesi seçiminde aynı indeks
  kullanılmalı (ölçülmedi, aynı desen).

### B5 — Conf Packages FK bilgisi yalnızca bağlantının varsayılan şemasında aranıyor (orta, işlevsel)
- Yer: `ConfPackagesView.findTable:518-540`. Paketlerde tablo adı şemasız (K42), arama yalnızca
  `defaultSchema`'da. Test DB'sinde tablolar `pcm`'de, varsayılan `public` → renkler/FK sırası hiç
  yüklenmedi, kopyalama "file order (FK info not loaded yet)" verdi; search_path değiştirmek de yetmedi.
- Öneri: paket satırında schema varsa (`pcm.dict_3`) onu kullan; yoksa varsayılan şema, sonra bağlı
  bağlantının tüm şemalarında ada göre ara (önce tek eşleşme). Bu iş zaten arka plan işinde.

### B6 — Conf Packages paneli açılınca grid'in FK etiketleri kayboldu (orta, gözlem)
- C1'de panel açıldıktan sonra açık grid'deki etiketler ve hücre içi seçici kayboldu (düz "Value view"
  açıldı); sorgu yeniden çalıştırılınca geri geldi. C1'de UI'de
  `SpreadsheetPresentation.getFkDictAssociation:1043 → getAttributeReferrers` sorguları görüldü.
  Kesin neden bulunmadı; panel `FkDictionaryLabels`'i paylaşıyor ve `invalidateLabels` global (B2) —
  ilk şüpheli bu. Yeniden üretme: grid açıkken Plus → Conf Packages.

### B7 — Filtre kutusu / hücre içi seçici UI'de iç içe olay döngüsüyle bekliyor (düşük-orta)
- Yer: `ResultSetFilterPanel.java:941` ve `FkValuePickerPopup.java:200` (`UIUtils.waitJobCompletion`).
  Yerelde donma görülmedi (G5: 0 meşgul dizi). Uzak DB'de yazarken her tuşta sorgu bitene kadar
  bekleme olur ve iç içe `readAndDispatch` yeniden giriş hatalarına açık. Öneri: sonucu iş bitince
  `asyncExec` ile listeye koy, eski isteği iptal et (debounce zaten var).

### B8 — İlerleme göstergesi gizli (düşük, algı) — K43'te çözüldü (sağ altta "Working: …")
- `ApplicationWorkbenchWindowAdvisor.java:170-178` ilerleme/durum çubuğunu gizliyor; arka plan işi
  (bağlanma, metadata yükleme) sürerken kullanıcı bunu donma sanıyor. Öneri: en azından ince bir ilerleme
  alanı ya da imleçte "meşgul" göstergesi.

## Test sırasında görülen diğer şeyler
- SQL editöründe `from ` sonrası tamamlama penceresi açılınca hızlı yazılan (xdotool) tuşlar kayboldu.
  Openbox'ın açılan pencereye odak vermesinden olabilir; gerçek masaüstünde tekrar denenmeli.
- Temiz veride (G1) kaydırma akıcı; etiketler arka plan `prefetch` ile geliyor — tasarım doğru, sorun
  yalnızca ıska yolunda.

## Önerilen iş sırası
1. B1 + B2 (tek dosya ağırlıklı, `FkDictionaryLabels` / `FkDictionaryHintProvider`) — en büyük kazanç.
2. B3 (sanal FK hedef önbelleği + bağlıysa göster).
3. B4 yalnızca paketler büyürse.
4. B5, B6, B7, B8.
Her düzeltmeden sonra aynı senaryo `kayit.sh` ile tekrar koşulup "UI'de soket işlemi" sıfıra yakın
olmalı (G2/G4/G7 hedef: 0) ve B ölçümü < 50 ms.

## Yeniden üretme
```
python3 test/perf/gecikme.py 15432 5432 15   # SSH benzeri gecikme (RTT 30 ms), bağlantıyı 15432'ye yönlendir
psql -h 127.0.0.1 -U mm -d postgres -f test/perf/perf_db.sql
~/dbeaver-perf/baslat.sh <ad>          # Xvfb :99'da DBeaver
~/dbeaver-perf/kayit.sh start G2 ; ...senaryo... ; ~/dbeaver-perf/kayit.sh stop G2
java test/perf/Bench.java   # B4 ölçümü
```

## K43 — donma kaydedici (eklendi)
- `ui.mm.workbench/UiFreezeWatchdog`: arayüz ≥1 sn cevap vermezse süre + UI yığını `~/.dbeaver-mm/donma.log`
  dosyasına; ≥2 sn ise sonra bildirim (Tercihler > User Interface > Notifications > "UI freeze" ile kapanır).
  Maliyet: 250 ms'de bir boş iş. Bilgisayar uykuya geçerse (aralık > 2 sn) donma sayılmaz.
- `BackgroundJobIndicator`: kullanıcı işi 1 sn'den uzun sürerse sağ altta "Working: <iş>" etiketi.
- G2L'de doğrulandı: log `FkDictionaryLabels.getLabel ← GridCellRenderer.renderHints` yığınını gösterdi.

## K44 — FK etiketi düzeltmesi: önce / sonra (30 ms RTT, `gecikme.py`)

| Senaryo | K43 (önce): UI'de soket / bekleme | K44 (sonra): UI'de soket / bekleme | Donma kaydı |
|---|---|---|---|
| Karşılıksız FK'li tabloyu açma (A0) | yüzlerce | **0** | yok |
| Fare gezdirme + Page Down (G2L / A1) | 402 / **4,2 sn** | **0 / 0** | önce 2,7 + 1,7 sn, sonra **yok** |
| Tek hücre düzenle + kaydet (G4 / A2) | 955 (yerel) | **0** | yok |
| Filtre kutusu `kolon =` listesi (A3) | 207 (yerel) | **0** (sorgu arka planda) | yok |

Yapılan (`ui/data/hints/FkDictionaryLabels.java`):
- `getLabel` çizim sırasında hiç sorgu atmaz; önbellekte olmayan değerler kuyruğa girer, tek arka plan işi
  (`LOADER`, 100 ms gecikmeli) hepsini `prefetch` ile toplu (IN) çeker ve grid'leri yeniden çizer.
- Sorulup etiketi çıkmayan değer `MISSING` olarak saklanır; FK olmayan kolon (`NOT_FK`) ve sanal FK'nin başka
  bağlantıya gidip gitmediği (`EXTERNAL`) bir kez hesaplanır. Yenileme (`invalidateLabels`) hepsini unutturur.
- Filtre kutusundaki "sonuçtaki değerler" listesi etiketleri tek sorguyla önceden çeker.

Kalan (CPU, DB'ye gitmiyor): upstream `ReferenceCellHintProvider:61` her hücrede sanal FK yolunu
çözüyor (`DBVEntityForeignKey.getRealReferenceConstraint`), fare gezdirmede birkaç ms. Gerekirse
`DBVEntityForeignKey` içinde çözülen kısıtı önbelleğe almak.

## K45 — sorun raporu
Plus › Create Problem Report: masaüstüne (yoksa ev klasörüne) `dbeaver-mm-report-<tarih>.zip`
(info.txt: sürüm, OS, Java, bellek; `donma.log`; Eclipse `.log`; son 4 oturumun `dbeaver-debug*.log`).
Hiçbir şey kendiliğinden gönderilmez; kullanıcı dosyayı iletir. Log'larda SQL metni / tablo adı olabilir, uyarı gösterilir.

## K46 — Conf Packages tabloyu doğru şemada arıyor (B5)
- Önce: `ConfPackagesView.findTable` paketteki şemayı yok sayıp yalnızca bağlantının **varsayılan şemasında**
  arıyordu. Varsayılan şema `public` iken `pcm.*` paketinde FK etiketi, ilişki rengi ve FK sırası hiç
  gelmiyordu; kopyalama sessizce "file order" yapıyordu.
- Sonra: şemalı INSERT (`pcm.dict_3`) o şemada; şemasız INSERT önce varsayılan şemada, sonra bağlı
  bağlantıların tüm şemalarında aranıyor (hepsi arka plan işinde).
- Doğrulandı: varsayılan şema `public` iken `pcm.*` paketi etiket + renk aldı; şemasız `crm` paketi
  (`customer_order` dosyada önce) "in FK order" kopyalandı, `city_district` önce geldi.
- B6 (panel açılınca grid etiketlerinin kaybolması) K44 sonrası tekrar üretilemedi.
