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

**Durum:** Kabul edildi

V3 şartnamesindeki lacivert/mavi/turkuaz palet kendi ifadesiyle "bağlayıcı marka rehberi değil, ilk konsept"tir. `docs/design.md` paleti (krem `#FCF7EB`, lacivert `#162340`, turuncu `#9E491B`) kullanıcının sağladığı logoyla birlikte gelmiştir ve daha yenidir.

## ADR-0105: Android derlemesi CI'da doğrulanır

**Durum:** Kabul edildi

Geliştirme oturumunun çalıştığı bulut ortamı Android SDK sunucusuna erişemez. Bu yüzden APK derlemesi, lint ve Android birim testleri GitHub Actions üzerinde çalışır (`.github/workflows/android-build.yml`). Emülatör ve gerçek cihaz testi bu kapsamda değildir ve ayrıca yapılmalıdır.
