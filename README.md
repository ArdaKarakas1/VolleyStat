# VolleyStat

**Voleybol Ve Veleybol** · Amatör kapalı saha 6 × 6 voleybol maçları için Android uygulaması.

Hedef: Kullanıcı maçı telefonla kaydeder ya da galeriden seçer. Sunucu videoyu analiz eder ve iki takımın istatistiklerini çıkarır. Kullanıcı yalnızca modelin emin olamadığı anları doğrular.

Bu depo yalnızca Android uygulamasını ve onun arka ucunu içerir. Web sitesi ayrı bir depodadır (`voleybolveveleybol`) ve bu projeyle kod paylaşmaz.

## Bugünkü durum (sürüm 0.4.2)

| Parça | Durum |
| --- | --- |
| Android uygulaması (UI Tasarım V1, on ekran) | Var. Ekran çizimleri, gezinme ve sunucuyla uçtan uca akış testli. Kimse uygulamayı gerçek telefonda denemedi. |
| Kamera kaydı (CameraX) ve klip oynatma (Media3) | Var. CI emülatöründe testli. Gerçek telefonda deneme yok. |
| Video yükleme (parçalı, kaldığı yerden devam, SHA-256) | Çalışıyor, testli. Yükleme uygulama açıkken sürer. |
| Video dosya doğrulaması (ffprobe) | Çalışıyor, sentetik kliplerle testli. Sunucu sahneyi ölçmez. |
| Analiz işi, doğrulama kartları, rapor (API 0.4) | Çalışıyor, testli. Rapor yalnız kullanıcı kararlarından oluşur. |
| Ralli baseline'ı (`ml/`, hareket enerjisi) | Var. **Kimse gerçek maçta doğruluğunu ölçmedi.** |
| Eğitilmiş yapay zekâ modeli | **Yok.** Uygulama bugün kendiliğinden istatistik çıkarmaz. Kullanıcı her rallinin kazananını kendisi seçer. |
| Hesap sistemi, HTTPS, bulut | Yok. Sunucu yalnızca geliştirme bilgisayarında çalışır. |

Özellik bazında tam döküm: [docs/GAP_ANALYSIS.md](docs/GAP_ANALYSIS.md). Yapay zekâ planı: [docs/AI_PLAN.md](docs/AI_PLAN.md). Mimari kararlar: [docs/ADR.md](docs/ADR.md).

## Yerel çalıştırma

### Arka uç

Python 3.12+ ve FFmpeg gerekir.

```bash
cd backend
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements-dev.txt
python -m app      # 127.0.0.1:8000, yalnız bu bilgisayardan erişilir
pytest -q          # başka bir terminalde
```

Sunucuda hesap sistemi ve TLS yoktur. İnternete açmayın. Yalnızca sentetik ya da kullanım izni açık test videoları yükleyin.

Sunucu varsayılan olarak analizör olmadan başlar ve analiz isteğine `503 MODEL_NOT_CONFIGURED` yanıtını verir. Analiz, inceleme ve rapor ekranlarını denemek için sunucuyu demo analizörle başlatın:

```bash
python -m app --analyzer demo      # ya da: VOLLEYSTAT_ANALYZER=demo python -m app
```

Demo analizör videonun görüntüsüne bakmaz. Demo analizör ralli aralıklarını yalnızca videonun süresinden uydurur, kazanan ve güven puanı önermez. Sunucu demo analizörün her işini ve raporunu `mock: true` ve `kind: "MOCK"` ile işaretler. Demo çıktısı uydurmadır. Bu çıktı gerçek bir analizin ya da maçın sonucu değildir.

Test klibi üretmek için:

```bash
ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30 -t 5 -c:v libx264 -pix_fmt yuv420p ornek.mp4
```

### Android

1. `android/` klasörünü Android Studio ile açın (JDK 17, Android SDK 36).
2. Arka ucu `python -m app --analyzer demo` komutuyla başlatın. Emülatör bu sunucuya `10.0.2.2:8000` adresinden ulaşır. USB ile bağlı telefon için önce `adb reverse tcp:8000 tcp:8000` komutunu çalıştırın ve uygulamanın Profil sekmesinden "USB ile bağlı telefon" seçeneğini seçin.
3. `app` modülünün **debug** sürümünü çalıştırın. Release sürümünde sunucu adresi bilerek boştur.

Demo analizörle gördüğünüz ralli aralıkları uydurmadır. Uygulama bu veriyi her ekranda DEMO etiketiyle gösterir.

Testler:

```bash
cd android
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

# Her ekranın görüntüsünü üretir: app/build/outputs/roborazzi/
./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true

# Uygulamanın istemcisini ve ekranlarını çalışan sunucuya karşı sürer.
# Sunucu 127.0.0.1:8000 adresinde demo analizörle açık olmalıdır.
VOLLEYSTAT_E2E_URL=http://127.0.0.1:8000/v1 VOLLEYSTAT_E2E_VIDEO=/yol/ornek.mp4 \
  ./gradlew :app:testDebugUnitTest --tests '*E2eTest*'

# Emülatörde ya da telefonda: kamera kaydı, klip oynatma ve baştan sona akış.
./gradlew :app:connectedDebugAndroidTest
```

Her PR'da GitHub Actions bu testleri çalıştırır. İş akışı debug APK'yı ve ekran görüntülerini artefakt olarak saklar.

### Ralli baseline'ı ve ölçüm araçları

```bash
cd ml
pip install -e ".[test]"
pytest -q
python -m volleystat_ml segment /yol/video.mp4
```

Baseline'ı sunucuda denemek için `ml` paketini sunucunun ortamına kurun ve sunucuyu `python -m app --analyzer motion-baseline` komutuyla başlatın. Baseline yalnız ralli aralığı önerir. Kimse bu önerilerin gerçek maçtaki doğruluğunu ölçmedi. Ayrıntı: [ml/README.md](ml/README.md).

## Depoya girmeyenler

Depo herkese açıktır. Maç videoları, oyuncu verileri, API anahtarları, veritabanları ve model ağırlıkları commit edilmez (`.gitignore`).

`tools/import_youtube.py` yalnızca indirme ve kullanım hakkı belgelenmiş videolar içindir. Araç `--rights` ile bir lisans ya da yazılı izin referansı ister ve bunu videonun yanına kaydeder. Bir YouTube bağlantısının var olması eğitim hakkı vermez.

## Belgeler

- [docs/GAP_ANALYSIS.md](docs/GAP_ANALYSIS.md): Gereksinim ve kod farkı, sıradaki işler
- [docs/AI_PLAN.md](docs/AI_PLAN.md): Maçı analiz edecek yapay zekâ için plan
- [docs/API_V1.md](docs/API_V1.md): Analiz, doğrulama ve rapor sözleşmesi (API 0.4)
- [docs/API.md](docs/API.md): Yükleme ve dosya doğrulama uçları
- [docs/UI_V1_PLAN.md](docs/UI_V1_PLAN.md): UI Tasarım V1 uygulama planı
- [docs/design.md](docs/design.md): Tasarım sistemi
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): Kod ağacı ve test katmanları
- [docs/ADR.md](docs/ADR.md): Mimari karar defteri
