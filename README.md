# GeçGeç

Spor salonu / Starbucks / Şok — oraya varınca uygulama kendi açılır.

## Ne yapman lazım

1. Android Studio'da bu klasörü aç, telefonunu bağla, Run'a bas.
2. Uygulama açılınca 5 tane izin ekranı gelir. Hepsine "Aç/İzin ver" de.
3. Spor salonundayken uygulamayı aç → "Buradayken bas" → QR uygulamanı seç → kaydet.
4. Aynısını Starbucks'ta ve Şok'ta yap.

Bitti. Bir daha dokunmana gerek yok.

## Kurulum ekranındaki 5 izin neden var

| İzin | Olmazsa |
|---|---|
| Konum | Hiç çalışmaz |
| "Her zaman izin ver" | Telefon cebindeyken çalışmaz |
| Üstte gösterme | Uygulama açılmaz, sadece bildirim gelir |
| Pili kısıtlama | Birkaç gün sonra durur |
| Bildirim | Hata olursa haber alamazsın |

Xiaomi/Samsung kullanıyorsan bir de: Ayarlar → Uygulamalar → GeçGeç → **Otomatik başlatma açık**.

## Ayarlar

Değiştirmek istersen `data/Places.kt` içinde:
- `radiusMeters = 130f` → alan yarıçapı (100'ün altına inme, çalışmaz)
- `dwellSeconds = 40` → önünden geçince değil, 40 sn kalınca açılsın
- `cooldownMinutes = 90` → aynı yerde tekrar tekrar açmasın
