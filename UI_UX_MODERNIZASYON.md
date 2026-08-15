# UI/UX Modernizasyon Önerileri — dbeaver-mm

> **Kapsam:** Tüm uygulama arayüzü (yalnızca sonuç grid'i değil).
> **Format:** Her öneride *sorun → öneri → referans → uygulanabilirlik* (hangi bundle/sınıf,
> zorluk, öncelik).
> **Kardeş doküman:** `DBEAVER_SURUM_KARSILASTIRMA.md` (özellik/edisyon karşılaştırması).
> Bu doküman **özellik** değil **arayüz ve etkileşim** odaklıdır.
> Araştırma tarihi: Ağustos 2026. Kaynaklar en altta.

---

## 0. Nasıl okunmalı

Etiketler:

| Etiket | Anlamı |
|---|---|
| **E1 / E2 / E3** | Efor: E1 = birkaç saat–1 gün, E2 = birkaç gün, E3 = hafta(lar) |
| **Ö1 / Ö2 / Ö3** | Öncelik: Ö1 = en yüksek getiri/efor oranı |
| **CE-safe** | Community API'siyle yapılabilir, `com.dbeaver.*` (PRO) bağımlılığı yok |
| **fork-riski** | Upstream DBeaver dosyasını değiştirir → her merge'de çakışma riski |
| **eklenti** | Yeni bir bundle olarak eklenebilir → upstream'e dokunmaz, en güvenli yol |

**Proje kuralı hatırlatması** (`CLAUDE.md`): DBeaver internal API yerine public/Eclipse standart
API tercih edilir; internal'a mecbur kalınırsa tek bir köprü sınıfında izole edilir
(`DBeaverScriptsBridge`, `InlineFkService` deseni). Aşağıdaki tüm öneriler bu kurala uyacak
şekilde konumlandırılmıştır.

---

## 1. Teşhis — mevcut arayüzün sorunları

Ekran görüntüsü + genel kullanım üzerinden tespit edilen, dış kaynaklarla da örtüşen sorunlar:

### 1.1. Görsel yoğunluk (visual density) kontrolsüz
Tek ekranda **6 ayrı bölge** aynı anda görünüyor: Connections, Projects, Files, editör sekmesi,
sonuç grid'i, SQL editörü, Recent SQL Scripts. Hiçbirinin görsel ağırlığı diğerinden farklı değil —
göz nereye bakacağını bilmiyor. Dış değerlendirmelerde DBeaver için tekrar eden eleştiri tam bu:
*"arayüz kalabalık hissettiriyor; çok sayıda panel, buton ve bağlam menüsü alan için yarışıyor"*
([Beekeeper Studio karşılaştırması](https://www.beekeeperstudio.io/blog/top-5-postgresql-guis)).
Hacker News'te bile savunanlar "iyi tasarlanmış, **kalabalık olsa da**" diye başlıyor.

### 1.2. Toolbar keşfedilebilir değil
Üst şeritte ~25 ikon var; büyük kısmı **etiketsiz, benzer boyutta, gruplanmamış**. Ayırıcılar
mantıksal grup değil, sadece bundle sınırı gösteriyor. Sonuç: kullanıcı ikonu deneyerek öğreniyor.
Karşılaştırma: TablePlus/Beekeeper *"şifreli ikonlar yok, sekme içinde sekme yok"* diye
pazarlanıyor — bu doğrudan DBeaver'a atıf.

### 1.3. Bilgi mimarisi: üç ayrı "ağaç" paneli aynı anda açık
`Connections`, `Projects`, `Files` — üçü de dosya/nesne ağacı, üçü de aynı görsel dile sahip,
aralarındaki ilişki belirsiz. Yeni kullanıcı için "hangi ağaç neye ait" ayrımı yok.

### 1.4. Tipografi ve hizalama tek düzey
Grid'de kolon başlığı, veri hücresi, `[NULL]` değeri, FK etiketi (`10.606 | Order Complete`)
**aynı ağırlık ve renkte**. Yani veri hiyerarşisi yok: birincil değer, türetilmiş etiket ve
"veri yok" hâli görsel olarak ayrışmıyor. Sayı kolonları (`sort_id`, `is_actv`) sağa hizalı ama
tip bilgisi sadece minik `123`/`AZ` rozetinde.

### 1.5. Durum ve geri bildirim dağınık
`200 row(s) fetched - 0,274s (0,01s fetch), on 2026-08-15 at 15:43:46` — kritik bilgi (kaç satır,
ne kadar sürdü) grid'in altındaki gri şeritte, tek satır düz metin olarak. Yanında `200` limit
kutusu, `200+`, `Export data`, `Refresh`, `Save`, `Cancel` karışık sırada. Alt status bar'da
ayrıca `TRT | tr_TR | Writable | Smart Insert | 29:46:960 | Sel: 0|0` var — bunların hiçbiri
veritabanı işiyle ilgili değil (Eclipse metin editöründen miras).

### 1.6. Otomatik tamamlama popup'ı bağlamsız
Screenshot'taki `bsn_flow_spec → shrt_code` popup'ı 15+ değeri **düz liste** hâlinde gösteriyor;
gruplama, tip ikonu, eşleşen karakter vurgusu (fuzzy highlight) veya sayaç yok. Modern
karşılıklarında (DataGrip completion, VS Code) eşleşen harfler vurgulanır, ikon tip belirtir,
alt satırda açıklama gösterilir.

### 1.7. Algılanan performans
Bu bir "his" sorunu olduğu kadar gerçek bir sorun: DBeaver deposundaki
[19162 numaralı tartışmada](https://github.com/orgs/dbeaver/discussions/19162) kullanıcılar
navigator ağacını açmanın, sekme taşımanın, pencere boyutlandırmanın rakiplerden yavaş olduğunu
bildiriyor; DBeaver bakımcısı (serge-rider) sorunun büyük ölçüde **Eclipse platformunun
yüksek-DPI render davranışından** kaynaklandığını doğruluyor. UI/UX modernizasyonunda
"daha az yeniden çizim" bir tasarım kısıtı olarak ele alınmalı.

### 1.8. Boş durum (empty state) yok
Recent SQL Scripts panelindeki `desc`, `sadasdasdas` gibi kartlar gösteriyor ki panel dolu ama
anlamsız içerik gösterebiliyor. Uygulama genelinde "henüz bağlantı yok / sonuç yok / script yok"
durumları için yönlendirici boş ekran tasarımı yok.

---

## 2. Rakip/benzer uygulamalar — hangi UX kalıbı nereden alınmalı

| Ürün | Alınacak kalıp | Neden |
|---|---|---|
| **TablePlus** | Minimal kabuk, tek birincil panel, native his | Kalabalık algısını kıran en net referans |
| **Beekeeper Studio** | "Şifreli ikon yok" ilkesi, açık etiketli aksiyonlar, temiz boş durumlar | Açık kaynak → tasarım kararları incelenebilir |
| **JetBrains DataGrip** | Search Everywhere, Recent Locations (`Ctrl+Shift+E`), File Structure popup (`Ctrl+F12`), nesne arama ile navigasyon | Klavye-öncelikli navigasyon; ağaçta gezinmeye alternatif |
| **VS Code** | Command Palette (`Ctrl+Shift+P`), fuzzy eşleşme vurgusu, sekme renk/ikon dili | Sektör standardı hâline gelmiş etkileşim modeli |
| **Navicat / DbVisualizer** | Bağlantı-seviyesi renk kodu, ortam ayrımı (prod/test) | Yanlış ortamda çalıştırma hatasını azaltır |
| **CloudBeaver** | Sadeleştirilmiş, tek amaçlı ekranlar | Aynı ekibin "az panelli" yorumu — referans olarak kullanılabilir |

Not: DataGrip'in **Search Everywhere**'ine karşılık Eclipse'te `Ctrl+3` Quick Access zaten var —
sorun yokluğu değil, **veritabanı-farkında olmaması** (tabloya, kolona, bağlantıya gitmiyor).

---

## 3. Tasarım ilkeleri (bundan sonraki her karar bunlara uymalı)

1. **Varsayılan sade, derinlik istek üzerine.** Panel/ikon varsayılanda gizli olabilir; güçlü
   kullanıcı açar. Şu an tersi: her şey açık, kullanıcı kapatmayı öğrenmek zorunda.
2. **Tek birincil odak.** Her an ekranda görsel olarak baskın **bir** bölge olmalı (aktif editör
   veya aktif sonuç). Diğerleri düşük kontrastla geri çekilmeli.
3. **Klavye birinci sınıf.** Fare ile ağaçta gezinme yerine "yaz ve git". Her yeni özelliğin
   klavye yolu olmalı.
4. **Anlam taşıyan renk, dekoratif değil.** Renk sadece üç şey için: ortam (prod/test), durum
   (hata/uyarı/başarı), veri tipi ipucu. Bunun dışında nötr gri skala.
5. **Çizim maliyeti bir tasarım kısıtı.** Gölge, animasyon, gradient, sürekli hover efekti
   Eclipse/SWT'de pahalıdır (§1.7). Her efekt "yeniden çizim bütçesi" ile değerlendirilir.
6. **Upstream'e dokunmadan.** Mümkün olan her modernizasyon **yeni bundle** veya **CSS teması**
   olarak yapılır; `LightGrid`/`SpreadsheetPresentation` gibi upstream dosyalarına ancak
   zorunluluk hâlinde ve izole edilmiş şekilde dokunulur.

---

## 4. Öneriler

### A. Görsel dil ve tema

#### A1. Kendi CSS temanız — "dbeaver-mm Dark/Light" · **E2 · Ö1 · CE-safe · eklenti**

**Sorun:** Mevcut dark tema Eclipse varsayılanının türevi; kontrast oranları tutarsız
(DBeaver 26.1 release notlarında hâlâ *"dark temada dikey sekmelerin ve bazı toolbar öğelerinin
beyaz arkaplanı düzeltildi"* maddesi var — yani tema tutarlılığı upstream'de de bitmemiş bir iş).

**Öneri:** `org.eclipse.e4.ui.css.swt.theme` extension point'i ile **kendi temanızı** kaydedin.
Somut değişiklikler:
- Panel arkaplanı ile editör arkaplanı arasında **bilinçli 1 kademe fark** (aktif alan en açık/en koyu).
- Seçili sekme için sol/üst 2px **accent şerit**; kalın çerçeve yerine.
- Tablo başlık arkaplanı (`swt-header-background-color`) gövdeden ayrışsın.
- Ayırıcı (sash) çizgileri 1px ve düşük kontrast — şu an panel sınırları veriden daha görünür.

**Uygulanabilirlik:** Yeni bundle (ör. `org.jkiss.dbeaver.ui.theme.mm`), içinde `theme.css` +
`plugin.xml`:
```xml
<extension point="org.eclipse.e4.ui.css.swt.theme">
  <theme id="org.jkiss.dbeaver.ui.theme.mm.dark"
         label="dbeaver-mm Dark"
         basestylesheeturi="css/mm-dark.css"/>
</extension>
```
**Kısıt:** SWT'de **menüler CSS ile stillenemez** (platform sınırı) — menü görünümü OS'a bağlı kalır.
Kaynak: [Eclipse 4 CSS Styling](https://www.vogella.com/tutorials/Eclipse4CSS/article.html).

#### A2. Tipografik hiyerarşi · **E1 · Ö1 · fork-riski (grid) / eklenti (tema)**

**Sorun:** §1.4 — grid'de her şey aynı ağırlıkta.

**Öneri:**
- **Kolon başlığı:** biraz daha küçük punto + %70 opaklıkta renk, **tümü büyük harf değil**
  (Türkçe I/ı tuzağı! bkz. `CLAUDE.md` tuzak #0 — başlık dönüşümü yapılacaksa `Locale.ROOT`).
- **`[NULL]`:** italik + %45 opaklık gri. Şu an normal metinle karışıyor.
- **FK dictionary etiketi** (`10.606 | Order Complete` — sizin PoC'unuz): ayırıcıdan sonraki
  kısım **%65 opaklık**; birincil değer olan ID tam kontrastta kalsın. Bu, PoC'un okunabilirliğini
  tek başına belirgin şekilde artırır.
- Veri hücrelerinde **tabular figures** (sabit genişlikli rakam) — sayı kolonlarında hizalama için.

**Uygulanabilirlik:** FK etiketi ve NULL stili `SpreadsheetPresentation` /
`GridColumnRenderer`'da renk seçimi noktasında. **Kritik:** `CLAUDE.md` tuzak #7 gereği
`PaintListener` içinden `UIStyles.mix/lighten` çağrılmaz → `UIUtils.getSharedColor(RGB)` ile
intern edin, yoksa her çizimde `Color` sızdırırsınız.

#### A3. İkon seti tutarlılığı · **E2 · Ö2 · eklenti**

**Sorun:** Toolbar'da farklı dönemlerden gelmiş ikonlar bir arada — bazıları renkli-detaylı,
bazıları düz çizgi. Ortak grid, ortak çizgi kalınlığı, ortak optik boyut yok.

**Öneri:** Tek bir ikon dili seçin (16px grid, 1.5px stroke, sadece accent renginde tek vurgu).
En çok kullanılan **~15 aksiyonu** yeniden çizmek yeterli — tamamını değil. Kendi eklentilerinizde
(`recentscripts` zaten SVG kullanıyor: `icons/recent_scripts.svg`) bu dili baştan uygulayın.

---

### B. Pencere düzeni ve bilgi mimarisi

#### B1. Varsayılan perspektifi sadeleştir · **E1 · Ö1 · CE-safe · ürün yapılandırması**

**Sorun:** §1.3 — üç ağaç paneli aynı anda açık.

**Öneri:** Varsayılan perspektifte **sol tarafta yalnızca Connections** açık kalsın;
`Projects` ve `Files` aynı stack'e **sekme olarak** yerleşsin (üçü yan yana üç ayrı sekme,
ama sadece Connections seçili). Recent SQL Scripts paneli sağda **minimize edilmiş** başlasın —
kullanıcı ikona basınca açılsın.

**Uygulanabilirlik:** `plugin.xml` `perspectiveExtensions` / perspektif fabrikası.
**Tuzak (kendi notunuzdan):** `perspectiveExtensions` **kalıcılaşmış perspektife uygulanmaz** →
mevcut workspace'te etkisini görmek için `Window → Reset Perspective`. Yeni kurulumlarda sorun yok.

#### B2. "Zen / Focus mode" · **E1 · Ö2 · eklenti**

Tek kısayolla (`Ctrl+Shift+F` gibi) tüm yan panelleri gizleyip **sadece SQL editörü + sonuç**
bırakan bir komut. Eclipse'te bu neredeyse bedava: `IWorkbenchPage.setPartState(...)` /
`toggleZoom` üzerinden. En düşük eforla en görünür "modern his" kazancı.

**Uygulanabilirlik:** Yeni bundle içinde tek `AbstractHandler` + `plugin.xml` command/binding.
Upstream'e sıfır dokunuş.

#### B3. Editör/sonuç oranını hatırla · **E1 · Ö3**

Şu an SQL editörü ile sonuç grid'i arasındaki sash oranı sekme başına sıfırlanabiliyor.
Kullanıcının son tercih ettiği oranı dialog settings'e yazıp geri yükleyin. Küçük ama günde
onlarca kez hissedilen bir sürtünme.

---

### C. Komut erişimi ve toolbar

#### C1. Veritabanı-farkında komut paleti · **E2–E3 · Ö1 · CE-safe · eklenti** ⭐

**Sorun:** §1.2 + §2 — ikon avlamak zorunda kalmak. Eclipse `Ctrl+3` var ama tablo/kolon/bağlantı
bilmiyor.

**Öneri:** `Ctrl+Shift+P` ile açılan tek bir popup; içine yazınca **fuzzy** olarak eşleştirsin:
- bağlantılar (`etiya`, `bss_common_dc_t3`)
- tablolar/görünümler (`bsn_flow_spec_wflw_config`)
- son scriptler (Recent Scripts eklentiniz zaten bu veriyi üretiyor)
- komutlar (Commit, Rollback, Export data…)

Eşleşen karakterler **vurgulanmalı**, satırda tip ikonu + kaynak (hangi bağlantı) yazmalı.
Bu, DataGrip'in Search Everywhere'inin CE karşılığıdır ve tek başına "modern" algısını en çok
değiştiren özelliktir.

**Uygulanabilirlik:** Yeni bundle. Mimari olarak sizin mevcut desenlerinizle birebir uyumlu:
- `core/CommandPaletteBridge.java` — **DBeaver API'sine dokunan tek sınıf** (navigator ağacından
  nesne listesi, `SQLEditorUtils` ile scriptler, `ICommandService` ile komutlar).
- `core/FuzzyMatcher.java` — saf fonksiyon, Eclipse'siz → doğrudan test edilebilir
  (`ScriptPreviewParser` ile aynı yaklaşım).
- `ui/CommandPalettePopup.java` — `FkPickerPopup`'ın kardeşi; filtre kutusu + `Table`,
  ok/Enter/Esc.

**⚠️ Türkçe locale tuzağı:** Fuzzy eşleştirmede **kesinlikle** `toLowerCase(Locale.ROOT)`.
`CLAUDE.md` tuzak #0'da bu tam olarak yaşandı (`"JOIN".toLowerCase()` → `"joın"`). Komut
paletinde aynı hata tüm `I` içeren tabloları (`bsn_inter_*` — sizde onlarca var) bulunamaz yapar.

**Performans:** Nesne listesi **lazy + limitli** olmalı (`InlineFkService` kuralınız: LIMIT + debounce).
Metadata hazır değilken boş dönebilir → arka plan job'ında `DBRProgressMonitor` ile çekin,
render anında değil (FK PoC tuzak #1).

#### C2. Toolbar'ı üçe indir · **E1 · Ö1 · fork-riski (düşük)**

**Öneri:** Varsayılan toolbar'da yalnızca üç grup kalsın:
1. **Bağlam:** aktif bağlantı + veritabanı/şema seçici (zaten var, ama görsel olarak birinci sınıf olsun)
2. **Çalıştır:** Execute / Execute script / Explain
3. **İşlem:** Commit / Rollback / Auto-commit anahtarı

Geri kalanı `>>` taşma menüsüne. Kullanıcı `Window → Customize Perspective` ile geri açabilir.
Bu tek değişiklik §1.2'nin büyük kısmını çözer.

#### C3. Bağlantı seçicisi görsel olarak yükseltilsin · **E1 · Ö2**

Şu an `etiya` ve `bss_common_dc_t3@etiya` diğer 25 ikonla aynı görsel ağırlıkta. Oysa
**"hangi veritabanına bağlıyım"** bu uygulamadaki en yüksek riskli bilgidir. Öneri: bağlantı
rengiyle (bkz. G1) boyalı bir chip/rozet hâline getirin, biraz daha yüksek kontrast verin.

---

### D. Navigator (Connections ağacı)

#### D1. Ağaçta anında filtreleme (type-to-filter) · **E2 · Ö2**

`Filter connections by name` kutusu var ama **sadece bağlantı** adına bakıyor; ağaç açıkken
tablo adına göre süzmüyor. Öneri: ağaç odaktayken yazmaya başlayınca **görünen düğümlerde**
canlı filtre + eşleşen harflerin vurgulanması.

**Uygulanabilirlik:** JFace `ViewerFilter` + `StyledCellLabelProvider` (eşleşme vurgusu için).
Standart Eclipse API — internal'a gerek yok. Yine `Locale.ROOT`.

#### D2. Boyut rozetlerini geri plana at · **E1 · Ö3**

Sağdaki `32K`, `144K`, `312K` rozetleri veri adlarıyla aynı kontrastta ve gözü sürekli sağa
çekiyor. %50 opaklık + daha küçük punto yeterli; bilgi kaybolmaz, gürültü kaybolur.

#### D3. Favoriler / son kullanılanlar ağacın tepesinde · **E2 · Ö2 · eklenti**

Recent Scripts eklentinizde **yıldız/pin mekanizması zaten çözülmüş durumda**
(`recent-scripts.favorite` resource property, `MAX_FAVORITES=50`). Aynı deseni navigator'daki
tablolara uygulayın: ağacın en üstünde "Pinned" ve "Recent" bölümleri. Kod deseni hazır, sadece
farklı bir nesne tipine uygulanacak.

---

### E. Sonuç grid'i (Data editor) — ekranın kalbi

#### E1. Sonuç özet şeridini yeniden tasarla · **E1 · Ö1**

**Sorun:** §1.5 — `200 row(s) fetched - 0,274s (0,01s fetch), on 2026-08-15 at 15:43:46` düz metin.

**Öneri:** Üç ayrı, hizalanmış öğe:
`⟨200 satır⟩  ⟨0,27 sn⟩  ⟨15:43:46⟩` — satır sayısı en yüksek kontrastta, süre orta, zaman damgası
en düşük. Limitin dolduğunu gösteren `200+` bir **uyarı rengi** taşısın (kullanıcı "veri bu kadar mı"
yanılgısına düşmesin — bu gerçek bir hata kaynağı).

#### E2. Aksiyon çubuğunu grupla · **E1 · Ö2**

Şu an sıra: `Refresh | Save Cancel | (4 ikon) | (5 navigasyon oku) | Export data | (ayar) | 200 | 200+`.
Öneri: **okuma** (Refresh, navigasyon, limit) solda; **yazma** (Save, Cancel) sağda ve
`Save` **değişiklik varken renkli**, yokken pasif. Şu an kaydedilmemiş değişiklik olup olmadığı
butona bakınca anlaşılmıyor.

#### E3. Kolon başlığı bilgi yoğunluğu · **E2 · Ö2 · fork-riski**

`123`/`AZ` tip rozetleri iyi bir fikir ama ince: PK/FK ayrımı, nullable olup olmadığı görünmüyor.
Öneri: başlıkta **anahtar ikonu (PK)** ve **bağlantı ikonu (FK)**; hover'da tooltip ile tam tip
(`NUMBER(10,0) NOT NULL`).

**⚠️ Geometri tuzağı:** `CLAUDE.md` tuzak #3 — başlık butonlarının hit-test'i
(`GridColumn.isOverFkDictButton`) ile `GridColumnRenderer.paint` çizim sırası **elle senkron**
tutuluyor (sağdan: filter, sort, FK butonu). Başlığa yeni ikon eklemek bu geometriyi kırar.
**Öneri:** yeni ikon eklemeden önce başlık ikonlarını **veri-güdümlü bir listeye** (sıralı
`List<HeaderAdornment>`) çevirin; çizim ve isabet testi aynı listeden üretilsin. Bu refactor
E2'dir ama bundan sonraki her başlık özelliğini E1'e indirir.

#### E4. Satır yoğunluğu ayarı (Compact / Comfortable) · **E1 · Ö3**

Tek bir tercih ile satır yüksekliği 3 kademe. Yoğun veri bakan kullanıcı compact, sunum yapan
comfortable seçer. Modern istemcilerin neredeyse tamamında var.

#### E5. Grid'i NatTable'a taşıma — **ÖNERİLMEZ**

Nebula NatTable ("high performance SWT data grid") teorik olarak daha zengin stil imkânı sunar,
ancak DBeaver'ın `LightGrid`'i **zaten owner-drawn ve virtual**; taşıma maliyeti hafta(lar)
seviyesinde ve upstream ile merge'i kalıcı olarak kırar. **Karar: yapılmasın.** İhtiyaç duyulan
her şey `LightGrid` üzerinde çizim seviyesinde elde edilebilir (E1–E4 bunu kanıtlıyor).

---

### F. SQL editörü

#### F1. Otomatik tamamlama popup'ını modernleştir · **E2 · Ö1**

**Sorun:** §1.6 — düz liste, vurgu yok, bağlam yok.

**Öneri:** (screenshot'taki `bsn_flow_spec → shrt_code` popup'ı sizin **inline FK picker**
eklentiniz — yani bu tamamen sizin kontrolünüzde, upstream'e dokunmadan iyileştirilebilir):
- **Eşleşen karakterleri kalın/accent** göster (fuzzy highlight).
- Satır sonunda kaç kayıtla eşleştiği veya ikincil açıklama kolonu (label) gösterilsin —
  `getDictionaryEnumeration` zaten `DBDLabelValuePair` döndürüyor, **label elinizde, kullanılmıyor**.
- Üstte "kaç sonuç / LIMIT 50'ye takıldı mı" bilgisi.
- Popup genişliği içeriğe göre (şu an sabit görünüyor, uzun değerler kesiliyor).

**Uygulanabilirlik:** `org.jkiss.dbeaver.ui.inlinefkpicker/ui/FkPickerPopup.java`.
Vurgu için `Table` yerine `StyledText`/owner-drawn `TableItem` (`SWT.MeasureItem`/`PaintItem`)
gerekir. **Renkleri `getSharedColor` ile alın** (tuzak #7).

#### F2. Aktif sorgu vurgusu · **E2 · Ö2**

İmlecin içinde bulunduğu statement'ın arkaplanı çok hafif (%3–5) farklı olsun. `Alt+X` (tümü) ile
`Ctrl+Enter` (tek statement) arasındaki farkı görsel yapar — `CLAUDE.md` tuzak #5'te bu ayrımın
karışıklık yarattığı zaten kayıtlı.

**Uygulanabilirlik:** Statement sınırlarını bulan altyapı **zaten var**:
`SQLEditorBase.extractQueryAtPos` (inline FK picker'da kullanıyorsunuz). Geriye sadece
`StyledText` arkaplan çizimi kalıyor. Performans: caret hareketinde debounce şart.

#### F3. Editör sekmesinde bağlantı rengi · **E1 · Ö1**

Sekme başlığında (`<etiya> cmd_config`) bağlantının renk kodu ince bir şerit olarak görünsün.
Çok sekmeli çalışmada "yanlış sekmede çalıştırma" hatasını doğrudan azaltır.

**Doğrulama:** Bu, upstream'de yıllardır açık ve talep gören bir eksik —
[dbeaver#7917 "Environment/connection of each tab cannot be distinguished (despite connection color)"](https://github.com/dbeaver/dbeaver/issues/7917)
ve [dbeaver#15808 "add color coding to tabs"](https://github.com/dbeaver/dbeaver/issues/15808).
Yani fork'unuzda çözülmesi hem gerçek bir ihtiyaç hem de upstream'e katkı adayı.

---

### G. Bağlantı yönetimi ve güvenlik hissi

#### G1. Ortam rengi — mevcut ama görünmez · **E1 · Ö1** ⭐

DBeaver CE'de bağlantı ayarlarında **renk atama zaten var** (bu PRO özelliği olan koşullu satır
renklendirmesinden farklıdır). Sorun: **keşfedilebilir değil ve yeterince belirgin uygulanmıyor.**

**Öneri:**
1. Yeni bağlantı sihirbazının **ilk adımına** "Ortam" seçimi koyun: Geliştirme / Test / **Üretim**
   → renk otomatik atansın (yeşil/sarı/kırmızı).
2. Renk **her yerde** görünsün: navigator düğümü, editör sekmesi, toolbar bağlantı chip'i,
   sonuç grid'inin üst kenarı.
3. Üretim bağlantısında `DELETE`/`UPDATE`/`DROP` çalıştırılırken **onay diyaloğu** (opt-in tercih).

Bu, listedeki en yüksek "gerçek fayda / efor" oranına sahip maddedir ve tamamen CE API'siyle
yapılabilir.

**Doğrulama:** Rengin **tutarsız uygulanması** upstream'de bilinen bir sorun —
[dbeaver#5705](https://github.com/dbeaver/dbeaver/issues/5705) (navigator'daki bağlantı öğesinin
rengi bağlantı rengiyle eşleşmiyor), [dbeaver#19859](https://github.com/dbeaver/dbeaver/issues/19859)
(aktif veri kaynağı değişince renk güncellenmiyor). Yani "renk her yerde ve doğru" hedefi
gerçekten eksik olan şeydir; sıfırdan bir mekanizma kurmuyorsunuz, var olanı tamamlıyorsunuz.

#### G2. Bağlantı listesi kart görünümü · **E2 · Ö3**

İlk açılışta ağaç yerine, son kullanılan bağlantıları **kart** olarak gösteren bir karşılama
ekranı. Kart deseni zaten elinizde: `ui/RecentScriptCard.java` (owner-drawn `Canvas`,
focus + tab traversal çözülmüş). **Kritik detay (kendi notunuzdan):** `Canvas.computeSize`
override edilmezse her kart 64×64 çizilir.

---

### H. Onboarding ve boş durumlar

#### H1. Anlamlı boş ekranlar · **E1–E2 · Ö2**

Her ana bölge için: **ne olduğu + tek bir birincil aksiyon**.
- Connections boşken: "Henüz bağlantı yok" + `Yeni bağlantı` butonu.
- Sonuç grid'i boşken: "Sorgu çalıştırın (Ctrl+Enter)".
- Recent Scripts boşken: "Kaydettiğiniz scriptler burada görünür".

Kendi eklentinizde (`RecentScriptsView`) bu bedava; upstream panellerde biraz daha iş.

#### H2. Kısayol keşfi · **E1 · Ö3**

İlk çalıştırmada kapatılabilir tek satırlık ipucu şeridi ("Komut paleti: Ctrl+Shift+P").
Abartılmamalı — tur/wizard değil, tek satır.

---

### I. Algılanan performans (§1.7'nin karşılığı)

#### I1. Çizim bütçesi kuralları · **E1 · Ö1 · süreç**

Kod inceleme kuralı hâline getirin — `CLAUDE.md`'ye eklenmeye değer:
1. `PaintListener` içinde **asla** `new Color`/`new Font` yok → `UIUtils.getSharedColor(RGB)`.
   (Bu kural zaten notlarınızda var, artık **tüm eklentiler için** genelleştirin.)
2. Hover efektleri **yalnızca değişen bölgeyi** `redraw(x,y,w,h,false)` ile yeniler; tam
   `redraw()` yok.
3. `BaseThemeSettings` fontları alanda cache'lenmez (tema değişiminde dispose edilir).
4. Animasyon yok. Modern görünüm animasyonla değil **hizalama, boşluk ve kontrastla** elde edilir —
   ve Eclipse/SWT'de animasyon en pahalı şeydir.

#### I2. Uzun işlemlerde iskelet (skeleton) yerine ilerleme · **E1 · Ö3**

Sorgu çalışırken grid'i boşaltmak yerine önceki sonucu **soluk** tutup üstte ince bir progress
şeridi gösterin. Algılanan hız artar, gerçek maliyet ~sıfır.

---

### J. Erişilebilirlik ve yerelleştirme

#### J1. Kontrast denetimi · **E1 · Ö2**
Yeni temada (A1) tüm metin/arkaplan çiftleri **WCAG AA (4.5:1)** üstünde olmalı. `[NULL]` ve
FK etiketi gibi bilinçli olarak soluklaştırılan öğelerde alt sınır **3:1**.

#### J2. Klavye ile tam gezinilebilirlik · **E1 · Ö2**
Yeni eklenen her owner-drawn widget'ta `Canvas` + tab traversal + odak göstergesi
— `RecentScriptCard`'da zaten doğru yapılmış, referans alın. `Label` kullanmayın (odak almaz).

#### J3. Türkçe locale · **E1 · Ö1 · KURAL**
`CLAUDE.md` tuzak #0'ın UI tarafındaki karşılığı: **arayüzde gösterilen** hiçbir metin
no-arg `toUpperCase()`/`toLowerCase()` ile dönüştürülmemeli. "ID" → "ıd", "INSERT" → "ınsert"
olur. Kolon başlıklarını büyük harfe çevirme fikri (A2) bu yüzden ya `Locale.ROOT` kullanmalı
ya da hiç yapılmamalı.

---

## 5. Özet matris

| # | Öneri | Etki | Efor | Öncelik | Nereye dokunur |
|---|---|---|---|---|---|
| G1 | Ortam rengi (prod/test) her yerde | ★★★ | E1 | **Ö1** | Bağlantı ayarları + tema |
| C1 | Veritabanı-farkında komut paleti | ★★★ | E2–E3 | **Ö1** | Yeni bundle |
| C2 | Toolbar'ı üç gruba indir | ★★★ | E1 | **Ö1** | `plugin.xml` / perspektif |
| B1 | Varsayılan perspektifi sadeleştir | ★★★ | E1 | **Ö1** | `perspectiveExtensions` |
| A1 | Kendi CSS teması | ★★☆ | E2 | **Ö1** | Yeni bundle (tema) |
| A2 | Tipografik hiyerarşi (NULL, FK etiketi) | ★★☆ | E1 | **Ö1** | `SpreadsheetPresentation` |
| E1 | Sonuç özet şeridi | ★★☆ | E1 | **Ö1** | Result set toolbar |
| F1 | FK picker popup'ı modernleştir | ★★☆ | E2 | **Ö1** | `FkPickerPopup` (sizin) |
| F3 | Sekmede bağlantı rengi | ★★☆ | E1 | **Ö1** | Editör sekme dekoratörü |
| I1 | Çizim bütçesi kuralları | ★★☆ | E1 | **Ö1** | Süreç / `CLAUDE.md` |
| J3 | Locale.ROOT kuralı (UI metinleri) | ★★★ | E1 | **Ö1** | Süreç / kod inceleme |
| B2 | Focus mode kısayolu | ★★☆ | E1 | Ö2 | Yeni handler |
| D1 | Ağaçta type-to-filter + vurgu | ★★☆ | E2 | Ö2 | JFace `ViewerFilter` |
| D3 | Navigator'da pin/recent | ★★☆ | E2 | Ö2 | recentscripts deseni |
| E2 | Aksiyon çubuğu gruplama + Save durumu | ★★☆ | E1 | Ö2 | Result set toolbar |
| E3 | Başlık ikonlarını veri-güdümlü yap | ★★☆ | E2 | Ö2 | `GridColumn(Renderer)` |
| F2 | Aktif sorgu vurgusu | ★★☆ | E2 | Ö2 | SQL editör |
| A3 | İkon seti tutarlılığı | ★☆☆ | E2 | Ö2 | İkon varlıkları |
| H1 | Boş durum ekranları | ★☆☆ | E1–E2 | Ö2 | Çeşitli view'lar |
| C3 | Bağlantı chip'i vurgusu | ★☆☆ | E1 | Ö2 | Toolbar |
| J1/J2 | Kontrast + klavye denetimi | ★☆☆ | E1 | Ö2 | Tema / widget'lar |
| D2 | Boyut rozetlerini soluklaştır | ★☆☆ | E1 | Ö3 | Navigator label provider |
| E4 | Satır yoğunluğu ayarı | ★☆☆ | E1 | Ö3 | Grid tercihleri |
| B3 | Sash oranını hatırla | ★☆☆ | E1 | Ö3 | SQL editör |
| G2 | Bağlantı kartı karşılama ekranı | ★☆☆ | E2 | Ö3 | Yeni view |
| H2 | Kısayol keşfi ipucu | ★☆☆ | E1 | Ö3 | Yeni bundle |
| I2 | Solgun sonuç + progress | ★☆☆ | E1 | Ö3 | Result set |
| E5 | ~~NatTable'a taşıma~~ | — | E3 | **YAPMA** | — |

---

## 6. Önerilen yol haritası

**Faz 1 — "Bedava kazançlar" (yaklaşık 2–3 gün, upstream'e ~sıfır dokunuş)**
B1 (perspektif) → C2 (toolbar) → G1 (ortam rengi) → B2 (focus mode) → I1 + J3 (kurallar).
Bu beş madde §1.1, §1.2, §1.3'ün büyük kısmını çözer ve merge riski taşımaz.

**Faz 2 — "Görsel kimlik" (yaklaşık 1 hafta)**
A1 (tema) → A2 (tipografi) → E1 + E2 (sonuç şeridi) → F3 (sekme rengi) → J1 (kontrast denetimi).

**Faz 3 — "Yeni yetenek" (yaklaşık 2 hafta)**
C1 (komut paleti — bu projenin bir sonraki büyük eklentisi olmaya en uygun aday) → F1 (FK popup) →
D1 + D3 (navigator) → F2 (aktif sorgu).

**Faz 4 — Cila**
E3 (başlık refactor) → A3 (ikonlar) → H1/H2 → E4, B3, G2, I2.

---

## 7. Eclipse RCP/SWT kısıtları — baştan bilinmesi gerekenler

| İstenen | Durum |
|---|---|
| Menülerin görünümünü değiştirme | **Mümkün değil** — SWT desteklemiyor, OS'a bağlı |
| Yuvarlak köşe / gölge / blur | Pratikte hayır (owner-drawn hariç, pahalı) |
| Tablo/ağaç başlık rengi | Evet — `swt-header-background-color` |
| Sekme (CTabFolder) stilleri | Evet — CSS ile geniş kontrol |
| Gradient arkaplan | Evet (linear/radial), ama çizim maliyeti var |
| Dinamik tema değişimi | Evet — `:selected`, `:disabled` pseudo-class'ları dahil |
| Özel widget çizimi | Evet — `Canvas` + `PaintListener` (`RecentScriptCard` örneği) |
| Yüksek DPI'da akıcı render | **Sınırlı** — platform kaynaklı, upstream'de bilinen sorun |

Kaynak: [Eclipse 4 CSS Styling](https://www.vogella.com/tutorials/Eclipse4CSS/article.html),
[E4/CSS wiki](https://wiki.eclipse.org/E4/CSS/),
[dbeaver#19162 performans tartışması](https://github.com/orgs/dbeaver/discussions/19162).

---

## 8. Bu projeye özel notlar

- **Yeni bundle eklerken üç kayıt yeri:** `plugins/pom.xml` (desktop profili), ilgili
  `features/*/feature.xml`, ve PDE launch config. Üçüncüsü unutulursa **hata sessizdir**:
  toolbar butonu hiç çıkmaz (`CLAUDE.md`, recentscripts tuzak #10).
- **`Bundle-Version` (`x.y.z.qualifier`) ile pom `<version>` (`x.y.z-SNAPSHOT`) birebir eşleşmeli.**
- **Bağımlılıklar `MANIFEST.MF` `Require-Bundle`'a**, `pom.xml`'e değil. Kaynak kökü `src/`.
- **CE uyumluluğu:** yukarıdaki hiçbir öneri `com.dbeaver.*` bundle'ına ihtiyaç duymaz;
  `visibleWhen` içine `hasPermission` testi konmamalı.
- **DBeaver API'sine dokunan sınıf sayısı bir olmalı** — her yeni eklentide bir "bridge"
  (`DBeaverScriptsBridge`, `InlineFkService` gibi). Komut paletinde bu `CommandPaletteBridge`.

---

## Kaynaklar

- [Beekeeper Studio — Top 5 PostgreSQL GUIs (2026)](https://www.beekeeperstudio.io/blog/top-5-postgresql-guis) — DBeaver, pgAdmin, TablePlus, DataGrip UI eleştirileri
- [Hacker News — DBeaver arayüz tartışması](https://news.ycombinator.com/item?id=39664499)
- [dbeaver/dbeaver Discussion #19162 — grafik performansı](https://github.com/orgs/dbeaver/discussions/19162) — bakımcı yanıtı dahil
- [DBeaver 26.1 sürüm notları](https://dbeaver.io/2026/05/31/dbeaver-26-1/) — dark tema düzeltmeleri
- [JetBrains — DataGrip'in 10. yılı, gizli özellikler](https://blog.jetbrains.com/datagrip/2025/12/16/datagrip-turns-10-hidden-gems-of-datagrip/)
- [JetBrains — DataGrip navigasyon özellikleri](https://www.jetbrains.com/datagrip/features/navigation.html)
- [Vogella — Eclipse 4 CSS Styling](https://www.vogella.com/tutorials/Eclipse4CSS/article.html)
- [Eclipsepedia — E4/CSS](https://wiki.eclipse.org/E4/CSS/)
- [Eclipse Nebula NatTable](https://eclipse.dev/nattable/)
- [UX Patterns — Command Palette](https://uxpatterns.dev/patterns/advanced/command-palette)
- [Setapp — TablePlus vs DBeaver](https://setapp.com/app-reviews/tableplus-vs-dbeaver)
- Upstream issue'ları (bağlantı rengi): [#7917](https://github.com/dbeaver/dbeaver/issues/7917),
  [#15808](https://github.com/dbeaver/dbeaver/issues/15808),
  [#5705](https://github.com/dbeaver/dbeaver/issues/5705),
  [#19859](https://github.com/dbeaver/dbeaver/issues/19859)
- [DbVis — Best DBeaver Alternatives 2026](https://www.dbvis.com/thetable/best-dbeaver-alternatives-of-2025/)
