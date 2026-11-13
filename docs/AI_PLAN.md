# VolleyStat yapay zekâ planı

**Tarih:** 12 Kasım 2026 · **Kaynaklar:** V4 devir raporu, `docs/API_V1.md`, `docs/GAP_ANALYSIS.md`, `ml/README.md`

Bu belge maç videosunu istatistiğe çeviren yapay zekânın mühendislik planını anlatır. Belgenin iki okuru vardır: ürün sahibi ve işi sürdürecek mühendis. Depoda eğitilmiş model yoktur. Bu belge VolleyStat için hiçbir doğruluk sayısı vermez, çünkü kimse böyle bir ölçüm yapmadı. Belgedeki eşikler V4 raporunun hedefleridir ve ölçüm sonucu değildir. Belge başka depoların kendi ölçüm sayılarını da aktarmaz, çünkü o sayılar başka görüntülerden gelir.

Lisans notlarındaki iki işaret şunu söyler:

- **(okundu):** Bu belgeyi yazan oturum lisans dosyasını ya da README'nin lisans bölümünü 12 Kasım 2026'da projenin GitHub deposundan okudu.
- **(doğrulanmalı):** Bilgi bir arama özetinden ya da önceki bilgiden gelir. Ekip lisans metnini kendisi okumadan bu bilgiye güvenmez.

Bir deponun kod lisansı o deponun ağırlık dosyalarını ve eğitim verisini kapsamayabilir. Ekip bir ağırlığı ürüne almadan önce kod, ağırlık ve eğitim verisi lisanslarını ayrı ayrı denetler (risk R14). VolleyStat para kazanmayı hedefler. Bu yüzden "yalnız araştırma" ya da "ticari olmayan kullanım" koşulu taşıyan her kaynak ürünün dışında kalır.

## 1. Bugünkü durum

| Parça | Durum | Açıklama |
|---|---|---|
| Dosya doğrulama | Var, testli | `backend/app/media.py` kullanıcının yüklediği her dosyayı ffprobe ile inceler: kapsayıcı, codec, süre, çözünürlük ve kare hızı. Sunucu sahneyi ölçmez. Saha, top ve ışık alanları `NOT_MEASURED` kalır. |
| Ralli baseline | Var, gerçek maçta ölçüm yok | `ml/volleystat_ml/segmenter.py` videodaki hareket enerjisinden ralli aralığı önerir. Algoritma eğitilmiş bir model değildir. Testler yalnız sentetik ffmpeg kliplerini kullanır. Kimse algoritmayı gerçek maçta ölçmedi. |
| Ölçüm ve veri araçları | Var, sentetik veriyle testli | `ml/` paketi etiket denetimini, maç grubuna göre ayrımı, sızıntı denetimini ve ralli sınırı metriklerini sunar (bölüm 4.5). |
| Önce inceleme akışı (review-first) | Var, testli | Arka uç 0.4 akışı çalıştırır (`docs/API_V1.md`): Her ralli önerisi bir `RALLY_WINNER` kartı açar, kullanıcı her rallinin kazananını kendisi seçer ve sunucu rapora yalnız kullanıcı kararını yazar. Testler akışı sentetik kliple ve uydurma aralık üreten `demo` analizörüyle uçtan uca dener. Kimse akışı gerçek maç videosuyla denemedi. Analizör seçilmeyen sunucu analiz isteğine `503 MODEL_NOT_CONFIGURED` döner. |
| Analizör arayüzü | Var | `backend/app/analyzers/` üç türü ayırır: `MOCK` (uydurma çıktı, yalnız arayüz denemesi), `BASELINE` (ölçümü olmayan gerçek algoritma) ve `MODEL` (ölçümü kayıtlı eğitilmiş model). Bugün `MODEL` türünde analizör yok. |

Şunlar yok:

- Eğitilmiş model, oyuncu tespiti, top takibi, saha tespiti, kazanan önerisi, aksiyon tanıma ve forma numarası okuma yok.
- Kalibre güven puanı yok. Sözleşme bu yüzden her öneride `confidence` alanını `null` tutar.
- Gerçek maç videosu, gold etiket ve kilitli test kümesi yok.
- Ölçümü olan kapı yok. G0–G8 kapılarının hepsi `NOT_MEASURED` durumundadır.
- GPU işçisi, üretim iş kuyruğu ve maliyet ölçümü yok.

## 2. Bileşenler

Ortak varsayım şudur: Kullanıcı tek telefonu arka çizginin arkasına, bulabildiği en yüksek noktaya sabitler ve yatay çeker. V4 kamera standardı 1080p çözünürlüğü, 60 fps kare hızını tercih eder ve 30 fps kare hızını kabul eder. Bu açıda yakın takım büyük görünür ve sırtını kameraya döner. Uzak takım küçük görünür ve file bu takımı kısmen kapatır.

Her bileşen yalnız öneri üretir. Bir bileşen emin olamadığı yerde `UNKNOWN` ve bir neden kodu döner. Sunucu öneriyi, kullanıcı kararını ve rapordaki değeri ayrı kayıtlarda tutar.

Aşağıdaki kaynakların lisansı kapalı kaynak ticari ürünü kısıtlar. AGPL ticari kullanımı yasaklamaz. AGPL, yazılımı ağ üzerinden sunan tarafın hizmetin bütün kaynak kodunu aynı lisansla açmasını ister. Ekip tablodaki AGPL ve GPL kaynaklarını yalnız ürün sahibi bütün kodu aynı lisansla açmaya karar verirse yeniden değerlendirir. Ekip ticari olmayan kullanım koşulu taşıyan kaynakları hiçbir durumda ürüne almaz.

| Kaynak | Lisans | Not |
|---|---|---|
| Ultralytics YOLO ailesi (`ultralytics/ultralytics`) | AGPL-3.0 ya da ücretli Enterprise lisansı (okundu) | Arama özetine göre Ultralytics, kullanıcının bu kodla eğittiği ağırlıkları da AGPL kapsamında sayar (doğrulanmalı). Proje ücretli abonelik önermez. |
| YOLOv10 (`THU-MIG/yolov10`) | AGPL-3.0 (okundu) | |
| YOLOv9 (`WongKinYiu/yolov9`) | GPL-3.0 (okundu) | GPL copyleft bir lisanstır ve türev kodu aynı lisansla açmayı şart koşar. |
| YOLO-NAS ağırlıkları (`Deci-AI/super-gradients`) | Deci'nin özel lisansı (okundu) | Lisans ayrı bir anlaşma olmadan ticari kullanımı yasaklar. Deponun kodu Apache 2.0 lisanslıdır. |
| RF-DETR Atto, Femto, Pico, XL, 2XL | Roboflow PML 1.0 (okundu) | Lisans bir Roboflow platform planı ister. Nano, Small, Medium ve Large boyutları bu kısıtı taşımaz. |
| BoxMOT (`mikel-brostrom/boxmot`) | AGPL-3.0 (okundu) | |
| VideoMAE (`MCG-NJU/VideoMAE`) | CC BY-NC 4.0 (okundu) | Lisans yalnız ticari olmayan kullanıma izin verir. |
| `mkoshkina/jersey-number-pipeline` | CC BY-NC 3.0 (okundu) | Ekip bu depodan yalnız fikir alır. |
| VolleyVision (`shukkkur/VolleyVision`) | Lisans dosyası AGPL-3.0, README rozeti CC BY-NC-ND 4.0 (okundu) | İki beyan birbiriyle çelişir ve ikisi de kısıtlıdır. |
| T-DEED, `SoccerNet/sn-gamestate` | GPL-3.0 (okundu) | |
| SportsMOT veri kümesi | CC BY-NC 4.0 ve yarışma koşulları (README'de okundu) | Depo ayrı bir lisans dosyası taşımaz. |

### 2.1 Oyuncu tespiti

- **Çıktı:** Bileşen örneklediği her karede saha içindeki her oyuncu için bir kutu, bir tespit puanı ve kareler boyunca sabit kalan bir `track_id` üretir. Bileşen her izi saha yarısına göre bir tarafa bağlar. Bileşen oyuncunun kim olduğunu söylemez (2.6).
- **En basit yaklaşım:** Ekip hazır bir kişi tespit modelini ince ayar yapmadan saniyede 5 ile 10 kare arasında çalıştırır. Ekip saha çokgeninin dışında kalan kutuları (seyirci, yedek oyuncu, hakem, yan saha) atar. Bir izleyici kalan kutuları kareler arasında bağlar.
- **Hazır adaylar:** RF-DETR Nano, Small, Medium ve Large (kod ve bu boyutların ağırlıkları Apache 2.0, okundu). RT-DETR, D-FINE, DEIM, YOLOX ve MMDetection içindeki RTMDet (kod Apache 2.0, okundu; ağırlık lisansı doğrulanmalı). torchvision Faster R-CNN (kod BSD 3-Clause, okundu; ağırlık lisansı doğrulanmalı). Önceki bilgiye göre yazarlar bu tespit ağırlıklarının çoğunu COCO ile eğitti. Arama özetine göre COCO etiketleri CC BY 4.0 lisanslıdır ve görseller Flickr koşullarına bağlıdır (doğrulanmalı). İzleyiciler: ByteTrack, BoT-SORT ve OC-SORT (MIT, okundu), `roboflow/trackers` (Apache 2.0, okundu).
- **Veri:** İlk deneme eğitim verisi istemez. Ölçüm için anotatörler ürün sahibinin videolarından seçtikleri karelerde oyuncu kutularını çizer (T2). Ekip ince ayar kararını bu ölçümden sonra verir.
- **Ölçüm ve kapı:** Ekip kutu düzeyinde precision ve recall (IoU 0,5), kare başına oyuncu sayısı hatası ve iz için IDF1 ile HOTA ölçer. Ekip yakın ve uzak yarıyı ayrı raporlar. V4 raporu bu bileşene ayrı bir kapı tanımlamaz. Bileşen G4 (oyuncu) ve G5 (aksiyon) kapılarına hizmet eder.
- **Ana risk:** File uzak takımı kapatır ve uzak oyuncular küçük kalır. Oyuncular file önünde üst üste biner ve izleyici kimlikleri karıştırır (R02).
- **Gerçek video olmadan:** Ekip yalnız tesisatı yazar: model sarmalayıcı, saha filtresi, çıktı şeması ve sentetik karelerle testler. Ekip gerçek video olmadan ölçüm yapamaz ve model seçemez.

### 2.2 Top tespiti ve takibi

- **Çıktı:** Bileşen her karede topun merkezini ve görünürlük durumunu üretir. Bileşen topu bulamadığı karede `UNKNOWN` ve bir neden kodu döner (`BALL_OCCLUDED`, `SMALL_BALL`, `OUT_OF_FRAME`, `MOTION_BLUR`).
- **En basit yaklaşım:** İlk sürüm topa bağlı değildir. Ralli bölümleme ve inceleme kartları top olmadan çalışır. Top denemesinde ekip art arda kareleri birlikte okuyan hazır bir ısı haritası modelini ürün sahibinin videosunda çalıştırır ve sonucu ölçer. Ekip tek karelik tespite güvenmez, çünkü hızlı top karede bulanık bir çizgiye dönüşür. Bir yörünge süzgeci fizik olarak tutarsız noktaları atar.
- **Hazır adaylar:** WASB-SBDT (kod MIT, okundu) bir voleybol ağırlığı sunar. Yazarlar bu ağırlığı YouTube videolarından derledikleri Volleyball Dataset ile eğitti, bu yüzden ağırlığın ticari kullanımı doğrulanmalı. `asigatchov/fast-volleyball-tracking-inference` deposu VballNet ONNX modellerini sunar (kod ve depodaki ağırlıklar MIT, okundu). README bir model sürümünün `yolo26n` kodlayıcısı kullandığını yazar, bu sürümün AGPL etkisi ve eğitim verisinin hakları doğrulanmalı. Yazarlar TrackNetV3 modelini (`qaz812345/TrackNetV3`, ağırlıklar dahil MIT, okundu) badminton topuyla eğitti, bu yüzden model voleybol için ince ayar ister. Arama özetine göre TrackNetV4 ve TrackNetV5 yazarları modelleri voleybolda sınamadı (doğrulanmalı). Roboflow Universe birkaç voleybol topu veri kümesini CC BY 4.0 olarak listeler (doğrulanmalı).
- **Veri:** Anotatörler seçtikleri rallilerde topun merkezini kare kare işaretler. İlk deneme gereken kare sayısını gösterir. V4 raporu T1 katmanında topun kritik anlarını ister.
- **Ölçüm ve kapı:** Ekip piksel toleranslı kare düzeyi precision, recall ve F1 ölçer. Ekip ayrıca bileşenin ralli süresinin ne kadarında topu bulduğunu ve en uzun kayıp süresini ölçer. Ekip 30 fps ve 60 fps kayıtlarını ayrı ölçer (SP-02). V4 raporu ayrı bir kapı tanımlamaz. B3 deneyi bu bileşenin G3 ve G5 kapılarına katkısını ölçer.
- **Ana risk:** Bu bileşen raporun en yüksek teknik riskini taşır (R01). Top karede birkaç piksel kalır, tavana doğru kadrajdan çıkar ve beyaz duvarın ya da lambanın önünde kaybolur. Yazarlar hazır modelleri çoğunlukla yayın görüntüsüyle eğitti. Kimse bu modelleri amatör salonda telefon görüntüsüyle ölçmedi.
- **Gerçek video olmadan:** Ekip yalnız çıktı şemasını ve yörünge süzgecini yazar. Sentetik bir nokta gerçek top hakkında bilgi vermez.

### 2.3 Saha tespiti

- **Çıktı:** Bileşen sahanın dört köşesini, file çizgisinin iki ucunu, görüntüden saha düzlemine homografiyi ve bir kalite durumunu üretir. Hangi yarının `HOME` olduğu bilgisi maç kaydından gelir. Yakın yarı `HOME` demek değildir, çünkü takımlar set arasında yer değiştirir.
- **En basit yaklaşım:** Kullanıcı videonun bir karesinde dört köşeye ve filenin iki ucuna dokunur (S07 ekranı). Kamera sabit durduğu için tek işaretleme bütün videoya yeter. Sunucu videonun birkaç anındaki kenar görüntüsünü ilk kareyle karşılaştırır. Kamera kaydıysa sunucu kullanıcıdan yeni işaret ister. Bu yaklaşım model istemez.
- **Hazır adaylar:** Oturum voleybol sahası için yerleşik bir hazır model bulmadı. Roboflow Universe birkaç saha anahtar noktası veri kümesini CC BY 4.0 olarak listeler (doğrulanmalı). Ekip bu kümelerin içeriğini ve kamera açılarını bilmiyor. OpenCV (Apache 2.0, okundu) homografi ve çizgi bulma için yeter.
- **Veri:** Elle işaretleme veri istemez. Otomatik tespit için anotatör her videoda tek karenin köşelerini işaretler.
- **Ölçüm ve kapı:** Ekip iki anotatörün köşe işaretleri arasındaki piksel farkını ölçer. Otomatik tespitte ekip köşe hatasını ve saha çokgeni IoU değerini ölçer. Kapı G1'dir: Pilot videoların en az %80'i işlenebilir bir saha ve eksiksiz bir yükleme verir.
- **Ana risk:** Alçak bir arka çizgi açısında uzak köşeler kadrajın dışında kalır ya da oyuncular köşeleri kapatır. Salondaki basketbol ve hentbol çizgileri voleybol çizgilerine karışır. Biri tripoda çarpar ve homografi geçersiz kalır.
- **Gerçek video olmadan:** Ekip bu bileşeni şimdi başlatabilir. Ekip `PATCH /videos/{id}/court-roi` sözleşmesini, homografi hesabını, saha içi filtresini ve işaretleme ekranını sentetik görüntüyle yazar ve test eder. Yalnız G1 ölçümü gerçek video ister.

### 2.4 Ralli bölümleme

- **Çıktı:** Bileşen her ralli için `startMs` ve `endMs` değerlerini üretir. Bileşen kazanan önermez.
- **En basit yaklaşım:** Mevcut hareket enerjisi baseline'ı ilk adaydır. Ekip sonraki adımları tek tek ekler: Enerjiyi saha çokgeniyle sınırlar (B2), sonra top uçuşu sinyalini ekler (B3). V4 raporu B1 deneyine sesi de koyar (düdük). Kamera standardı sesi varsayılan olarak kapalı tutar, bu yüzden ekip sesi yalnız kullanıcının sesle kaydettiği videoda dener.
- **Hazır adaylar:** Bu adım hazır model istemez. `fast-volleyball-tracking-inference` deposu top izinden ralli çıkaran bir betik içerir (MIT, okundu). Ekip bu betiği B3 deneyinde karşılaştırma olarak dener. Roboflow Universe bir "Volleyball Rally Classification" veri kümesi listeler (lisans doğrulanmalı).
- **Veri:** İki anotatör önce 20–50 rallilik gold mini kümeyi etiketler (T0, B0). Ekip sonra farklı maçlardan 100'den fazla ralli toplar.
- **Ölçüm ve kapı:** `evaluate` komutu tahmini gold ralliyle ±1000 ms toleransla bire bir eşler. Komut precision, recall, F1, başlangıç ve bitiş hatasının medyanını ve 90. yüzdeliğini ve aralık IoU değerini yazar. Kapı G2'dir: ±1 saniye toleransta F1 en az 0,90. Baseline bu kapıyı geçemezse ralli bölme ve birleştirme önce inceleme kipinde kalır.
- **Ana risk:** Oyuncular ralli aralarında da hareket eder (rotasyon, kutlama, top toplama, ısınma). Servis hatası gibi kısa bir ralli 2 saniyenin altında kalır ve baseline o ralliyi atar. `ml/README.md` bu koşulların tam listesini verir.
- **Gerçek video olmadan:** Tesisat hazırdır ve sentetik kliplerle testlidir. Ölçüm gerçek video ve gold etiket ister.

### 2.5 Aksiyon tanıma

- **Çıktı:** Bileşen her temas için zamanı, türü (`SERVE`, `RECEPTION`, `SET`, `ATTACK`, `BLOCK`, `DIG`), tarafı ve sonuç kodunu üretir. Oyuncu ataması ayrı bir çıktıdır. İlk hedef yalnız servis ve ralliyi bitiren aksiyondur.
- **En basit yaklaşım:** Kullanıcı ralliyi bitiren aksiyonu `TERMINAL_ACTION` kartında seçer. Sözleşme bu kartı zaten tanımlar. Sonraki adım bir kuraldır: Rallinin ilk teması servistir. Top izi geldikten sonra ekip topun yön değişimlerinden temas adayı çıkarır ve temasın sırasına ve fileye uzaklığına göre tür önerir. Piksellerden sınıflayan bir model en son adımdır ve sınıf başına yeterli etiket ister.
- **Hazır adaylar:** PySlowFast içindeki SlowFast ve X3D, MMAction2 ve `tensorflow/models` içindeki MoViNet genel video omurgalarıdır (kod Apache 2.0, okundu). Önceki bilgiye göre yazarlar bu omurgaların hazır ağırlıklarının çoğunu Kinetics ile eğitti. Kinetics videoları YouTube kaynaklıdır ve arama özetleri veri lisansı konusunda çelişir, bu yüzden ağırlıkların ticari kullanımı doğrulanmalı. E2E-Spot (`jhong93/spot`, üç maddeli BSD, okundu) olay yakalama kodudur. Voleybol veri kümeleri: Volleyball Dataset (kod BSD 2-Clause, okundu; README veri için lisans yazmaz ve videolar YouTube kaynaklıdır, doğrulanmalı), VNL-STES (arama özetine göre profesyonel maçlar ve görüntüler Volleyball World izniyle; depo lisans dosyası taşımaz, doğrulanmalı) ve VREN (doğrulanmalı). Bu kümelerin hepsi yayın kamerası görüntüsü içerir ve amatör salonu temsil etmez.
- **Veri:** Anotatörler önce servisi ve bitiren aksiyonu etiketler (T1), sonra bütün temasları etiketler (T3). Ekip sınıf başına örnek sayısını devir paketindeki `13_SINIF_DAGILIMI.csv` şablonuyla izler.
- **Ölçüm ve kapı:** Ekip sınıf bazında F1, macro-F1 ve karışıklık matrisi raporlar. Ekip olayın net göründüğü klipleri ve bütün uygun klipleri iki ayrı raporda verir. Kapı G5'tir: İlk açılacak üç sınıfta macro-F1 en az 0,80. Bir sınıf kapıyı geçemezse ekip o sınıfı otomasyondan çıkarır.
- **Ana risk:** Bileşen top izine ve oyuncu izine dayanır ve ikisi de risklidir. Anotatörler karşılama ve pas kalitesinde anlaşamayabilir (R09).
- **Gerçek video olmadan:** Ekip yalnız kural motorunu ve şemayı yazar. Model işi gerçek etiket gelmeden başlamaz.

### 2.6 Oyuncu kimliği

- **Çıktı:** Bileşen her iz için tarafı, bir forma numarası adayını ya da `UNKNOWN_PLAYER` değerini ve kanıt karelerini üretir. Sunucu oyuncu adı tutmaz. Bileşen yüz tanıma kullanmaz (ADR-010).
- **En basit yaklaşım:** Kullanıcı sayıyı yapan oyuncuyu `SCORER` kartında forma numarasıyla seçer. Taraf bilgisi saha yarısından gelir ve model istemez. Otomatik adımda bileşen bir izin en net karelerinden gövdeyi kırpar, bir metin tanıma modeliyle numarayı okur, iz boyunca oy toplar ve yalnız kadroda bulunan numarayı kabul eder. Arka çizgi açısı yakın takımın sırt numarasını kameraya gösterir. Uzak takımın göğüs numarası küçük kalır.
- **Hazır adaylar:** PARSeq, PaddleOCR ve EasyOCR genel metin tanır (kod Apache 2.0, okundu; hazır ağırlıkların lisansı doğrulanmalı). MMPose ve ViTPose gövde bölgesini bulmaya yarar (kod Apache 2.0, okundu). `jersey-number-pipeline` ve SportsMOT kısıtlıdır (tablo). SoccerNet Jersey veri kümesinin deposu kökünde lisans dosyası taşımaz, kullanım koşulları doğrulanmalı.
- **Veri:** Anotatörler iz ve numara etiketlerini maç kadrosuyla birlikte üretir (T2). Ekip önce ışığı ve forma kontrastı iyi kliplerden başlar.
- **Ölçüm ve kapı:** Ekip `player_auto_precision` ve `player_coverage` değerlerini mesafeye ve kapanmaya göre katmanlı ölçer (SP-04). Kapı G4'tür: Bileşenin atadığı oyuncularda precision en az 0,95 olur ve ekip coverage değerini açıkça raporlar. Bileşen kapıyı geçemezse oyuncu `UNKNOWN_PLAYER` kalır ve rapor yalnız takım metriği verir.
- **Ana risk:** Kamera uzaktaki numarayı okutmaz. İki oyuncu file önünde yer değiştirir ve izleyici kimlikleri karıştırır. Oyuncu değişikliği kadrajın dışında olur (R02).
- **Gerçek video olmadan:** Ekip yalnız kadro kısıtını ve oylama mantığını yazar. Okunabilirlik sorusunu yalnız gerçek video yanıtlar.

### 2.7 Ralli kazananı

- **Çıktı:** Bileşen `HOME`, `AWAY` ya da `UNKNOWN` değerini, kalibre bir güven değerini ya da `null` değerini, kanıt klibini ve belirsizlik nedenini üretir.
- **En basit yaklaşım:** Bugün kazananı kullanıcı seçer. Bu belgenin ilk otomatik önerisi bir kuraldır ve kimse bu kuralı ölçmedi: Ralliyi kazanan takım sonraki servisi atar. Bileşen her rallinin başında servisi hangi yarının attığını bulur ve önceki rallinin kazananını buradan çıkarır. Arka çizgi açısında yakın tarafın servis atan oyuncusu kameranın hemen önünde durur. Kural setin son rallisini, hakemin tekrarlattığı ralliyi ve bölümlemenin kaçırdığı ralliyi çözemez. Bileşen bu durumlarda `UNKNOWN` döner. Tutarlılık kuralları (B5) skorla çelişen öneriyi incelemeye gönderir. Amatör salonların çoğunda skor tabelası kadraja girmez, bu yüzden ekip ürünü tabela okumaya (B4) bağlamaz.
- **Hazır adaylar:** Oturum bu görev için hazır bir model bulmadı.
- **Veri:** T0 etiketleri yeter: servis tarafı, kazanan ve kanıt türü.
- **Ölçüm ve kapı:** Ekip `selective_precision`, `auto_coverage`, `UNKNOWN` oranı, ECE, Brier ve yüksek güvenli yanlış karar sayısını ölçer. Kullanıcı kararı model başarısına girmez. Kapı G3'tür: Otomatik kararda precision en az 0,95 ve coverage en az 0,60. Bileşen kapıyı geçemezse bütün skorlar önce inceleme kipinde kalır.
- **Ana risk:** Bileşen yanlış bir skoru yüksek güvenle verebilir (R04). Sonraki servis kuralı bölümleme hatasını taşır: Kaçan bir ralli sonraki atamaları kaydırır.
- **Gerçek video olmadan:** Ekip kural motorunu ve tutarlılık denetimini sentetik ralli dizileriyle yazar ve test eder. Servis tarafı tespiti ve G3 ölçümü gerçek video ister.

### 2.8 İstatistik üretimi

- **Çıktı:** Bileşen `docs/API_V1.md` 7. bölümündeki raporu üretir. Her değer `{value, status}` biçimindedir. Rapor her ölçü için `eligibleN`, `observedN` ve `verifiedN` sayılarını taşır.
- **En basit yaklaşım:** Saf ve deterministik bir fonksiyon raporu yalnız doğrulanmış olaylardan hesaplar. Bu bileşen model içermez. Öneriler rapora girmez. Fonksiyon ölçümü olmayan değeri `null` ve `UNKNOWN` olarak yazar ve hiçbir zaman `0` yazmaz.
- **Hazır adaylar:** Bileşen hazır model istemez. Kotlin çekirdeği set ve skor kurallarını zaten içerir.
- **Veri:** Bileşen gerçek veri istemez. Testler sentetik olay dizileri kullanır.
- **Ölçüm ve kapı:** Birim testleri her kuralı sentetik olaylarla denetler. Bu bileşenin model kapısı yoktur. İlgili ürün kapısı G6'dır: 90 dakikalık videoda medyan aktif doğrulama süresi en çok 15 dakika.
- **Ana risk:** Kod bir öneriyi doğrulanmış olayla karıştırabilir ya da bilinmeyen değeri sıfır olarak yazabilir.
- **Gerçek video olmadan:** Ekip bu bileşeni tamamen bitirebilir.

## 3. Sıra

Değişmez kural şudur: Bir bileşen kendi kapısını kilitli test kümesinde geçene kadar önce inceleme kipinde kalır. Bu kipte bileşen yalnız öneri üretir, kullanıcı her öneriyi kartta onaylar ve sunucu onaysız öneriyi rapora yazmaz.

G0 (hak ve izin) ve G1 (işlenebilir saha ve yükleme) bütün deneylerin ön koşuludur. Deneyler V4 raporunun B0–B6 sırasını izler:

| Deney | Ekip neyi değiştirir | Deney hangi soruyu yanıtlar | Sonuç hangi kararı açar |
|---|---|---|---|
| B0 | İki anotatör aynı 20–50 ralliyi birbirinden bağımsız etiketler. | İnsanlar hangi düzeyde anlaşır? | Ekip etiket kılavuzunu onaylar ve test manifestini kilitler. Uyum düşükse ekip model deneyi başlatmaz ve önce kılavuzu düzeltir. |
| B1 | Ekip mevcut hareket enerjisi baseline'ını ve varsa sesi kilitli testte ölçer. | İlk otomasyon değer taşıyor mu? | G2'nin ilk ölçümü çıkar. Ekip baseline'ı öneri üretici olarak tutar ya da değiştirir. |
| B2 | Ekip saha çokgenini ekler. | Saha bilgisi sonucu ne kadar iyileştirir ve kullanıcıya kaç saniyeye mal olur? | Ekip dört köşe adımını uygulamada zorunlu yapar ya da isteğe bağlı bırakır. |
| B3 | Ekip oyuncu ve top izini ekler. | İzler kazananı ve aksiyonu iyileştiriyor mu? | Ekip top takibi için etiket ve ince ayar yatırımına karar verir. G3'ün ilk ölçümü çıkar. |
| B4 | Ekip skor tabelası okumayı ekler. | Yöntem yayın kaydına mı bağımlı? | Ekip tabela okumayı ürüne alır ya da bırakır. |
| B5 | Ekip kural tutarlılığı denetimini ekler. | Yanlış ama kesin cevapların sayısı azalıyor mu? | Ekip otomatik yükseltmenin ön koşulu olacak kuralları belirler. |
| B6 | Ekip eşikleri ve inceleme sırasını değiştirir. | Kullanıcının harcadığı her dakika ne kadar kalite kazandırır? | G3 ve G6 kararı çıkar: Hangi karar otomatik yükselir, hangi karar kartta kalır. |

Oyuncu (G4) ve aksiyon (G5) deneyleri B6'dan sonra aynı protokolle gelir. Her deney şu kurallara uyar:

1. Her deney tek bir şeyi değiştirir. Ekip böylece kazancın hangi bileşenden geldiğini bilir.
2. Ekip parametreyi yalnız validation kümesinde seçer. Ekip test kümesini yalnız kapı kontrolünde açar.
3. Ekip 30'dan az gold rallide oran yazmaz. Ekip ham sayıyı (örneğin `18/20`) ve güven aralığını yazar.
4. `UNKNOWN` oranını yükselterek precision kazanan bir aday coverage kapısını da geçmek zorundadır.
5. Ekip her deneyi raporun şablonuyla kaydeder: `experiment_id`, `dataset_version`, `test_manifest_sha256`, `model_version`, `eligible_n`, `TP/FP/FN`, `decision`, `signed_off_by`.

## 4. Veri

### 4.1 Ürün sahibinin çekeceği videolar

- **Kaç maç:** İlk adım için bir ya da iki maç yeter. V4 raporu keşif için 5–10 farklı izinli video ve pilot genişleme için 20–30 farklı maç öngörür. Rapor bu sayıları planlama örneği sayar ve veri yeterliliği garantisi saymaz.
- **Açı:** Ürün sahibi telefonu arka çizginin ortasının arkasına, bulabildiği en yüksek sabit noktaya tripodla kurar. Çekim yatay, 1080p ve mümkünse 60 fps olur. Kadraj iki arka çizgiyi, fileyi ve servis alanını içerir. Ürün sahibi maçtan önce iki dakikalık bir deneme çeker ve dosyayı açıp oynatır.
- **Çeşitlilik:** Farklı salon, farklı takım ve farklı forma rengi modelin sahneyi ezberlemesini önler. Ürün sahibi her video için salonu ve kamera kurulumunu envantere yazar.
- **İzin:** Ürün sahibi sahadaki herkesten üç ayrı amaç için ayrı izin alır: analiz, model eğitimi ve paylaşım. 18 yaşından küçük oyuncu için veli izni gerekir. İzni eksik video eğitim kümesine girmez (G0). Bir KVKK uzmanı nihai izin metnini onaylar.
- **Saklama:** Depo herkese açıktır. Kimse depoya video, kişi verisi taşıyan etiket ya da model ağırlığı koymaz.

### 4.2 Etiket katmanları

| Katman | Anotatör neyi etiketler | Katman hangi bileşeni besler |
|---|---|---|
| T0 Pilot | İlk 20–50 rallide ralli sınırı, servis tarafı, kazanan, kanıt türü ve `UNKNOWN` nedeni. Kutu çizimi gerekmez. | Ralli bölümleme, ralli kazananı, B0 |
| T1 Core | Farklı maçlardan 100'den fazla rallide servis, bitiren aksiyon, takım ataması, topun kritik anları ve skor kanıtı. | Kazanan, bitiren aksiyon, top |
| T2 Player | Oyuncu izleri, forma numaraları ve temas bazında kimlik. Anotatör önce ışığı iyi kliplerden başlar. | Oyuncu tespiti, oyuncu kimliği |
| T3 Full Action | Karşılama, pas ve savunma kalitesi. Anotatörler anlaşabiliyorsa ekip bu katmanı açar. | Aksiyon tanıma |

### 4.3 İki anotatör kuralı

1. Ekip test manifestini önceden dondurur ve iki anotatöre aynı kaynağı ve aynı zaman kodunu verir.
2. Anotatörler birbirinin etiketini görmez. Kimse gold etiket hazırlarken anotatöre model önerisi göstermez.
3. Ekip ralli çiftlerinde sınır farkını ve aralık IoU değerini, kategorik etiketlerde uyum oranını ve Cohen's kappa değerini ölçer.
4. Üçüncü bir kişi uyuşmazlığı kanıt klibiyle çözer. Karar `ADJUDICATED`, `UNRESOLVABLE` ya da `NEEDS_MORE_EVIDENCE` olur. Kimse zorla bir sınıf seçmez.
5. Nihai test yalnız `DOUBLE_AGREED` ve `ADJUDICATED` etiketleri kullanır. Tek anotatörlü etiket ayrı bir katmanda kalır.

### 4.4 Maç grubuna göre ayrım

- Ekip videoları maç, video ve salon düzeyinde ayırır. Aynı maçın kareleri train ve test kümelerine birlikte girmez.
- Aynı maçın farklı kamerayla çekimi, farklı kodlaması ve parçaları aynı `match_group_id` değerini taşır.
- Ekip test manifestini ilk ölçümden önce kilitler (`TEST_LOCK_V1`). Değişen test kümesi yeni bir sürüm olur.
- Ekip düşük kaliteli videoyu testten sessizce çıkarmaz. Ekip her elemeyi nedeniyle raporlar.

### 4.5 `ml/` içindeki hazır araçlar

| Komut (`python -m volleystat_ml …`) | Görev |
|---|---|
| `validate-labels` | Komut etiket dosyasını V3 şemasına ve alan kurallarına göre denetler: zaman aralığı, sıra, çakışma, `UNKNOWN` için neden ve tek bitiş olayı. |
| `make-splits` | Komut videoları `match_group_id` değerine göre train, validation ve test kümelerine ayırır. Komut `training_allowed` değeri `true` olmayan videoyu train kümesine koymaz ve kilit manifestini özetiyle yazar. |
| `check-leakage` | Komut iki kümede birden görünen maç grubunu ve SHA-256 değerini bulur. Komut manifestin kilitten sonra değiştiğini de bildirir. |
| `evaluate` | Komut ralli sınırı metriklerini ve %95 bootstrap güven aralığını hesaplar. Komut 30'dan az gold rallide oran yazmaz. |
| `segment` | Komut hareket enerjisi baseline'ını bir videoda çalıştırır. |

Şemalar `ml/schemas/` klasöründedir: `ANNOTATION_SCHEMA_V3.json`, `01_VIDEO_ENVANTERI.csv`, `03_RALLI_GOLD.csv` ve `11_TEST_MANIFEST.csv`. Paket şu ölçümleri henüz içermez: anotatör uyumu, kazanan metrikleri, kalibrasyon, aksiyon ve oyuncu metrikleri. Ekip bu araçları sentetik veriyle gerçek video gelmeden yazabilir.

### 4.6 İki değişmez veri kuralı

- **Kullanıcı düzeltmesi kendiliğinden eğitim verisi olmaz.** Sunucu düzeltmeyi `USER_CORRECTION` kaynağıyla ayrı bir kayıt olarak tutar. Bir düzeltme eğitim kümesine ancak üç koşulla girer: Kullanıcı eğitim için ayrı izin verdi, video sahibi ve görünen kişiler için hak durumu uygun ve ikinci bir anotatör düzeltmeyi kalite kontrolünden geçirdi. Eğitim izni varsayılan olarak kapalıdır ve sunucu bugün `allowTraining: true` isteğini reddeder.
- **Ekip YouTube videosunu eğitim için indirmez.** Tek istisna, ekibin o video için indirme ve eğitim hakkını yazılı olarak doğruladığı durumdur. Bir bağlantının herkese açık olması hak vermez. Arama özetine göre YouTube hizmet koşulları izinsiz indirmeyi ve otomatik erişimi yasaklar (doğrulanmalı). `tools/import_youtube.py` aracı bir `--rights` gerekçesi ister ve gerekçeyi kaydeder. Araç gerekçenin doğruluğunu denetlemez. Bu denetim ekibin işidir.

## 5. Maliyet ve altyapı

Bu belge fiyat vermez, çünkü kimse aşağıdaki değerleri ölçmedi ve hiçbir sağlayıcıdan teklif almadı. Ekip bir fiyat söylemeden önce 90 dakikalık gerçek bir kayıtta şu alanları ölçer:

| Alan | Alan neyi söyler |
|---|---|
| `duration_sec`, `input_bytes`, `original_size_gb`, `proxy_size_gb` | Videonun süresi ve boyutu |
| `upload_gb`, `network_gb`, `egress_gb` | Yükleme ve indirme trafiği |
| `storage_gb_days` | Depolama boyutu ile saklama gününün çarpımı |
| `decode_fps`, `frames_processed` | Çözme hızı ve analizin işlediği kare sayısı |
| `GPU_type`, `gpu_s`, `cpu_s` | Donanım türü ve analizin harcadığı işlem süresi |
| `job_retry`, başarısız iş oranı | Yeniden deneme ve hata maliyeti |
| `third_party_API_calls` | Dış hizmet çağrıları |
| `user_review_seconds` | Kullanıcının doğrulamaya harcadığı süre |
| `support_incidents` | Destek yükü |

`worker_hourly_usd` gibi birim fiyatlar ölçümden gelmez. Ekip bu değerleri sağlayıcının güncel teklifinden alır. V4 raporunun formülü ölçümleri birim fiyatlarla çarpar ve toplar: yükleme, depolama, GPU, CPU, dış çağrı, çıkış trafiği ve inceleme işletme maliyeti. G7 kapısı bu toplamı ürün sahibinin bütçesiyle karşılaştırır.

İlk pilot ücretli abonelik olmadan şu seçeneklerle çalışabilir:

- **Kendi bilgisayarı:** Baseline GPU istemez. Algoritma saniyede 5 kareyi 96x54 gri tonda işler. Sunucu ve analiz mühendisin ya da ürün sahibinin bilgisayarında çalışır.
- **CPU üzerinde hazır model:** ONNX Runtime (MIT, okundu) tespit modelini düşük kare hızında CPU üzerinde çalıştırır. Ekip gerçek süreyi ölçer ve bu belge bir süre tahmini vermez.
- **Ücretsiz not defteri ortamları:** Kaggle Notebooks ve Google Colab ücretsiz katmanı deney ve ince ayar için GPU verir. Arama özetlerine göre kotalar değişir ve sağlayıcılar süre garantisi vermez (doğrulanmalı). Ekip oyuncu görüntüsü taşıyan bir videoyu üçüncü taraf bir hizmete yüklemeden önce izin kapsamını ve verinin gideceği ülkeyi denetler.
- **Etiketleme:** CVAT (MIT, okundu) ve Label Studio (Apache 2.0, okundu) yerel bilgisayarda ücretsiz çalışır. Ralli sınırı etiketi için `03_RALLI_GOLD.csv` şablonu ve bir video oynatıcı yeter.
- **Testler:** GitHub Actions `ml/` testlerini sentetik kliplerle zaten çalıştırır.

Ücretli bulut ve GPU kararı ürün sahibine aittir. Ekip bu kararı G7 ölçümü olmadan istemez.

## 6. Sahipten beklenenler

Aşağıdaki işleri yalnız ürün sahibi yapabilir:

1. Ürün sahibi kendi çektiği bir ya da iki amatör salon maçını bölüm 4.1'deki açıyla kaydeder ve maçtan önce iki dakikalık deneme çeker.
2. Ürün sahibi sahadaki herkesten analiz, model eğitimi ve paylaşım için ayrı ayrı yazılı izin alır. Ürün sahibi 18 yaşından küçük oyuncu için veli izni alır.
3. Ürün sahibi videoları depoya koymadan mühendise teslim eder ve her video için tarihi, salonu, kamera kurulumunu ve izin durumunu bildirir.
4. Ürün sahibi voleybol bilen ikinci bir anotatör bulur. İki anotatör ilk 20–50 ralliyi birbirinden bağımsız etiketler (B0).
5. Ürün sahibi her maç için iki takımın forma numaralarını içeren kadroyu verir.
6. Ürün sahibi lisans yönünü seçer: Kod kapalı bir ticari ürün olur ya da bütün kod AGPL ile açık kalır. Bu karar bölüm 2'deki tabloyu doğrudan etkiler.
7. Ürün sahibi önce açılacak üç aksiyon sınıfını seçer (G5). Bu belge servis, hücum ve bloğu önerir, çünkü sözleşmedeki bitiren aksiyon kartı bu türleri zaten sorar.
8. Ürün sahibi izin metni ve saklama süresi için bir KVKK uzmanından görüş alır.
9. Ürün sahibi bulut, GPU ve bütçe kararını G7 ölçümünden sonra verir.

## 7. Doğrulanmamış bilgiler

Araştırma yöntemi: Oturum web aramasının özetlerini kullandı. Oturum `ultralytics.com`, `youtube.com` ve `cocodataset.org` sayfalarını açmayı denedi ve bu sayfalara ulaşamadı. Oturum yalnız GitHub'daki lisans dosyalarını ve README dosyalarını doğrudan okuyabildi. Aşağıdaki bilgiler bu yüzden doğrulama bekler:

- Oturumun lisans dosyasını ya da README lisans bölümünü okuduğu depolar şunlardır: `ultralytics/ultralytics`, `THU-MIG/yolov10`, `WongKinYiu/yolov9`, `Deci-AI/super-gradients`, `roboflow/rf-detr`, `roboflow/rf-detr_plus`, `lyuwenyu/RT-DETR`, `Peterande/D-FINE`, `ShihuaHuang95/DEIM`, `Megvii-BaseDetection/YOLOX`, `open-mmlab/mmdetection`, `pytorch/vision`, `ifzhang/ByteTrack`, `NirAharon/BoT-SORT`, `noahcao/OC_SORT`, `roboflow/trackers`, `mikel-brostrom/boxmot`, `nttcom/WASB-SBDT`, `asigatchov/fast-volleyball-tracking-inference`, `qaz812345/TrackNetV3`, `shukkkur/VolleyVision`, `opencv/opencv`, `facebookresearch/SlowFast`, `open-mmlab/mmaction2`, `tensorflow/models`, `MCG-NJU/VideoMAE`, `jhong93/spot`, `arturxe2/T-DEED`, `mostafa-saad/deep-activity-rec`, `mkoshkina/jersey-number-pipeline`, `baudm/parseq`, `PaddlePaddle/PaddleOCR`, `JaidedAI/EasyOCR`, `open-mmlab/mmpose`, `ViTAE-Transformer/ViTPose`, `SoccerNet/sn-gamestate`, `MCG-NJU/SportsMOT`, `cvat-ai/cvat`, `HumanSignal/label-studio`, `microsoft/onnxruntime`. Bu listenin dışında kalan her lisans bilgisi arama özetinden ya da önceki bilgiden gelir.
- Arama özetlerinin dayandığı başlıca sayfalar şunlardır: `ultralytics.com/license`, `roboflow.com/model-licenses/rf-detr`, `hoangqnguyen.github.io/stes`, `universe.roboflow.com`, `support.google.com/youtube/answer/15509945`, `arxiv.org/abs/2311.05237` ve `arxiv.org/abs/2409.14543`.

- Ultralytics'in kullanıcıların eğittiği ağırlıkları AGPL kapsamında saydığı bilgisi ve Enterprise lisansının koşulları arama özetinden gelir. Oturum yalnız depodaki AGPL-3.0 dosyasını ve README'nin iki seçenekli lisans bölümünü okudu.
- COCO etiketlerinin CC BY 4.0 olduğu, görsellerin Flickr koşullarına bağlı kaldığı ve Kinetics veri lisansının belirsiz olduğu bilgisi arama özetinden gelir. Hazır ağırlıkların COCO ve Kinetics verisinden geldiği bilgisi önceki bilgiden gelir. Böyle bir ağırlığın ticari kullanımı hukuki görüş ister.
- "(okundu)" işareti çoğu satırda yalnız kod lisansını kapsar. Oturum RT-DETR, D-FINE, DEIM, YOLOX, RTMDet, torchvision, SlowFast, MMAction2, MoViNet, PARSeq, PaddleOCR, EasyOCR, MMPose ve ViTPose ağırlıklarının ayrı lisansını okumadı.
- WASB-SBDT voleybol ağırlığının ve VballNet modellerinin eğitim verisi hakları belirsizdir. VballNet README'sindeki `yolo26n` kodlayıcısının AGPL etkisi belirsizdir.
- Oturum `SoccerNet/sn-jersey` ve `hoangqnguyen/stes` depolarının kökünde lisans dosyası bulamadı. Oturum yalnız yaygın dal ve dosya adlarını denedi. SoccerNet Jersey, VNL-STES, VREN ve Volleyball Dataset kullanım koşulları doğrulama bekler.
- Roboflow Universe veri kümelerinin CC BY 4.0 etiketi arama özetinden gelir. Bir kümenin etiketi, içindeki görsellerin kaynağını ve haklarını doğrulamaz.
- TrackNetV4 ve TrackNetV5 hakkındaki bilgi arama özetinden gelir. Oturum bu modellerin deposunu ve lisansını incelemedi.
- YOLOv7 lisansı tabloda yer almaz, çünkü oturum o depoyu okumadı. Önceki bilgi GPL-3.0 der (doğrulanmalı).
- YouTube hizmet koşullarının indirmeyi ve otomatik erişimi yasakladığı bilgisi arama özetinden gelir.
- Kaggle ve Colab ücretsiz kotaları arama özetinden gelir ve sağlayıcılar bu kotaları değiştirebilir.
- Bölüm 2.7'deki sonraki servis kuralı ve bölüm 2.5'teki temas sırası kuralı bu belgenin mühendislik önerisidir. Kimse bu kuralları gerçek maçta ölçmedi. Amatör salonların çoğunda skor tabelasının kadraja girmediği bilgisi de bir varsayımdır.
- Kimse hazır modellerin hiçbirini VolleyStat'ın kamera açısında ölçmedi. Arka çizgi açısında forma numarasının okunabilirliği (SP-04), topun görünürlüğü (SP-01, SP-02) ve skor tabelası olmayan videoda kazanan güveni (SP-03) açık sorulardır.
- Bölüm 2'deki bütün eşikler (0,90, 0,95, 0,60, 0,80, 15 dakika, %80) V4 raporunun hedefleridir. Hiçbiri ölçüm sonucu değildir.
- Lisanslar zamanla değişir. Ekip bir kaynağı ürüne aldığı gün lisans metnini yeniden okur ve sürümü kaydeder.
