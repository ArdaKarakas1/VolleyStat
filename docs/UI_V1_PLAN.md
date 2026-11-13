# UI Tasarım V1 uygulama planı

**Tarih:** 12 Kasım 2026 · **Kaynaklar:** V4 devir raporu, UI Tasarım V1 paketi (10 ekran görseli, ekran sözleşmesi, token dosyası)

Kaynak önceliği: Ürün ve iş kurallarında V4, renk/tipografi/ekran düzeninde UI V1, güvenlik ve platform davranışında doğrulanmış teknik gereksinim. Görsellerdeki maç adları, skorlar, oyuncu adları ve yüzdeler örnek veridir; kodda ve testlerde kullanılmaz.

Tasarım paketinde Figma dosyası yok. Uygulama PNG görsellerini görsel hedef, `04_DESIGN_TOKENS.json` dosyasını stil başlangıcı olarak alır. Görseller varlık olarak uygulamaya girmez.

## 1. Fark analizi: mevcut kod ve UI V1

| # | Ekran | Mevcut kod (12 Kasım) | UI V1 ile fark | İş |
|---|---|---|---|---|
| 01 | Açılış | Yok | Mor geçişli karşılama, "Başla" | Yeni. Yalnız ilk açılışta görünür. |
| 02 | Ana ekran | Var, krem tema, 3 sekme | Karşılama satırı, mor "Yeni Maç" kartı, dört liste satırı, 4 sekme | Yeniden düzenlenir. Sayılar gerçek kayıtlardan gelir. |
| 03 | Maç oluştur | Yalnız iki takım adı | Maç adı, tarih, salon, maç türü | Alanlar eklenir. Hepsi isteğe bağlı ya da varsayılanlıdır. |
| 04 | Takım ve kadro | İki metin alanı | Kayıtlı takımdan seçim, isteğe bağlı kadro | Yerel takım ve kadro kaydı eklenir. |
| 05 | Video kaynağı | İki kart, ayrı rehber | Radyo kartları, önerilen ayarlar, tek düğme | Yeniden düzenlenir. Dosya kontrol adımı korunur. |
| 06 | Kamera kaydı | Sistem kamera uygulaması | Uygulama içi kayıt: süre, duraklat, bitir, kamera değiştir | CameraX ile yeni ekran. |
| 07 | AI analiz | Statik "henüz yok" | İş durumu ve adımlar | Arka uç iş API'si ve durum ekranı. |
| 08 | Doğrulama | Yok | Klip, soru, tek dokunuş, önceki/sonraki | Yeni. Media3 ile klip. |
| 09 | Maç raporu | Boş sekmeli kabuk | Skor, setler, bölüm listesi | Rapor API'si ve ekran. |
| 10 | Oyuncu istatistikleri | Yok | Takım sekmesi, oyuncu tablosu | Yeni. |
| | Tema | Krem, lacivert, turuncu | Mor `#6A3DF0`, beyaz, `#F5F3FF`, Inter | Token değişimi. |

Mevcut koddan korunanlar: veri katmanı (API istemcisi, kaldığı yerden süren yükleme motoru, yerel kayıt, cihazda video okuma), ViewModel ve StateFlow yapısı, Navigation Compose kabuğu, Türkçe metin kaynakları, ekran görüntüsü test altyapısı, uyarlanır uygulama ikonu.

## 2. Görseldeki öğelerden bilerek ayrılan noktalar

| Görselde | Uygulamada | Neden |
|---|---|---|
| "%72" analiz halkası | Halka yalnız gerçek ölçüm varsa dolar. Yoksa belirsiz gösterge döner. | Sahte yüzde yasak (UI sözleşmesi 07, V4). |
| "Oyuncular tespit edildi", "İstatistikler oluşturuluyor" adımları | Yalnız sunucunun bildirdiği aşamalar | Olmayan modelin adımı gösterilmez. |
| "Bu pozisyon hangi aksiyon?" ilk soru | İlk soru rallinin kazananı, aksiyon sorusu isteğe bağlı ikinci kart | V4: skoru etkileyen karar önce gelir, bir ekranda tek karar. |
| Oyuncu tablosunda dolu Sav. sütunu | Ölçülmeyen sütun "Ölçüm yok" yazar | Eksik veri 0 gösterilmez. |
| "Merhaba Arda" | Ad Profil'de girildiyse görünür | Uygulama hesap ya da ad bilgisi tutmuyor. |
| Örnek kulüp adları | Testlerde "A Takımı", "B Takımı" | V4: başka topluluk adı örnek olarak kullanılmaz. |
| Maç adı, salon zorunlu görünümü | İsteğe bağlı | En az giriş ilkesi. |

Uygulama ikonu kullanıcının verdiği suluboya logodur (krem, lacivert, turuncu). Uygulamanın içi mor ve beyazdır. Bu iki kimlik aynı paleti paylaşmıyor. Karar marka sahibinindir; uygulama logoyu açılışta ve Profil'de küçük boyda gösterir.

## 3. Tasarım sistemi

Tek kaynak `ui/theme/Theme.kt`. Ekranlar renk ya da ölçü sabiti yazmaz.

| Token | Açık tema | Koyu tema | Kullanım |
|---|---|---|---|
| primary | `#6A3DF0` | `#B9A6FF` | Ana eylem, seçili durum |
| primaryLight | `#8B5CF6` | `#8B5CF6` | Geçiş, vurgu |
| background | `#F5F3FF` | `#0F1020` | Sayfa |
| surface | `#FFFFFF` | `#1A1B2E` | Kart |
| textPrimary | `#111827` | `#F3F4F6` | Metin |
| textSecondary | `#596174` | `#A8B0C0` | İkincil metin |
| success / warning / error | `#16A34A` / `#D97706` / `#DC2626` | açık tonları | Durum (her zaman ikon ve sözle birlikte) |
| divider | `#E5E7EB` | `#2B2D45` | Çizgi |

- Yazı: Inter. Başlık 24 sp, alt başlık 20 sp, gövde 14–16 sp, açıklama 12 sp. Değişen rakamlar eş genişlikli (`tnum`).
- Ölçü: yatay kenar 16 dp, kart köşesi 16 dp, düğme köşesi 12 dp, taban aralık 8 dp, en küçük dokunma alanı 48 dp.
- Kontrast: Beyaz üstünde `#6A3DF0` 5,6:1, `#596174` 6,1:1. Mor zemin üstünde beyaz metin 5,6:1. Uyarı turuncusu `#D97706` beyaz üstünde 3,2:1 kaldığı için metinde koyu tonu (`#92400E`) kullanılır.
- Dinamik (duvar kâğıdı) renk kapalıdır.

Bileşenler (`ui/components/`): `VsCard`, `HeroCard`, `ListRow`, `PrimaryButton`, `SecondaryButton`, `SelectCard` (radyo kartı), `SegmentedChoice`, `StatusChip`, `StageList`, `ProgressRing`, `TeamBadge`, `ScoreHeader`, `SetChips`, `ValueCell` (ölçülen / sıfır / ölçüm yok), `ProvenanceBadge` (öneri / senin kararın), `DemoBanner`, `Notice`, `EmptyState`, `FlowScaffold`.

## 4. Mimari

```
android/core        Saf Kotlin: kurallar, rapor modeli (Android bağımlılığı yok)
android/app
  data/             ApiClient, UploadEngine, LocalStore, VideoInspector, AnalysisRepository
  model/            Uygulama modelleri
  ui/theme          Tokenlar
  ui/components     Yeniden kullanılan bileşenler
  ui/screens        Ekranlar (durumsuz: durum ve geri çağrı alır)
  AppViewModel      Maçlar, taslak, yükleme, takımlar, ayarlar
  AnalysisViewModel Bir maçın işi, kartları, raporu
  VolleyStatApp     Gezinme grafiği
```

- Ekranlar durumsuzdur; bu yüzden her durum ekran görüntüsü testinde sabit veriyle çizilir.
- Bağımlılık enjeksiyonu elle yapılır (`AppContainer`). Hilt şimdilik eklenmedi: iki ViewModel ve dört bağımlılık için derleme süresi ve üretilen kod maliyeti karşılığını vermiyor. Modül sayısı artınca yeniden değerlendirilir.
- Sunucu tarafı sözleşmesi: [API_V1.md](API_V1.md).

## 5. Ekran bazlı plan

| # | Ekran | Durumlar | Veri kaynağı |
|---|---|---|---|
| 01 | Açılış | İlk açılış | Yerel bayrak |
| 02 | Ana ekran | Boş, dolu, süren yükleme, inceleme bekleyen | Yerel kayıt |
| 03 | Maç oluştur | Varsayılanlar, geçersiz tarih | Taslak |
| 04 | Takım ve kadro | Kayıtlı takım yok, aynı takım seçimi, kadro boş | Yerel takım kaydı |
| 05 | Video kaynağı | Kamera izni yok, video okunamadı, sınır aşımı, hak onayı | Cihaz |
| 06 | Kamera kaydı | İzin istemi, ret, kayıt, duraklama, depolama az, hata | CameraX |
| 07 | Analiz durumu | Yükleniyor, sırada, işleniyor, inceleme bekliyor, tamam, hata, model yok, DEMO | Yükleme motoru, iş API'si |
| 08 | Doğrulama | Klip yükleniyor, klip açılmadı, kart yok, sürüm çakışması, DEMO | Kart API'si |
| 09 | Maç raporu | Taslak, kısmen doğrulanmış, doğrulanmış, rapor yok, DEMO | Rapor API'si |
| 10 | Oyuncu istatistikleri | Kadro yok, oyuncusu bilinmeyen sayılar, ölçüm yok | Rapor API'si ve yerel kadro |

Ek ekranlar (V4): Maçlar listesi, Takımlar ve kadro yönetimi, Profil (gizlilik, sunucu bağlantısı, kayıtlar), çekim rehberi, video kontrol adımı.

## 6. Bağımlılıklar

| Kütüphane | Neden | Lisans |
|---|---|---|
| Compose BOM, Material 3, Navigation Compose, Lifecycle | Mevcut | Apache-2.0 |
| CameraX (`camera-camera2`, `camera-lifecycle`, `camera-video`, `camera-view`) | Ekran 06 | Apache-2.0 |
| Media3 (`media3-exoplayer`, `media3-ui`) | Ekran 08 klip oynatma | Apache-2.0 |
| Inter yazı tipi | UI V1 tipografisi | SIL OFL 1.1 |
| Material Symbols (seçili ikonlar) | İkonlar | Apache-2.0 |
| Roborazzi, Robolectric, MockWebServer (yalnız test) | Ekran görüntüsü ve JVM testleri | Apache-2.0 / MIT |

## 7. Riskler

| Risk | Etki | Önlem |
|---|---|---|
| Geliştirme ortamında emülatör yok (KVM yok) | Kamera ve gerçek cihaz davranışı yerelde denenemez | Enstrümantasyon testleri GitHub Actions emülatöründe koşar. Koşmayan test geçti sayılmaz. |
| CameraX kodu yalnız emülatör kamerasıyla denenir | Gerçek telefonlarda çözünürlük, ısınma, odak farkları | Sistem kamerası ve galeri yolu yedek olarak kalır. Gerçek cihaz denemesi kullanıcıdadır. |
| Yükleme uygulama sürecinde çalışıyor | Uygulama arka planda uzun kalırsa yükleme durur | Kaldığı yerden sürer. WorkManager ön plan servisi sonraki iştir. |
| Model yok | Skor ve aksiyonlar otomatik çıkmaz | Ralli aralığı baseline'ı ve tümüyle kullanıcı doğrulaması (V4: kapı geçilmeden önce "review-first"). DEMO açıkça işaretli. |
| İkon ve uygulama içi palet farklı | Marka tutarsızlığı | Karar marka sahibinde; raporda belirtilir. |
| Tasarım 234×455 piksellik görsellerden okunuyor | İnce ölçüler belirsiz | Token dosyası ve ekran sözleşmesi önceliklidir. |

## 8. Test stratejisi

| Katman | Araç | Kapsam | Nerede |
|---|---|---|---|
| Kural ve rapor mantığı | JUnit (saf Kotlin) | Skor, set, kapsama, bilinmeyen ≠ 0 | Yerel ve CI |
| Veri katmanı | Robolectric + MockWebServer | Yükleme protokolü, kesinti, sağlama, yerel kayıt | Yerel ve CI |
| İstemci–sunucu | `BackendE2eTest` | Gerçek FastAPI sunucusuna yükleme, sürdürme, iş, kart, rapor | Yerel ve CI |
| Görsel | Roborazzi | Her ekranın her durumu, açık ve koyu tema, büyük yazı | Yerel ve CI (görüntüler artefakt) |
| Cihaz akışı | Compose UI testleri, emülatör | Gezinme, form, izin akışı, kamera kaydı | Yalnız CI |
| Arka uç | pytest | Yükleme, dosya kontrolü, iş, kart, karar, rapor | Yerel ve CI |
| Analiz | pytest, sentetik video | Ralli bölütleme ölçüleri, etiket doğrulama | Yerel ve CI |

Bir test koşmadıysa raporda "koşmadı" yazar.

## 9. İlk modül

Sıra: sözleşme → tasarım sistemi → gezinme ve ekran 01–05 → arka uç iş API'si → ekran 06–10.

İlk geliştirilen modül tasarım sistemidir (`ui/theme`, `ui/components`). Gerekçe: On ekranın tamamı ona bağlıdır, yanlış bir token kararı her ekranı yeniden yazdırır ve veri katmanı zaten hazırdır. Sözleşme (`API_V1.md`) aynı anda yazılır, çünkü ekran 07–10 ile arka uç ona göre paralel gelişir.

## 10. Uygulama sonucu ve plandan sapmalar

Ekip bu bölümü 13 Kasım 2026'da, kodu yazdıktan sonra ekledi. Üstteki bölümler planı anlatır. Bu bölüm planla kod arasındaki farkı anlatır.

### Plandan sapan mimari kararlar

| Planda | Kodda | Neden |
|---|---|---|
| `AppViewModel` ve `AnalysisViewModel` | Tek `AppViewModel` | İş, kartlar ve rapor aynı maç kaydına bağlıdır. İkinci ViewModel durumu ikiye bölerdi (ADR-0107). |
| `AppContainer`, `AnalysisRepository` | Yok | `AppViewModel` üç veri sınıfını kendisi kurar. Tek kullanıcısı olan bir ara katman yalnız dosya ekler. |
| `HeroCard`, `SetChips`, `ProvenanceBadge` bileşenleri | Ana ekrandaki yeni maç kartı, `ScoreHeader` içindeki set satırı, yanıt satırındaki "Senin kararın" yazısı | Her biri tek ekranda kullanılır. Ayrı bileşen gerekmedi. |
| Sistem kamerası yedek yol | Yok | Uygulama içi kayıt ve galeri seçimi yeter. Sistem kamerası yolu `FileProvider` ve ek izin akışı isterdi. |

### Ekran görüntülerine bakarak eklenen sapmalar

| Konu | Uygulamada | Neden |
|---|---|---|
| Kayıt ekranı | Yatay düzen ve dikey tutuşta "telefonu çevir" uyarısı | Çekim rehberi yatay çekim ister. Görsel dikey ekranı gösterir. |
| Hizalama köşeleri | Köşeler düğmelerin arasındaki boş alanda durur | İlk çizimde köşeler geri ve kamera değiştir düğmelerinin altında kalıyordu. |
| Doğrulama yanıtları | İki takım yan yana, diğer iki yanıt alt satırda | 320 dp telefonda dört satırlık liste klibin altında ekran dışına taşıyordu. |
| Oyuncu tablosu | Dar ekranda ve büyük yazıda ad sayıların üstüne geçer | Ad sütunu 320 dp genişlikte çöküyordu. |
| Oyuncu sayıları | Doğrulama bitene kadar `5+` | Sayı hâlâ artabilir. Kesin görünen bir `0` eksik ölçümü sıfır gibi gösterirdi. |
| Sütun adları | "Hücum" ve "As" | Sütun plaseyi de sayar. "Servis" sütunu servis sayısını değil ası sayar. |
| DEMO bandı | Doğrulama ve oyuncu ekranında tek satır | Tam açıklama yanıtları ekranın dışına itiyordu. Durum ve rapor ekranı tam açıklamayı taşır. |
| Profil | "Bu sürümde neler var" listesi | "Çalışıyor" etiketi cihazda denenmemiş özellik için fazla iddialıydı. |
| Rapor | Kararsız ralli uyarısı | Sunucu set skorunu kazananı belli olmayan ilk rallide durdurur. Ekran bunu söyler. |

### Gerçekleşen test katmanları

| Katman | Test | Nerede çalıştı |
|---|---|---|
| Veri | `UploadEngineTest`, `LocalStoreTest` | Yerel ve CI |
| Görsel | `ScreenshotTest`: açık, koyu, 320 dp, 1,5 kat yazı, yatay kayıt | Yerel ve CI |
| Gezinme | `JourneyTest`: gerçek etkinlik, gezinme grafiği, ViewModel ve yerel kayıt | Yerel ve CI |
| İstemci ve sunucu | `BackendE2eTest` | Yerel ve CI |
| Ekranlar ve sunucu | `AppFlowE2eTest`: analiz, kararlar, rapor, oyuncu tablosu | Yerel ve CI |
| Cihaz | `RecordingDeviceTest`, `DeviceFlowE2eTest` | Yalnız CI emülatörü. İlk koşu PR #4'te sürüyor. |

Geliştirici görsel hedefle karşılaştırmayı elle yaptı: Her ekran görüntüsüne baktı ve görüntüyü tasarım panosuyla yan yana koydu. Depoda piksel karşılaştırması yapan bir test yok, çünkü tasarım paketi Figma dosyası içermez ve görseller 234 × 455 pikseldir.
