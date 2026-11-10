## Kurulum (Windows)

1. Aşağıdaki **Assets** listesinden `dbeaver-mm-windows-x64.zip` dosyasını indir (*Source code* dosyalarını değil).
2. Zip'e sağ tık › **Tümünü ayıkla** › `C:\dbeaver-mm` klasörüne çıkar.
3. `C:\dbeaver-mm\dbeaver\dbeaver.exe` dosyasına çift tıkla. Java içinde gelir, yönetici yetkisi gerekmez.
4. "Windows bilgisayarınızı korudu" uyarısında **Ek bilgi › Yine de çalıştır** de (dosya imzasız).

**Fetch IDs kullanacaksan:** `id-api.properties` dosyasını dbeaver-mm'yi veren kişiden al, `dbeaver.exe`'nin yanına
koy, sonra **Plus Settings › ID API account...** ile kendi kullanıcı adını ve şifreni gir.

**Yeni sürüme geçerken:** yeni zip'i ayrı bir klasöre çıkar ve eski klasördeki `id-api.properties`'i yanına kopyala.
Bağlantıların ve ayarların korunur.

Ayrıntılar, eski DBeaver bağlantılarını taşıma ve sorun giderme:
[Kurulum Rehberi](https://github.com/miracmenekse/dbeaver-mm/blob/main/docs/KURULUM.md) ·
[Kullanım Rehberi](https://github.com/miracmenekse/dbeaver-mm/blob/main/docs/KULLANIM.md)
