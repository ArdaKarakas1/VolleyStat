# Fark analizi: gereksinimler ve mevcut kod

**Tarih:** 15 Kasım 2026 · **Kod sürümü:** 0.6.0 · **Kaynaklar:** V4 devir raporu ve ekleri, UI Tasarım V1 paketi

Durum etiketleri:

- **TEST EDİLDİ**: Kod var ve otomatik testi bu sürümde çalıştı. Satır testin nerede çalıştığını yazar.
- **KISMEN**: Bir bölümü var. Satır eksik bölümü yazar.
- **YOK**: Kimse bu işi henüz yazmadı. İş yazılım işidir ve dış bağımlılık beklemez.
- **DIŞ BAĞIMLILIK**: İş gerçek veri, hesap, cihaz, bütçe ya da hukuki onay ister.
- **ARAŞTIRMA**: Ekip hangi yöntemin işe yaradığını ölçmeden karar veremez.

Depoda eğitilmiş model yok. Kimse hiçbir yapay zekâ bileşeninin doğruluğunu ölçmedi. G0–G8 kapılarının hepsi `NOT_MEASURED` durumundadır. Uygulama bugün gerçek maç istatistiğini kendiliğinden çıkarmaz: Kullanıcı her rallinin kazananını kendisi seçer ve rapor bu kararlardan oluşur.

**En önemli sınır:** Kullanıcı ralli listesini sürüm 0.5.0 ile düzeltebilir: Kaçan ralliyi ekler, birleşmiş ralliyi böler, iki parçayı birleştirir ve sınırı taşır. Rapordaki her sayı yine de kullanıcı listeyi onaylayana kadar alt sınırdır. Onay kullanıcının beyanıdır ve sunucu listenin eksiksiz olduğunu doğrulayamaz. Kimse bu akışı gerçek bir maçta denemedi ve kimse bir maçı düzeltmenin kaç dakika sürdüğünü ölçmedi. Kullanıcı maçın kuralını seçer (ADR-0115). Sunucu süreyle biten seti tanımaz ve maçı bitirmez.

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
| FR-11 | Doğrulama kuyruğu | P0 | KISMEN | Kartlar, sürüm denetimli karar, geri alma ve denetim kaydı TEST EDİLDİ. Ekran: klip, tek soru, tek dokunuş. Kullanıcı ralli listesini düzeltir: ekleme, taşıma, bölme ve birleştirme TEST EDİLDİ (JVM, gerçek sunucu). Kullanıcı listeyi skor denetimiyle onaylar. Testler akışı yalnız DEMO analizörle ve betikli sunucuyla denedi. Kimse düzeltmenin süresini ölçmedi. |
| FR-12 | İki takım raporu | P0 | KISMEN | Sunucu raporu yalnız kullanıcı kararlarından hesaplar (TEST EDİLDİ). Her değer `MEASURED`, `PARTIAL` ya da `UNKNOWN` durumu taşır. Bir değer ancak kullanıcı ralli listesini onayladıktan sonra `MEASURED` olur. Set sayımı kazananı belli olmayan ilk rallide durur. Sunucu seti kullanıcının seçtiği kurala göre kapatır (TEST EDİLDİ): Kural maçı kaç set alan takımın kazandığını, setin ve karar setinin kaç sayıda bittiğini söyler. Sunucu maçı bitirmez ve süreyle biten seti tanımaz. |
| FR-13 | Ralli bazında video oynatma | P0 | KISMEN | Sunucu videoyu `Range` isteğiyle sunar (TEST EDİLDİ). Media3 oynatıcı CI emülatöründe klibi sunucudan parça parça istedi ve hata vermeden oynattı. Test görüntünün doğru kareyi gösterdiğini denetlemez. |
| FR-14 | Oyuncu eşlemesi | P1 | KISMEN | Kullanıcı sayıyı alan oyuncuyu forma numarasıyla seçer. Otomatik eşleme DIŞ BAĞIMLILIK'tır. |
| FR-15 | Tam aksiyon çıkarımı | P1 | DIŞ BAĞIMLILIK | Kullanıcı yalnız ralliyi bitiren aksiyonu seçebilir. |
| FR-16 | Eğitim kullanım izni | P0 | KISMEN | İzin varsayılan olarak kapalıdır ve sunucu `allowTraining: true` isteğini reddeder (TEST EDİLDİ). Ayrı onay ve geri çekme akışı yok. |
| FR-17 | Veriyi ve videoyu silme | P0 | TEST EDİLDİ | Maç silme (`DELETE /matches/{id}`) maç bilgilerini, yükleme kayıtlarını, videoyu, analiz işlerini, rallileri, kartları ve kararları tek veritabanı işleminde siler. Sunucu silinen satırların üzerine sıfır yazar. Uygulama maç kimliğini kendisi ürettiği için yanıtı kaybolan bir maçı da silebilir. Uygulama maçı telefondan yalnız sunucu sildikten sonra kaldırır ve telefondaki video dosyasına dokunmaz. Sunucuda süreye bağlı otomatik silme yok. Sunucu silinen video dosyasının disk bloklarının üzerine yazmaz. |
| FR-18 | Canlı kamera analizi | P2 | Kapsam dışı | Kayıtlı video analizi kanıtlandıktan sonra. |
| FR-19 | Cihaz üzerinde model | P2 | Kapsam dışı | |
| FR-20 | Sosyal medya çıktısı | P1 | YOK | |

## 2. Android ekranları (UI Tasarım V1)

| # | Ekran | Durum | Açıklama |
|---|---|---|---|
| 01 | Açılış | TEST EDİLDİ (JVM) | Yalnız ilk açılışta görünür. |
| 02 | Ana ekran | TEST EDİLDİ (JVM) | Yeni maç kartı, devam eden maçlar, dört liste satırı. Sayılar gerçek kayıtlardan gelir. Yarım kalan kurulumun kartı FIVB dışındaki kuralı adıyla yazar. |
| 03 | Maç oluştur | TEST EDİLDİ (JVM) | Ad, tarih, salon ve tür isteğe bağlıdır. Maç kuralı FIVB seçili gelir. |
| 04 | Takım ve kadro | TEST EDİLDİ (JVM) | Kayıtlı takımdan seçim, yeni takım, kadro. |
| 05 | Video kaynağı | TEST EDİLDİ (JVM) | Kamera ya da galeri. Ardından telefonun okuduğu dosya bilgileri ve hak beyanı. Maçın kuralı FIVB kuralı değilse kural kartı hak beyanının hemen üstünde durur. |
| 06 | Maç kaydı | TEST EDİLDİ (JVM, CI emülatörü) | Çizim ve izin akışı JVM'de, kayıt emülatörde testli. Gerçek telefonda deneme yok. |
| 07 | Yükleme ve analiz durumu | TEST EDİLDİ (JVM, gerçek sunucu) | |
| 08 | Belirsiz olay doğrulama | TEST EDİLDİ (JVM, gerçek sunucu, CI emülatörü) | Klip oynatma emülatörde testli. |
| 09 | Maç raporu | TEST EDİLDİ (JVM, gerçek sunucu) | |
| 10 | Oyuncu istatistikleri | TEST EDİLDİ (JVM, gerçek sunucu) | Savunma sütunu ölçümsüzdür ve boş kalır. |
| 11 | Ralli listesi | TEST EDİLDİ (JVM, gerçek sunucu) | Ralliler, aralarındaki boşluklar, set başlıkları, maç kuralı, skor denetimi ve liste onayı. UI Tasarım V1 paketinde yoktur (ADR-0114). Testler onayı ekrandan yalnız betikli sunucuyla dener, çünkü gerçek sunucu deneme analizörünün listesini onaylamaz. |
| 12 | Ralli düzenleyici | TEST EDİLDİ (JVM, gerçek sunucu) | Video, konum çubuğu, adım düğmeleri ve işaretler. JVM testlerinde oynatıcının yerinde bir vekil durur. Gerçek oynatıcıyı yalnız CI emülatöründeki cihaz testi dener. |
| 13 | Maç kuralı | TEST EDİLDİ (JVM, gerçek sunucu) | Maçı kaç set alan takımın kazandığını, setin ve karar setinin kaç sayıda bittiğini seçtirir. Yeni maçta seçim taslağa gider. Ralli listesinden açılan ekran kuralı sunucuya gönderir ve yanıt gelene kadar açık kalır. Bir test ekranı gerçek sunucuya karşı sürer: Kullanıcı kuralı listeden değiştirir. Test, ekran açıkken kuralı başka bir istekle değiştirir. Sunucu ekranın isteğini reddeder. Ekran kuralın değiştiğini söyler ve sunucunun şimdiki kuralını yazar. Öbür ekran testleri betikli sunucuyla çalışır. UI Tasarım V1 paketinde yoktur (ADR-0115). |
| | Maçlar, Takımlar, Profil, Çekim rehberi | TEST EDİLDİ (JVM) | |

V4 raporundaki şu ekranlar yok: hesap ve gizlilik onayı (S01), saha bölgesi seçimi (S07'nin dört nokta adımı), oyuncu eşleştirme (S12), paylaşım (S14).

## 3. API sözleşmesi

| Rota | Durum |
|---|---|
| `POST /matches`, `PUT /matches/{id}`, `GET /matches/{id}`, `PUT /matches/{id}/rules`, `DELETE /matches/{id}` | TEST EDİLDİ |
| `POST /matches/{id}/uploads`, `PUT /uploads/{id}/chunks`, `GET /uploads/{id}`, `POST /uploads/{id}/complete`, `DELETE /uploads/{id}` | TEST EDİLDİ |
| `GET /videos/{id}`, `GET` ve `POST /videos/{id}/quality`, `DELETE /videos/{id}` | TEST EDİLDİ. Sahne ölçümleri `NOT_MEASURED`. |
| `GET /videos/{id}/content` (`Range`) | TEST EDİLDİ |
| `POST /videos/{id}/analyses`, `GET /analyses/{id}`, `GET /videos/{id}/analyses/latest`, `POST /analyses/{id}/cancel` | TEST EDİLDİ |
| `GET /analyses/{id}/rallies`, `GET /analyses/{id}/review-cards` | TEST EDİLDİ |
| `POST /review-cards/{id}/decisions`, `POST /review-cards/{id}/reopen` | TEST EDİLDİ |
| `POST /analyses/{id}/rallies`, `POST /rallies/{id}/move`, `POST /rallies/{id}/split`, `POST /rallies/{id}/merge-previous`, `POST /analyses/{id}/rally-list/confirm` | TEST EDİLDİ |
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
| Değerlendirme hattı | KISMEN | Ralli sınırı metrikleri, güven aralığı, etiket denetimi, ayrım ve sızıntı denetimi var ve testli. Kazanan metrikleri ve kalibrasyon da var ve testli (`evaluate-winner`, ADR-0116). Depoda kazanan öneren bir bileşen yoktur, bu yüzden kimse bu araçla bir ölçüm yapmadı. Aksiyon ve oyuncu metrikleri, güven eşiği taraması ve alt grup metrikleri yok. |
| `UNKNOWN`, `PARTIAL`, `NEEDS_REVIEW` | TEST EDİLDİ | Sözleşme, sunucu ve ekranlar bu durumları taşır. Uygulama bilinmeyen değeri `0` yazmaz. |

## 5. Veri hattı

| İş | Durum | Açıklama |
|---|---|---|
| Etiket şeması ve CSV şablonları | TEST EDİLDİ | `ml/schemas/` |
| Etiket dosyası doğrulayıcı | TEST EDİLDİ | `python -m volleystat_ml validate-labels` |
| Maç bazlı ayrım ve sızıntı denetimi | TEST EDİLDİ | `make-splits`, `check-leakage` |
| İki etiketçinin uyum ölçümü (B0) | TEST EDİLDİ | `python -m volleystat_ml agreement`: sınır farkı, IoU, uyum oranı, Cohen's kappa ve hakem için uyuşmazlık listesi. Kimse aracı gerçek etiketle çalıştırmadı, çünkü etiket yok. |
| Video kaynak envanteri | DIŞ BAĞIMLILIK | Kimse envanterdeki bağlantıların lisansını doğrulamadı. Ekip hiçbir video indirmedi. |
| Gold etiketler | DIŞ BAĞIMLILIK | İki bağımsız etiketçi ve izinli video gerekir. Hiç gold veri yok. |

## 6. Bu sürümde çalışan testler

Sayılar son yerel koşudan gelir. CI aynı testleri her PR'da çalıştırır.

| Kanıt | Sonuç |
|---|---|
| Arka uç `pytest` | 585 test geçti (yerelde Python 3.13). CI aynı testleri Python 3.12 ile çalıştırır. |
| `ml` `pytest` | 508 test geçti (yerel). 269 test kazanan ölçümüyle geldi ve uydurma ralli listeleriyle çalışır. |
| Android JVM testleri (`:core:test`, `:app:testDebugUnitTest`) | 269 test geçti, hiçbiri atlanmadı (yerel): `:core` 5, `:app` 264. Testler 89 ekran görüntüsü üretti. |
| Uygulama gerçek sunucuya karşı (JVM) | 11 test geçti (yerel): `BackendE2eTest` 7, `AppFlowE2eTest` 1, `AppUploadFlowE2eTest` 1, `RallyFixE2eTest` 2. Bu on bir test yukarıdaki 269 testin içindedir. İkisi ralli düzeltmeyi dener: biri API istemcisinden, öbürü ekranlardan. İkisi maç kuralını dener. Biri API istemcisinden gider: Kural maçla gider, sunucu sunulan 18 kuralın hepsini kabul eder, listede yeni kuralı verir ve eski kurala dayanan geç bir isteği reddeder. Öbürü kuralı ekranlardan değiştirir. Bu iki test hiçbir set sınırına ulaşmaz. Setleri kuralla sayan rotaları sunucu testleri dener. |
| Lint | 0 hata |
| Mutasyon sınaması (sürüm 0.6.0) | Oturum maç kuralının sunucudaki 47 kuralını ve uygulamadaki 100 kuralını tek tek bozdu. Testler son kodda sunucudaki 47 bozmanın hepsini ve uygulamadaki 100 bozmanın 99'unu yakalar. Kalan bozma kural ekranından geri dönerken ret uyarısını temizlemeyi atlar. Ralli listesi o uyarıyı açılırken kendisi temizler, bu yüzden uyarı yalnız bir kare görünür ve hiçbir test o kareyi göremez. Testler dokuz bozmayı ilk koşusunda yakalamadı ve bu bozmalar altı test boşluğunu gösterdi. Oturum o boşlukları kapattı. Son beşini üçüncü okuma buldu: Üç bozma kural karşılaştırmasını daraltıyordu, biri onay kutusunun işaretini kural ekranından dönüşte düşürüyordu, biri tarih kartının "değiştir" yazısını dar ekranda alt satıra indirmiyordu. |
| Mutasyon sınaması (sürüm 0.5.0) | Oturum ralli düzeltmenin uygulamadaki 161 kuralını ve sunucudaki 9 kuralını tek tek bozdu. Testler son kodda bozmaların hepsini yakalar. Beş bozma ilk koşusunda yakalanmadı ve dört test boşluğunu gösterdi. Oturum o boşlukları kapattı. |
| Emülatörde cihaz testleri (CI, API 34) | 2 test: kamera kaydı ve baştan sona akış (yeni maç, kayıt, yükleme, dosya kontrolü, deneme analizi, sunucudan klip, ralli düzenleyicide gerçek oynatıcıyla bitiş taşıma, karar, rapor). İlk iki koşu kırmızıydı ve uygulamada gerçek bir gezinme hatasını gösterdi. Düzenleyici adımı sürüm 0.5.0 ile geldi. |
| Gerçek telefonda deneme | **Yok.** |
| Model doğruluğu | **Yok.** Kimse ölçmedi. |

Bütün test klipleri ffmpeg'in ürettiği sentetik desenlerdir. Uçtan uca akış yalnız DEMO etiketli deneme analizörüyle çalıştı. Deneme analizörü ralli aralıklarını uydurur.

### Bağımsız inceleme

Ayrı inceleyiciler üç parçayı ve yapay zekâ planını okudu. Ayrı bir doğrulayıcı her bulguyu koda karşı denedi.

| Parça | Bulgu | Sonuç |
|---|---|---|
| Arka uç | 16 | 15 bulgu düzeldi. 1 bulgu tasarım gereğidir: `motion-baseline` modülü `ml` paketiyle gelir. Doğrulayıcının eklediği 4 küçük nokta da kapandı. |
| `ml` paketi | 8 | 8 bulgu düzeldi. Oturum düzeltmeleri test paketinin dışındaki kliplerle ayrıca denedi. Sonradan gelen `agreement` komutunu ikinci bir inceleyici 30 denemeyle sınadı ve 3 kusur buldu. Üçü de düzeldi. |
| Yapay zekâ planı | 11 | 11 bulgu belgeye işlendi. |
| Android uygulaması (sürüm 0.4.0) | 21 | Doğrulayıcı 19 bulguyu doğruladı ve 2 bulguyu reddetti. Doğrulanan 19 bulgu sürüm 0.4.1 ile düzeldi. Reddedilen bulgulardan biri metin kuralıydı ve o metinler de düzeldi. Öbürü uygulanmadı: "Oyuncusu belli olmayan sayı" alt sınır değildir, çünkü kullanıcı oyuncu seçtikçe bu sayı azalır. |
| Maç silme, gizlilik satırları ve sayı türü tanımları (sürüm 0.4.2) | 19 | Doğrulayıcılar 11 bulguyu doğruladı, 5 bulguyu kısmen doğruladı ve 3 bulguyu reddetti. Tekrarlar ayıklanınca dokuz ayrı kusur kaldı ve dokuzu da düzeldi. Oturum her düzeltmeyi geri alıp ilgili testin kırıldığını gördü. Reddedilen 3 bulgu sözcük tercihiydi. |
| Ralli düzeltme (sürüm 0.5.0), üç tur | 36 + 22 + 17 | Dört inceleyici 31 bulgu yazdı. Doğrulayıcılar 16 bulguyu doğruladı, 11 bulguyu kısmen doğruladı ve 4 bulguyu reddetti. Eksiklik denetçisi 5 bulgu daha ekledi. Oturum 32 bulguyu düzeltti. İkinci bir tur bu düzeltmeleri koda karşı yeniden denedi: 21 düzeltme tam, 11 düzeltme yarım çıktı ve tur 22 yeni nokta buldu. Oturum yarım kalan düzeltmeleri tamamladı ve yeni noktaları kapattı. Üçüncü tur ikinci turun düzeltmelerini okudu ve 17 nokta yazdı. Doğrulayıcılar 2 noktayı reddetti ve oturum kalan 15 noktayı kapattı. Açık kalan iki konu bu inceleme listelerinin altındadır. |
| Maç kuralı (sürüm 0.6.0), beş tur | 29 + 25 + 15 + 11 + 1 | Dört inceleyici 26 bulgu yazdı. Doğrulayıcılar 15 bulguyu doğruladı, 11 bulguyu kısmen doğruladı ve hiçbirini reddetmedi. Üç bulgu başka bir bulgunun tekrarıydı. Eksiklik denetçisi 3 bulgu daha ekledi. Oturum bir bulgu dışında hepsini kapattı. İkinci tur bu düzeltmeleri koda karşı yeniden denedi: Dört okuyucu 21 nokta yazdı. Doğrulayıcılar 13 noktayı doğruladı, 7 noktayı kısmen doğruladı ve 1 noktayı reddetti. Eksiklik denetçisi 4 nokta daha ekledi. Oturum bu noktaları kapattı. Biri bilinen sınır olarak kaldı. Üçüncü tur ikinci turun düzeltmelerini okudu: Üç okuyucu 23 düzeltmeyi yeniden denedi. 21 düzeltme tam, 2 düzeltme yarım çıktı. Okuyucular 12 yeni nokta yazdı. Doğrulayıcılar 8 noktayı doğruladı, 4 noktayı kısmen doğruladı ve hiçbirini reddetmedi. Eksiklik denetçisi 3 nokta daha ekledi. Oturum bu noktaları kapattı. Biri bilinen küçük sınır olarak kaldı. Dördüncü tur üçüncü turun düzeltmelerini okudu: İki okuyucu 11 nokta yazdı. Üç nokta başka bir noktanın tekrarıydı. Doğrulayıcılar kalan 8 noktanın 7'sini doğruladı ve 1'ini kısmen doğruladı. Oturum bu noktaları kapattı. Biri bilinen küçük sınır olarak kaldı. Beşinci tur dördüncü turun düzeltmelerini okudu: Bir okuyucu 1 nokta yazdı ve doğrulayıcı o noktayı kısmen doğruladı. Oturum noktayı bir testle kapattı. Açık kalan konular bu inceleme listelerinin altındadır. |

Android incelemesinin bulduğu en ağır hatalar şunlardı:

- Yavaş ağda bir maçın kartları başka bir maçın doğrulama ekranına düşebiliyordu. Kullanıcının kararı o durumda yanlış maça yazılıyordu.
- Kullanıcı kart atladıysa ekran son karttan sonra "Bütün ralliler için karar verdin" diyordu.
- Set skoru alt sınırken artı işareti taşımıyordu.
- Bağlantı koptuktan sonra "Yeniden Dene" düğmesi yeni bir analiz başlatıyordu. Yeni iş, kullanıcının doğruladığı işin yerine geçiyordu.
- Kartların yenilenmesi başarısız olunca uygulama sunucunun kaydettiği kararı "kaydedemedi" diye gösteriyordu.
- Uygulama iptal edilen işi hata olarak ve sunucunun hata kodlarını ekranda gösteriyordu.

Sürüm 0.4.2 incelemesinin bulduğu kusurlar şunlardı:

- Sunucunun sildiği maçın takım adları veritabanı dosyasında okunabilir kalıyordu.
- Maç açma yanıtı kaybolursa telefon o maçı sunucudan silemiyordu.
- Uygulama her silme hatasında "sunucunun açık olduğunu denetle" diyordu. Sunucu videoyu denetlerken de aynı cümle çıkıyordu.
- JSON olmayan bir yanıt uygulamayı kapatabiliyordu ve yanlış adresten gelen her başarılı yanıt silme sayılıyordu.
- Sayı türü tanımları çakışıyordu: Servisi karşılarken yapılan çift vuruş hem servis asına hem rakip hatasına uyuyordu.
- Tasarım belgesi her DEMO kartının kendi etiketini taşıdığını yazıyordu, rapor kartları taşımıyordu.

Üç düzeltmenin otomatik testi yoktur: kamera izninin ayarlardan dönüşte yeniden okunması (cihaz ister), çift dokunmada aynı ekranın iki kez açılmaması ve video okunurken ekrandan ayrılan kullanıcıya kontrol ekranının kendiliğinden açılmaması. Bir bulgu yarım kapandı: Uygulama yerel kayıt dosyasını hâlâ çağıran iş parçacığında yazar. Yazma hatası artık uygulamayı kapatmaz ve ana ekran hatayı bildirir.

Ralli düzeltme incelemesinin (sürüm 0.5.0) bulduğu en ağır kusurlar şunlardı:

- Yanıtı kaybolan bir düzeltmeyi kullanıcı yeniden deneyince uygulama "düzeltmeyi yeniden yap" diyordu. Sunucu ilk denemeyi uygulamıştı ve ikinci bir birleştirme başka bir ralliyi silerdi.
- Uygulama maçın işi değişince eski işin ralli listesini bırakmıyordu ve yeni işin listesi yerine eskisini gösteriyordu.
- Sunucu bir düzeltmeyi uyguladıktan sonra uygulama listeyi okuyamazsa ekran eski listeyi güncel liste gibi gösteriyordu.
- Oynatma hatasından sonra düzenleyici kilitli kalıyordu ve videoyu yeniden isteyen bir düğme yoktu.
- Kullanıcı aynı kazananı ikinci kez seçince sunucu liste onayını sessizce düşürüyordu.
- Sunucu sonu taşınan rallide analizörün eski kazanan önerisini tutuyordu. O öneriyi yeni klibi görmeyen bir analizör vermişti.
- Rapor analizörün önerdiği aralık sayısını ralli sayısı diye gösteriyordu ve "Listede N ralli var" cümlesi kullanıcının ralli saymadığı aralıkları da sayıyordu.

İkinci turun bulduğu kusurlar şunlardı:

- Kullanıcı düzeltme yoldayken düzenleyiciden çıkarsa sunucunun reddini hiçbir ekran göstermiyordu. Düzenleyici ve ralli listesi artık yanıt gelene kadar açık kalır.
- Küçük telefonda sunucunun reddi ekranın görünmeyen bölümünde kalıyordu ve dokunuş boşa gitmiş gibi görünüyordu. Düzenleyici reddi artık işaret düğmelerinin hemen üstünde gösterir.
- Oynayan görüntü rallinin sonunu geçerken kullanıcı "Burada Böl" düğmesine dokunursa uygulama sunucunun reddettiği bir konum gönderiyordu. Ekran artık düğmeyi açan kuralı dokunma anındaki konuma yeniden sorar.
- Ekran ilk açıldığında ve "Yeniden Dene" düğmesinden sonra görüntü gelmeden işaret düğmeleri açıktı. Düzenleyici artık işaret düğmelerini oynatıcı o konumda "hazır" dediğinde açar.
- Sunucu videoyu artık tanımayınca uygulama eski listeyi ekranda tutuyordu ve düzeltme düğmeleri hiçbir şey yapmıyordu.
- Deneme analizinin listesi, kullanıcının hiç veremeyeceği bir onaydan sonra kesin skor vaat ediyordu.
- Rapor onaydan sonra bütün takım sayılarının artı işaretinin kalkacağını söylüyordu. Sayı türü satırları, kullanıcı her sayının türünü seçene kadar artı işaretini korur.
- Belgeler set kuralını uygulamaya mal ediyordu. Kuralı sunucu uygular.

Mutasyon sınaması bu turda üç bozmayla iki test boşluğu daha gösterdi: Hiçbir test geri tuşunun gerçekten tutulduğunu ve düzenleyicinin yalnız bir kez kapandığını ölçmüyordu. Oturum iki boşluğu kapattı. Ekran görüntüsü testleri de test sırasına bağlıydı: Bir düzenleyici testinin hemen ardından koşan ve dönen bir yükleme işareti içeren ekran görüntüsü hiç bitmiyordu. Ekran görüntüsü testleri artık animasyonları kapatır.

Üçüncü turun bulduğu kusurlar şunlardı:

- Sunucu bir düzeltmeyi uyguladıktan sonra uygulama kartları 60 saniyeye kadar bekliyordu ve ekran o sürede kullanıcıyı tutuyordu. Bir düzeltmenin üç isteği de artık kısa süre bekler.
- Sunucu videoyu artık tutmuyorsa ralli listesi "uygulama listeyi alamadı" diyordu ve hiçbir işe yaramayan bir "Yeniden Dene" düğmesi gösteriyordu. Liste ve düzenleyici artık sunucunun verdiği nedeni yazar.
- Kullanıcının bir ralli için açtığı doğrulama, ilk yükleme başarısız olunca o ralliyi unutuyordu.
- Küçük telefonda deneme analizinin DEMO şeridi adım düğmelerini ekranın dışına itiyordu. Düzenleyici DEMO etiketini artık aralığı gösteren satırın üstünde taşır.
- Oynatıcı geç hazır olduğunda işaret düğmeleri hiçbir açıklama olmadan soluk kalıyordu. Düzenleyici artık resmin üstünde bir bekleme işareti gösterir.
- Uzun bir ret metni küçük telefonda çubuğu ve adım düğmelerini ekranın dışına itiyordu ve kullanıcı düzeltmeyi yinelerken orada kalıyordu. Ret artık kullanıcı görüntüyü hareket ettirince kalkar.
- Belgeler deneme raporunun alt sınırdan söz etmediğini yazıyordu. Deneme raporu artı işaretlerini korur.

Ralli düzeltme incelemesinden iki konu açık kaldı. Hiçbir test gerçek oynatıcının bir hatadan sonra videoyu yeniden açtığını ve uygulama arka plana geçince durduğunu denemez. JVM testleri oynatıcıyı tutan sınıfı durumunu testin elle kurduğu bir oynatıcıyla dener ve bu iki davranış gerçek telefon denemesini bekler. Testler düzeltmenin bekleme süresini kısaltarak dener ve kimse 15 saniyenin gerçek bir ağda yeterli olduğunu ölçmedi.

Maç kuralı incelemesinin (sürüm 0.6.0) bulduğu en ağır kusurlar şunlardı:

- Kullanıcı yeni maç ekranında bir kural seçip kurulumu bıraktıysa kural taslakta kalıyordu ve ana ekran hiçbir şey göstermiyordu. Sonraki maç, standart bir maç bile, o kuralla açılıyordu.
- Kural yoldayken gelen bir kazanan yanıtı kural ekranını bekleme ekranına çeviriyordu. Ekran kullanıcıyı tutmayı bırakıyordu ve seçimi siliyordu. Kural henüz gönderilmemişken de aynı yanıt kullanıcının seçimini sessizce siliyordu.
- Uygulamanın beklemekten vazgeçtiği bir kural isteği sonradan sunucuya ulaşırsa kullanıcının sonraki seçimini geri alabiliyordu. Kural isteği artık telefonun sunucudan son okuduğu kuralı taşır. Maçın kuralı o kural değilse sunucu geç isteği reddeder.
- Kural ekranı kullanıcının kaydetmediği seçimi sunucunun kuralı gibi düz cümleyle söylüyordu. Ret metni de kuralı suçluyordu, oysa sunucu uygulamanın sunduğu hiçbir kuralı reddetmez.
- Sunucu maç isteğindeki bilinmeyen alanı sessizce düşürüyordu. Başka bir adla gelen kural FIVB kuralına dönüyordu.
- 320 dp telefonda büyük yazıyla kural kartı "5. set 15 sayı" gibi parçaları sözcüğün ortasından bölüyordu. Tarih kartında da aynı sorun vardı.
- Uygulama kuralın yolda olduğunu söyleyen satırı ve sunucunun reddini canlı bölge olarak işaretlemiyordu. Düzenleyicide ve ralli listesinde de aynı eksik vardı.
- Skor denetimi, başka bir kuralın skorları her zaman bozduğunu söylüyordu. "Hayır" ipucu yanlış seçilmiş bir kazananı hiç anmıyordu. Onaylı listede kural ekranı sunucunun "yalnız setleri yeniden saydığını" yazıyordu, oysa onay da düşer.
- Hiçbir test kural ekranını gerçek sunucuya karşı sürmüyordu, ama belge ekranı gerçek sunucuyla denenmiş gösteriyordu. Bu tur o testi ekledi.

Maç kuralı incelemesinin ikinci turunun bulduğu kusurlar şunlardı:

- Kural değişikliğinin geri alma testi isteği hiç göndermiyordu. İlk turun eklediği yardımcı önce maçı okuyordu ve test o okumada duruyordu. Yanıtı işlemin dışına taşıyan bir bozma bu yüzden bütün testlerden geçiyordu.
- Sunucu kural değişikliğinde yalnız kuralları karşılaştırır. Belgeler ise geç gelen bir isteğin kullanıcının sonraki seçimini hiçbir durumda geri alamayacağını yazıyordu.
- Sunucudaki kural ekran açıkken değiştiyse kural ekranı kullanıcıya seçimini denetlemesini söylüyordu, ama sunucunun şimdiki kuralını yazmıyordu.
- Ana ekrandaki "Yeni Maç" kartı da yarım kalan kurulumu açıyordu. Küçük telefonda kural kartı yeni maç ekranının görünmeyen bölümünde kalıyordu ve yarım kurulumun kuralı yine sonraki maça görünmeden geçebiliyordu.
- Küçük telefonda büyük yazıyla ralli listesi, onayın yolda olduğunu ve sunucunun reddini ekranın görünmeyen bölümünde gösteriyordu.
- Üç test, oturum denediği düzeltmeyi geri aldığında da geçebiliyordu. Biri hangi yanıtın önce geldiğine bağlıydı, biri yalnız tek bir seçimi geri okuyordu, biri FIVB kuralını sabit bir değerden ayırt edemiyordu.

İkinci tur bir soruyu ölçümle kapattı: İki ajan, ekranda yeni beliren bir canlı bölgeyi Android'in ekran okuyucuya bildirip bildirmediği konusunda ayrıştı. Oturum uygulamanın gönderdiği erişilebilirlik olaylarını bir testte ölçtü. Uygulama, satır belirince o satırdan bir değişiklik olayı gönderir. Testler artık bu olayı üç ekranda da denetler.

Maç kuralı incelemesinin üçüncü turunun bulduğu kusurlar şunlardı:

- Sunucunun eski kural denetimini deneyen test tek bir kural çifti kullanıyordu ve o çiftte kazanılacak set sayısı farklıydı. Karşılaştırmayı daraltan üç bozma bütün testlerden geçiyordu: Biri yalnız kazanılacak set sayısını karşılaştırıyordu, ikisi karşılaştırmadan bir alanı çıkarıyordu. Test artık sonraki değişikliği iki alanda birden ve her alanda tek tek dener.
- Kural ekranının testi yalnız kullanıcının son dokunduğu sorunun bildirdiği kuralı denetliyordu. Öbür iki sorunun yanlış bildirdiği bir kural testten geçiyordu.
- Hiçbir test, kullanıcı yüklemeden önceki ekrandan kural ekranına gidip döndüğünde onay kutusunun işaretli kaldığını ölçmüyordu.
- Ralli listesinin ve düzenleyicinin uyarısı, listenin ekran açıldıktan sonra değiştiğini söylüyordu. Uygulama listenin ne zaman değiştiğini bilmez. İkinci tur aynı iddiayı kural ekranından çıkarmıştı.
- Bu belgenin rota tablosu `PUT /matches/{id}/rules` rotasını listelemiyordu. Tasarım belgesi ve arayüz planı kural kartının yerini, eski kural uyarısını ve listedeki reddin ne zaman kalktığını ekranların gösterdiğinden farklı anlatıyordu. İki cümle ekran okuyucunun ne okuduğunu ölçülmüş gibi yazıyordu.
- Tarih kartının dar ekran testi "değiştir" yazısını tarihle değil, tarihin üstündeki "Tarih" etiketiyle karşılaştırıyordu. Kartı hiç alt satıra indirmeyen bir bozma testten geçiyordu. Test artık tarihin kendisiyle karşılaştırır.
- 320 dp genişlikte ve 568 dp yükseklikte telefonda 1,5 kat yazıyla "Skoru Denetle" düğmesi ilk ekranın alt kenarında durur. Onayın "gönderiyor" satırı ve sunucunun reddi düğmenin üstünde açılıyordu, düğmeyi aşağı itiyordu ve kendisi de ekranın altında kalıyordu. Liste artık, başı ekrandayken satır belirince satırı ve düğmesini ekrana getirir.
- 320 dp genişlikte telefonda 1,5 kat yazıyla kural ekranının eski kural uyarısı ekranın çoğunu kaplıyordu. Doğrulayıcının hesabına göre uyarı üç soruya 87 ile 118 dp arasında yer bırakıyordu. Oturum uyarıyı kısalttı ve bu durumu bir ekran görüntüsüyle ve bir testle ölçtü.

Üçüncü turun bir kusuru incelemenin kendisindeydi: Okuyucuların baktığı ekran görüntüleri incelenen commit'ten değil, oturumun o sırada üzerinde çalıştığı ağaçtan geliyordu. Eski kural uyarısının metni iki ağaçta farklıydı. Doğrulayıcı uyarının kapladığı yeri iki metin için de hesapladı ve oturum son metni testle ölçtü.

Okuyucular bir yazım sorusu da açtı. Bu belgeler her cümlede özneyi adıyla söyler. Okuyucular, öznesi belli bir cümlenin içindeki edilgen sıfat-fiili de ("oynanan setler", "izin verilen aralık") kusur saydı. Oturum bu biçimi kusur saymadı ve yalnız aynı cümlenin üç belgede farklı yazıldığı altı yeri düzeltti. Ürün sahibi bu biçimi de istemezse tarama ekran metinlerini de kapsar.

Maç kuralı incelemesinin dördüncü turunun bulduğu kusurlar şunlardı:

- Üçüncü turun eklediği kaydırma, liste başının her çizilişinde çalışıyordu. Liste bir satırla açılırsa kendiliğinden kayıyordu. Kaydırmayı artık ekran yapar ve yalnız satır belirince ya da değişince yapar. Ekranın açılırken bulduğu satır listeyi kaydırmaz.
- Üçüncü turun iki ekran görüntüsü listeyi kodun istediğinin iki katı kaymış gösteriyordu. O görüntüleri animasyonları kapatan ekran görüntüsü koşusu çekmişti. Test de kaymanın ne kadar olduğunu ölçmüyordu. Test artık düğmenin ekranın alt kenarında bittiğini ve kural kartının bütün kaldığını ölçer. İki görüntüyü de bu test çeker.
- Ralli listesi yalnız onay gönderir, ama listedeki ret kullanıcıya "düzeltmeyi yeniden yap" diyordu. Liste artık kullanıcıya skoru yeniden denetlemesini söyler ve genel ret için "Sunucu onayını kaydetmedi" der.
- Bu belgenin dört cümlesi koddan ya da kendi sayılarından ayrılıyordu: ekran 13 satırı eski uyarıyı anlatıyordu, bir madde testin denemediği bir durumu sayıyordu, bir cümle kimsenin ölçmediği bir ekran okuyucu davranışını yazıyordu ve inceleme satırı dört düzeltmeyi iki kez sayıyordu.

Dördüncü turda üç doğrulayıcı kullanım sınırına takıldı. Onların noktaları, öbür doğrulayıcıların doğruladığı üç noktanın tekrarıydı.

Beşinci tur tek nokta yazdı: Hiçbir test listenin kaymasını animasyonları kaldırılmış bir telefondaki yoldan ölçmüyordu. İki kat kayma yalnız o yolda görülmüştü. Yeni bir test aynı kaymayı animasyon ölçeği sıfırken ölçer ve liste orada da düğmenin alt kenarda bittiği yerde durur. İki kat kayma böylece yalnız ekran görüntüsü koşusunda kaldı. Kimse bu kaymayı gerçek telefonda ölçmedi.

Maç kuralı incelemesinden beş konu açık kaldı. Kullanıcı onayın yanıtını beklerken ralli listesini aşağı kaydırdıysa liste yerinde kalır. Kullanıcı sunucunun reddini o durumda listenin başına dönünce görür. 320 dp genişlikte ve 568 dp yükseklikte bir telefonda "sunucudaki kural değişmiş" uyarısı 1,5 kat yazıyla 271 dp, 2 kat yazıyla 355 dp yer tutar. Üç soru o sırada kalan dar bölümde kayar ve ilk bakışta hiçbir yanıt satırı görünmez. Bir test iki yazı boyunda da Kaydet düğmesinin ekranda kaldığını ve kullanıcının bir yanıt satırına dokunabildiğini ölçer. Uygulama 0.6.0 eski bir 0.5 sunucusuyla çalışırsa sunucu seçilen kuralı yok sayar. Uygulama o sunucunun ralli listesini okuyamadığını söyler ve rapor sunucunun kullandığı kuralı yazar, ama uygulama seçilen kuralın düştüğünü ayrıca söylemez (ADR-0115). Sunucu kural değişikliğinde yalnız kuralları karşılaştırır: Telefonun beklemekten vazgeçtiği bir istek yoldayken kullanıcı kuralı iki kez daha değiştirip ilk kurala dönerse sunucu geç gelen isteği uygular (ADR-0115). Testler uygulamanın ekran okuyucuya gönderdiği olayı ölçer, ama kimse TalkBack'i gerçek telefonda dinlemedi.

## 7. Sıradaki dilimler

Sıra bağımlılığa göredir. İlk dört iş gerçek video beklemez.

1. **Düzeltme süresi:** Ralli düzeltme sürüm 0.5.0 ile, maç kuralı sürüm 0.6.0 ile geldi (ADR-0114, ADR-0115). Kimse düzeltmenin gerçek bir maçta kaç dakika sürdüğünü ölçmedi. Ekip bu süreyi ilk gerçek maçta ölçer.
2. **Saha yarısı ve taraf değişimi:** Maç kaydı hangi takımın hangi yarıda başladığını ve takımların ne zaman yer değiştirdiğini taşımıyor. Kazanan önerisi ve oyuncu tarafı bu bilgiyi ister.
3. **Saha bölgesi seçimi:** Kullanıcı bir karede sahanın köşelerine dokunur. Sunucu homografiyi saklar. Baseline hareketi saha içiyle sınırlar (deney B2'nin ön koşulu).
4. **Arka plan yükleme:** WorkManager ve ön plan hizmeti. Gerçek telefonda deneme ister.
5. **Eksik ölçüm araçları:** Aksiyon ve oyuncu metrikleri, güven eşiği taraması ve risk-kapsama eğrisi (B6), alt grup metrikleri. Kazanan metrikleri ve kalibrasyon hazırdır (`evaluate-winner`). Anotatör uyumu aracı hazırdır (`agreement`).
6. **B0 ve B1 deneyleri:** İzinli video ve iki etiketçi gelince. Ekip baseline'ı ilk kez gerçek maçta ölçer.
7. **Üretim altyapısı:** Hesap, HTTPS, nesne depolama, iş kuyruğu. Bulut ve bütçe kararı ister.

Benzer bir ürünün herkese açık sayfalarını inceleyen çalışma şu adayları ekledi. Bağımsız doğrulayıcılar her adayı VolleyStat'ın koduna karşı denedi.

8. **Çekim öncesi kadraj denetimi:** Saha bölgesi seçimiyle (3) aynı işaretleme ekranını kullanır. Kullanıcı yanlış kadrajı maçtan önce görür (`docs/AI_PLAN.md` 2.3).
9. **Oyuncu başına ralli klipleri:** Kullanıcı oyuncu tablosundan o oyuncuya yazdığı sayıların kliplerini açar. Sunucu klip aralıklarını zaten tutuyor. Sıra ilk gerçek maçtan sonradır, çünkü bu ekranın değerini yalnız gerçek bir maç gösterir.
10. **Oyuncu adı taşımayan paylaşım görseli (FR-20):** Görsel skoru ve takım sayımlarını taşır, alt sınır ve DEMO etiketini de taşır. Ralli düzeltme geldiği için ekip bu işe artık başlayabilir.

Doğrulayıcılar iki adayı reddetti. Önceki rallinin kazananından çıkarılan side-out ve break sayımı, bölümleme bir ralliyi kaçırdığında sonraki bütün rallileri yanlış sınıfa koyar. "Kullanıcı yalnız emin olunmayan ralliyi doğrular" diyen bir tanıtım metni de bugün doğru değildir, çünkü kullanıcı her ralliyi kendisi doğruluyor.

## 8. Ürün sahibinden beklenenler

| Konu | Neden gerekli |
|---|---|
| Kendi çektiğin bir ya da iki amatör salon maçı ve oyuncuların yazılı izni | Model seçimi ve ölçüm izinli video olmadan başlayamaz. Açı ve izin ayrıntısı `docs/AI_PLAN.md` 4.1 bölümünde. |
| Voleybol bilen ikinci bir etiketçi | Gold etiket iki bağımsız etiketçi ister (B0). |
| Gerçek telefonda bir deneme | Emülatör gerçek salonu, tripodu ve uzun kaydı temsil etmez. |
| Lisans yönü kararı | Kod kapalı ticari ürün mü olacak, AGPL ile açık mı kalacak? Karar kullanılabilir model listesini değiştirir (`docs/AI_PLAN.md` 2. bölüm). |
| Uygulama simgesi kararı | Suluboya logo küçük boyda zor okunur ve mor arayüzle aynı paleti paylaşmaz. |
| Bulut ve GPU kararı | Ücretli hizmet onayı sende. O zamana kadar her şey yerelde çalışır. |
| Depo görünürlüğü | Depo herkese açık. Kapalı olmasını istersen GitHub ayarından değiştirirsin. |
| Maçın bitişi | Sunucu maçı bitirmez. Takımlar 2-0 biten üç setlik maçta bir dostluk seti oynarsa sunucu o seti de karar seti olarak sayar ve kazanılan setlere katar (ADR-0115). Sunucu bir takım gereken seti alınca sayımı durdurabilir. Bu da o dostluk setini rapordan çıkarır. Karar senin. |
| Kural değerleri | Kullanıcı setin ve karar setinin sayısı için yalnız 25, 21 ve 15 arasından seçer. Başka bir değer (30 sayı, sayı tavanı) gerekiyorsa sen söylersin. |
| İzin cümlesinin hukuki onayı | Uygulama 14 Kasım kararınla şu cümleyi gösterir: "Bu videoyu yükleme hakkım var. Sahadaki oyuncular çekimi biliyor ve analiz için izin verdi." Hiçbir KVKK uzmanı bu cümleyi okumadı. Cümle kişisel veri konusudur ve son hâlini bir uzman onaylar. |
| Geri bildirim adresi | Uygulama içinden geri bildirim için bir e-posta adresi gerekir. Adresi yalnız sen verebilirsin. |
