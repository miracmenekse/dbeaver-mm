# Conf eklerken audit alanlarını otomatik doldurma

Sorun: Kullanıcı tabloda yeni bir conf satırı eklerken cdate, cuser, sdate gibi audit alanlarına her seferinde elle statik değer giriyor. Bu tekrarlı ve hataya açık.

İstek: Bu alanların otomatik (yeni satırda varsayılan) ya da bir buton/menü komutuyla (ör. "audit alanlarını doldur") doldurulması.

Bağlam: dbeaver-mm'de grid değişiklikleri "Add changes to conf package" ile Scripts/conf/<task id>.sql'e INSERT olarak yazılır. Satırlar Conf Packages panelinde gösterilir.

Tartışılacak: (1) otomatik mi buton mu; (2) hangi alanlar, hangi değerler (cdate=şimdi, cuser=?, sdate=?); (3) tablo bazında alan eşlemesi nerede tutulur (tenant/data_tp gibi ~/.dbeaver-mm altında mı); (4) mevcut doluysa ezilsin mi; (5) conf paketine yazarken mi, gridde satır eklenirken mi.

## Kullanıcı yorumu (2026-11-23, önceki tartışmaya yanıt)
SQL yazarken manuel olacak. Yalnızca tablo üzerinden, "+" veya duplike ile eklenen yeni satırlarda:
- cdate, sdate (tabloda varsa) otomatik now() ile dolsun.
- cuser otomatik, Plus Settings'e eklenecek yeni alandan (kullanıcının girdiği cuser) dolsun.
Ayrıca FetchId butonunun yanına bir buton daha gelsin: hücre seç → butona bas → hücreye now() yazılsın. Onun yanına bir buton daha: hücre seç → bas → Settings'teki cuser hücreye yazılsın.
Karar: otomatik doldurma = yalnız yeni eklenen/duplike satır, SQL editörü kapsam dışı; elle doldurma = iki buton (now, cuser). cuser kaynağı artık belli (Plus Settings alanı).
Önceki tartışmadan bu yorumla geçersiz kalanlar: eşleme dosyası/tenant-tablo modeli yerine alan adına göre (cdate/sdate/cuser) basit tespit yeterli mi, personalar değerlendirsin.

## Kullanıcı kararı (2026-11-23)
Tarih alanlarına literal `now()` yazılır (dialekt seçimi yok). cuser: Plus Settings'e girilen değer aynen basılır.
