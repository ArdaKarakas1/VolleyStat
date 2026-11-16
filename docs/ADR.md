# Mimari karar defteri

Sabit ürün kararları (ADR-001 … ADR-012) V4 devir raporundadır ve burada tekrarlanmaz. Bu dosya geliştirme sırasında alınan kararları tutar.

## ADR-0101: Tek kod tabanı Kotlin/Compose + FastAPI

**Tarih:** 12 Kasım 2026 · **Durum:** Kabul edildi

**Bağlam:** 12 Kasım'da iki ayrı başlangıç kodu vardı.

1. `main` dalındaki commit `b66a620` (9 Kasım): Java + Android View tabanlı uygulama (`com.volleyvolleystat.app`) ve bağımlılıksız Python sunucusu. Ana akışı elle olay etiketlemedir: kullanıcı videoyu oynatır ve her olayı kendi girer.
2. V4 devir paketindeki önizleme: Kotlin + Jetpack Compose uygulaması (`com.voleybolveveleybol.volleystat`), saf Kotlin kural çekirdeği ve FastAPI sunucusu. V4 raporu bu kodu "mevcut kod" kabul eder ve `main` dalındaki commit'ten söz etmez.

**Karar:** Depo ikinci kod tabanıyla devam eder. Birinci kod tabanı ağaçtan çıkarıldı ve git geçmişinde (`b66a620`) durur.

**Gerekçe:**

- Sabit karar 2 istemciyi Kotlin + Jetpack Compose olarak belirler. Birinci uygulama Java + View'dir.
- Sabit karar 3 elle ralli girişini zorunlu kılan tasarımı reddeder. Birinci uygulamanın ana akışı tam olarak budur.
- Birinci sunucunun istatistik toplamı ölçülmeyen metriği `0` döndürür. Ürün kuralı `UNKNOWN ≠ 0`'dır.
- İki paralel uygulamayı ve iki sunucuyu sürdürmek her dilimde çift iş demektir.

**Birinci kod tabanından korunanlar:** ffprobe ile dosya doğrulama yaklaşımı (`backend/app/media.py` olarak genişletildi), marka logosu, tasarım paleti (`docs/design.md`), yedekleme dışlama kuralları, yalnız iki yerel adrese açık debug ağ ayarı, Gradle wrapper, izin kayıtlı YouTube aktarım aracı.

**İleride geri alınacak fikirler (kod `b66a620` içinde):** HTTP Range ile video sunma (kanıt klibi dilimi), olay sürümüyle çakışma denetimi (inceleme kararı dilimi), aynı ralliye ikinci sayı yazmayı engelleyen benzersizlik kuralı.

## ADR-0102: Uygulama kimliği `com.voleybolveveleybol.volleystat`

**Durum:** Kabul edildi

Paket adı markayı taşır. Eski `com.volleyvolleystat.app` kimliği yayımlanmadı, bu yüzden geçiş maliyeti yoktur. Play Store'a ilk yüklemeden sonra bu kimlik değiştirilemez.

## ADR-0103: Dosya doğrulaması kabul kapısıdır, kalite uyarısı ret değildir

**Durum:** Kabul edildi

- Sunucu her yüklenen dosyayı ffprobe ile inceler. Geçemeyen dosya `QUARANTINED` olur ve analiz edilemez. Dosya silinmez; kullanıcı nedeni görür ve videoyu kendisi siler.
- Düşük çözünürlük, düşük kare hızı, dikey çekim ve değişken kare hızı yalnızca uyarıdır. Kamera standardı 720p videoyu "ayrı kalite katmanı" sayar, otomatik reddetmez.
- ffprobe demuxer'ı dosyanın imza baytlarından seçilir ve protokol `file` ile sınırlanır. Böylece hazırlanmış bir dosya ffprobe'u ağdan içerik okumaya yönlendiremez.
- Sunucu sahneyi (saha, top, sabitlik, ışık) ölçmez. Kalite raporu bu alanları `NOT_MEASURED` döndürür.

## ADR-0104: Arayüz paleti `docs/design.md` paletidir

**Durum:** ADR-0106 bu kararın yerini aldı

V3 şartnamesindeki lacivert/mavi/turkuaz palet kendi ifadesiyle "bağlayıcı marka rehberi değil, ilk konsept"tir. `docs/design.md` paleti (krem `#FCF7EB`, lacivert `#162340`, turuncu `#9E491B`) kullanıcının sağladığı logoyla birlikte gelmiştir ve daha yenidir.

## ADR-0105: Android derlemesi CI'da doğrulanır

**Durum:** ADR-0109 bu kararın yerini aldı

Geliştirme oturumunun çalıştığı bulut ortamı Android SDK sunucusuna erişemez. Bu yüzden APK derlemesi, lint ve Android birim testleri GitHub Actions üzerinde çalışır (`.github/workflows/android-build.yml`). Emülatör ve gerçek cihaz testi bu kapsamda değildir ve ayrıca yapılmalıdır.

## ADR-0106: Uygulamanın görsel kimliği UI Tasarım V1'dir

**Tarih:** 12 Kasım 2026 · **Durum:** Kabul edildi

Ürün sahibi UI Tasarım V1 paketini verdi ve mor ve beyaz kimliği temel almamızı istedi. Uygulama bu paketin renklerini, Inter yazı tipini ve on ekranın düzenini kullanır. Tokenlar `ui/theme/Theme.kt` dosyasındadır ve `docs/design.md` bu sistemi anlatır.

Uygulama görsellerden şu noktalarda bilerek ayrılır, çünkü görseldeki öğe ürün kuralını çiğner: sahte yüzde gösteren halka, sunucunun bildirmediği analiz adımları, dolu görünen ama ölçümü olmayan sütunlar ve örnek kulüp adları. Tam liste `docs/UI_V1_PLAN.md` dosyasının 2. bölümündedir.

Uygulama simgesi ürün sahibinin suluboya logosudur. Logonun paleti (krem, lacivert, turuncu) uygulamanın moruyla uyuşmaz. Bu kararı marka sahibi verir.

## ADR-0107: Tek ViewModel, elle bağımlılık kurulumu, Hilt yok

**Durum:** Kabul edildi

Uygulamada bir `AppViewModel` vardır. `AppViewModel` üç veri sınıfını kendisi kurar: `LocalStore`, `ApiClient` ve `VideoInspector`. Bu ölçekte bir bağımlılık çatısı yalnız derleme süresi ve öğrenme yükü ekler. Testler aynı sınıfları doğrudan kurar ya da gerçek etkinliği sürer.

Ekip Hilt'i şu durumda yeniden değerlendirir: Uygulama birden çok `ViewModel` ve ortak bir depo katmanı taşır ya da arka plan işçisi aynı bağımlılıklara ihtiyaç duyar.

## ADR-0108: Uygulama içi kayıt CameraX, klip Media3

**Durum:** Kabul edildi

- Kayıt ekranı CameraX `VideoCapture` kullanır. Kalite sırası Full HD, HD, SD biçimindedir. Uygulama ses kaydetmez, çünkü analiz sesi kullanmaz ve salondaki konuşmalar kişisel veridir.
- Etkinlik dönüşte yeniden kurulmaz (`configChanges`), çünkü yeniden kurulum süren kaydı ve yüklemeyi keserdi. Bu yüzden kayıt ekranı ekranın dönüşünü kendisi dinler ve kaydediciye bildirir.
- Kayıt dosyası uygulamanın özel klasöründe durur. Kullanıcı kaydı Profil sekmesinden siler.
- Doğrulama ekranı klibi Media3 ile sunucudan `Range` isteğiyle oynatır. Sunucu ayrı klip dosyası üretmez.

## ADR-0109: Test katmanları ve cihaz testleri

**Durum:** Kabul edildi

Geliştirme oturumunun bulut ortamı artık Android SDK'ya erişir ve uygulamayı derler, ama emülatör çalıştıramaz (donanım hızlandırma yok). Testler bu yüzden üç yerde çalışır:

1. **JVM (Robolectric):** Yükleme motoru, yerel kayıt, gezinme ve her ekranın çizimi. Roborazzi her ekranın görüntüsünü üretir ve CI bu görüntüleri artefakt olarak saklar.
2. **JVM ve gerçek sunucu:** `BackendE2eTest` ve `AppFlowE2eTest` uygulamanın istemcisini ve ekranlarını çalışan bir sunucuya karşı sürer.
3. **Emülatör (yalnız CI):** `RecordingDeviceTest` ve `DeviceFlowE2eTest` kamerayı, sunucudan klip oynatmayı ve baştan sona akışı dener.

Uçtan uca ve cihaz testleri sunucu ya da kamera yoksa kendilerini atlar. CI'da `tools/require_tests_ran.py` atlanan testi hata sayar.

Gerçek telefonda deneme hâlâ ürün sahibinin işidir. Emülatörün kamerası sanal bir sahne üretir ve gerçek bir salonu, gerçek bir tripodu ve uzun bir kaydı temsil etmez.

Cihaz testi ilk koşularında değerini gösterdi: Emülatördeki tam akış testi, JVM testlerinin hiçbirinin geçmediği bir yolda (kontrol ekranından yüklemeyi başlatma) uygulamanın kullanıcıyı ana ekrana attığını buldu. Ekip hatayı önce JVM'de yeniden üreten bir test yazdı, sonra düzeltti.

## ADR-0110: Analizör eklentisi ve önce inceleme akışı

**Durum:** Kabul edildi

- Sunucu analizörü `VOLLEYSTAT_ANALYZER` değişkeninden ya da `--analyzer` seçeneğinden alır. Seçim yoksa analiz isteği `503 MODEL_NOT_CONFIGURED` döner.
- Her analizör türünü bildirir: `MOCK`, `BASELINE` ya da `MODEL`. Sunucu `MOCK` işini ve raporunu `mock: true` ile işaretler ve uygulama bu veriyi her ekranda DEMO etiketiyle gösterir.
- Analizör yalnız ralli aralığı ve isterse kazanan önerir. Rapor yalnız kullanıcı kararlarından oluşur. Öneri hiçbir zaman doğrudan rapora girmez.
- Bir bileşen kendi kapısını (`docs/AI_PLAN.md`) kilitli test kümesinde geçene kadar önce inceleme kipinde kalır.

## ADR-0111: Yükleme uygulama açıkken sürer

**Durum:** Kabul edildi, geçici

Yükleme `AppViewModel` içinde çalışır ve uygulama kapanınca durur. Kullanıcı uygulamayı yeniden açıp yüklemeyi sürdürdüğünde yükleme kaldığı bayttan devam eder. Uzun bir maç videosu için arka plan işçisi (WorkManager ve ön plan hizmeti) gerekir. Ekip bu işi gerçek bir telefonda denemeden yazmadı, çünkü Android'in arka plan kuralları sürümden sürüme değişir ve emülatör bu davranışı temsil etmez. Sunucu bugün yalnız geliştirme bilgisayarında çalıştığı için bu eksik ilk pilotu engellemez.

## ADR-0112: Rapor alt sınır verir ve set sayımı belirsiz rallide durur

**Tarih:** 13 Kasım 2026 · **Durum:** Kabul edildi

Bağımsız inceleme iki sorun buldu. Kararsız tek bir ralli sonraki bütün sayıları tek bir açık sete yığıyordu ve rapor `74-60` gibi kurallara aykırı bir set skoru veriyordu. İnceleme akışı analizörün kaçırdığı ralliyi ekleyemediği hâlde rapor bütün kartlar yanıtlanınca değerleri `MEASURED` olarak bildiriyordu.

Kararlar:

- Sunucu set sayımını kazananı belli olmayan ilk rallide durdurur ve o rallinin sıra numarasını `score.stoppedAtRally` alanında bildirir. O rallinin kazananı sonraki her sayının hangi sete ait olduğunu belirler, bu yüzden sunucu tahmin yürütmez. Takım toplamları doğrulanmış bütün rallileri saymayı sürdürür.
- Sunucu maçı üç sette bitirmez. Antrenman maçları çoğu zaman sabit sayıda set oynar. Beşinci set 15 sayıya, öbür setler 25 sayıya gider.
- Analizörün türü `MODEL` değilse rapor `ralliesMayBeMissing: true` taşır ve hiçbir değeri `MEASURED` olarak bildirmez. Uygulama bu durumu rapor ekranında yazar ve sayıları artı işaretiyle gösterir.
- Yalnız `MODEL` türündeki analizör güven puanı verebilir. `MOCK` kazanan öneremez. `BASELINE` kazanan önerebilir ve uygulama öneriyi yalnız ipucu olarak gösterir.

Açık kalan iş: Kullanıcının kaçan ralliyi eklemesi, ralliyi bölmesi, birleştirmesi ve sınırını düzeltmesi. Bu iş `docs/GAP_ANALYSIS.md` 7. bölümün ilk maddesidir.

