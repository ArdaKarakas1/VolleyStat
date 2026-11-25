# VolleyStat

**Voleybol Ve Veleybol** · Amatör kapalı saha 6 × 6 voleybol maçları için Android uygulaması.

Hedef: Kullanıcı maçı telefonla kaydeder ya da galeriden seçer. Sunucu videoyu analiz eder ve iki takımın istatistiklerini çıkarır. Kullanıcı yalnızca modelin emin olamadığı anları doğrular ve gerekirse ralli listesini düzeltir. Maç FIVB kuralıyla oynanmadıysa kullanıcı maç kuralını bir kez seçer.

Bu depo yalnızca Android uygulamasını ve onun arka ucunu içerir. Web sitesi ayrı bir depodadır (`voleybolveveleybol`) ve bu projeyle kod paylaşmaz.

## Bugünkü durum (uygulama 0.9.0, sunucu 0.9.0)

| Parça | Durum |
| --- | --- |
| Android uygulaması (UI Tasarım V1, on ekran) | Var. Ekran çizimleri, gezinme ve sunucuyla uçtan uca akış testli. Kimse uygulamayı gerçek telefonda denemedi. |
| Kamera kaydı (CameraX) ve klip oynatma (Media3) | Var. CI emülatöründe testli. Gerçek telefonda deneme yok. |
| Video yükleme (parçalı, kaldığı yerden devam, SHA-256) | Çalışıyor, testli. Yükleme uygulama açıkken sürer. |
| Video dosya doğrulaması (ffprobe) | Çalışıyor, sentetik kliplerle testli. Sunucu sahneyi ölçmez. |
| Analiz işi, doğrulama kartları, rapor (API 0.7) | Çalışıyor, testli. Rapor yalnız kullanıcı kararlarından oluşur. |
| Ralli düzeltme ve liste onayı | Var, testli. Kullanıcı kaçan ralliyi ekler, ralliyi böler, iki parçayı birleştirir ve sınırı taşır. Rapor kullanıcı listeyi onaylayana kadar alt sınır verir. Kimse düzeltmeyi gerçek maçta denemedi. |
| Maç kuralı | Var, testli. Kullanıcı maçı açarken kuralı seçer ve FIVB kuralı seçili gelir. Sunucu set skorlarını bu kurala göre sayar. Kullanıcı kuralı ralli listesinden de değiştirir. |
| Ralli baseline'ı (`ml/`, hareket enerjisi) | Var. **Kimse gerçek maçta doğruluğunu ölçmedi.** |
| Eğitilmiş yapay zekâ modeli | **Yok.** Uygulama bugün kendiliğinden istatistik çıkarmaz. Kullanıcı her rallinin kazananını kendisi seçer. |
| Hesaplar (uygulama 0.9, sunucu 0.9) | Var, testli: kayıt, giriş, çıkış, görünen ad, şifre değişikliği ve hesap silme. Uygulama giriş yapmamış kişiye yalnız karşılama, başlangıç, giriş ve kayıt ekranlarını gösterir. Telefon her hesabın maçlarını, kadrolarını ve kayıtlarını ayrı klasörde tutar ve oturumu telefonun anahtar deposundaki bir anahtarla şifreler. Kayıt için operatörün kayıt kodu gerekir. Sunucu sağlık, kayıt ve giriş dışındaki her uçta oturum ister ve her maçı yalnız sahibi olan hesaba gösterir. Sunucu kayıt koduyla açılan bir hesabın kişisel videolarına 8 GiB, kodsuz açılan bir hesabınkine 4 GiB ve bütün hesaplarınkine 40 GiB ayırır. Sunucu açık kaldığı bir gün boyunca parça almayan yüklemeyi siler. Uygulama bu retleri sunucunun gönderdiği sayılarla yazar. Sunucu bir maçı hesaba göstermezse uygulama maçı silinmiş saymaz ve kişi maçı yalnız telefondan kaldırabilir. Kimse giriş ekranlarını gerçek telefonda denemedi. |
| HTTPS, bulut | Yok. Sunucu yalnızca geliştirme bilgisayarında çalışır. |

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

Sunucuda TLS yoktur. İnternete açmayın. Sunucu kayıt kodu olmadan da hesap açar ve kodsuz hesabın kişisel videolarına 4 GiB ayırır. `python -m app.admin signup-code` komutunun çıkardığı kayıt koduyla açılan hesap 8 GiB alır. Kodsuz kaydı kapatmak için sunucuyu `VOLLEYSTAT_SIGNUP=link python -m app` komutuyla başlatın. Yalnızca sentetik ya da kullanım izni açık test videoları yükleyin.

Bir depolama klasörünü aynı anda tek bir sunucu kullanır. Aynı klasörde ikinci bir `python -m app` başlatırsanız ikinci sunucu bir hata yazar ve çıkar. Operatör komutları (`python -m app.admin`) sunucu çalışırken de çalışır.

Sunucu yeni bir yüklemenin boyutunu ve bitmemiş yüklemelerin henüz gelmeyen baytlarını diskteki boş yerden düşer. Kalan yer 20 GiB'ın altına inecekse sunucu yüklemeyi `507 STORAGE_FULL` ile reddeder. Bir parça boş yeri bu sınırın yarısının altına indirecekse sunucu o parçayı yazmaz. Diskiniz küçükse sınırı GiB olarak değiştirin: `VOLLEYSTAT_DISK_FLOOR_GIB=5 python -m app`.

Sunucuyu 0.8'den 0.9'a yükselttiyseniz önceki maçların hiçbiri bir hesaba bağlı değildir. Uygulama 0.8'in giriş yapmış bir hesapla yüklediği maçlar da buna dahildir. Uygulama bu maçlara erişemez. Uygulama 0.9 böyle bir maçın ekranında hesabın maça erişimi olmadığını yazar ve kişiye maçı yalnız telefondan kaldırmayı önerir. Kişi hesabını silince sunucu bu maçları silmez. Uygulamanın hesap silme ekranı bunu yazar ve kişiye maçın takım adlarını ve tarihini not almasını söyler. Önce `python -m app.admin legacy --list` ile maçları listeleyin. Liste her maçın kimliğini, tarihlerini ve takım adlarını gösterir. Sunucu 0.8 hangi hesabın hangi maçı yüklediğini kaydetmiyordu, bu yüzden maçın kime ait olduğunu takım adlarından ve tarihten siz bulursunuz. Sonra her maçı `python -m app.admin legacy --assign <kullanıcı adı> --match <maç kimliği>` ile o kişinin hesabına bağlayın ya da `python -m app.admin legacy --delete --match <maç kimliği>` ile silin. Sunucuda tek hesap varsa `python -m app.admin legacy --assign <kullanıcı adı> --all` bütün maçları o hesaba bağlar. Birden çok hesap varsa komut `--all` ile hiçbir şeyi değiştirmez. `legacy --delete` komutu `--match` olmadan hiçbir hesaba bağlı olmayan bütün maçları siler.

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
2. Arka ucu `python -m app --analyzer demo` komutuyla başlatın. Emülatör bu sunucuya `10.0.2.2:8000` adresinden ulaşır. USB ile bağlı telefon için önce `adb reverse tcp:8000 tcp:8000` komutunu çalıştırın ve giriş ya da kayıt ekranının altındaki "USB ile bağlı telefon" seçeneğini seçin.
3. Uygulama 0.9 açılışta bir hesap ister ve sunucunun 0.9 sürümüyle çalışır, çünkü uygulamanın hesap silme ve gizlilik metinleri yalnız bu sunucu için doğrudur. Hesap oluşturmak için `python -m app.admin signup-code` komutuyla bir kayıt kodu çıkarın ve kodu kayıt ekranındaki "Kayıt kodu" alanına yazın.
4. `app` modülünün **debug** sürümünü çalıştırın. Release sürümünde sunucu adresi bilerek boştur. Bu yüzden kişi release sürümünde hesap açamaz, giriş yapamaz ve başlangıç ekranını geçemez. Ekip sunucuyu internete açan dilimde release sürümüne bir sunucu adresi verene kadar bu sınır sürer. Uygulama 0.7'nin release sürümü maçları, kadroları ve kamera kayıtlarını telefonda hesapsız tutuyordu. Bu veri telefonda kalır, ama kişi 0.8'den beri release sürümünde veriye uygulamadan ulaşamaz.

Demo analizörle gördüğünüz ralli aralıkları uydurmadır. Uygulama bu veriyi her ekranda DEMO etiketiyle gösterir.

Testler:

```bash
cd android
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

# Her ekranın görüntüsünü üretir: app/build/outputs/roborazzi/
./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true

# Uygulamanın istemcisini ve ekranlarını çalışan sunucuya karşı sürer.
# Sunucu 127.0.0.1:8000 adresinde demo analizörle ve açık kayıt kapısıyla çalışmalıdır:
# VOLLEYSTAT_SIGNUP=open VOLLEYSTAT_DISK_FLOOR_GIB=1 python -m app --analyzer demo. Testler kendi e2e_ hesaplarını açar ve siler.
# Kota testi 1 GiB'lık bir yükleme bildirir. Bu yüzden sunucunun diskinde en az 2 GiB boş yer olmalıdır.
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

## Lisans

VolleyStat kapalı bir ticari ürünün kaynağıdır. `LICENSE` dosyası bütün hakları saklı tutar: Depoyu okuyabilmek kodu kullanma, kopyalama ya da dağıtma hakkı vermez. Ürün yalnız Apache 2.0, MIT ve BSD gibi serbest lisanslı modelleri ve kütüphaneleri kullanır. Kararın gerekçesi ADR-0117'dedir.

## Belgeler

- [docs/GAP_ANALYSIS.md](docs/GAP_ANALYSIS.md): Gereksinim ve kod farkı, sıradaki işler
- [docs/AI_PLAN.md](docs/AI_PLAN.md): Maçı analiz edecek yapay zekâ için plan
- [docs/API_V1.md](docs/API_V1.md): Analiz, doğrulama, ralli düzeltme, maç kuralı, rapor, hesap, erişim ve depolama sözleşmesi (API 0.9)
- [docs/API.md](docs/API.md): Yükleme ve dosya doğrulama uçları
- [docs/UI_V1_PLAN.md](docs/UI_V1_PLAN.md): UI Tasarım V1 uygulama planı
- [docs/design.md](docs/design.md): Tasarım sistemi
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): Kod ağacı ve test katmanları
- [docs/ADR.md](docs/ADR.md): Mimari karar defteri
