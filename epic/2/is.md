# Lang (çok dilli) kayıtlarını otomatik çevirme

Sorun: Kullanıcı bir kayıt için 3 dilde (en, fr, tr) satır girerken `name` / `descr` metnini alıp Google Çeviri'de dil seçip çeviriyor, sonucu gridde geri yapıştırıyor. Her kayıt için 3 satır x 2 alan elle kopyala-çevir-yapıştır; yavaş ve hataya açık.

İstek: Gridde `lang` sütunundaki değere (en, fr, tr) göre, ilgili satırın `name` ve `descr` alanlarının otomatik çevrilip yazılması. Çeviri için dış bir servis (ya da başka bir yetenek) kullanılabilir.

Örnek (gridde): aynı kaydın 3 satırı var. en satırı kaynak: name="In Store Purchase", descr="In Store Purchase". fr ve tr satırları şu an kaynakla aynı ya da yarım ("Offer", "Teklif"); lang'e göre çevrilmeli (fr: Achat en magasin, tr: Mağazada Satın Alma).

Bağlam: dbeaver-mm'de grid değişiklikleri "Add changes to conf package" ile Scripts/conf/<task id>.sql'e INSERT/UPDATE olarak yazılır. Veri tp/etiket gibi proje ayarları ~/.dbeaver-mm altında tutuluyor. Şirket verisi dış servise gidebilir: gizlilik tartışılmalı.

Tartışılacak: (1) çeviri motoru: Google Cloud Translate / DeepL / LibreTranslate (kendi sunucu) / LLM (OpenRouter, Claude) / resmi olmayan Google uç noktası; maliyet, kota, kalite, ağ erişimi; (2) tetik: sağ tık "Translate by lang" menüsü mü, satır eklenince otomatik mi, seçili satırlar için toplu mu; (3) kaynak dil nasıl belirlenir (aynı kaydın en satırı? seçili satır?) ve kayıtların aynı gruba ait olduğu nasıl anlaşılır; (4) hangi sütunlar çevrilir (name, descr; başka metin sütunu?) ve tablo/sütun eşlemesi nerede tutulur; (5) dolu hücre ezilsin mi (kaynakla aynıysa ezilsin, farklıysa sor); (6) çeviriyi yazmadan önce önizleme/onay; (7) terim sözlüğü (marka/ürün adları çevrilmesin, "Offer" gibi alan terimleri tutarlı çevrilsin); (8) şirket verisinin dış servise gitmesi: izin, anahtar saklama (id-api.properties gibi), maskeleme; (9) çevrimdışı/başarısız durumda davranış.
