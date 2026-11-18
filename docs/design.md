# VolleyStat tasarım sistemi

Marka Voleybol Ve Veleybol, ürün VolleyStat. Uygulamanın görsel kimliği UI Tasarım V1 paketinden gelir: mor ve beyaz, Inter yazı tipi, yuvarlatılmış beyaz kartlar. Ürün kuralları V4 devir raporundan gelir. İki kaynak çelişirse görsel konuda UI V1, davranış konusunda V4 geçerlidir.

Tasarım paketinde Figma dosyası yoktur. Ekip PNG görsellerini görsel hedef, `04_DESIGN_TOKENS.json` dosyasını stil başlangıcı olarak aldı. Görsellerdeki skorlar, adlar ve yüzdeler örnek veridir ve uygulamaya girmez.

## Tokenlar

Bütün değerler `android/app/src/main/java/com/voleybolveveleybol/volleystat/ui/theme/Theme.kt` dosyasında durur. Ekranlar renk ve ölçü yazmaz, bu dosyadaki adları kullanır.

| Token | Açık tema | Kullanım |
|---|---|---|
| Ana renk | `#6A3DF0` | Ana düğme, seçili sekme, vurgu |
| Açık ana renk | `#8B5CF6` | Geçişli kartın ikinci rengi |
| Zemin | `#F5F3FF` | Ekran zemini |
| Yüzey | `#FFFFFF` | Kart, alt çubuk |
| Metin | `#111827` | Başlık ve gövde |
| İkincil metin | `#596174` | Yardımcı satır, etiket |
| Olumlu | `#16A34A` | Biten adım, hazır rapor |
| Uyarı | `#D97706` | Doğrulama bekleyen iş, DEMO etiketi |
| Hata | `#DC2626` | Reddedilen dosya, duran iş, kayıt göstergesi |
| Ayraç | `#E5E7EB` | Satır çizgisi, kart kenarı |

Koyu temanın kendi şeması vardır. Koyu temada ana renk açılır ve yüzeyler koyulaşır. Uygulama sistem ayarını izler.

Ölçüler: Ekran kenar boşluğu 16 dp, kart köşesi 16 dp, düğme köşesi 12 dp, temel aralık 8 dp, dokunma alanı en az 48 dp.

Yazı: Inter Regular, Medium, SemiBold ve Bold (SIL Open Font License, lisans metni `android/licenses/` klasöründe). Sayılar eş genişlikli rakamla çizilir, böylece skor ve tablo sütunları titremez.

## Bileşenler

Ortak bileşenler `ui/components/Components.kt` dosyasındadır.

| Bileşen | Görev |
|---|---|
| `VsCard` | Beyaz, 16 dp köşeli kart |
| `PrimaryButton`, `SecondaryButton` | Ekranın ana ve ikincil eylemi |
| `ListRow`, `IconTile` | Simge kutulu liste satırı |
| `StatusChip` | Durum etiketi. Durumu renk tek başına taşımaz, etiket her zaman bir simge ve bir sözcük içerir. |
| `Notice` | Ekran içi bilgi, uyarı ve hata kutusu |
| `DemoBanner` | Deneme analizörünün ürettiği her verinin üstündeki DEMO etiketi |
| `NotYetCard`, kesik çizgili kutu | Henüz var olmayan ya da ölçümü olmayan şey |
| `StageList` | Sunucunun bildirdiği iş adımları |
| `ProgressRing` | İlerleme halkası. Halka yalnız sunucu ya da yükleme gerçek bir oran bildirdiğinde dolar. |
| `TeamBadge` | Ev sahibi dolu, misafir çerçeveli rozet |
| `SegmentedChoice`, `SelectCard` | İkili seçim ve radyo kartı |
| `EmptyState` | Boş liste açıklaması |

## Dürüstlük kalıpları

Bu kalıplar görsel etkiden önce gelir.

- **Ölçüm yoksa sayı yok.** Uygulama bilinmeyen değeri kesik çizgili boş kutuyla çizer ve ekran okuyucuya "Ölçüm yok" der. Uygulama bilinmeyen değeri hiçbir yerde `0` yazmaz.
- **Alt sınır artı işareti taşır.** Doğrulama bitmeden görünen sayı `28+` biçimindedir. Ekran artının anlamını bir satırla açıklar. Kazanılan set sayısı da alt sınırken artı taşır (`1+ - 0+`). Bir videoda birden çok bölüm varsa maç kartı artıyı her bölüme ayrı verir: Liste eksik olabilirken her bölüm artı taşır. Liste onaylıysa yalnız sayımın durduğu bölüm artı taşır, çünkü o bölüm sonradan set kazanabilir. Ekran okuyucu artıyı "en az" diye okur ve süren seti "set sürüyor" diye söyler.
- **Bitmemiş set kesik çizgilidir.** Süren setin skoru kesin set skoru gibi görünmez.
- **DEMO her ekranda yazılıdır.** Deneme analizörünün verisi durum, doğrulama, rapor ve oyuncu ekranlarında DEMO etiketi taşır.
- **Sahte yüzde yok.** Analiz ekranı yalnız sunucunun bildirdiği adımı ve oranı gösterir.
- **Atlanan kart açık kalır.** Kullanıcı son karttan sonra atladığı kart varsa "karar bekleyen kart" sayısını görür. Ekran yalnız bütün kartlar yanıtlandığında "Bütün ralliler için karar verdin" der.
- **Öneri seçili gelmez.** Sunucu bir kazanan önerirse ekran öneriyi ipucu olarak yazar. Kararı kullanıcı verir.
- **Oyuncuya sayı yazmak karar ister.** Oyuncu tablosu yalnız kullanıcının doğrulamada forma numarası seçtiği sayıları içerir. Oyuncusu belli olmayan sayılar ayrı satırda durur.
- **Uygulama yalnız doğrulanmış sayımı gösterir.** Bileşik performans puanı, yüzdelik dilim, oyuncu sıralaması, gelecek tahmini ve yapay zekânın yazdığı yorum bu uygulamada yoktur. Ekip bunlardan birini ancak arkasındaki ölçümün doğruluğunu gerçek maçlarda ölçtükten ve ürün sahibi onayladıktan sonra ekler.
- **Uygulama ölçülmemiş güven değerini göstermez.** Uygulama bir karara "güven yüzdesi" yazmaz. Ekip bu değeri ancak kalibrasyonu kilitli test kümesinde ölçtükten sonra ekrana çıkarır (`docs/AI_PLAN.md` 2.7).
- **Oran, sayımıyla birlikte durur.** Uygulama bir yüzde ya da oran gösterirse payı ve paydayı aynı ekrana yazar. Kullanıcı oranı ekrandaki sayılardan yeniden hesaplayabilir. Bugünkü rapor oran göstermez, yalnız sayım gösterir (`30 / 40`).
- **Örnek etiketi öğenin üstündedir.** Uygulamanın DEMO veriyle çizdiği sayı kartı etiketi kendi başlığında taşır. Rapor ekranında "Takım İstatistikleri" ve "Veri Güvenilirliği" kartlarının başlığı DEMO yazar ve ekranın tepesindeki bant ayrıca durur. Kullanıcı tek bir kartın ekran görüntüsünü alsa da görüntü DEMO yazar. Ekip yeni bir sayı kartını, paylaşım görselini ve tanıtım görüntüsünü aynı kuralla çizer. Ralli düzenleyici de DEMO etiketini uydurma aralığı gösteren satırın üstünde taşır.
- **Sayı türünün tek tanımı vardır.** Doğrulama kartı ve rapor aynı "Sayı Türleri" penceresini açar. Kullanıcının kararı ve rapordaki sayı aynı tanıma dayanır.
- **Liste onayı kullanıcının beyanıdır.** Uygulama ralli listesini kendisi eksiksiz ilan etmez. Kullanıcı listenin set skorlarını maçta oynanan skorlarla karşılaştırır ve yalnız "Evet, Aynı" yanıtı listeyi onaylar. "Hayır" ve "Bilmiyorum" yanıtları hiçbir sayıyı kesinleştirmez. Onay ekranı onaylanan liste sürümünün skorunu gösterir.
- **Kullanıcının yaptığı ralli işaret taşır.** Liste satırı "Sen oluşturdun" yazar. Bu işaret kullanıcının eklediği ralliyi de bölerek ayırdığı parçayı da kapsar. Rapor kullanıcının oluşturduğu rallileri sayar ve aralıkların kaynağında kullanıcının düzeltmelerini adıyla yazar.
- **Oynatıcı hazır değilse işaret yok.** Ralli düzenleyici işaret düğmelerini oynatıcı o konumda "hazır" dediğinde açar. Oynatıcı geç hazır olursa düzenleyici resmin üstünde bir bekleme işareti gösterir. Oynatıcı videoyu açamazsa düzenleyici konum isteyen bütün düğmeleri kapatır ve videoyu yeniden isteyen bir düğme gösterir. Her işaret oynatıcının dokunma anındaki konumudur. Ekran o konumu "Konum" satırında yazar.
- **Ralli listesindeki skor da alt sınırdır.** Liste her rallinin yanında o andaki set skorunu gösterir. Kullanıcı listeyi onaylayana kadar ekran bu skorların alt sınır olduğunu ve eksik bir rallinin set sınırını kaydırdığını yazar. Deneme analizinin listesinde ekran alt sınırdan söz etmez: Sunucu o aralıkları uydurmuştur ve skorlar gerçek maç hakkında bilgi vermez.
- **Bir pencere yalnız var olan yanıtı sayar.** Birleştirme penceresi önceki rallinin kararından yalnız o karar varsa söz eder. Sunucu kalan rallinin "Bu bir ralli değil" yanıtını silecekse pencere bunu da yazar. Düzenleyicideki not sayı türü ve oyuncu yanıtlarından koşullu söz eder: "verdiysen".
- **Sayıyı kim saydıysa cümlenin öznesi odur.** Kullanıcı maçın kuralını seçer ve sunucu skoru bu kurala göre sayar. Ekran bu yüzden "Sunucu listedeki rallileri şu skorla saydı" ve "Sunucu seti 21 sayıda, 3. seti 15 sayıda ve iki farkla bitirir" der. Ekran "Uygulama saydı" demez. Ekran kuralı sunucunun yanıtından okur ve telefon set sınırı hesaplamaz.
- **Ekran kullanıcının kaydetmediği seçimi koşul cümlesiyle söyler.** Sunucu yeni kuralla ancak Kaydet düğmesinden sonra sayar. Kural ekranı bu yüzden seçimi "Kaydedersen sunucu seti 21 sayıda, 3. seti 15 sayıda ve iki farkla bitirir" diye yazar. Maçın kuralı ise düz cümleyle durur.
- **Ret metni suçu doğru yere koyar.** Sunucu uygulamanın sunduğu her kuralı kabul eder. Kural ekranının ret metni bu yüzden kuralı suçlamaz ve kullanıcıya sunucuyu denetlemesini söyler. Sunucudaki kural ekranın gösterdiği kuraldan farklıysa ekran kuralın değiştiğini söyler, sunucunun şimdiki kuralını yazar ve kullanıcının seçimini korur. Ekran kuralın ne zaman değiştiğini söylemez, çünkü uygulama bunu bilmez. Ralli listesi ve düzenleyici de değişen bir liste için aynı kalıbı kullanır. Ret, kullanıcının o ekranda yaptığı adımı adıyla söyler: Düzenleyici düzeltmeyi, ralli listesi skor denetimini yinelemeyi ister.
- **Ekran maçın nerede bittiğini söyler.** Bir takım gereken setleri alınca sunucu maçı bitirir ve sonraki ralliyle yeni bir maç açar (ADR-0118). Kural ekranı ve rapor bunu yazar: "Bir takım gereken setleri alınca sunucu maçı bitirir ve sonraki ralliyle aynı kuralla yeni bir maç açar."
- **Bir video birden çok bölüm taşıyabilir.** Rapor o zaman başlıkta her takımın kazandığı maç sayısını gösterir. "Maçlar" kartı her bölümü adıyla ("Maç 1", "Maç sonrası", "Bitmemiş maç") ve setleriyle listeler. Takım ve oyuncu istatistiğinin üstündeki seçim "Bütün video" ile bölümler arasında geçer. Ralli listesi her bölümün başına adını koyar ve setleri her bölümde 1'den sayar.
- **Yanıtı bekleyen ekran açık kalır.** Düzeltme gönderen ekran sunucunun yanıtı gelene kadar geri tuşunu kapatır ve düzeltmenin yolda olduğunu yazar: Düzenleyici "Uygulama düzeltmeyi sunucuya gönderiyor", ralli listesi "Uygulama onayı sunucuya gönderiyor", maç kuralı ekranı "Uygulama kuralı sunucuya gönderiyor" der. Kullanıcı sunucunun reddini ya da bağlantı hatasını böylece ekrandan çıkmadan önce görür.
- **Bir dokunuşun sonucu kullanıcının dokunduğu düğmenin yanında durur.** Ralli düzenleyici "gönderiyor" satırını ve sunucunun reddini işaret düğmelerinin hemen üstünde gösterir. Ekranın kayan bölümünde duran bir ret küçük telefonda görünmez ve kullanıcı dokunuşun boşa gittiğini sanar. Düzenleyicideki ret, kullanıcı görüntüyü yeniden hareket ettirince kalkar. Ralli listesi aynı satırları "Skoru Denetle" düğmesinin hemen üstünde gösterir. Satır belirince ya da değişince liste, başı ekrandaysa satırı ve düğmeyi ekrana getirir. Kullanıcı yanıtı beklerken listeyi aşağı kaydırdıysa liste yerinde kalır ve kullanıcı sonucu listenin başına dönünce görür. Listedeki ret, kullanıcı listeyi yeniden onaylayana ya da listeyi yeniden açana kadar kalır.
- **Kural değişince onay düşer ve ekran bunu önceden söyler.** Kullanıcı listeyi eski kuralın set skorlarıyla onayladı. Kural ekranı bunu kullanıcı Kaydet düğmesine basmadan önce yazar. Ekran kullanıcının kararlarının ve düzeltmelerinin kaldığını da yazar.
- **Standart maç dokunuş istemez.** Yeni maç ekranı FIVB kuralını seçili getirir. Kullanıcı başka bir kuralla oynanan maçta kuralı bir kez seçer. Kural FIVB kuralı değilse ana ekrandaki kurulum kartı ve yüklemeden önceki son ekran kuralı gösterir. Kullanıcı standart dışı bir kuralı böylece yükleme başlamadan önce bir kez daha görür.
- **Ekran yokluğun nedenini söyler.** İşi olmayan maçta ralli listesi ve düzenleyici sunucunun verdiği nedeni yazar. "Yeniden Dene" düğmesi yalnız yeniden sormanın yanıtı değiştirebileceği yerde durur.
- **Bir metin başka bir düğmeyi anıyorsa etiketi o düğmeden alır.** İpucu "Kuralı değiştir" düğmesini, "Kazananı Seç" düğmesini ve "Bu bir ralli değil" yanıtını kendi metninde yazmaz. Uygulama o üç etiketi ipucuna çalışırken koyar.
- **Silme, sunucu sildikten sonra görünür.** Uygulama maçı listeden yalnız sunucu maçın bütün kayıtlarını sildiğini bildirince kaldırır. Sunucu silmediyse ekran nedenini yazar: Sunucu yanıt vermedi, sunucu videoyu hâlâ denetliyor ya da sunucu isteği reddetti. Profil sekmesindeki gizlilik satırları sunucunun gerçek davranışını yazar: neyin gittiğini, kimin görebildiğini, sunucunun ne kadar sakladığını ve silmenin neyi sildiğini.
- **Hesaptaki her değişiklik sunucu onaylayınca görünür.** Giriş, kayıt, görünen ad, şifre, çıkış ve hesap silme ancak sunucu onaylayınca ekrana yansır. Sunucu çıkışı onaylamazsa oturum telefonda açık kalır ve ekran "Yine de Çıkış Yap" düğmesini gösterir. Profil sekmesi telefonda ayrı bir ad tutmaz: Görünen ad alanı sunucunun tuttuğu adı gösterir ve "Adı Kaydet" adı sunucuya gönderir. Hesap silme ekranı sunucunun neyi sildiğini, telefonun neyi sildiğini ve sunucuda neyin kaldığını yazar.
- **Hesap formlarında ret formun başında durur.** Giriş, kayıt, şifre değiştirme ve hesap silme ekranları "Uygulama sunucunun yanıtını bekliyor" satırını düğmenin hemen üstünde gösterir. Sunucu çok sayıda istek işlediğini bildirdiyse aynı satır uygulamanın birkaç saniye sonra yeniden deneyeceğini yazar. Sunucunun reddi formun başında durur ve form ret belirince başa kayar. Bu ekranlardaki retler uzundur: Alttaki düğme alanında duran uzun bir ret 320 dp genişliğinde ve 1,5 kat yazılı bir telefonda forma yer bırakmıyordu.

## Durumlar

Her ekran dört durumu çizer: yükleniyor, boş, hata ve dolu. Hata mesajı neyin olmadığını ve kullanıcının ne yapabileceğini söyler. Uygulama hata kodu göstermez. Uygulama iptal edilen işi hata olarak göstermez.

## Erişilebilirlik ve ekran boyutları

- Dokunma alanları en az 48 dp yüksekliğindedir.
- Uygulama, kullanıcı yanıt beklerken beliren satırı ve sunucunun reddini canlı bölge olarak işaretler. Testler, satır belirince uygulamanın ekran okuyucuya o satırdan bir değişiklik olayı gönderdiğini ölçer. Kimse bu satırları TalkBack ile gerçek telefonda dinlemedi.
- Kural ekranında iki soru aynı yanıtı sunar ("21 sayı"). Uygulama her satıra sorusunu da içeren bir erişilebilirlik açıklaması verir.
- Dar ekranda ve büyük yazıda tarih kartı ve kural kartı "değiştir" yazısını alt satıra taşır. Kartın bilgisi böylece bir sözcüğün ya da sayının ortasından bölünmez.
- Renk hiçbir bilgiyi tek başına taşımaz. Seçili yanıt çerçeve, zemin ve "Senin kararın" yazısıyla işaretlenir.
- Ekran görüntüsü testleri her ana ekranı 411 dp ve 320 dp genişlikte, açık ve koyu temada ve 1,5 kat yazı boyutunda üretir.
- Dar ekranda ve büyük yazıda oyuncu tablosu adı sayıların üstüne taşır. Doğrulama ekranı iki takımı yan yana koyar, böylece yanıtlar klibin yanında kalır.
- Ralli düzenleyici 320 dp telefonda videoyu, konum çubuğunu, adım düğmelerini ve işaret düğmelerini kaydırmadan gösterir.
- Kayıt ekranı yatay tutuş için tasarlanmıştır. Dikey tutuşta ekran kullanıcıdan telefonu çevirmesini ister.

## Uygulama simgesi

Simge ürün sahibinin verdiği suluboya logodur (krem, lacivert, turuncu). Uygulamanın içi mor ve beyazdır. İki kimlik aynı paleti paylaşmaz. Logo ayrıntılı bir çizimdir ve küçük boyda okunması zordur. Ürün sahibi 16 Kasım 2026'da logonun simge olarak kalmasına karar verdi (ADR-0117). Uygulama logoyu açılış ekranında ve Profil sekmesinde küçük boyda gösterir. Logonun üstündeki yazı ana ekrandaki küçük simgede okunmaz. Yazısız tek bir işaret bu boyda daha iyi çalışır. Ürün sahibi yeni bir simge verirse ekip onu bu iki boyda dener.
