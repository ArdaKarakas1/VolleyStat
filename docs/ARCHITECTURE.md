# VolleyStat mimari özeti

## Ürün kararı

- Marka Voleybol Ve Veleybol, ürün VolleyStat.
- İstemci Android'dir: Kotlin, Jetpack Compose, Material 3. Web sitesi ayrı depodadır ve bu projeyle kod paylaşmaz.
- Kapsam amatör kapalı saha 6 × 6 maçıdır. Uygulama iki takımı da izler.
- İlk analiz sunucuda, kayıtlı video üzerinde çalışır. Canlı analiz sonraki iştir.
- Kullanıcı yalnız sistemin emin olamadığı kararları verir. Bugün eğitilmiş model yoktur, bu yüzden kullanıcı her rallinin kazananını kendisi seçer (önce inceleme akışı).

## Akış

```
Yeni maç → Takımlar → Video kaynağı → Kayıt ya da seçim → Dosya kontrolü → Yükleme
        → Analiz işi → Belirsiz ralliler için kartlar → Kullanıcı kararları → Rapor
```

Sunucu üç kaydı birbirinden ayrı tutar:

| Kayıt | Kim üretir | Rapora girer mi |
|---|---|---|
| Öneri (`rallies.proposed_*`) | Analizör | Hayır |
| Karar (`review_decisions`) | Kullanıcı | Evet |
| Rapor değeri | Sunucu, kararlardan hesaplar | Rapordur |

Bilinmeyen değer `{"value": null, "status": "UNKNOWN"}` biçimindedir ve hiçbir katman onu `0` yapmaz.

## Kod ağacı

### `android/app`

| Katman | Dosyalar | Görev |
|---|---|---|
| Sunum | `ui/screens/*`, `ui/components/*`, `ui/theme/*`, `VolleyStatApp.kt` | Ekranlar, ortak bileşenler, tema, gezinme grafiği |
| Durum | `AppViewModel.kt` | Tek `ViewModel`. Ekran durumunu `StateFlow` ile yayar. Yükleme, analiz izleme, doğrulama ve rapor işlerini yürütür. |
| Alan | `model/*`, `android/core` | Maç, takım, iş, kart ve rapor tipleri. `core` modülü Android'den bağımsız skor kurallarını taşır. |
| Veri | `data/ApiClient.kt`, `data/UploadEngine.kt`, `data/LocalStore.kt`, `data/VideoInspector.kt` | HTTP istemcisi, kaldığı yerden süren yükleme, telefondaki JSON kayıt, videonun telefonda okunan özellikleri |

Uygulama kamera kaydı için CameraX, klip oynatma için Media3 kullanır. Uygulama ses kaydetmez. Oyuncu adları yalnız telefonda durur. Uygulama sunucuya yalnız forma numarası gönderir.

### `backend/app`

| Dosya | Görev |
|---|---|
| `main.py` | FastAPI uygulaması, maç ve yükleme uçları, hata zarfı |
| `media.py` | ffprobe ile dosya doğrulaması. Sunucu kapsayıcıya, video akışına ve dosyanın sonunun okunabilirliğine bakar. Sunucu görüntünün içeriğini ölçmez. |
| `db.py` | SQLite şeması ve bağlantı |
| `jobs.py` | Analiz işi, işçi iş parçacığı, durum makinesi, iptal ve kurtarma |
| `analyzers/` | Analizör arayüzü ve kayıt defteri: `demo` (`MOCK`), `motion-baseline` (`BASELINE`) |
| `review.py` | Ralliler, inceleme kartları, kararlar, geri alma, denetim kaydı |
| `report.py` | Kararlardan rapor üreten saf fonksiyon |
| `content.py` | Videoyu `Range` isteğiyle sunma |

Sunucu yalnız geliştirme bilgisayarında çalışır. Hesap sistemi ve TLS yoktur. Sunucu `127.0.0.1` adresini dinler.

Yüklenen her dosya `storage/media/<uploadId>.bin` adında tek bir dosyadır. Tamamlama adımı dosyayı taşımaz, video kaydı aynı dosyayı gösterir.

### `ml`

`volleystat_ml` paketi ilk gerçek analiz bileşenini ve ölçüm araçlarını taşır: hareket enerjisinden ralli aralığı öneren bölümleyici, ralli sınırı metrikleri, etiket denetimi, maç grubuna göre ayrım ve sızıntı denetimi. Paket yalnız numpy ve ffmpeg ister. Ayrıntı `ml/README.md` ve `docs/AI_PLAN.md` dosyalarındadır.

## Analizör türleri

| Tür | Anlam | Bugün |
|---|---|---|
| `MOCK` | Uydurma çıktı. Yalnız arayüz ve test içindir. Sunucu işi ve raporu `mock: true` ile işaretler. | `demo` |
| `BASELINE` | Gerçek algoritma, ölçümü yok | `motion-baseline` |
| `MODEL` | Ölçümü kayıtlı eğitilmiş model | Yok |

Analizör seçilmeyen sunucu analiz isteğine `503 MODEL_NOT_CONFIGURED` döner.

## Test katmanları

| Katman | Nerede çalışır | Neyi kanıtlar |
|---|---|---|
| Arka uç `pytest` | Her yerde | API sözleşmesi, iş durumları, kartlar, rapor kuralları, dosya doğrulaması |
| `ml` `pytest` | Her yerde | Bölümleyicinin tesisatı, metrikler, etiket ve ayrım kuralları. Gerçek maçta doğruluk ölçmez. |
| Android JVM testleri | Her yerde | Yükleme motoru, yerel kayıt, gezinme, her ekranın çizimi (ekran görüntüsü) |
| İstemci ve sunucu uçtan uca | Sunucu açıkken | Uygulamanın istemcisi ve ekranları gerçek sunucuyla çalışır |
| Cihaz testleri | Emülatör ya da telefon | Kamera kaydı, sunucudan klip oynatma, baştan sona akış |

## Sürüm kapıları

- Kimse yapay zekâ doğruluğunu ölçmedi. Eğitilmiş model yok.
- Video yükleme hakkı beyanı eğitim izninden ayrıdır. Eğitim izni varsayılan olarak kapalıdır ve sunucu bugün `allowTraining: true` isteğini reddeder.
- Sunucu üretim hizmeti değildir: kimlik doğrulama, TLS, nesne depolama, iş kuyruğu ve GPU işçisi yok.
- Ekip videoları maç bazında ayırır. Aynı maçın kareleri eğitim ve test kümelerine birlikte girmez.
