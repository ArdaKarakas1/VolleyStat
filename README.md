# VolleyStat

**Voleybol Ve Veleybol** · Amatör kapalı saha 6 × 6 voleybol maçları için Android uygulaması.

Hedef: Kullanıcı maçı telefonla kaydeder ya da galeriden seçer. Sunucu videoyu analiz eder ve iki takımın istatistiklerini çıkarır. Kullanıcı yalnızca modelin emin olamadığı anları doğrular.

Bu depo yalnızca Android uygulamasını ve onun arka ucunu içerir. Web sitesi ayrı bir depodadır (`voleybolveveleybol`) ve bu projeyle kod paylaşmaz.

## Bugünkü durum (sürüm 0.2.0)

| Parça | Durum |
| --- | --- |
| Video yükleme API'si (parçalı, kaldığı yerden devam, SHA-256) | Çalışıyor, testli |
| Video dosya doğrulaması (ffprobe) | Çalışıyor, sentetik kliplerle testli |
| Kotlin kural çekirdeği (set bitişi, servis sırası, skor) | Çalışıyor, testli |
| Android uygulaması | Tek ekran: takım adları, video seçme ya da kaydetme, yükleme, dosya kontrol sonucu |
| Yapay zekâ modeli | **Yok.** Analiz isteği `503 MODEL_NOT_CONFIGURED` döner. Uygulama istatistik üretmez. |
| Hesap sistemi, HTTPS, bulut | Yok. Sunucu yalnızca geliştirme bilgisayarında çalışır. |

Özellik bazında tam döküm: [docs/GAP_ANALYSIS.md](docs/GAP_ANALYSIS.md). Mimari kararlar: [docs/ADR.md](docs/ADR.md).

## Yerel çalıştırma

### Arka uç

Python 3.12+ ve FFmpeg gerekir.

```bash
cd backend
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements-dev.txt
uvicorn app.main:app --host 127.0.0.1 --port 8000
pytest -q          # başka bir terminalde
```

Sunucuda hesap sistemi ve TLS yoktur. İnternete açmayın. Yalnızca sentetik ya da kullanım izni açık test videoları yükleyin.

Test klibi üretmek için:

```bash
ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30 -t 5 -c:v libx264 -pix_fmt yuv420p ornek.mp4
```

### Android

1. `android/` klasörünü Android Studio ile açın (JDK 17, Android SDK 36).
2. Arka ucu `127.0.0.1:8000` adresinde başlatın. Emülatör bu adrese `10.0.2.2:8000` üzerinden ulaşır.
3. `app` modülünün **debug** sürümünü çalıştırın. Release sürümünde sunucu adresi bilerek boştur.

Komut satırı:

```bash
cd android
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Her PR'da GitHub Actions bu komutları çalıştırır ve debug APK'yı artefakt olarak saklar.

## Depoya girmeyenler

Depo herkese açıktır. Maç videoları, oyuncu verileri, API anahtarları, veritabanları ve model ağırlıkları commit edilmez (`.gitignore`).

`tools/import_youtube.py` yalnızca indirme ve kullanım hakkı belgelenmiş videolar içindir. Araç `--rights` ile bir lisans ya da yazılı izin referansı ister ve bunu videonun yanına kaydeder. Bir YouTube bağlantısının var olması eğitim hakkı vermez.

## Belgeler

- [docs/GAP_ANALYSIS.md](docs/GAP_ANALYSIS.md): Gereksinim ve kod farkı, sıradaki işler
- [docs/ADR.md](docs/ADR.md): Mimari karar defteri
- [docs/API.md](docs/API.md): Yerel API
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): Kod ağacı ve veri ayrımları
- [docs/design.md](docs/design.md): Tasarım dili
