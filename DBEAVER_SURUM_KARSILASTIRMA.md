# DBeaver Sürüm Karşılaştırması ve Rakip Uygulamalar

> Bu doküman, DBeaver'ın ücretsiz **Community** sürümünde bulunmayan ancak ücretli
> (**PRO / Lite / Enterprise / Ultimate / Team**) sürümlerde bulunan özellikleri;
> her özelliğin ne olduğunu ve ne işe yaradığını açıklar. Ardından DBeaver'a benzer
> diğer veritabanı istemcilerini ve en çok kullanılan özelliklerini aynı formatta ele alır.
>
> Kaynaklar dokümanın sonunda listelenmiştir. (Araştırma tarihi: Ağustos 2026)

---

## 1. DBeaver Ürün Ailesi — Genel Bakış

DBeaver eskiden "Community" ve "Enterprise (PRO)" olarak ikiye ayrılıyordu. Güncel ürün
hattı ise şu şekildedir:

| Sürüm | Konum | Kısaca |
|-------|-------|--------|
| **Community (CE)** | Ücretsiz, açık kaynak | Temel SQL veritabanı istemcisi |
| **Lite** | Ücretli (giriş seviyesi) | CE + NoSQL/BigData + temel PRO araçları |
| **Enterprise (EE)** | Ücretli (profesyonel) | Lite + yönetim, göç, karşılaştırma, görev zamanlama |
| **Ultimate** | Ücretli (en üst) | EE + Cloud Explorer + tüm NoSQL/bulut sürücüleri + Kafka |
| **Team Edition / CloudBeaver** | Sunucu tabanlı, web | Ekip işbirliği, merkezi erişim, web arayüzü |

> **Not:** "PRO" terimi genellikle ücretli masaüstü sürümlerin tümünü (Lite/Enterprise/Ultimate)
> kapsayan şemsiye bir isimdir. Aşağıda özellikler, ait oldukları sürümle birlikte belirtilmiştir.

---

## 2. Community'de OLMAYAN, PRO/Ücretli Sürümlerde OLAN Özellikler

### 2.1. Veritabanı Desteği (NoSQL, BigData, Bulut)

Community sürümü yalnızca yaygın **SQL** veritabanlarını (PostgreSQL, MySQL, MariaDB,
Oracle, SQL Server, SQLite, DB2 vb.) destekler. Ücretli sürümler bunun ötesine geçer:

- **NoSQL / BigData veritabanı desteği** *(Lite ve üzeri)* — SQL dışı veri kaynaklarına
  bağlanma. Ne işe yarar: Doküman, anahtar-değer, grafik ve zaman serisi veritabanlarını
  aynı araçtan yönetmenizi sağlar.
- **Ultimate'e özel yerleşik NoSQL/bulut sürücüleri:**
  - **MongoDB** — Doküman tabanlı veritabanı. JSON benzeri belgeleri sorgulama/yönetme.
  - **Cassandra** — Dağıtık, yüksek ölçekli NoSQL veritabanı.
  - **Redis** — Bellek içi anahtar-değer deposu (önbellek, kuyruk).
  - **AWS DynamoDB** — Amazon'un yönetilen NoSQL servisi.
  - **AWS DocumentDB** — MongoDB uyumlu bulut veritabanı.
  - **Azure Cosmos DB** (çeşitli API'ler) — Microsoft'un çok modelli bulut veritabanı.
  - **Google Firestore** — Gerçek zamanlı doküman veritabanı.
  - **Google Cloud Bigtable** — Geniş sütunlu (wide-column) NoSQL deposu.
  - **AWS Keyspaces** — Cassandra uyumlu yönetilen servis.
  - **InfluxDB** — Zaman serisi (time-series) veritabanı; metrik/IoT verisi için.
  - **Couchbase / CouchDB** — Doküman odaklı veritabanları.
  - **Neo4j** — Grafik (graph) veritabanı; ilişki ağırlıklı veriler için.
  - **Amazon Neptune** — Yönetilen grafik veritabanı servisi.
  - **Apache Kafka** — Veri akışı (stream) entegrasyonu ve mesaj kuyruğu işleme.

### 2.2. Bulut Entegrasyonu (Cloud) *(Ultimate / Team)*

- **Cloud Explorer** — AWS, Google Cloud (GCP) ve Azure üzerinde barındırılan
  veritabanlarını merkezi kimlik doğrulama ve otomatik yapılandırma ile keşfetme/yönetme.
  Ne işe yarar: Bulut hesabınızdaki tüm veritabanı örneklerini elle bağlantı bilgisi
  girmeden otomatik olarak listeler ve bağlanır.
- **Cloud Storage** — AWS ve GCP ortamlarında dosya yönetimi (ör. dışa aktarımları
  doğrudan bulut depolamaya yazma).
- **AWS / GCP / Azure yerel (native) desteği** — Bulut sağlayıcılarının kimlik doğrulama
  mekanizmalarıyla (IAM rolleri vb.) sorunsuz bağlantı.

### 2.3. Yapay Zekâ (AI) Özellikleri *(PRO / Enterprise)*

- **AI Assistant / AI Chat** — SQL Editörü içine gömülü yapay zekâ yardımcısı.
  Ne işe yarar: Düz metin (doğal dil) isteklerini doğru SQL sorgularına çevirir.
  OpenAI, Azure AI, Google Gemini veya GitHub Copilot entegrasyonlarını kullanır.
- **Sorgu ve nesne açıklama** — Herhangi bir SQL sorgusu veya veritabanı nesnesi için
  anında açıklama üretir ("Bu sorgu ne yapıyor?").
- **Söz dizimi hatası düzeltme** — Sözdizimi hatalarını iki tıkla önerilerle düzeltir.
  Ne işe yarar: Sorguları daha hızlı yazma ve öğrenme eğrisini kısaltma.

### 2.4. Şema Tasarımı ve ERD *(Enterprise / Ultimate; Lite'ta temel ERD)*

- **ERD Edit Mode (Düzenlenebilir Varlık-İlişki Diyagramı)** — Veritabanı şemasını
  görsel modda tasarlama. Community yalnızca **salt-okunur** ER diyagramı gösterirken,
  PRO ile diyagram üzerinden şema **oluşturma/değiştirme** (forward engineering) yapılır.
- **Reverse Engineering** — Var olan veritabanını otomatik olarak ER diyagramına dönüştürme.
- **SQL Execution Plan Diagram (Yürütme Planı Diyagramı)** *(Lite ve üzeri)* — Sorgunun
  nasıl çalıştırıldığını görsel olarak inceleyip performans darboğazlarını bulma.

### 2.5. Veri Üretimi ve Karşılaştırma *(Enterprise / Ultimate)*

- **Mock Data Generator (Sahte Veri Üretici)** — Test/geliştirme için gerçekçi rastgele
  veri üretme. Ne işe yarar: Boş bir tabloyu binlerce örnek satırla doldurup uygulamayı
  gerçek veri olmadan test etme.
- **Schema Compare / Migration (Şema Karşılaştırma ve Göç)** — İki veritabanının yapısını
  (tablolar, sütunlar, indeksler) karşılaştırıp farkları bulma ve senkronize etme.
- **Table Data Comparison (Tablo Veri Karşılaştırma)** — İki kaynak arasındaki satır
  verilerinin farklarını tespit etme. Ne işe yarar: Prod ve test ortamlarını eşitleme.

### 2.6. Veri Aktarımı ve Göç *(Enterprise / Ultimate)*

- **Data Migration / Direct Database Transfer** — Farklı veritabanı sistemleri arasında
  doğrudan veri taşıma (ör. MySQL → PostgreSQL).
- **Çoklu format dışa/içe aktarma** — Community daha kısıtlıyken, PRO daha fazla dosya
  formatını (CSV, JSON, XML, XLSX, SQL, HTML vb.) destekler.
- **Veritabanı yedekleme araçları desteği (Backup/Restore)** — Yerel yedekleme/geri
  yükleme araçlarıyla entegrasyon.
- **Database maintenance tools (Bakım araçları)** — Vacuum, analyze, reindex gibi
  bakım işlemleri için arayüzler.

### 2.7. Güvenlik ve Kimlik Doğrulama *(PRO / Enterprise)*

- **Master Password (Ana Parola)** — Tüm kayıtlı bağlantı kimlik bilgilerini güçlü
  şifreleme ile koruma. Community'de temel kullanıcı adı/parola varken PRO güçlü
  kimlik şifrelemesi sunar.
- **Project Password (Proje Parolası)** — Belirli projeleri ayrı parolayla koruma.
- **Kurumsal kimlik doğrulama** — SAML, SSO, OKTA, Kerberos gibi kurumsal yöntemler.
  Ne işe yarar: Şirket içi tekli oturum açma (single sign-on) altyapısıyla entegrasyon.

### 2.8. Otomasyon ve Yönetim *(Enterprise / Ultimate)*

- **Task Scheduler (Görev Zamanlayıcı)** — Dışa aktarma, içe aktarma, SQL betiği çalıştırma
  gibi tekrarlayan işleri belirli zamanlarda otomatik çalıştırma.
- **Multicomponent Task Manager (Çok Bileşenli Görev Yöneticisi)** — Birden fazla adımı
  tek bir görevde zincirleme.
- **Server Health Dashboards (Sunucu Sağlık Panoları)** — Veritabanı sunucusunun durumunu
  izleyen görsel panolar.
- **Git entegrasyonu** — Projeleri ve SQL betiklerini bir Git deposuna senkronize edip
  sürüm kontrolü yapma.

### 2.9. Görselleştirme ve Analiz *(Enterprise / Ultimate)*

- **Color Coding (Renk Kodlama)** — Tablo satır ve alanlarına koşullu vurgulama uygulama
  (ör. belirli değerleri kırmızıya boyama).
- **Functional Panels (İşlevsel Paneller)** — Karmaşık veri türlerini yönetmek için ek
  çalışma alanı sekmeleri.
- **Tableau entegrasyonu** — Veriyi doğrudan Tableau'ya gönderip ileri analiz yapma.
- **SQL Debugger (PostgreSQL)** — Saklı yordamları (stored procedure) adım adım
  hata ayıklama; kesme noktaları (breakpoint) koyma.

### 2.10. Ekip İşbirliği *(Team Edition / CloudBeaver)*

- **Gerçek zamanlı veri işbirliği** — Aynı bağlantı ve betikler üzerinde ekipçe çalışma.
- **Paylaşılan betikler ve bağlantılar** — Merkezi olarak yönetilen ortak kaynaklar.
- **Tüm kullanıcıların sorgu geçmişi** — Ekip düzeyinde denetim ve izlenebilirlik.
- **Birleşik kullanıcı erişimi** — SAML / Azure AD / SSO ile merkezi yetkilendirme.
- **Gelişmiş Rol Yönetimi ve Ölçeklenebilirlik** — Kurumsal ekipler için yetki katmanları.

---

## 3. DBeaver Sürümleri — Özellik Matrisi (Özet)

| Özellik | Community | Lite | Enterprise | Ultimate | Team |
|---|:---:|:---:|:---:|:---:|:---:|
| SQL veritabanları | ✅ | ✅ | ✅ | ✅ | ✅ |
| NoSQL / BigData sürücüleri | ❌ | ✅ | ✅ | ✅ | ✅ |
| Bulut yerel destek + Cloud Explorer | ❌ | ❌ | ❌ | ✅ | ✅ |
| AI Assistant | ❌ | ✅ | ✅ | ✅ | ✅ |
| ERD düzenleme modu | ❌ (salt-okunur) | temel | ✅ | ✅ | ✅ |
| Mock veri üretimi | ❌ | ❌ | ✅ | ✅ | ✅ |
| Şema/veri karşılaştırma | ❌ | ❌ | ✅ | ✅ | ✅ |
| Görev zamanlayıcı | ❌ | ❌ | ✅ | ✅ | ✅ |
| Master/Proje parolası | ❌ | ✅ | ✅ | ✅ | ✅ |
| SQL Debugger (PostgreSQL) | ❌ | ❌ | ✅ | ✅ | ✅ |
| Git senkronizasyonu | ❌ | ❌ | ✅ | ✅ | ✅ |
| Ekip işbirliği / web arayüzü | ❌ | ❌ | ❌ | ❌ | ✅ |

*(Matris özet niteliğindedir; DBeaver zaman zaman özellik dağılımını güncelleyebilir.)*

---

## 4. Benzer Uygulamalar (DBeaver Alternatifleri) ve En Çok Kullanılan Özellikleri

Aşağıda DBeaver ile aynı kategoride yer alan popüler veritabanı istemcileri ve
kullanıcıların en çok kullandığı/övdüğü özellikler yer alır.

### 4.1. JetBrains DataGrip *(ücretli)*
- **Konum:** Geliştirici odaklı, IntelliJ tabanlı profesyonel SQL IDE.
- **En güçlü yönü:** Kod yardımı (bağlam-farkında otomatik tamamlama, akıllı refactoring).
- **En çok kullanılan özellikler:**
  - Gelişmiş **kod tamamlama** ve hata analizi (yazarken canlı denetim).
  - **AI destekli sorgu üretimi** ve açıklama.
  - Çok sayıda veritabanı için tek arayüz; sürüm kontrolü (Git) entegrasyonu.
  - Güçlü **refactoring** (tablo/sütun yeniden adlandırmayı sorgulara yayma).
- **Kime uygun:** Kod kalitesine önem veren geliştiriciler.

### 4.2. DbVisualizer *(ücretsiz + ücretli)*
- **Konum:** Java (JVM) tabanlı, kurumsal düzeyde çok platformlu istemci.
- **En çok kullanılan özellikler:**
  - **40–50+ veritabanı** desteği; tek araçta geniş kapsam.
  - **Görsel ERD** ile şema keşfi.
  - macOS/Windows/Linux'ta tutarlı deneyim (NASA, Google gibi kurumlarca kullanılıyor).
  - Gelişmiş veri düzenleme ve dışa aktarma.
- **Kime uygun:** Çok sayıda farklı veritabanıyla çalışan kurumsal ekipler.

### 4.3. TablePlus *(ücretsiz sınırlı + ücretli)*
- **Konum:** Sade ve hızlı, yerel (native) arayüzlü modern istemci.
- **En çok kullanılan özellikler:**
  - **Native performans** (macOS'ta Swift/Cocoa, Windows'ta .NET) — çok akıcı arayüz.
  - Temiz, minimal kullanıcı deneyimi; hızlı bağlantı ve gezinme.
  - Satır içi veri düzenleme, çoklu sekme, güvenli SSH bağlantıları.
  - SQL ve NoSQL (Redis, MongoDB) desteği.
- **Kime uygun:** Hız ve sadelik isteyen, özellikle macOS kullanıcıları.

### 4.4. HeidiSQL *(tamamen ücretsiz, açık kaynak)*
- **Konum:** Windows için hafif, hızlı istemci.
- **En çok kullanılan özellikler:**
  - Özellikle **MySQL / MariaDB** için mükemmel; ayrıca PostgreSQL, SQL Server.
  - Çok hafif ve hızlı; günlük işler için pratik.
  - "Value for money" (bedava olmasına rağmen güçlü) açısından en yüksek puanlardan.
  - Tablo düzenleme, dışa aktarma, kullanıcı yönetimi.
- **Kime uygun:** Windows'ta MySQL/MariaDB ile çalışan, ücretsiz çözüm arayanlar.

### 4.5. Navicat *(ücretli)*
- **Konum:** Kapsamlı, ticari veritabanı yönetim ve tasarım aracı.
- **En çok kullanılan özellikler:**
  - Geniş destek: MySQL, PostgreSQL, MongoDB, MariaDB, SQL Server, Oracle, SQLite,
    Redis, Snowflake.
  - **Veritabanı tasarımı** (görsel modelleme), veri göçü, senkronizasyon.
  - **AI Assistant**, sorgu oluşturma, ekip işbirliği.
  - Zamanlanmış görevler ve raporlama.
- **Kime uygun:** Tasarım + yönetim + göç işlerini tek üründe isteyen profesyoneller.

### 4.6. Beekeeper Studio *(açık kaynak + ücretli)*
- **Konum:** Modern, açık kaynak, kullanıcı dostu istemci.
- **En çok kullanılan özellikler:**
  - Modern JavaScript teknolojileriyle geliştirilmiş temiz arayüz.
  - **Doğrudan AI entegrasyonu**.
  - Community sürümü tamamen açık kaynak; ücretli katmanlar bulut çalışma alanları,
    AI ve ekip özellikleri ekliyor.
  - SQL düzenleyici, veri düzenleme, çoklu sekme.
- **Kime uygun:** Açık kaynak seven, sade ve modern arayüz isteyenler.

### 4.7. CloudBeaver *(DBeaver'ın web sürümü)*
- **Konum:** Tarayıcı tabanlı, sunucuya kurulan veritabanı istemcisi.
- **En çok kullanılan özellikler:**
  - Kurulum gerektirmeden **web üzerinden** veritabanı erişimi.
  - Merkezi erişim yönetimi, ekip paylaşımı.
  - Enterprise sürümde SSO, rol yönetimi, denetim.
- **Kime uygun:** Masaüstü kurulumu istemeyen, merkezi/uzaktan erişim gereken ekipler.

---

## 5. Özet ve Öneri

- **DBeaver Community** çoğu geliştiricinin günlük SQL ihtiyacını ücretsiz karşılar.
- **NoSQL/bulut veritabanları, AI, şema/veri karşılaştırma, görev zamanlama, mock veri,
  SQL debugger ve kurumsal güvenlik** gibi özellikler için **ücretli (PRO) sürümler** gerekir.
- **Bulut (Cloud Explorer) ve tam NoSQL sürücü seti** yalnızca **Ultimate**'te bulunur.
- **Ekip işbirliği / web erişimi** için **Team Edition / CloudBeaver** tercih edilir.
- Alternatifler arasında **DataGrip** (kod yardımı), **DbVisualizer** (geniş DB desteği),
  **TablePlus** (hız/sadelik), **HeidiSQL** (ücretsiz, hafif) ve **Navicat** (tasarım+göç)
  en öne çıkanlardır.

---

## Kaynaklar

- [DBeaver Editions & Pricing Comparison](https://dbeaver.com/edition/)
- [Switch from DBeaver Community to DBeaver PRO](https://dbeaver.com/switch-to-dbeaver-pro/)
- [DBeaver Enterprise Edition — GitHub Wiki](https://github.com/dbeaver/dbeaver/wiki/Enterprise-Edition)
- [DBeaver Ultimate Edition — GitHub Wiki](https://github.com/dbeaver/dbeaver/wiki/Ultimate-Edition)
- [DBeaver Enterprise](https://dbeaver.com/dbeaver-enterprise/)
- [CloudBeaver Enterprise](https://dbeaver.com/cloudbeaver-enterprise/)
- [QueryGlow — DBeaver Community vs Lite vs Enterprise vs Ultimate](https://queryglow.com/blog/dbeaver-sql-client)
- [DbVis — Best DBeaver Alternatives 2026](https://www.dbvis.com/thetable/best-dbeaver-alternatives-of-2025/)
- [DbVis — Top DataGrip / TablePlus Alternatives 2026](https://www.dbvis.com/thetable/top-5-datagrip-alternatives-of-2025-complete-comparison/)
- [UI Bakery — Best PostgreSQL GUI Tools 2026](https://uibakery.io/postgresql-gui-tools)
- [DEV Community — Best Database Clients in 2026](https://dev.to/dory-nemo/best-database-clients-in-2026-top-sql-gui-tools-compared-42dm)
