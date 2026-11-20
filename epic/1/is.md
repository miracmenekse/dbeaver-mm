# Conf eklerken audit alanlarını otomatik doldurma

Sorun: Kullanıcı tabloda yeni bir conf satırı eklerken cdate, cuser, sdate gibi audit alanlarına her seferinde elle statik değer giriyor. Bu tekrarlı ve hataya açık.

İstek: Bu alanların otomatik (yeni satırda varsayılan) ya da bir buton/menü komutuyla (ör. "audit alanlarını doldur") doldurulması.

Bağlam: dbeaver-mm'de grid değişiklikleri "Add changes to conf package" ile Scripts/conf/<task id>.sql'e INSERT olarak yazılır. Satırlar Conf Packages panelinde gösterilir.

Tartışılacak: (1) otomatik mi buton mu; (2) hangi alanlar, hangi değerler (cdate=şimdi, cuser=?, sdate=?); (3) tablo bazında alan eşlemesi nerede tutulur (tenant/data_tp gibi ~/.dbeaver-mm altında mı); (4) mevcut doluysa ezilsin mi; (5) conf paketine yazarken mi, gridde satır eklenirken mi.
