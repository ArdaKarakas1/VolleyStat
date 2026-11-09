# Pilot akışı

Android, sistem kamerasının döndürdüğü videoyu veya sistem dosya seçicisinden alınan dosyayı uygulamanın özel dizinine kopyalar. Kopya, yükleme onaylanana kadar saklanır. Yükleme önce SHA-256 hesaplar, ardından dosyayı parçalar halinde aktarır. Bağlantı kesilirse aynı maç kimliğiyle aktarım yeniden denenebilir. Bir maçın başarıyla yüklenmiş videosu sabittir.

`awaiting_upload → queued → analyzing → needs_review → reviewed`

İnceleme hataları `failed` durumuna geçer ve yeniden kuyruğa alınabilir. Süreç yeniden başlatıldığında yarım kalan analiz yeniden sıraya girer. Bir olay eklemek, düzeltmek veya çıkarmak incelemeyi tekrar açar. İncelemenin tamamlanması kullanıcının kararını kaydeder. Veri kapsamı girilmiş olaylarla sınırlıdır.

## API

İstek kökü `http://127.0.0.1:8080/v1/matches/{match_id}`. Maç ve olay kimlikleri istemcinin oluşturduğu UUID v4 değerleridir.

| İşlem | İstek | Veri |
| --- | --- | --- |
| Maçı oluştur | `PUT /` | `{"home":"Ev Sahibi","away":"Konuk"}` |
| Durumu ve istatistikleri getir | `GET /` | Olaylar, iki takım, durum ve video bilgisi |
| Videoyu yükle | `PUT /video` | Video gövdesi, `Content-Length` ve `X-Content-SHA256` |
| Videoyu oynat | `GET /video` | `Range: bytes=…` desteklenir |
| Analizi tekrar dene | `POST /analysis/retry` | Başarısız videoyu yeniden sıraya alır |
| Olayı kaydet veya onayla | `PUT /events/{event_id}` | `action: verify`, `revision` ve `event` |
| Olayı çıkar veya öneriyi reddet | `PUT /events/{event_id}` | `action: reject` ve `revision` |
| İncelemeyi bitir | `POST /review/complete` | En az bir doğrulama ve sıfır bekleyen öneri gerekir |

Tabloda `/` maç kökünün kendisini ifade eder. İsteği maç kimliğinden sonra eğik çizgi eklemeden gönderin. Yeni bir olay için `revision: 0`, düzenleme için son okunan sürümü kullanın. Sunucu aynı isteğin tekrarını tanır ve çakışan bir düzenlemeye `409` döndürür.

```json
{
  "action": "verify",
  "revision": 0,
  "event": {
    "team": "home",
    "set": 1,
    "rally": 3,
    "at_ms": 12500,
    "player": 7,
    "kind": "attack",
    "outcome": "kill"
  }
}
```

## İstatistik sözlüğü

- Bir set ve ralli için tek doğrulanmış ralli sayısı tutulur. Set skorları bu sayılardan oluşur. Ace veya hücum sayısı tek başına skoru değiştirmez.
- Hücum denemeleri sayı, hata ve oyunda kalan hücumlardan oluşur. Hücum verimi `(sayı − hata) / deneme` olarak hesaplanır. Deneme yoksa değer boştur.
- Servis ve karşılama denemeleri, ace, hatalar, tekli sayı bloğu, blok asisti, asist, çıkarılan top ve top kullanımı hatası ayrı olaylardır. Takım blok toplamı tekli bloklar artı blok asistlerinin yarısıdır.
- Servis hatası dışında kalan bir servisin karşılaması, ace'e bağlı karşılama hatası ve blokla sonuçlanan hücumun hücum hatası gibi ilişkili istatistikler pilotta ayrı ayrı etiketlenir.
- Forma numarası bilinmeyen olay takım toplamına katkı verir. Bekleyen veya çıkarılan olaylar hiçbir toplama dahil edilmez.
- Skor girişi 1–5 seti destekler. Rotasyon, diziliş, oyuncu değişikliği, lig kuralları ve tüm maçın eksiksiz etiketlenip etiketlenmediği pilotta otomatik doğrulanmaz.

[İstatistik tanımları için NCAA voleybol formu](https://fs.ncaa.org/Docs/stats/VB_Forms/Volleyball%20Box%20Score%20Form%20Instructions.pdf)

## Model bağlantısı

`Server` video inceleyicisini ve analiz bileşenini ayrı alır. Varsayılan analiz bileşeni `manual_only` modunu ve boş bir öneri listesi döndürür. Gerçek bir model `AnalysisResult("assisted", proposals)` döndürebilir. Öneriler aynı olay sözleşmesinden geçer, `pending` durumuyla kaydedilir ve bir insan onaylayana kadar istatistiğe katılmaz. Video ayrıştırma ile maç olaylarının tanınması ayrı aşamalardır.

Pilot verisi için iki bağımsız etiketçi, anlaşmazlık çözümü ve maç bazlı eğitim/test ayrımı kullanın. Saha açısı ve video kalitesine göre ralli tespiti, olay sınıflandırması, forma tanıma, yanlış yüksek güvenli öneriler ve insanın harcadığı süreyi ölçün. Ölçülmüş model sonuçları elde edilene kadar otomatik spor istatistiği yayımlanmaz.

## Çalıştırma kapsamı

Pilot arka uç bilgisayarın yerel adresini dinler ve hesap sistemi içermez. Android'de şifresiz yerel bağlantı yalnızca debug yapılandırmasında ve iki geliştirme adresinde açıktır. Video dosyaları ve yerel veri depoya eklenmez.
