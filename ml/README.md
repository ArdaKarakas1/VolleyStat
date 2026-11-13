# volleystat_ml

Bu paket VolleyStat'ın mock olmayan ilk analiz bileşenini ve o bileşeni ölçecek araçları içerir. Paket, hareket enerjisine dayanan bir sezgisel algoritma (baseline) sunar. Kimse bu algoritmayı veriyle eğitmedi, yani algoritma bir model değildir.

Bu algoritmanın gerçek voleybol videosundaki doğruluğunu kimse ölçmedi. "Doğruluk durumu: gerçek maçta ölçüm yok" bölümü bu konuyu ayrıntısıyla anlatır.

## Paketin parçaları

| Dosya | Görev |
|---|---|
| `volleystat_ml/segmenter.py` | Segmenter videodaki hareket enerjisinden ralli aralığı önerir. |
| `volleystat_ml/metrics.py` | Bu modül tahmin aralıklarını gold aralıklarla karşılaştırır. |
| `volleystat_ml/labels.py` | Bu modül etiket dosyasını V3 şemasına ve alan kurallarına göre denetler. |
| `volleystat_ml/splits.py` | Bu modül videoları maç grubuna göre train, validation ve test kümelerine ayırır, sızıntı arar ve kilit manifestini yazar. |
| `volleystat_ml/cli.py` | Bu modül beş alt komutu çalıştırır. |
| `schemas/` | Bu klasör devir paketindeki şemanın ve üç CSV şablonunun değişmemiş kopyalarını tutar. |
| `tests/` | Bu klasör sentetik kliplerle çalışan testleri tutar. |

Arka uç bu paketi `backend/app/analyzers/motion_baseline.py` adaptörü üzerinden kullanır.

## Baseline ne yapar

Segmenter tek bir varsayıma dayanır: Görüntü ralli sırasında çok, ralli aralarında az değişir. Segmenter şu adımları izler:

1. Segmenter videoyu `ffmpeg` ile çözer. `ffmpeg` kareleri saniyede 5 kareye ve 96x54 gri tona indirir ve bir boru (pipe) üzerinden gönderir.
2. Segmenter art arda gelen iki kare arasındaki ortalama mutlak piksel farkını hesaplar. Bu değer o anın hareket enerjisidir.
3. Segmenter enerji dizisini 1 saniyelik medyan filtresiyle yumuşatır.
4. Segmenter eşiği klibin kendi enerji dağılımından çıkarır (logaritmik ölçekte Otsu yöntemi). Eşik enerjiyi durgun ve hareketli olmak üzere iki düzeye ayırır.
5. Segmenter histerezis uygular: Çıkış düzeyinin üstünde kalan art arda örnekler, içlerinden en az biri giriş düzeyine ulaşırsa bir aralık oluşturur.
6. Segmenter aralarında 1500 ms'den kısa boşluk kalan aralıkları birleştirir ve 2000 ms'den kısa aralıkları atar.
7. Segmenter kalan aralıkları milisaniye cinsinden döndürür.

Segmenter kareleri 32'lik parçalar hâlinde okur ve videonun tamamını belleğe almaz. Segmenter ilerlemeyi işlediği kare sayısı ve beklediği kare sayısı olarak bildirir. `ffmpeg` mesajlarını bir geçici dosyaya yazar, böylece dolan bir hata borusu çözücüyü durdurmaz. Segmenter hata ya da iptal durumunda `ffmpeg` sürecini öldürür ve sürecin bitmesini bekler.

Enerji iki düzeye net ayrılmıyorsa segmenter tahmin yürütmez ve boş liste döndürür. Sonucun `status` alanı nedeni söyler:

| `status` | Anlam |
|---|---|
| `OK` | Segmenter en az bir aralık buldu. |
| `NO_SEPARATION` | Enerji iki ayrı düzeye ayrılmadı. Durağan bir klip, baştan sona hareketli bir klip ya da çok zayıf bir hareket bu sonucu verir. |
| `TOO_SHORT` | Klip iki düzeyi kestirmeye yetecek kadar örnek içermiyor. |
| `ONLY_SHORT_RUNS` | İki düzey var, ama her hareketli bölüm `min_rally_ms` değerinden kısa sürüyor. |

Segmenter topu, sahayı, fileyi ve skoru tanımaz. Segmenter bu yüzden kazanan önermez ve güven değeri üretmez. Her öneri `proposedWinner: "UNKNOWN"`, `confidence: null` ve `uncertaintyReasons: ["NO_WINNER_MODEL"]` değerlerini taşır.

Çıktıdaki `energy` alanı iki düzeyin ham enerji değerini ve oranını verir. Bu sayılar parametre ayarlayan kişiye yardım eder. Hiçbir ekran bu sayıları güven puanı olarak göstermemelidir.

## Doğruluk durumu: gerçek maçta ölçüm yok

Bu depoda gerçek maç görüntüsü yoktur. Testler `ffmpeg` programının `lavfi` kaynaklarından ürettiği sentetik klipleri kullanır. Birinci klipte beyaz bir kutu mavi bir zeminde önceden belirli saniyelerde hareket eder ve kalan sürede durur. İkinci klip durağan ve hareketli test desenlerini aynı saniyelerde art arda dizer.

Sentetik testler yalnızca tesisatın çalıştığını kanıtlar. Testler şunları gösterir:

- Kod videoyu çözer, enerjiyi hesaplar ve sınırlarını testin bildiği yapay bir sinyalde aralıkları bulur.
- Segmenter hareketsiz klipte ve baştan sona hareketli klipte aralık uydurmaz.
- Segmenter ilerlemeyi gerçek kare sayısıyla bildirir, `ffmpeg` sürecini her durumda kapatır ve videoyu belleğe yığmaz.
- Metrik, etiket ve küme araçları kendi kurallarını uygular.

Testler algoritmanın gerçek bir voleybol maçında ralli bulduğunu göstermez. Gerçek voleybol videosundaki doğruluğu kimse ölçmedi: Kimse bu algoritmayı gerçek maç videosunda çalıştırıp sonucu gold etiketlerle karşılaştırmadı. Bu yüzden bu belge gerçek maç için doğruluk, isabet ya da hata oranı vermez. Sentetik kliplerden çıkan sayılar gerçek maç hakkında bilgi taşımaz.

Parametrelerin varsayılan değerleri mühendislik tahminidir. Kimse bu değerleri gerçek maç videosuyla ayarlamadı.

Analizör bu nedenle `BASELINE` türünü taşır. `docs/API_V1.md` bu türü "Doğruluğu ölçülmemiştir." cümlesiyle tanımlar.

### Hareket enerjisini yanıltan koşullar

Algoritma yalnızca görüntünün ne kadar değiştiğini ölçer ve hareketi kimin ya da neyin yaptığını bilmez. Aşağıdaki koşullar gerçek bir salonda bu yaklaşımı bozar. Bu liste algoritmanın çalışma biçiminden çıkar. Bu hataların hangi sıklıkta görüleceğini kimse ölçmedi.

| Koşul | Algoritmanın yapacağı hata |
|---|---|
| Oyuncular ralli aralarında hareket eder (rotasyon, kutlama, yer değiştirme, oyuncu değişikliği, mola). | Segmenter bu hareketi ralli olarak önerir ya da iki ralliyi tek aralıkta birleştirir. |
| Bir oyuncu sahadan çıkan topu toplar ya da top sahada yuvarlanır. | Segmenter rallinin bitişini geç koyar ya da top toplamayı ayrı bir ralli sayar. |
| Kamera titrer ya da sallanır. Kullanıcının elinde tuttuğu kamera karedeki bütün pikselleri değiştirir. | Enerji ralli dışında da yükselir. Segmenter sarsıntıyı ralli sayar ya da iki düzeyi ayıramaz ve hiç aralık döndürmez. Otomatik pozlama ve odak değişimi aynı etkiyi yapar. |
| Seyirciler, yedek oyuncular, hakem ya da yan sahadaki başka bir maç kadraja girer. | Segmenter bu hareketi sahadaki oyundan ayıramaz ve ralli olarak önerir. |
| Takımlar maçtan önce ısınır. | Segmenter ısınmayı ralli olarak önerir. Uzun bir ısınma eşiği de kaydırır, çünkü segmenter eşiği klibin bütününden çıkarır. |
| Ralli çok kısa sürer ya da takımlar çok hızlı servis atar. | Servis hatası gibi bir ralli 2000 ms sınırının altında kalabilir ve segmenter o ralliyi atar. İki ralli arasındaki boşluk 1500 ms'den kısaysa segmenter iki ralliyi birleştirir. |
| Kamera sahaya uzaktır ve oyuncular karede küçük kalır. | Hareket az piksel değiştirir. Enerji gürültü tabanına yaklaşır ve segmenter `NO_SEPARATION` döndürür. |

`docs/API_V1.md` sözleşmesine göre her öneri bir inceleme kartına gider ve kullanıcı karar vermeden istatistiğe girmez.

### Gerçek ölçüm için gereken adımlar

1. Ekip kullanım izni olan gerçek maç videolarını envantere (`01_VIDEO_ENVANTERI.csv`) yazar.
2. Anotatörler ralli sınırlarını `03_RALLI_GOLD.csv` ya da V3 JSON biçiminde etiketler. `validate-labels` komutu etiketleri denetler.
3. `make-splits` komutu test kümesini maç grubuna göre ayırır ve kilitler.
4. `segment` komutu test videolarını işler. `evaluate` komutu tahminleri gold etiketlerle karşılaştırır.

`evaluate` komutu 30'dan az gold rallide oran yazmaz. Komut bu durumda ham sayıları ve güven aralığını verir.

## Kurulum

Paket Python 3.10 ya da üstünü, `numpy` kütüphanesini ve `PATH` üzerinde `ffmpeg` ile `ffprobe` programlarını ister. Aşağıdaki komutlar depo kökünden çalışır:

```bash
sudo apt-get install -y ffmpeg
pip install -e ml
```

## Komutlar

Her komut `python -m volleystat_ml <alt komut>` biçiminde çalışır. Komutlar üç çıkış kodu kullanır: `0` başarıyı, `1` denetimin sorun bulduğunu, `2` komutun girdiyi kullanamadığını bildirir. Her alt komut `--help` seçeneğiyle kendi seçeneklerini listeler.

### segment

```bash
python -m volleystat_ml segment mac.mp4 --video-id V001 --out V001.pred.json
```

Komut tek bir video için ralli aralığı önerir ve sonucu JSON olarak yazar. Kullanıcı `--out` vermezse komut JSON'u standart çıktıya yazar. `--video-id` komutun çıktıya yazdığı kimliği belirler. Kullanıcı bu seçeneği vermezse komut dosya adının uzantısız hâlini kullanır. Komut ilerlemeyi `frames 96/200` biçiminde standart hataya yazar ve `--quiet` bu satırları kapatır.

Çıktı şu alanları taşır: `analyzer` (kimlik, sürüm, `BASELINE` türü), `mock: false`, `videoId`, `durationMs`, `status`, `frames` (işlenen ve beklenen kare), `energy`, `params` ve `rallies`. Her ralli `startMs`, `endMs`, `proposedWinner`, `confidence` ve `uncertaintyReasons` alanlarını taşır.

Parametrelerin hepsi `SegmenterParams` veri sınıfında durur ve komut satırından değişir:

| Seçenek | Varsayılan | Görev |
|---|---|---|
| `--sample-fps` | 5.0 | Segmenter videodan saniyede bu kadar kare örnekler. Değer kaynak videonun kare hızından düşük kalmalıdır, çünkü `ffmpeg` eksik kareyi tekrarlar ve bu tekrar sıfır hareket gibi görünür. |
| `--frame-width`, `--frame-height` | 96, 54 | Segmenter enerjiyi bu boyuttaki gri karede hesaplar. |
| `--smooth-window-ms` | 1000 | Bu değer medyan filtresinin genişliğini belirler. Filtre tek karelik sıçramaları (sahne kesmesi, flaş) siler. |
| `--noise-floor` | 0.05 | Segmenter bu değeri logaritmadan önce enerjiye ekler. Sıkıştırma gürültüsü böylece ayrı bir düzey oluşturmaz. |
| `--min-contrast-ratio` | 3.0 | Hareketli düzeyin durgun düzeye oranı bu değerin altında kalırsa segmenter aralık döndürmez. |
| `--enter-level` | 0.5 | Bir aralığın oluşması için en az bir örnek bu düzeye ulaşmalıdır. Değer, durgun düzey (0) ile hareketli düzey (1) arasındaki konumu gösterir. |
| `--exit-level` | 0.25 | Aralık, enerjinin bu düzeyin üstünde kaldığı komşu örneklerin hepsini kapsar. |
| `--merge-gap-ms` | 1500 | Segmenter aralarında bu süreden kısa boşluk kalan aralıkları birleştirir. |
| `--min-rally-ms` | 2000 | Segmenter birleştirmeden sonra bu süreden kısa aralıkları atar. |

### evaluate

```bash
python -m volleystat_ml evaluate --gold V001.gold.json --pred V001.pred.json
python -m volleystat_ml evaluate --gold 03_RALLI_GOLD.csv --pred tahminler.csv --tolerance-ms 1000 --seed 0 --json
```

Komut tahmin aralıklarını gold aralıklarla video video karşılaştırır ve sonuçları toplar. `--gold` seçeneği V3 etiket JSON'u ya da `video_id`, `start_ms` ve `end_ms` sütunlarını taşıyan bir CSV alır. `--pred` seçeneği `segment` çıktısını ya da aynı sütunları taşıyan bir CSV alır. İki seçenek de birden çok dosya kabul eder.

Komut şu kuralları uygular:

- Bir tahmin, başlangıcı ve bitişi ayrı ayrı toleransın içinde kalırsa bir gold ralliyle eşleşir. Varsayılan tolerans 1000 ms'dir.
- Eşleşme bire birdir. Komut en çok sayıda çift kuran eşlemeyi seçer. Komut, aynı sayıda çift kuran eşlemeler arasından toplam hatası en küçük olanı alır.
- Komut eşleşmeyen tahmini FP, eşleşmeyen gold ralliyi FN sayar.
- Komut TP, FP, FN, precision, recall ve F1 değerlerini yazar. Komut eşleşen çiftler için başlangıç ve bitiş hatasının medyanını ve 90. yüzdeliğini, ayrıca ortalama aralık IoU değerini yazar.
- Gold ralli sayısı 30'un altındaysa komut precision, recall ve F1 için oran yazmaz. Komut bu durumda ham sayıları (`18/20` gibi), örneklemin küçük olduğunu söyleyen bir uyarıyı ve güven aralığını yazar. `--json` çıktısı aynı durumu `percentagesReliable: false` alanıyla bildirir.
- Komut precision ve recall için %95'lik bootstrap güven aralığı hesaplar. `--seed` rastgele sayı üretecinin tohumunu, `--resamples` yeniden örnekleme sayısını belirler. Aynı girdi ve aynı tohum aynı aralığı verir. Komut rallileri tek tek yeniden örnekler. Aynı maçın rallileri birbirine bağlıdır, bu yüzden yeni maçlar için geçerli aralık daha geniştir.
- Komut gold tarafındaki JSON dosyasını önce `validate-labels` kurallarıyla denetler ve hatalı etiketle ölçüm yapmaz.
- Komut yalnız bir tarafta bulunan videoyu listeler ve sayıma katmaz.

### validate-labels

```bash
python -m volleystat_ml validate-labels V001.gold.json --duration-ms 5400000
```

Komut bir etiket dosyasını iki katmanda denetler. Birinci katman dosyanın yapısını `schemas/ANNOTATION_SCHEMA_V3.json` şemasına göre denetler. Bu katman şemayı kendi küçük yorumlayıcısıyla okur ve üçüncü taraf bir JSON Schema kütüphanesi kullanmaz. İkinci katman şemanın ifade edemediği kuralları uygular:

- Her rallide `0 <= startMs < endMs` koşulu geçerlidir. Kullanıcı `--duration-ms` verirse komut `endMs` değerinin video süresini aşmadığını da denetler.
- Ralliler dosyada zaman sırasıyla durur. Dosya `sequence` alanı taşıyorsa bu alan kesin artar. Dosya `setNumber` alanı da taşıyorsa komut sırayı her set için ayrı izler.
- Ralliler çakışmaz. Bir rallinin bitişi sonraki rallinin başlangıcına eşit olabilir.
- `winningSide`, `servingSide` ya da `observationStatus` alanı `UNKNOWN` olan ralli bir `uncertaintyReason` taşır.
- Bir rallide en çok bir aksiyon `endEvent: true` değerini taşır.

Komut her sorunu konumuyla birlikte ayrı bir satıra yazar. Şu satır bir örnektir: `$.rallies[2]: endMs (36000) is after the end of the video (35000 ms)`. Komut sorun bulursa `1` koduyla çıkar.

`sequence`, `setNumber` ve `endEvent` alanları şemada yoktur. İlk iki alan `03_RALLI_GOLD.csv` şablonundaki `sequence` ve `set_number` sütunlarının JSON yazımıdır. `endEvent` alanı devir paketindeki `04_AKSIYON_GOLD.csv` şablonunun `end_event` sütununa karşılık gelir.

### make-splits

```bash
python -m volleystat_ml make-splits --inventory 01_VIDEO_ENVANTERI.csv --out TEST_LOCK_V1.csv --seed 0
```

Komut bir video envanterini okur, videoları kümelere dağıtır ve kilit manifestini yazar:

- Komut videoları `match_group_id` değerine göre gruplar ve her grubu bütün olarak tek bir kümeye koyar. Aynı maçın kareleri böylece iki kümeye dağılmaz.
- Varsayılan paylar train için 0,70, validation için 0,15 ve test için 0,15 değeridir. `--ratios 0.70,0.15,0.15` seçeneği payları değiştirir.
- Komut envanterin `split` sütununda kayıtlı kümeyi korur.
- `training_allowed` değeri `true` olmayan bir video train kümesine girmez. Komut o videonun bütün maç grubunu validation ya da test kümesine koyar. Envanter böyle bir videoyu train kümesinde gösteriyorsa komut hata verir ve manifest yazmaz.
- Komut boş `match_group_id` değerini, 64 onaltılık hane taşımayan `sha256` değerini, iki kez geçen `video_id` değerini ve iki ayrı maç grubunda görünen aynı `sha256` değerini reddeder.
- Komut manifesti `11_TEST_MANIFEST.csv` sütunlarıyla yazar. Manifestin son satırı (`# content_sha256=...`) başlığın ve satırların SHA-256 özetini taşır.
- Komut var olan bir dosyanın üstüne yazmaz. Değişen bir test kümesi yeni bir adla yeni bir sürüm olur.
- Aynı envanter ve aynı `--seed` aynı dağılımı verir.

### check-leakage

```bash
python -m volleystat_ml check-leakage TEST_LOCK_V1.csv
python -m volleystat_ml check-leakage 01_VIDEO_ENVANTERI.csv
```

Komut bir kilit manifestini ya da bir envanteri okur. Komut iki kümede birden görünen her `match_group_id` ve her `sha256` değerini yazar. Manifest okurken komut özeti yeniden hesaplar ve dosya kilitten sonra değiştiyse bunu bildirir. Envanter okurken komut train kümesinde duran ve `training_allowed` değeri `true` olmayan videoları da yazar. Komut sorun bulursa `1` koduyla çıkar.

## Arka uç adaptörü

`backend/app/analyzers/motion_baseline.py` dosyasındaki `MotionBaselineAnalyzer` sınıfı segmenter'ı arka ucun `Analyzer` arayüzüne bağlar:

- Analizörün kimliği `motion-baseline`, türü `BASELINE` değeridir. Sürüm `segmenter.ALGORITHM_VERSION` sabitinden gelir.
- Adaptör her öneride `proposed_winner` alanına `UNKNOWN`, `confidence` alanına `None` ve belirsizlik nedenine `NO_WINNER_MODEL` yazar.
- Adaptör ilerlemeyi `DETECTING_RALLIES` aşamasında `FRAMES` birimiyle bildirir.
- Adaptör çözemediği videoyu `VIDEO_UNREADABLE`, diğer segmenter hatalarını `ANALYZER_ERROR` koduyla bildirir.
- Segmenter aralık bulamazsa adaptör boş liste döndürür. Sözleşmeye göre iş bu durumda `NO_RALLIES_FOUND` koduyla başarısız olur.
- `volleystat_ml` paketi kurulu değilse adaptör kurulum komutunu söyleyen bir `ImportError` fırlatır. `app.analyzers.load_analyzer` işlevi bu hatayı `AnalyzerNotAvailable` hatasına çevirir.

`docs/API_V1.md` belgesinin 2. bölümüne göre sunucu bu adaptörü `VOLLEYSTAT_ANALYZER=motion-baseline` değeriyle seçer.

## Testler

```bash
cd ml
pip install -e ".[test]"
python -m pytest -q
```

Testler bir oturum fixture'ında `ffmpeg` ile sentetik klipler üretir. Klipler 320x180 boyutunu ve 40 saniyeyi aşmaz. `ffmpeg` kurulu değilse pytest klip isteyen testleri atlar. `VOLLEYSTAT_REQUIRE_FFMPEG=1` değişkeni bu atlamayı hataya çevirir.

Adaptörün testi de bu klasörde durur (`tests/test_backend_adapter.py`), çünkü arka uç testleri `volleystat_ml` paketini kurmaz.

GitHub Actions bu testleri `.github/workflows/ml-tests.yml` iş akışıyla çalıştırır. İş akışı `ml/` klasörüne, adaptör dosyasına ya da adaptörün dayandığı arayüz dosyalarına dokunan her PR'da başlar. Aynı dosyalara dokunan ve `main` dalına gelen her push da iş akışını başlatır.
