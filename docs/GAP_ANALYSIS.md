# Fark analizi: gereksinimler ve mevcut kod

**Tarih:** 13 Kasım 2026 · **Kod sürümü:** 0.4.1 · **Kaynaklar:** V4 devir raporu ve ekleri, UI Tasarım V1 paketi

Durum etiketleri:

- **TEST EDİLDİ**: Kod var ve otomatik testi bu sürümde çalıştı. Satır testin nerede çalıştığını yazar.
- **KISMEN**: Bir bölümü var. Satır eksik bölümü yazar.
- **YOK**: Kimse bu işi henüz yazmadı. İş yazılım işidir ve dış bağımlılık beklemez.
- **DIŞ BAĞIMLILIK**: İş gerçek veri, hesap, cihaz, bütçe ya da hukuki onay ister.
- **ARAŞTIRMA**: Ekip hangi yöntemin işe yaradığını ölçmeden karar veremez.

Depoda eğitilmiş model yok. Kimse hiçbir yapay zekâ bileşeninin doğruluğunu ölçmedi. G0–G8 kapılarının hepsi `NOT_MEASURED` durumundadır. Uygulama bugün gerçek maç istatistiğini kendiliğinden çıkarmaz: Kullanıcı her rallinin kazananını kendisi seçer ve rapor bu kararlardan oluşur.

**En önemli sınır:** İnceleme yalnızca analizörün önerdiği ralliyi onaylar ya da reddeder. Kullanıcı analizörün kaçırdığı ralliyi ekleyemez, birleşmiş iki ralliyi bölemez ve ralli sınırını düzeltemez. Bu yüzden rapordaki her sayı alt sınırdır ve set sınırı kayabilir. Sunucu bunu raporda `ralliesMayBeMissing` alanıyla bildirir ve uygulama raporda yazar. Bu eksik kapanmadan uygulama gerçek bir maçın skorunu güvenle veremez.

## 1. Fonksiyonel gereksinimler (FR-01 … FR-20)

| ID | Özellik | Öncelik | Durum | Açıklama |
|---|---|---|---|---|
| FR-01 | Modern Android arayüzü | P0 | TEST EDİLDİ (JVM) | UI Tasarım V1: on ekran, dört sekme, açık ve koyu tema. Ekran görüntüsü testleri 411 dp, 320 dp ve 1,5 kat yazıyı kapsar. Kimse uygulamayı gerçek telefonda denemedi. |
| FR-02 | Hesap ve takım taslağı | P0 | KISMEN | Takımlar ve kadrolar telefonda durur ve testlidir. Hesap sistemi yok. |
| FR-03 | Galeriden video | P0 | KISMEN | Sistem Photo Picker bağlı, küçük resim ve süre görünür. Seçici hiçbir otomatik testte çalışmadı. |
| FR-04 | Kamera video kaydı | P0 | KISMEN | CameraX ile uygulama içi kayıt: süre, duraklat, bitir, boş alan uyarısı, izin akışı. Kayıt CI emülatöründe (API 34) TEST EDİLDİ. Uzun kayıt ve gerçek telefon denemesi yok. |
| FR-05 | Kamera yerleşim rehberi | P0 | KISMEN | Çekim rehberi ekranı ve hizalama köşeleri var. Uygulama sahayı algılamaz ve kadrajı denetlemez. |
| FR-06 | Video kabul kontrolü | P0 | KISMEN | Sunucu codec, süre, çözünürlük, kare hızı ve dosya bütünlüğünü denetler (TEST EDİLDİ). Sunucu sahneyi ölçmez. Saha bölgesi seçimi yok. |
| FR-07 | Kesintiye dayanıklı yükleme | P0 | KISMEN | Protokol ve istemci motoru TEST EDİLDİ (kopma, kaldığı yerden sürme, sağlama toplamı). Yükleme uygulama açıkken sürer. Arka plan işçisi yok (ADR-0111). |
| FR-08 | Analiz iş durumu | P0 | TEST EDİLDİ | İş kuyruğu, durum makinesi, iptal, çöken işi kurtarma. Ekran yalnız sunucunun bildirdiği adımı ve oranı gösterir. |
| FR-09 | Ralli tahmini | P0 | KISMEN + DIŞ BAĞIMLILIK | Hareket enerjisi baseline'ı var ve sentetik kliplerle testli. Gerçek maçta ölçüm izinli video ve gold etiket ister. |
| FR-10 | Olası takım kazananı | P0 | DIŞ BAĞIMLILIK | Hiçbir analizör kazanan önermiyor. Plan `docs/AI_PLAN.md` 2.7 bölümünde. |
| FR-11 | Doğrulama kuyruğu | P0 | KISMEN | Kartlar, sürüm denetimli karar, geri alma ve denetim kaydı TEST EDİLDİ. Ekran: klip, tek soru, tek dokunuş. Kullanıcı kaçan ralliyi ekleyemez, ralliyi bölemez ve sınırını düzeltemez. Akış yalnız DEMO analizörle denendi. |
| FR-12 | İki takım raporu | P0 | KISMEN | Sunucu raporu yalnız kullanıcı kararlarından hesaplar (TEST EDİLDİ). Her değer `MEASURED`, `PARTIAL` ya da `UNKNOWN` durumu taşır. Ölçümü kayıtlı bir model gelene kadar hiçbir değer `MEASURED` olmaz. Set sayımı kazananı belli olmayan ilk rallide durur. |
| FR-13 | Ralli bazında video oynatma | P0 | KISMEN | Sunucu videoyu `Range` isteğiyle sunar (TEST EDİLDİ). Media3 oynatıcı CI emülatöründe klibi sunucudan parça parça istedi ve hata vermeden oynattı. Test görüntünün doğru kareyi gösterdiğini denetlemez. |
| FR-14 | Oyuncu eşlemesi | P1 | KISMEN | Kullanıcı sayıyı alan oyuncuyu forma numarasıyla seçer. Otomatik eşleme DIŞ BAĞIMLILIK'tır. |
| FR-15 | Tam aksiyon çıkarımı | P1 | DIŞ BAĞIMLILIK | Kullanıcı yalnız ralliyi bitiren aksiyonu seçebilir. |
| FR-16 | Eğitim kullanım izni | P0 | KISMEN | İzin varsayılan olarak kapalıdır ve sunucu `allowTraining: true` isteğini reddeder (TEST EDİLDİ). Ayrı onay ve geri çekme akışı yok. |
| FR-17 | Veriyi ve videoyu silme | P0 | TEST EDİLDİ | Video silme işi, rallileri, kartları ve kararları da siler. Uygulama maçı ve telefondaki kaydı siler. |
| FR-18 | Canlı kamera analizi | P2 | Kapsam dışı | Kayıtlı video analizi kanıtlandıktan sonra. |
| FR-19 | Cihaz üzerinde model | P2 | Kapsam dışı | |
| FR-20 | Sosyal medya çıktısı | P1 | YOK | |

## 2. Android ekranları (UI Tasarım V1)

| # | Ekran | Durum | Açıklama |
|---|---|---|---|
| 01 | Açılış | TEST EDİLDİ (JVM) | Yalnız ilk açılışta görünür. |
| 02 | Ana ekran | TEST EDİLDİ (JVM) | Yeni maç kartı, devam eden maçlar, dört liste satırı. Sayılar gerçek kayıtlardan gelir. |
| 03 | Maç oluştur | TEST EDİLDİ (JVM) | Ad, tarih, salon ve tür isteğe bağlıdır. |
| 04 | Takım ve kadro | TEST EDİLDİ (JVM) | Kayıtlı takımdan seçim, yeni takım, kadro. |
| 05 | Video kaynağı | TEST EDİLDİ (JVM) | Kamera ya da galeri. Ardından telefonun okuduğu dosya bilgileri ve hak beyanı. |
| 06 | Maç kaydı | TEST EDİLDİ (JVM, CI emülatörü) | Çizim ve izin akışı JVM'de, kayıt emülatörde testli. Gerçek telefonda deneme yok. |
| 07 | Yükleme ve analiz durumu | TEST EDİLDİ (JVM, gerçek sunucu) | |
| 08 | Belirsiz olay doğrulama | TEST EDİLDİ (JVM, gerçek sunucu, CI emülatörü) | Klip oynatma emülatörde testli. |
| 09 | Maç raporu | TEST EDİLDİ (JVM, gerçek sunucu) | |
| 10 | Oyuncu istatistikleri | TEST EDİLDİ (JVM, gerçek sunucu) | Savunma sütunu ölçümsüzdür ve boş kalır. |
| | Maçlar, Takımlar, Profil, Çekim rehberi | TEST EDİLDİ (JVM) | |

V4 raporundaki şu ekranlar yok: hesap ve gizlilik onayı (S01), saha bölgesi seçimi (S07'nin dört nokta adımı), oyuncu eşleştirme (S12), paylaşım (S14).

## 3. API sözleşmesi

| Rota | Durum |
|---|---|
| `POST /matches`, `GET /matches/{id}` | TEST EDİLDİ |
| `POST /matches/{id}/uploads`, `PUT /uploads/{id}/chunks`, `GET /uploads/{id}`, `POST /uploads/{id}/complete`, `DELETE /uploads/{id}` | TEST EDİLDİ |
| `GET /videos/{id}`, `GET` ve `POST /videos/{id}/quality`, `DELETE /videos/{id}` | TEST EDİLDİ. Sahne ölçümleri `NOT_MEASURED`. |
| `GET /videos/{id}/content` (`Range`) | TEST EDİLDİ |
| `POST /videos/{id}/analyses`, `GET /analyses/{id}`, `GET /videos/{id}/analyses/latest`, `POST /analyses/{id}/cancel` | TEST EDİLDİ |
| `GET /analyses/{id}/rallies`, `GET /analyses/{id}/review-cards` | TEST EDİLDİ |
| `POST /review-cards/{id}/decisions`, `POST /review-cards/{id}/reopen` | TEST EDİLDİ |
| `GET /matches/{id}/report` | TEST EDİLDİ |
| `PATCH /videos/{id}/court-roi` | YOK |
| `PATCH /videos/{id}/training-permission` | YOK |

Sözleşme `docs/API_V1.md` dosyasındadır ve `docs/openapi-local-dev.json` anlık görüntüsü bir testle korunur.

Altyapı: Kimlik doğrulama, kiracı sınırı, HTTPS, PostgreSQL, nesne depolama, dağıtık iş kuyruğu ve GPU işçisi **YOK**. Ekip bu kodu yazabilir. Çalıştırmak bulut hesabı ve bütçe ister (DIŞ BAĞIMLILIK).

## 4. Yapay zekâ bileşenleri

Ayrıntılı plan `docs/AI_PLAN.md` dosyasındadır.

| Bileşen | Durum | Ne gerekiyor |
|---|---|---|
| Oyuncu tespiti | ARAŞTIRMA + DIŞ BAĞIMLILIK | Hazır model adayları ve lisansları planda. Ölçüm izinli amatör salon videosu ister. |
| Top tespiti ve takibi | ARAŞTIRMA + DIŞ BAĞIMLILIK | En yüksek teknik risk (R01). |
| Saha tespiti | YOK | İlk çözüm kullanıcının dört köşeyi işaretlemesidir ve model istemez. Ekip bu işi gerçek video beklemeden yazabilir. |
| Ralli bölümleme | KISMEN | Hareket enerjisi baseline'ı var. Kimse gerçek maçta ölçmedi. Ölçüm 20–50 gold ralli ister. |
| Aksiyon tanıma | DIŞ BAĞIMLILIK | Bugün kullanıcı ralliyi bitiren aksiyonu kartta seçer. |
| Oyuncu kimliği | ARAŞTIRMA + DIŞ BAĞIMLILIK | Bugün kullanıcı forma numarasını kartta seçer. Yüz tanıma kapsam dışıdır. |
| Ralli kazananı | ARAŞTIRMA + DIŞ BAĞIMLILIK | Bugün kullanıcı kazananı kartta seçer. |
| İstatistik üretimi | TEST EDİLDİ | Sunucu raporu kararlardan hesaplar. Bu bileşen model içermez. |
| Değerlendirme hattı | KISMEN | Ralli sınırı metrikleri, güven aralığı, etiket denetimi, ayrım ve sızıntı denetimi var ve testli. Kazanan, aksiyon, oyuncu ve kalibrasyon metrikleri yok. |
| `UNKNOWN`, `PARTIAL`, `NEEDS_REVIEW` | TEST EDİLDİ | Sözleşme, sunucu ve ekranlar bu durumları taşır. Uygulama bilinmeyen değeri `0` yazmaz. |

## 5. Veri hattı

| İş | Durum | Açıklama |
|---|---|---|
| Etiket şeması ve CSV şablonları | TEST EDİLDİ | `ml/schemas/` |
| Etiket dosyası doğrulayıcı | TEST EDİLDİ | `python -m volleystat_ml validate-labels` |
| Maç bazlı ayrım ve sızıntı denetimi | TEST EDİLDİ | `make-splits`, `check-leakage` |
| Video kaynak envanteri | DIŞ BAĞIMLILIK | Kimse envanterdeki bağlantıların lisansını doğrulamadı. Ekip hiçbir video indirmedi. |
| Gold etiketler | DIŞ BAĞIMLILIK | İki bağımsız etiketçi ve izinli video gerekir. Hiç gold veri yok. |

## 6. Bu sürümde çalışan testler

Sayılar son yerel koşudan gelir. CI aynı testleri her PR'da çalıştırır.

| Kanıt | Sonuç |
|---|---|
| Arka uç `pytest` | 402 test geçti (yerelde Python 3.13, CI'da Python 3.12) |
| `ml` `pytest` | 209 test geçti (yerel ve CI) |
| Android JVM testleri (`:core:test`, `:app:testDebugUnitTest`) | 61 test geçti, hiçbiri atlanmadı (yerel). Testler 42 ekran görüntüsü üretti. |
| Uygulama gerçek sunucuya karşı (JVM) | 6 test geçti (yerel ve CI): `BackendE2eTest` 4, `AppFlowE2eTest` 1, `AppUploadFlowE2eTest` 1. Bu altı test yukarıdaki 61 testin içindedir. |
| Lint | 0 hata |
| Emülatörde cihaz testleri (CI, API 34) | 2 test geçti: kamera kaydı ve baştan sona akış (yeni maç, kayıt, yükleme, dosya kontrolü, deneme analizi, sunucudan klip, karar, rapor). İlk iki koşu kırmızıydı ve uygulamada gerçek bir gezinme hatasını gösterdi. |
| Gerçek telefonda deneme | **Yok.** |
| Model doğruluğu | **Yok.** Kimse ölçmedi. |

Bütün test klipleri ffmpeg'in ürettiği sentetik desenlerdir. Uçtan uca akış yalnız DEMO etiketli deneme analizörüyle çalıştı. Deneme analizörü ralli aralıklarını uydurur.

### Bağımsız inceleme

Ayrı inceleyiciler üç parçayı ve yapay zekâ planını okudu. Ayrı bir doğrulayıcı her bulguyu koda karşı denedi.

| Parça | Bulgu | Sonuç |
|---|---|---|
| Arka uç | 16 | 15 bulgu düzeldi. 1 bulgu tasarım gereğidir: `motion-baseline` modülü `ml` paketiyle gelir. Doğrulayıcının eklediği 4 küçük nokta da kapandı. |
| `ml` paketi | 8 | 8 bulgu düzeldi. Oturum düzeltmeleri test paketinin dışındaki kliplerle ayrıca denedi. |
| Yapay zekâ planı | 11 | 11 bulgu belgeye işlendi. |
| Android uygulaması | 21 | Doğrulayıcı 19 bulguyu doğruladı ve 2 bulguyu reddetti. Doğrulanan 19 bulgu sürüm 0.4.1 ile düzeldi. Reddedilen bulgulardan biri metin kuralıydı ve o metinler de düzeldi. Öbürü uygulanmadı: "Oyuncusu belli olmayan sayı" alt sınır değildir, çünkü kullanıcı oyuncu seçtikçe bu sayı azalır. |

Android incelemesinin bulduğu en ağır hatalar şunlardı:

- Yavaş ağda bir maçın kartları başka bir maçın doğrulama ekranına düşebiliyordu. Kullanıcının kararı o durumda yanlış maça yazılıyordu.
- Kullanıcı kart atladıysa ekran son karttan sonra "Bütün ralliler için karar verdin" diyordu.
- Set skoru alt sınırken artı işareti taşımıyordu.
- Bağlantı koptuktan sonra "Yeniden Dene" düğmesi yeni bir analiz başlatıyordu. Yeni iş, kullanıcının doğruladığı işin yerine geçiyordu.
- Kartların yenilenmesi başarısız olunca uygulama sunucunun kaydettiği kararı "kaydedemedi" diye gösteriyordu.
- Uygulama iptal edilen işi hata olarak ve sunucunun hata kodlarını ekranda gösteriyordu.

Üç düzeltmenin otomatik testi yoktur: kamera izninin ayarlardan dönüşte yeniden okunması (cihaz ister), çift dokunmada aynı ekranın iki kez açılmaması ve video okunurken ekrandan ayrılan kullanıcıya kontrol ekranının kendiliğinden açılmaması. Bir bulgu yarım kapandı: Uygulama yerel kayıt dosyasını hâlâ çağıran iş parçacığında yazar. Yazma hatası artık uygulamayı kapatmaz ve ana ekran hatayı bildirir.

## 7. Sıradaki dilimler

Sıra bağımlılığa göredir. İlk dört iş gerçek video beklemez.

1. **Ralli düzeltme:** Kullanıcı kaçan ralliyi ekler, birleşmiş ralliyi böler, iki parçayı birleştirir ve sınırı düzeltir. Sözleşme, sunucu ve doğrulama ekranı bu işe girer. Bu iş bitmeden rapor yalnız alt sınır verir ve B1 ölçümünün sonucu ürüne yansımaz.
2. **Saha yarısı ve taraf değişimi:** Maç kaydı hangi takımın hangi yarıda başladığını ve takımların ne zaman yer değiştirdiğini taşımıyor. Kazanan önerisi ve oyuncu tarafı bu bilgiyi ister.
3. **Saha bölgesi seçimi:** Kullanıcı bir karede sahanın köşelerine dokunur. Sunucu homografiyi saklar. Baseline hareketi saha içiyle sınırlar (deney B2'nin ön koşulu).
4. **Arka plan yükleme:** WorkManager ve ön plan hizmeti. Gerçek telefonda deneme ister.
5. **Eksik ölçüm araçları:** Anotatör uyumu, kazanan metrikleri, kalibrasyon.
6. **B0 ve B1 deneyleri:** İzinli video ve iki etiketçi gelince. Ekip baseline'ı ilk kez gerçek maçta ölçer.
7. **Üretim altyapısı:** Hesap, HTTPS, nesne depolama, iş kuyruğu. Bulut ve bütçe kararı ister.

## 8. Arda'dan beklenenler

| Konu | Neden gerekli |
|---|---|
| Kendi çektiğin bir ya da iki amatör salon maçı ve oyuncuların yazılı izni | Model seçimi ve ölçüm izinli video olmadan başlayamaz. Açı ve izin ayrıntısı `docs/AI_PLAN.md` 4.1 bölümünde. |
| Voleybol bilen ikinci bir etiketçi | Gold etiket iki bağımsız etiketçi ister (B0). |
| Gerçek telefonda bir deneme | Emülatör gerçek salonu, tripodu ve uzun kaydı temsil etmez. |
| Lisans yönü kararı | Kod kapalı ticari ürün mü olacak, AGPL ile açık mı kalacak? Karar kullanılabilir model listesini değiştirir (`docs/AI_PLAN.md` 2. bölüm). |
| Uygulama simgesi kararı | Suluboya logo küçük boyda zor okunur ve mor arayüzle aynı paleti paylaşmaz. |
| Bulut ve GPU kararı | Ücretli hizmet onayı sende. O zamana kadar her şey yerelde çalışır. |
| Depo görünürlüğü | Depo herkese açık. Kapalı olmasını istersen GitHub ayarından değiştirirsin. |
