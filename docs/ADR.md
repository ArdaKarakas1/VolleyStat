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

## ADR-0113: Maç kimliğini telefon üretir ve silme sunucuda iz bırakmaz

**Tarih:** 13 Kasım 2026 · **Durum:** Kabul edildi

Bağımsız inceleme "Maçı sil" işinde üç boşluk buldu. Video silme çağrısı sunucudaki maç bilgilerini (takım adları, maç adı, tarih, salon) bırakıyordu. Sunucunun sildiği satırlar veritabanı dosyasında okunabilir kalıyordu. Maç açma yanıtı kaybolursa telefon maçın kimliğini hiç öğrenmiyor ve o maçı silemiyordu.

Kararlar:

- Sunucu `DELETE /matches/{id}` çağrısıyla maçın bütün kayıtlarını ve baytlarını tek veritabanı işleminde siler. Uygulama maçı telefondan yalnız sunucunun `204` ya da `404 MATCH_NOT_FOUND` yanıtından sonra kaldırır.
- Telefon maç kimliğini kendisi üretir, kimliği diske yazar ve maçı `PUT /matches/{id}` ile açar. Çağrı yinelenebilir. Telefon böylece yanıtı kaybolan maçı da adresler.
- Her veritabanı bağlantısı `PRAGMA secure_delete=ON` ile açılır. Sunucu silmeden sonra veritabanı günlüğünü boşaltır. Bir test silinen maçın adlarını veritabanı dosyalarında arar.
- Sunucu dosyaları veritabanı işlemini onaylamadan önce kaldırır. Yarıda kalan bir silme geride dosya bırakmaz, dosyası eksik satır bırakabilir. İstemci çağrıyı yineleyince sunucu o satırları da siler.
- Uygulama silinemeyen maç için nedeni ayırır: Sunucu yanıt vermedi, sunucu videoyu hâlâ denetliyor ya da sunucu isteği reddetti.

Açık kalan sınır: Sunucu silinen video dosyasının disk bloklarının üzerine yazmaz. Sunucuda süreye bağlı otomatik silme de yok. Bu iki konu üretim altyapısı kararını bekler.

## ADR-0114: Kullanıcı ralli listesini düzeltir ve onaylar

**Tarih:** 14 Kasım 2026 · **Durum:** Kabul edildi

Ürün kuralı "Kullanıcı elle yalnız belirsiz olayı doğrular" diyordu. Bu kural rapora bir tavan koyuyordu: Analizörün kaçırdığı rallinin kartı olmadığı için kullanıcı o sayıyı hiçbir yolla ekleyemiyordu ve rapordaki her sayı alt sınır kalıyordu. Ürün sahibi 14 Kasım 2026'da bu kurala tek bir istisna tanıdı: Kullanıcı kaçan ralliyi ekler, birleşmiş ralliyi böler, iki parçayı birleştirir ve bir rallinin başını ya da sonunu taşır. Amaç raporun kesin skor verebilmesidir.

Kararlar:

- Sunucu dört düzeltme işlemi sunar: ekleme, taşıma, bölme ve öncekiyle birleştirme. Sunucu silme ucu sunmaz. Kullanıcı ralli olmayan bir aralığı o rallinin sorusunda "Bu bir ralli değil" yanıtıyla sayımdan çıkarır. Ralli listesinden o soruya düzenleyicideki "Kazananı Seç" düğmesi götürür.
- Ralli listesi bir sürüm numarası taşır. Listeyi değiştiren her düzeltme ve yanıtı değiştiren her kazanan kararı sürümü artırır. Her düzeltme isteği kullanıcının baktığı sürümü yazar ve sunucu eski sürüme gelen isteği reddeder. Düzeltmeler bu yüzden yineleme anahtarı taşımaz: Sunucu yanıtı kaybolan bir düzeltmeyi ikinci kez uygulamaz. Uygulama yanıt gelmeyince listeyi yeniden okur ve kendi düzeltmesini o listede arar: eklediği aralığı, taşıdığı sınırı, kestiği noktayı, birleştirdiği başlangıcı ya da onayı. Sürümün ilerlemesi tek başına kanıt değildir, çünkü yolda kalan bir kazanan kararı da sürümü artırır. Uygulama aynı aramayı sunucu "liste değişti" dediğinde de yapar, çünkü sunucu bu yanıtı, ilk denemesi uygulamanın haberi olmadan yerine ulaşmış bir düzeltmenin ikinci denemesine de verir. Uygulama o durumda kullanıcıdan düzeltmeyi yinelemesini isteseydi, kullanıcının yinelediği birleştirme başka bir ralliyi silerdi.
- Bir kazanan kararı listeyi ilerletir. Uygulama o karardan önce okuduğu listeyi göstermez ve o liste üzerinde düzeltme göndermez. Ekran önce listeyi yeniden okur. Kararın yanıtı ekran açıldıktan sonra gelirse ekran listeyi bir kez daha okur. Uygulama bildiği işten daha yeni bir listeyi güncel sayar, çünkü yanıtı kaybolan bir karar işi geride bırakır.
- Bir karar rallinin sonuna aittir, çünkü kullanıcı kazananı klibin sonunda gördüğüne göre seçer. Kullanıcı başı taşıyınca yanıtlar kalır. Kullanıcı sonu taşıyınca sunucu kazanan sorusunu yeniden açar, o rallinin sayı türü ve oyuncu yanıtlarını siler ve analizörün o ralli için kazanan önerisini kaldırır, çünkü hiçbir analizör yeni sonu görmedi. Bölmede sonraki parça rallinin kimliğini ve yanıtlarını korur. Birleştirmede sonraki ralli kalır ve sunucu önceki rallinin yanıtlarını denetim kaydına `REMOVED` olarak yazar.
- Aynı işin rallileri zamanda çakışmaz. "Bu bir ralli değil" yanıtı alan ralli de yerini korur, çünkü tek bir yanıt onu sayıma geri getirir.
- Kart zaten taşıdığı yanıtı yeniden alırsa sunucu hiçbir şey yazmaz ve hiçbir sürümü artırmaz. Kullanıcının seçili yanıta yeniden dokunması böylece liste onayını düşürmez.
- Kullanıcı listeyi tek bir soruyla onaylar: "Bu skorlar maçta oynanan skorlarla aynı mı?" Yalnız "Evet, Aynı" yanıtı onaylar. Sunucu listenin eksiksiz olduğunu doğrulayamaz ve onayı kullanıcının beyanı olarak kaydeder. Onay liste sürümüne bağlıdır. Listeyi değiştiren her düzeltme ve yanıtı değiştiren her kazanan kararı onayı düşürür.
- Onay ekranındaki set skorları liste yanıtının içinden gelir (`sets`). Kullanıcının gördüğü skor böylece onayladığı sürümün skorudur. Telefon skor hesaplamaz.
- Rapor kullanıcı listeyi onaylayana kadar her sayıyı alt sınır olarak verir. Bu kural analizörün türüne bakmaz, çünkü ölçümü kayıtlı bir model de ralli kaçırır.
- Sunucu deneme analizörünün işinde liste onayını kaydetmez. Sunucu o aralıkları uydurmuştur ve uydurma aralıklar gerçek maç hakkında bilgi vermez. Uygulama bu yüzden deneme listesinde alt sınırdan söz etmez. Deneme raporu artı işaretlerini korur, çünkü sunucu o değerleri `PARTIAL` olarak bildirir. Rapor aynı ekranda sayıların gerçek maç hakkında bilgi vermediğini yazar.
- Düzenleyici her zaman uygulamanın güncel listeden hesapladığı bir pencerede çalışır: Rallinin çevresindeki boş süre (her yana en çok 30 saniye) ya da iki ralli arasındaki boşluk. Kullanıcı bu pencerede başka bir ralliyle çakışan bir konuma ulaşamaz. Sunucu çakışmayı yine de kendisi denetler.
- Sunucu bir düzeltmeyi uygulayınca düzenleyici kapanır ve kullanıcı geldiği ekrana döner. Doğrulama ekranı kullanıcının düzelttiği rallinin sorusunda durur. Uygulama düzeltmenin yanıtındaki işi ve yeniden okuduğu listeyi ekrana birlikte verir. Düzenleyici bu yüzden kapanmadan önce "yükleniyor" ekranına düşmez.
- Düzeltme gönderen ekran sunucunun yanıtı gelene kadar açık kalır. Düzenleyici ve ralli listesi o sırada geri tuşunu kapatır ve düzeltmenin yolda olduğunu yazar. Kullanıcı o sırada başka bir ekrana geçebilseydi sunucunun reddini ve bağlantı hatasını hiçbir ekran göstermezdi. Düzenleyici bu iki bilgiyi işaret düğmelerinin hemen üstünde gösterir, çünkü küçük ekranda içeriğin altı görünmez. Ret, kullanıcı görüntüyü yeniden hareket ettirince ekrandan kalkar. Uygulama bir düzeltmede üç istek yapar: Düzeltmeyi gönderir, listeyi okur ve sunucu düzeltmeyi uyguladıysa kartları okur. Her istek bağlantı için en çok 5, yanıt için en çok 15 saniye bekler.
- Uygulama bir işin kartlarını, ralli listesini ve yoldaki düzeltmesini o işe bağlı tutar. Maç yeni bir analiz işi alınca ya da sunucu işi artık tanımayınca uygulama bunların hepsini bırakır, çünkü eski işin liste sürümü yeni işin listesi hakkında hiçbir şey söylemez ve işi olmayan bir listeyi kimse düzeltemez. İşi olmayan maçta ralli listesi ve düzenleyici sunucunun verdiği nedeni yazar ve yeniden denemeyi yalnız bağlantı koptuysa sunar.
- İş, analizörün önerdiği ralli sayısını listedeki ralli sayısından ayrı taşır (`counts.proposed`). Durum ekranı "Sunucu şu kadar ralli aralığı önerdi" derken bu sayıyı kullanır. Rapor listedeki aralık sayısını ve analizörün önerdiği sayıyı ayrı satırlarda yazar.
- Düzenleyicinin kuralları ekrandadır, oynatıcıda değildir: Oynatma pencerenin sonunda ve videonun kendi sonunda durur, işaret oynatıcının dokunma anındaki konumudur ve hız ekranın durumudur. Ekran bir düğmeyi açan kuralı dokunma anındaki konuma yeniden sorar, çünkü oynayan görüntü ekranın son baktığı konumdan ileridedir. Konum kuralı tutmuyorsa ekran yalnız oynatmayı durdurur ve hiçbir şey göndermez. JVM testleri bu kuralları oynatıcının yerindeki vekille dener.
- Düzenleyici işaret düğmelerini oynatıcı o konumda "hazır" dediğinde açar: ekran ilk açıldığında, her konum değişikliğinden sonra ve kullanıcı videoyu yeniden istediğinde. Oynatıcı 0,6 saniyeden uzun süre hazır olmazsa düzenleyici resmin üstünde bir bekleme işareti gösterir. Oynatıcı videoyu açamazsa düzenleyici konum isteyen bütün düğmeleri kapatır ve videoyu yeniden isteyen bir düğme gösterir.
- Düzenleyici videoyu sessiz oynatır. Uygulama ses kaydetmez ve galeriden gelen videodaki konuşmalar kişisel veridir.
- Sunucu düzeltmeleri eğitim verisi olarak kullanmaz. Düzeltme kayıtları (`rally_corrections`) yalnız denetim içindir.

Ekip şunları bilerek yapmadı: geri alma ucu, onayı geri çeken uç, en kısa ralli süresi, boşlukları sıralama ya da öne çıkarma, kullanıcının elle skor yazması, kullanıcının set sonunu işaretlemesi ve düzeltme süresinin ölçümü.

Açık kalan sınırlar:

- Set kuralı sabittir: Set 25 sayıda, beşinci set 15 sayıda ve iki farkla biter. Sunucu üç setlik maçın 15 sayılık karar setinde set skorunu doğru, takımların kazandığı set sayısını yanlış hesaplar. Sunucu 21 sayılık sette set sınırını da kaydırır ve set skorlarını yanlış hesaplar. Kullanıcı o maçta skor denetimine "Evet" diyemez ve rapor alt sınır olarak kalır. İki çözüm adayı ürün sahibinin kararını bekler. Birinci adayda kullanıcı maçı açarken maç kuralını bir kez seçer: maçı kaç set kazananın aldığını, setin kaç sayıda bittiğini ve karar setinin kaç sayıda bittiğini. İkinci adayda kullanıcı her setin son rallisini listede işaretler. Ekibin önerisi birinci adaydır, çünkü kullanıcı standart kuralla oynanan maçta hiçbir şeye dokunmaz. İkinci aday süreyle biten seti de karşılar. İki aday da yeni bir elle giriştir.
- Kimse düzeltmenin gerçek bir maçta ne kadar sürdüğünü ölçmedi. Kaba tahmin şudur: 175 kart yaklaşık 10 dakika ister ve kaçan her ralli yaklaşık 90 saniye ister. Bu tahminle 15 dakikalık hedef ancak maç başına en çok üç kaçan ralliyle tutar. Analizörün kaç ralli kaçırdığını B1 deneyi ölçecek.
- Kimse düzenleyiciyi gerçek telefonda denemedi. JVM testleri ekranı oynatıcının yerindeki bir vekille dener. Oynatıcıyı tutan sınıfı da durumunu testin elle kurduğu bir oynatıcıyla dener. Gerçek oynatıcıyı yalnız CI emülatöründeki cihaz testi dener. O test oynatmayı, çubukla konum değiştirmeyi ve bir bitiş işaretini dener. Hiçbir test oynatıcının uygulama arka plana geçince durduğunu ve gerçek oynatıcının bir hatadan sonra videoyu yeniden açtığını denemez.
- Bir işaret oynatıcının konumudur. Düzenleyici işaret düğmelerini oynatıcı "hazır" dediğinde açar. Kimse gerçek bir telefonda oynatıcının o anda tam o konumun karesini gösterdiğini ölçmedi.
- Testler düzeltmenin bekleme süresini kısaltarak dener. Kimse 15 saniyenin gerçek bir ağda yeterli olduğunu ölçmedi. Sunucu o süreden geç yanıt verirse uygulama yanıtı kayıp sayar ve düzeltmeyi yeniden okuduğu listede arar.
- Sunucu, uygulamanın yinelediği bir karara sakladığı yanıtı verir ve bu yanıt araya giren bir düzeltmeden sonra da eski kartı taşır. Uygulama her karardan sonra kartları yeniden okuduğu için bu yanıtı ekranda tutmaz. Bağımsız inceleme bu bulguyu reddetti ve davranış değişmedi.
- Testler liste onayını ekrandan yalnız betikli bir sunucuya karşı dener, çünkü gerçek sunucu deneme analizörünün listesini onaylamaz. Sunucunun onay kurallarını sunucu testleri dener.
