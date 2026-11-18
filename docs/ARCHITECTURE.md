# VolleyStat mimari özeti

## Ürün kararı

- Marka Voleybol Ve Veleybol, ürün VolleyStat.
- İstemci Android'dir: Kotlin, Jetpack Compose, Material 3. Web sitesi ayrı depodadır ve bu projeyle kod paylaşmaz.
- Kapsam amatör kapalı saha 6 × 6 maçıdır. Uygulama iki takımı da izler.
- İlk analiz sunucuda, kayıtlı video üzerinde çalışır. Canlı analiz sonraki iştir.
- Kullanıcı yalnız sistemin emin olamadığı kararları verir. Bugün eğitilmiş model yoktur, bu yüzden kullanıcı her rallinin kazananını kendisi seçer (önce inceleme akışı).
- Bu kuralın iki istisnası vardır. Birincisi ralli listesidir: Kullanıcı kaçan ralliyi ekler, birleşmiş ralliyi böler, iki parçayı birleştirir ve bir rallinin sınırını taşır. Kullanıcı sonra listeyi onaylar. Rapor ancak o zaman set skorunu ve takım sayılarını alt sınır işareti olmadan verir. Sayı türü alanları, kullanıcı her sayının türünü seçene kadar alt sınır kalır. Bu kesinlik kullanıcının beyanına dayanır ve sunucu onu doğrulayamaz (ADR-0114). İkincisi maç kuralıdır: Kullanıcı maçı açarken maçı kaç set alan takımın kazandığını ve setlerin kaç sayıda bittiğini bir kez seçer. Uygulama FIVB kuralını seçili getirir ve sunucu setleri bu kurala göre sayar (ADR-0115).

## Akış

```
Yeni maç → Takımlar → Video kaynağı → Kayıt ya da seçim → Dosya kontrolü → Yükleme
        → Analiz işi → Belirsiz ralliler için kartlar → Kullanıcı kararları → Rapor
        → Ralli listesi düzeltmesi → Skor denetimi ve liste onayı → Onaylı listenin raporu
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
| Alan | `model/*`, `android/core` | Maç, takım, iş, kart ve rapor tipleri. `core` modülü Android'den bağımsız skor kurallarını taşır, ama uygulama bu modülü bugün kullanmaz. Set skorunu sunucu maçın kuralıyla sayar (ADR-0115). |
| Veri | `data/ApiClient.kt`, `data/UploadEngine.kt`, `data/LocalStore.kt`, `data/VideoInspector.kt` | HTTP istemcisi, kaldığı yerden süren yükleme (sunucu yüklemeyi kapattıysa yükleme baştan başlar), telefondaki JSON kayıt, videonun telefonda okunan özellikleri |

Uygulama kamera kaydı için CameraX, klip oynatma için Media3 kullanır. Uygulama ses kaydetmez. Oyuncu adları yalnız telefonda durur. Uygulama sunucuya yalnız forma numarası gönderir.

### `backend/app`

| Dosya | Görev |
|---|---|
| `main.py` | FastAPI uygulaması, maç ve yükleme uçları, hata zarfı |
| `access.py` | Erişim: her nesneyi maçına çözer, maçın sahibini denetler (`404` ve `403 NOT_A_MEMBER`), yazma işleminde oturumu yeniden okur |
| `quotas.py` | Kişisel kota, sunucunun kişisel video havuzu ve boş disk sınırı (`VOLLEYSTAT_DISK_FLOOR_GIB`) |
| `sweep.py` | Dakikalık tarama: bir gün boyunca parça almayan yüklemeyi ve bir gün önce başlayıp bitmeyen doğrulamayı kapatır |
| `deletion.py` | Yüklemelerin ve videoların dosya yolları, bir maçın satırlarını ve dosyalarını silen ortak kod |
| `media.py` | ffprobe ile dosya doğrulaması. Sunucu kapsayıcıya, video akışına ve dosyanın sonunun okunabilirliğine bakar. Sunucu görüntünün içeriğini ölçmez. |
| `db.py` | SQLite şeması ve bağlantı |
| `jobs.py` | Analiz işi, işçi iş parçacığı, durum makinesi, iptal ve kurtarma |
| `analyzers/` | Analizör arayüzü ve kayıt defteri: `demo` (`MOCK`), `motion-baseline` (`BASELINE`) |
| `review.py` | Ralliler, inceleme kartları, kararlar, geri alma, denetim kaydı |
| `corrections.py` | Ralli listesinin düzeltmeleri (ekleme, taşıma, bölme, birleştirme) ve liste onayı |
| `report.py` | Kararlardan rapor üreten saf fonksiyon |
| `content.py` | Videoyu `Range` isteğiyle sunma |
| `accounts.py` | Hesaplar: kayıt, giriş, oturum (`current_user`), şifre özeti havuzu, deneme sınırı, `/v1/me` uçları, hesap silme |
| `admin.py` | Operatör komutları: kayıt kodu, geçici şifre ve hiçbir hesaba bağlı olmayan maçlar (`python -m app.admin legacy`) |
| `wordlists/` | Yaygın şifre listesi ve lisansı |

Sunucu yalnız geliştirme bilgisayarında çalışır. Sunucu sağlık, kayıt ve giriş dışındaki her uçta oturum ister. Sunucuda TLS yoktur. Sunucu `127.0.0.1` adresini dinler.

Yüklenen her dosya `storage/media/<uploadId>.bin` adında tek bir dosyadır. Tamamlama adımı dosyayı taşımaz, video kaydı aynı dosyayı gösterir.

### `ml`

`volleystat_ml` paketi ilk gerçek analiz bileşenini ve ölçüm araçlarını taşır: hareket enerjisinden ralli aralığı öneren bölümleyici, ralli sınırı metrikleri, kazanan ölçümü ve kalibrasyon, etiket denetimi, maç grubuna göre ayrım ve sızıntı denetimi. Paket yalnız numpy ve ffmpeg ister. Ayrıntı `ml/README.md` ve `docs/AI_PLAN.md` dosyalarındadır.

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
- Sunucu üretim hizmeti değildir: maç uçlarında erişim denetimi, TLS, nesne depolama, iş kuyruğu ve GPU işçisi yok.
- Ekip videoları maç bazında ayırır. Aynı maçın kareleri eğitim ve test kümelerine birlikte girmez.
