# dbeaver-mm Kurulum Rehberi

dbeaver-mm'yi GitHub Releases'tan indirip ilk kez kuracaklar için. Kurulumdan sonra özelliklerin nasıl kullanıldığı
[Kullanım Rehberi](KULLANIM.md)'nde.

**Gerekenler:** Windows 10 ya da 11 (64 bit), yaklaşık 500 MB boş alan. Yönetici yetkisi ve Java gerekmez: Java
zip'in içinde gelir, hiçbir şey sisteme kurulmaz.

## İçindekiler

1. [Zip'i indir ve çalıştır](#1-zipi-indir-ve-çalıştır)
2. [Var olan bağlantılarını taşı (isteğe bağlı)](#2-var-olan-bağlantılarını-taşı-isteğe-bağlı)
3. [Fetch IDs için ID API ayarı](#3-fetch-ids-için-id-api-ayarı)
4. [Yeni sürüme geçiş](#4-yeni-sürüme-geçiş)
5. [Kaldırma](#5-kaldırma)
6. [Sorun giderme](#6-sorun-giderme)
7. [Dağıtan kişi için](#7-dağıtan-kişi-için)

## 1. Zip'i indir ve çalıştır

1. [Son sürümün sayfasından](https://github.com/miracmenekse/dbeaver-mm/releases/latest) **Assets** altındaki
   `dbeaver-mm-windows-x64.zip` dosyasını indir. *Source code* dosyalarını indirme: onlar kaynak koddur, çalışmaz.
2. Zip'e sağ tıkla › **Tümünü ayıkla** › hedef olarak `C:\dbeaver-mm` yaz. `Program Files`, OneDrive ya da
   İndirilenler klasörünü seçme: ilki yönetici ister, diğerlerinde dosyalar eşitlenip bozulabilir.
3. `C:\dbeaver-mm\dbeaver\dbeaver.exe` dosyasına çift tıkla. Kısayol için: sağ tık › **Gönder › Masaüstü (kısayol
   oluştur)**.
4. "Windows bilgisayarınızı korudu" uyarısı çıkarsa **Ek bilgi › Yine de çalıştır** de. Dosya imzasız olduğu için
   normaldir, yalnız ilk açılışta çıkar.

Pencere başlığında *DBeaver* ve menüde **Plus** ile **Plus Settings** görünüyorsa kurulum tamam.

## 2. Var olan bağlantılarını taşı (isteğe bağlı)

Zaten bir DBeaver'da bağlantıların varsa yeniden tanımlamak zorunda değilsin.

**Eski DBeaver hâlâ duruyorsa (aynı ya da başka bilgisayarda):**

1. Eski DBeaver'da **File › Export › DBeaver › Project** › projeni (genelde *General*) seç › bir klasöre kaydet.
2. dbeaver-mm'de **File › Import › DBeaver › Project** › kaydettiğin dosyayı seç.
3. **Window › Projects** › içe aldığın projeye sağ tık › **Set active project**.

**Elinde yalnızca eski bilgisayarın `DBeaverData` yedeği varsa:**

1. dbeaver-mm'de **Window › Projects** › soldaki *yeni proje* simgesi › eski projenin adıyla bir proje oluştur.
2. dbeaver-mm'yi kapat.
3. Yedekte `DBeaverData\workspace6\<proje adı>` klasöründeki **`.dbeaver`** ve **`Scripts`** klasörlerini,
   dbeaver-mm'nin aynı adlı proje klasörüne kopyala (yerini *File › Switch Workspace › Other...* gösterir) ve
   **Değiştir** de. `.dbeaver` gizli görünebilir: Gezgin'de **Görünüm › Göster › Gizli öğeler**.
4. dbeaver-mm'yi aç › projeye sağ tık › **Set active project**.

Kayıtlı şifreler genelde birlikte gelir. Bağlanırken şifre sorulursa yeniden yazman yeterli.

## 3. Fetch IDs için ID API ayarı

*Edit › Fetch IDs for new rows* kullanmayacaksan bu adımı atla.

1. `id-api.properties` dosyasını sana dbeaver-mm'yi veren kişiden al. Bu dosya GitHub'da yoktur.
2. Dosyayı `dbeaver.exe`'nin yanına koy: `C:\dbeaver-mm\dbeaver\id-api.properties`. Adı tam olarak bu olmalı
   (Windows uzantıyı gizliyorsa sonuna `.txt` eklenmediğinden emin ol).
3. dbeaver-mm'yi aç › **Plus Settings › ID API account...** › kendi kullanıcı adını ve şifreni gir.
   Dosyayı `dbeaver.exe`'nin yanına koyamıyorsan aynı penceredeki **Import...** ile seç.
4. ID API şirket ağından ya da VPN'den erişiliyorsa önce bağlan.

Senin bilgilerin `C:\Users\<kullanıcı>\.dbeaver-mm\` altına kaydedilir ve dağıtılan dosyaya yazılmaz.

## 4. Yeni sürüme geçiş

1. Yeni zip'i indir ve **başka bir klasöre** çıkar (ör. `C:\dbeaver-mm-yeni`). Açıkken eski klasörün üzerine çıkarma.
2. Eski `dbeaver` klasöründeki **`id-api.properties`** dosyasını yeni `dbeaver` klasörüne kopyala.
3. Yeni `dbeaver.exe`'yi aç. Bağlantılar, betikler ve ayarlar `%APPDATA%\DBeaverData` ile
   `%USERPROFILE%\.dbeaver-mm` altında durduğu için olduğu gibi gelir.
4. Her şey yerindeyse eski klasörü silebilirsin.

## 5. Kaldırma

`C:\dbeaver-mm` klasörünü sil. Kişisel verileri de silmek istersen `%USERPROFILE%\.dbeaver-mm` klasörünü sil.
`%APPDATA%\DBeaverData` klasörü bağlantılarını tutar ve normal DBeaver ile ortaktır; ancak emin olduğunda sil.

## 6. Sorun giderme

| Belirti | Ne yapmalı |
| --- | --- |
| "Windows bilgisayarınızı korudu" | **Ek bilgi › Yine de çalıştır**. İmzasız dosya için normaldir. |
| `dbeaver.exe` açılmıyor ya da hemen kapanıyor | Zip'i tamamen ayıklamadan zip'in içinden çalıştırmış olabilirsin: **Tümünü ayıkla** ile yeniden çıkar. Yol çok uzunsa `C:\dbeaver-mm` gibi kısa bir klasör kullan. |
| Bağlantılar görünmüyor | **Window › Projects** › doğru projeye sağ tık › **Set active project**. |
| *Fetch IDs*: `isn't set up` | `id-api.properties` `dbeaver.exe`'nin yanında değil ya da adı farklı (`.txt` uzantısı?). [3. adım](#3-fetch-ids-için-id-api-ayarı). |
| *Fetch IDs*: `/bin/bash: C:Users...: No such file or directory` | Eski sürüm ve Linux'tan gelen bir `id-api.sh` var. Dağıtılan `id-api.properties`'i koy ya da yeni sürüme geç. |
| *Fetch IDs*: `Login failed` | Kullanıcı adı ya da şifre yanlış: **Plus Settings › ID API account...** ile yeniden gir. |
| *Fetch IDs*: bağlantı hatası, zaman aşımı | Şirket ağına ya da VPN'e bağlı olduğundan emin ol. |
| Başka bir sorun | **Plus › Create Problem Report** ile oluşan zip'i dbeaver-mm'yi veren kişiye gönder. |

Daha fazlası: [Kullanım Rehberi › Sorun giderme](KULLANIM.md#sorun-giderme).

## 7. Dağıtan kişi için

- Kullanıcıya [son sürümün linkini](https://github.com/miracmenekse/dbeaver-mm/releases/latest) ve bu sayfayı ver.
- Fetch IDs kullanacaklara ayrıca `id-api.properties` dosyasını **elden** ver (Teams, şirket içi ortak klasör vb.).
  Repo herkese açık: içinde şirket adresleri olan bu dosyayı GitHub'a, issue'ya ya da release'e koyma.
- Dağıtılan dosyada kimsenin kullanıcı adı ve şifresi olmasın; giriş gövdesinde onların yerinde `{user}` ve
  `{password}` yazsın. Sıfırdan hazırlarken ya da eski bir `id-api.sh`'den taşırken
  [`id-api.properties.example`](id-api.properties.example) şablonunu kullan.
