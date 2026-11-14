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
- **Alt sınır artı işareti taşır.** Doğrulama bitmeden görünen sayı `28+` biçimindedir. Ekran artının anlamını bir satırla açıklar. Kazanılan set sayısı da alt sınırken artı taşır (`1+ - 0+`).
- **Bitmemiş set kesik çizgilidir.** Süren setin skoru kesin set skoru gibi görünmez.
- **DEMO her ekranda yazılıdır.** Deneme analizörünün verisi durum, doğrulama, rapor ve oyuncu ekranlarında DEMO etiketi taşır.
- **Sahte yüzde yok.** Analiz ekranı yalnız sunucunun bildirdiği adımı ve oranı gösterir.
- **Atlanan kart açık kalır.** Kullanıcı son karttan sonra atladığı kart varsa "karar bekleyen kart" sayısını görür. Ekran yalnız bütün kartlar yanıtlandığında "Bütün ralliler için karar verdin" der.
- **Öneri seçili gelmez.** Sunucu bir kazanan önerirse ekran öneriyi ipucu olarak yazar. Kararı kullanıcı verir.
- **Oyuncuya sayı yazmak karar ister.** Oyuncu tablosu yalnız kullanıcının doğrulamada forma numarası seçtiği sayıları içerir. Oyuncusu belli olmayan sayılar ayrı satırda durur.
- **Uygulama yalnız doğrulanmış sayımı gösterir.** Bileşik performans puanı, yüzdelik dilim, oyuncu sıralaması, gelecek tahmini ve yapay zekânın yazdığı yorum bu uygulamada yoktur. Ekip bunlardan birini ancak arkasındaki ölçümün doğruluğunu gerçek maçlarda ölçtükten ve ürün sahibi onayladıktan sonra ekler.
- **Uygulama ölçülmemiş güven değerini göstermez.** Uygulama bir karara "güven yüzdesi" yazmaz. Ekip bu değeri ancak kalibrasyonu kilitli test kümesinde ölçtükten sonra ekrana çıkarır (`docs/AI_PLAN.md` 2.7).
- **Oran, sayımıyla birlikte durur.** Uygulama bir yüzde ya da oran gösterirse payı ve paydayı aynı ekrana yazar. Kullanıcı oranı ekrandaki sayılardan yeniden hesaplayabilir. Bugünkü rapor oran göstermez, yalnız sayım gösterir (`30 / 40`).
- **Örnek etiketi öğenin üstündedir.** Uygulamanın DEMO veriyle çizdiği sayı kartı etiketi kendi başlığında taşır. Rapor ekranında "Takım İstatistikleri" ve "Veri Güvenilirliği" kartlarının başlığı DEMO yazar ve ekranın tepesindeki bant ayrıca durur. Kullanıcı tek bir kartın ekran görüntüsünü alsa da görüntü DEMO yazar. Ekip yeni bir sayı kartını, paylaşım görselini ve tanıtım görüntüsünü aynı kuralla çizer.
- **Sayı türünün tek tanımı vardır.** Doğrulama kartı ve rapor aynı "Sayı Türleri" penceresini açar. Kullanıcının kararı ve rapordaki sayı aynı tanıma dayanır.
- **Liste onayı kullanıcının beyanıdır.** Uygulama ralli listesini kendisi eksiksiz ilan etmez. Kullanıcı listenin set skorlarını maçta oynanan skorlarla karşılaştırır ve yalnız "Evet, Aynı" yanıtı listeyi onaylar. "Hayır" ve "Bilmiyorum" yanıtları hiçbir sayıyı kesinleştirmez. Onay ekranı onaylanan liste sürümünün skorunu gösterir.
- **Kullanıcının yaptığı ralli işaret taşır.** Liste satırı "Sen oluşturdun" yazar. Bu işaret kullanıcının eklediği ralliyi de bölerek ayırdığı parçayı da kapsar. Rapor kullanıcının oluşturduğu rallileri sayar ve aralıkların kaynağında kullanıcının düzeltmelerini adıyla yazar.
- **Video oynamıyorsa işaret yok.** Video oynamıyorsa ralli düzenleyici konum isteyen bütün düğmeleri kapatır ve videoyu yeniden isteyen bir düğme gösterir. Her işaret oynatıcının dokunma anındaki konumudur. Ekran o konumu "Konum" satırında yazar.
- **Ralli listesindeki skor da alt sınırdır.** Liste her rallinin yanında o andaki set skorunu gösterir. Kullanıcı listeyi onaylayana kadar ekran bu skorların alt sınır olduğunu ve eksik bir rallinin set sınırını kaydırdığını yazar.
- **Bir pencere yalnız var olan yanıtı sayar.** Birleştirme penceresi önceki rallinin kararından yalnız o karar varsa söz eder. Kalan rallinin "Bu bir ralli değil" yanıtı silinecekse pencere bunu da yazar.
- **Sayıyı kim saydıysa cümlenin öznesi odur.** Skoru ve set kuralını sunucu uygular. Ekran bu yüzden "Sunucu listedeki rallileri şu skorla saydı" der ve "Uygulama saydı" demez.
- **Silme, sunucu sildikten sonra görünür.** Uygulama maçı listeden yalnız sunucu maçın bütün kayıtlarını sildiğini bildirince kaldırır. Sunucu silmediyse ekran nedenini yazar: Sunucu yanıt vermedi, sunucu videoyu hâlâ denetliyor ya da sunucu isteği reddetti. Profil sekmesindeki gizlilik satırları sunucunun gerçek davranışını yazar: neyin gittiğini, kimin görebildiğini, sunucunun ne kadar sakladığını ve silmenin neyi sildiğini.

## Durumlar

Her ekran dört durumu çizer: yükleniyor, boş, hata ve dolu. Hata mesajı neyin olmadığını ve kullanıcının ne yapabileceğini söyler. Uygulama hata kodu göstermez. Uygulama iptal edilen işi hata olarak göstermez.

## Erişilebilirlik ve ekran boyutları

- Dokunma alanları en az 48 dp yüksekliğindedir.
- Renk hiçbir bilgiyi tek başına taşımaz. Seçili yanıt çerçeve, zemin ve "Senin kararın" yazısıyla işaretlenir.
- Ekran görüntüsü testleri her ana ekranı 411 dp ve 320 dp genişlikte, açık ve koyu temada ve 1,5 kat yazı boyutunda üretir.
- Dar ekranda ve büyük yazıda oyuncu tablosu adı sayıların üstüne taşır. Doğrulama ekranı iki takımı yan yana koyar, böylece yanıtlar klibin yanında kalır.
- Ralli düzenleyici 320 dp telefonda videoyu, konum çubuğunu, adım düğmelerini ve işaret düğmelerini kaydırmadan gösterir.
- Kayıt ekranı yatay tutuş için tasarlanmıştır. Dikey tutuşta ekran kullanıcıdan telefonu çevirmesini ister.

## Uygulama simgesi

Simge ürün sahibinin verdiği suluboya logodur (krem, lacivert, turuncu). Uygulamanın içi mor ve beyazdır. İki kimlik aynı paleti paylaşmaz. Logo ayrıntılı bir çizimdir ve küçük boyda okunması zordur. Bu karar marka sahibine aittir. Uygulama logoyu açılış ekranında ve Profil sekmesinde küçük boyda gösterir.
