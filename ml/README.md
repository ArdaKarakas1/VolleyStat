# volleystat_ml

Bu paket VolleyStat'ın mock olmayan ilk analiz bileşenini ve o bileşeni ölçecek araçları içerir. Paket, hareket enerjisine dayanan bir sezgisel algoritma (baseline) sunar. Kimse bu algoritmayı veriyle eğitmedi, yani algoritma bir model değildir.

Bu algoritmanın gerçek voleybol videosundaki doğruluğunu kimse ölçmedi. "Doğruluk durumu: gerçek maçta ölçüm yok" bölümü bu konuyu ayrıntısıyla anlatır.

## Paketin parçaları

| Dosya | Görev |
|---|---|
| `volleystat_ml/segmenter.py` | Segmenter videodaki hareket enerjisinden ralli aralığı önerir. |
| `volleystat_ml/metrics.py` | Bu modül tahmin aralıklarını gold aralıklarla karşılaştırır. |
| `volleystat_ml/winner.py` | Bu modül bir kazanan bileşeninin önerilerini gold kazananlarla karşılaştırır: seçici precision, otomatik kapsama, kalibrasyon ve G3 hedefleriyle karşılaştırma. Depoda kazanan öneren bir bileşen yoktur. |
| `volleystat_ml/agreement.py` | Bu modül iki anotatörün aynı videolara verdiği etiketleri karşılaştırır: ralli sınırı, kazanan taraf ve servis tarafı. |
| `volleystat_ml/labels.py` | Bu modül etiket dosyasını V3 şemasına ve alan kurallarına göre denetler. |
| `volleystat_ml/splits.py` | Bu modül videoları maç grubuna göre train, validation ve test kümelerine ayırır, sızıntı arar ve kilit manifestini yazar. |
| `volleystat_ml/cli.py` | Bu modül yedi alt komutu çalıştırır. |
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

Aralıklar oynatıcının gösterdiği zaman çizgisindedir: 0, videonun ilk karesidir. Kapsayıcının başlangıç zamanı ve görüntüden önce başlayan ses aralıkları kaydırmaz. Sonuçtaki `durationMs` değeri kapsayıcının süresidir. Kapsayıcı 0'dan başlamıyorsa ya da ses görüntüden uzun sürüyorsa bu değer görüntünün süresinden büyük olur.

Farkı tam olarak sıfır çıkan bir örnek iki ayrı şey anlatabilir ve enerji bu ikisini ayıramaz. Örnek bir kopya kare olabilir: Zaman damgalarındaki bir boşluk, donmuş bir görüntü ve kaynak kare hızının üstündeki bir örnekleme kopya kare üretir. Örnek tamamen durağan bir görüntünün dinlenme anı da olabilir. Segmenter önce birinci okumayı dener, çünkü gerçek bir kamera görüntüsü hiçbir zaman tam olarak durmaz. Bu okumada segmenter sıfır örnekleri düzey kestiriminin dışında tutar ve durgun süre sayar. Hareket eden örnekler dinlenme ve ralli olarak ayrılıyorsa segmenter bu sonucu döndürür. Ayrılmıyorsa segmenter ikinci okumaya geçer ve en az bir medyan penceresi kadar süren sıfır bölümlerini durgun düzey sayar. Tek tek gelen sıfır örnekler iki okumada da düzey kestirimine girmez.

`ffmpeg` kesik ya da kısmen bozuk bir dosyada hata mesajı yazar ve yine de `0` koduyla çıkar. `ffmpeg` bir hata mesajı yazdıysa segmenter videoyu sonuna kadar okumadığını kabul eder ve `DecodeError` fırlatır. Segmenter okuyabildiği bölüm için aralık döndürmez, çünkü o aralıklar videonun tamamının sonucu gibi görünür.

Segmenter kareleri 32'lik parçalar hâlinde okur ve videonun tamamını belleğe almaz. Segmenter ilerlemeyi işlediği kare sayısı ve beklediği kare sayısı olarak bildirir. `ffmpeg` mesajlarını bir geçici dosyaya yazar, böylece dolan bir hata borusu çözücüyü durdurmaz. Segmenter hata ya da iptal durumunda `ffmpeg` sürecini öldürür ve sürecin bitmesini bekler.

Enerji iki düzeye net ayrılmıyorsa segmenter tahmin yürütmez ve boş liste döndürür. Sonucun `status` alanı nedeni söyler:

| `status` | Anlam |
|---|---|
| `OK` | Segmenter en az bir aralık buldu. |
| `NO_SEPARATION` | Enerji iki ayrı düzeye ayrılmadı. Durağan bir klip, baştan sona hareketli bir klip ya da çok zayıf bir hareket bu sonucu verir. Yalnız dinlenme gürültüsü taşıyan ve kaynak kare hızının üstünde örneklenen bir klip de bu sonucu verir. |
| `TOO_SHORT` | Klip iki düzeyi kestirmeye yetecek kadar örnek içermiyor. |
| `ONLY_SHORT_RUNS` | İki düzey var, ama her hareketli bölüm `min_rally_ms` değerinden kısa sürüyor. |

Segmenter topu, sahayı, fileyi ve skoru tanımaz. Segmenter bu yüzden kazanan önermez ve güven değeri üretmez. Her öneri `proposedWinner: "UNKNOWN"`, `confidence: null` ve `uncertaintyReasons: ["NO_WINNER_MODEL"]` değerlerini taşır.

Çıktıdaki `energy` alanı iki düzeyin ham enerji değerini ve oranını verir. Bu sayılar parametre ayarlayan kişiye yardım eder. Hiçbir ekran bu sayıları güven puanı olarak göstermemelidir.

## Doğruluk durumu: gerçek maçta ölçüm yok

Bu depoda gerçek maç görüntüsü yoktur. Testler `ffmpeg` programının `lavfi` kaynaklarından ürettiği sentetik klipleri kullanır. Birinci klipte beyaz bir kutu mavi bir zeminde önceden belirli saniyelerde hareket eder ve kalan sürede durur. İkinci klip durağan ve hareketli test desenlerini aynı saniyelerde art arda dizer. Test düzeneği birinci klipten beş klip daha türetir: yarıda kestiği bir kopya, ortasını bozduğu bir kopya, beş saniyelik karesini çıkardığı bir kopya, kapsayıcısını 5. saniyede başlattığı bir kopya ve sesini görüntüden 2 saniye önce başlattığı bir kopya.

Sentetik testler yalnızca tesisatın çalıştığını kanıtlar. Testler şunları gösterir:

- Kod videoyu çözer, enerjiyi hesaplar ve sınırlarını testin bildiği yapay bir sinyalde aralıkları bulur.
- Segmenter hareketsiz klipte ve baştan sona hareketli klipte aralık uydurmaz.
- Segmenter `ffmpeg` programının sonuna kadar çözemediği videoyu reddeder.
- Segmenter zaman damgası boşluğunu durgun düzey saymaz ve aralıkları videonun ilk karesinden sayar.
- Segmenter tamamen durağan bir dinlenme görüntüsünü durgun düzey sayar ve tek tek gelen kopya kareleri ikinci bir düzey saymaz.
- Segmenter ilerlemeyi gerçek kare sayısıyla bildirir, `ffmpeg` sürecini her durumda kapatır ve videoyu belleğe yığmaz.
- Metrik, uyum, etiket ve küme araçları kendi kurallarını uygular.

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
| Videoda hiç ralli yoktur ve zaman damgalarında uzun bir boşluk vardır ya da görüntü bir süre donar. | Segmenter boşluğu dinlenme, kalan gürültüyü oyun sayar ve boşluğun dışındaki bölümleri ralli olarak önerir. Enerji bu klibi, tamamen durağan bir görüntüde tek bir oyun bölümü taşıyan klipten ayıramaz. |

`docs/API_V1.md` sözleşmesine göre her öneri bir inceleme kartına gider ve kullanıcı karar vermeden istatistiğe girmez.

### Gerçek ölçüm için gereken adımlar

1. Ekip kullanım izni olan gerçek maç videolarını envantere (`01_VIDEO_ENVANTERI.csv`) yazar.
2. Anotatörler ralli sınırlarını `03_RALLI_GOLD.csv` ya da V3 JSON biçiminde etiketler. `validate-labels` komutu etiketleri denetler. İki anotatör aynı videoları birbirinden bağımsız etiketlerse `agreement` komutu iki etiket setinin uyumunu ölçer.
3. `make-splits` komutu test kümesini maç grubuna göre ayırır ve kilitler. Ekip komutun yazdığı `content_sha256` değerini dosyanın dışında bir yere, örneğin deney kaydına yazar. Envantere video eklenince ekip yeni kilidi `--previous` seçeneğiyle üretir ve iki kilidi `check-leakage` komutuyla karşılaştırır.
4. `segment` komutu test videolarını işler. `evaluate` komutu tahminleri gold etiketlerle karşılaştırır.
5. Bir kazanan bileşeni geldiğinde `evaluate-winner` komutu o bileşenin önerilerini gold kazananlarla karşılaştırır. Depoda bugün kazanan öneren bir bileşen yoktur.

`evaluate` komutu 30'dan az gold rallide oran yazmaz. Komut bu durumda ham sayıları ve güven aralığını verir. Bu kural metin çıktısında da `--json` çıktısında da geçerlidir.

## Kurulum

Paket Python 3.10 ya da üstünü, `numpy` kütüphanesini ve `PATH` üzerinde `ffmpeg` ile `ffprobe` programlarını ister. Aşağıdaki komutlar depo kökünden çalışır:

```bash
sudo apt-get install -y ffmpeg
pip install -e ml
```

## Komutlar

Her komut `python -m volleystat_ml <alt komut>` biçiminde çalışır. Komutlar üç çıkış kodu kullanır: `0` başarıyı, `1` denetimin sorun bulduğunu, `2` komutun girdiyi kullanamadığını bildirir. Her alt komut `--help` seçeneğiyle kendi seçeneklerini listeler.

Komut kullanamadığı girdiyi standart hataya `error: ...` biçiminde tek satırla yazar ve hata izi (traceback) basmaz. Komutun okuyamadığı dosya, UTF-8 olmayan dosya, dosya yerine gelen klasör ve aralığının dışında kalan seçenek değeri bu gruba girer. Argüman ayrıştırıcı seçenek aralıklarını komut çalışmadan önce denetler. Bir dosyanın birden çok satırı kural bozuyorsa komut her satırı ayrı bir satırda yazar. Komutlar CSV ve JSON dosyalarını UTF-8 olarak okur ve başka kodlamayla gelen dosyayı tahminle çözmez.

### segment

```bash
python -m volleystat_ml segment mac.mp4 --video-id V001 --out V001.pred.json
```

Komut tek bir video için ralli aralığı önerir ve sonucu JSON olarak yazar. Kullanıcı `--out` vermezse komut JSON'u standart çıktıya yazar. `--video-id` komutun çıktıya yazdığı kimliği belirler. Kullanıcı bu seçeneği vermezse komut dosya adının uzantısız hâlini kullanır. Komut ilerlemeyi `frames 96/200` biçiminde standart hataya yazar ve `--quiet` bu satırları kapatır.

Komut `--out` yolunu videoyu çözmeden önce denetler. `ffmpeg` videoyu sonuna kadar çözemezse komut sonuç yazmaz ve `2` koduyla çıkar.

Çıktı şu alanları taşır: `analyzer` (kimlik, sürüm, `BASELINE` türü), `mock: false`, `videoId`, `durationMs`, `status`, `frames` (işlenen ve beklenen kare), `energy`, `params` ve `rallies`. Her ralli `startMs`, `endMs`, `proposedWinner`, `confidence` ve `uncertaintyReasons` alanlarını taşır. Segmenter `startMs` ve `endMs` değerlerini videonun ilk karesinden milisaniye olarak sayar. `durationMs` kapsayıcının süresidir.

Parametrelerin hepsi `SegmenterParams` veri sınıfında durur ve komut satırından değişir. Argüman ayrıştırıcı her seçeneğin aralığını denetler. Komut `--exit-level` değerinden küçük bir `--enter-level` değerini de reddeder.

| Seçenek | Varsayılan | Görev |
|---|---|---|
| `--sample-fps` | 5.0 | Segmenter videodan saniyede bu kadar kare örnekler. Kaynak videonun kare hızını aşan bir değer kazanç sağlamaz, çünkü `ffmpeg` eksik kareyi tekrarlar ve segmenter kopya kareyi düzey kestiriminin dışında tutar. |
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
- Gold ralli sayısı 30'un altındaysa komut precision, recall ve F1 için oran yazmaz. Komut bu durumda ham sayıları (`18/20` gibi), örneklemin küçük olduğunu söyleyen bir uyarıyı ve güven aralığını yazar. `--json` çıktısı bu durumda `precision.value`, `recall.value` ve `f1` alanlarına `null` yazar, pay ve paydayı korur ve `percentagesReliable: false` alanını taşır. Komut aynı kuralı `videos` altındaki her videoya ayrı uygular.
- Komut precision ve recall için %95'lik bootstrap güven aralığı hesaplar. `--seed` rastgele sayı üretecinin tohumunu, `--resamples` yeniden örnekleme sayısını belirler. Aynı girdi ve aynı tohum aynı aralığı verir. Komut rallileri tek tek yeniden örnekler. Aynı maçın rallileri birbirine bağlıdır, bu yüzden yeni maçlar için geçerli aralık daha geniştir.
- Komut gold tarafındaki JSON dosyasını önce `validate-labels` kurallarıyla denetler ve hatalı etiketle ölçüm yapmaz.
- Komut gold tarafındaki CSV dosyasına aynı sıra, çakışma ve tekrar kurallarını uygular: Her videonun rallileri dosyada zaman sırasıyla durur ve çakışmaz. Komut kuralı bozan satırların numarasını yazar ve `2` koduyla çıkar. Tahmin dosyası bu kurallara bağlı değildir.
- Komut başlıktan fazla hücre taşıyan CSV satırını reddeder.
- Komut yalnız bir tarafta bulunan videoyu listeler ve sayıma katmaz.
- Argüman ayrıştırıcı `--tolerance-ms` (en az 0), `--resamples` (en az 1) ve `--seed` (en az 0) değerlerini denetler.

### evaluate-winner

```bash
python -m volleystat_ml evaluate-winner --gold 03_RALLI_GOLD.csv --pred V001.pred.json V002.pred.json
python -m volleystat_ml evaluate-winner --gold gold.csv --pred tahminler.csv --manifest TEST_LOCK_V1.csv --expect-sha256 <özet> --out hatalar.csv --json
```

Komut bir kazanan bileşeninin önerilerini gold kazananlarla karşılaştırır. Depoda bugün kazanan öneren bir bileşen yoktur ve `segment` her öneride `UNKNOWN` yazar. Komut yalnız ölçer: Komut model eğitmez ve kapı kararı vermez. Tanımlar devir paketinin `05_DENEY_METRIKLERI_KABUL_KAPILARI.md` belgesinden gelir. Kaynağın karar vermediği yerleri ADR-0116 kaydeder (`docs/ADR.md`).

Komut girdiyi şöyle okur:

- `--gold` seçeneği V3 etiket JSON'u ya da `03_RALLI_GOLD.csv` düzeninde bir CSV alır. Komut her rallinin kazananını (`winning_side`), gözlem durumunu (`observation_status`) ve etiket durumunu (`ground_truth_status`) okur. JSON dosyası etiket durumunu `groundTruthStatus` anahtarında taşır. Komut gold dosyasına `evaluate` komutunun sıra ve çakışma kurallarını uygular. Komut CSV dosyasında bu sütunlarda yalnız şemanın değerlerini kabul eder. `ground_truth_status` sütunu `SINGLE_HUMAN`, `DOUBLE_AGREED`, `ADJUDICATED`, `UNRESOLVABLE` ya da `NEEDS_MORE_EVIDENCE` değerini taşır ya da boş kalır.
- Komut iki isteğe bağlı sütunu da okur ve değerlerini denetler. `review_status` sütunu `UNREVIEWED`, `ACCEPTED`, `CORRECTED`, `UNRESOLVABLE` ya da `DISPUTED` değerini taşır. `winner_evidence` sütunu `VIDEO`, `SCOREBOARD`, `AUDIO`, `RULE_CONSTRAINT` ya da `EXTERNAL_NOTES` değerini taşır. İki sütun da boş kalabilir. Komut başka bir yazımı (`disputed` gibi) satır numarasıyla reddeder, çünkü yanlış yazılmış bir `DISPUTED` tartışmalı ralliyi puana katardı. JSON dosyası bu alanları `reviewStatus` ve `winnerEvidence` anahtarlarında taşır.
- Hiç ralli taşımayan bir gold dosyası etiketlenmiş bir videoyu göstermez. Komut o videoyu değerlendirmez ve "the gold file holds no rally" diye listeler. Bütün rallileri `NOT_ANNOTATED` olan ve hiçbir rallisi `UNRESOLVABLE` ya da `NEEDS_MORE_EVIDENCE` durumu taşımayan bir gold dosyası da hiçbir kazananı etiketlemez. Komut o videoyu da değerlendirmez ve "the gold file annotates no winner" diye listeler. Tahmin dosyası olan hiçbir videonun gold dosyası kazanan etiketlemiyorsa komut `2` koduyla çıkar. Komut kazananı `UNKNOWN` olan, etiketi `UNRESOLVABLE` ya da `NEEDS_MORE_EVIDENCE` olan videoyu ise değerlendirir, çünkü etiketçi o kayda baktı ve zor bir kayıt test kümesinde kalır.
- Komut kapanmamış bir tırnağı, kapanan tırnaktan sonra gelen bir yazıyı ya da CSV sınırını aşan bir hücreyi bozuk kaydın başladığı satırın numarasıyla bildirir ve `2` koduyla çıkar. Başlık satırı da bu kurala girer. Kapanmamış bir tırnak sonraki bütün satırları tek bir hücreye çekerdi ve gold dosyası o rallileri sessizce kaybederdi.
- Komut değerlendirmediği videolardaki `HOME` ve `AWAY` önerilerini sayar ve yazar (`notEvaluated.automaticProposals`). Değerlendirilen videolar hiç karar taşımasa bile okur bu kararların var olduğunu görür.
- `--pred` seçeneği bir analizörün çıktısını alır: `segment` komutunun yazdığı biçimde JSON ya da `video_id`, `start_ms`, `end_ms` ve `proposed_winner` sütunlarını taşıyan CSV. `confidence` sütunu isteğe bağlıdır.
- Komut yalnız `proposedWinner` alanını okur. Komut kullanıcının kararını taşıyan `winner` alanını okumaz.
- Her öneri `HOME`, `AWAY` ya da `UNKNOWN` değerini taşır. Komut alanı taşımayan JSON rallisini ve boş CSV hücresini reddeder, çünkü yanlış yazılmış bir sütun adı bütün kararları çekimser gösterir.
- `confidence` 0 ile 1 arasında bir sayıdır ya da boştur. Komut ondalık virgülle yazılmış değeri (`0,85`) satır numarasıyla reddeder.
- Komut şu dosyaları reddeder: `"mock": true` taşıyan çıktı, türü `MOCK` olan analizörün çıktısı ve kullanıcının üzerinde çalıştığı ralli listesi (`rallyListVersion`, `listConfirmed`, `origin`, `status` ya da `winner` taşıyan belge). Komut aynı adları taşıyan CSV sütunlarını da reddeder. O listenin aralıkları kullanıcı düzeltmesi taşıyabilir ve kullanıcının işi model başarısına girmez.
- Bütün tahmin dosyaları aynı analizörü (`analyzer.id` ve `analyzer.version`) adlandırır ya da hiçbiri adlandırmaz. Komut karışımı reddeder.

Komut sayıları şu kurallarla çıkarır:

- Komut önerileri gold rallilerle `evaluate` komutunun eşlemesiyle eşler: Başlangıç ve bitiş ayrı ayrı `--tolerance-ms` değerinin içinde kalır ve eşleşme bire birdir. Varsayılan tolerans 1000 ms'dir.
- Kesin kazananı olmayan gold ralli hiçbir orana girmez. Komut o ralliyi şu nedenlerden ilk uyanıyla dışarıda tutar ve nedenleri sayar: `NOT_ANNOTATED`, `NEEDS_MORE_EVIDENCE`, `UNRESOLVABLE`, `REVIEW_DISPUTED`, `REVIEW_UNRESOLVABLE`, `WINNER_UNKNOWN`, `OBSERVATION_UNKNOWN`, `OBSERVATION_NOT_APPLICABLE`.
- Komut uygun rallileri iki katmana ayırır. `GOLD` katmanı etiket durumu `DOUBLE_AGREED` ya da `ADJUDICATED` olan rallileri tutar. `SINGLE` katmanı tek anotatörün etiketlediği ve durumu yazılmamış rallileri tutar. Komut her katmanın sayılarını ayrı verir.
- Komut `GOLD` katmanının başlığının altına çift etiketli kümenin zor rallilerini yazar: `UNRESOLVABLE` ve `NEEDS_MORE_EVIDENCE` sayısını ve bunlardan kaçının otomatik karar aldığını. Bu ralliler katmanın dışında kalır ve precision'ı kolay rallilere bırakır. Komut bu sayıları etiket durumundan okur ve kazananı boş kalmış bir `UNRESOLVABLE` etiketi de sayar. `--json` çıktısı aynı sayıları `hardCases` alanında taşır.
- Her uygun ralli tek bir sonuç alır: `CORRECT`, `WRONG`, `UNKNOWN` (bileşen çekimser kaldı) ya da `NOT_PROPOSED` (hiçbir öneri o ralliyle eşleşmedi).
- `selective_precision` doğru otomatik kararların otomatik kararlara oranıdır. `auto_coverage` otomatik kararların uygun rallilere oranıdır. Komut `UNKNOWN` oranını ve önerilmeyen rallilerin oranını da verir. Bu üç pay toplamda 1 eder. Komut dört oranın her birine bir güven aralığı yazar.
- Komut bir oranı yalnız o oranın paydası en az 30 ise yazar. Payda 30'un altındaysa metin çıktısı ham sayıyı (`18/20` gibi) ve güven aralığını yazar, `--json` çıktısı `value` alanına `null` yazar.
- Komut bir hedefin yanındaki payı hedefin öbür yanına yuvarlamaz. 189/199 payı hedefin altındadır ve komut onu `0.950` değil `0.9497` diye yazar. Aralığın ucu için de aynı kural geçerlidir.
- Güven aralığı %95'lik Wilson aralığıdır. Aralık rallileri birbirinden bağımsız sayar. Aynı maçın rallileri aynı salonu, kamerayı ve takımları paylaşır, bu yüzden yeni maçlar için geçerli aralık daha geniştir.
- Komut iki tür otomatik kararı sayar ve hiçbir orana katmaz: kesin kazananı olmayan bir gold ralliye verilen karar ve hiçbir gold ralliyle eşleşmeyen karar. Komut ikinci türü üçe ayırır: Öneri, başka bir önerinin eşleştiği gold rallinin ikinci önerisidir (`SECOND_PROPOSAL`), ya da öneri bir gold ralliyle çakışıyor ama sınırı toleransın dışında kalıyor (`OVERLAPS_GOLD`), ya da gold o aralıkta hiç ralli taşımıyor (`NO_GOLD_THERE`). Komut precision satırının altına bir alt sınır yazar: Komut eşleşmeyen her kararı yanlış saysaydı precision kaç olurdu.
- Komut önerileri dosyadaki sıralarına göre değil, içeriklerine göre eşler: Satırları karıştırılmış bir dosya aynı sayıları verir. Bir gold ralliyle aynı maliyette eşleşebilecek iki öneriden komut güveni yüksek olanı puanlar, öbürünü `SECOND_PROPOSAL` diye sayar. Aralıkları farklı olsa da kural aynıdır. Güvenli bir yanlış karar böylece sayımdan kaçmaz.
- Komut her öneriyi toleransın içinde en yakın olduğu gold ralliye bağlar. Uzaklık başlangıç farkı ile bitiş farkının toplamıdır. Eşleyicinin bir gold ralliyle eşlediği öneri de o rallinin adına konuşur. Bir gold rallinin adına konuşan öneriler farklı kazanan adlandırırsa (`HOME` ile `AWAY`, ya da bir taraf ile `UNKNOWN`) komut dosyayı reddeder ve `2` koduyla çıkar, çünkü bileşen o rallide tek bir karar vermedi. Komşu bir ralliye daha yakın olan ve o ralliyle eşleşen öneri hiçbir öneriyle çelişmez. Komut aynı tarafı iki kez söyleyen öneriyi reddetmez. İki `UNKNOWN` öneri hiçbir karar vermez ve komut onları da reddetmez.
- Komut her gold taraf için neyin önerildiğini sayar (`HOME`, `AWAY`, `UNKNOWN`, önerilmedi). Hep aynı tarafı söyleyen bir bileşen bu tabloda görünür.
- Değerlendirilen videoların önerileri arasında hiç `HOME` ya da `AWAY` yoksa komut oran ve aralık yazmaz ve hiçbir kazanan bileşeninin ölçülmediğini söyler. Böyle bir koşu kilitli test kümesinde de pilot koşudur. `segment` çıktısı bugün bu sonucu verir.
- Komut kazanan kanıtını (`winner_evidence`) yalnız bir kanıt yazan rallilerde sayar. Metin "evidence RULE_CONSTRAINT: 1 of the 5 that state an evidence" gibi bir satır yazar. Hiçbir ralli kanıt yazmıyorsa sayı `null` olur ve komut `0` yazmaz. Boş bir sütun da kanıt yazmaz.

Komut kalibrasyonu güven değeri taşıyan otomatik kararlardan hesaplar:

- Komut güven değerini önerilen tarafın doğru olma olasılığı olarak okur.
- Brier değeri güven ile sonuç arasındaki farkın karesinin ortalamasıdır. Komut Brier için tohumlu bir bootstrap aralığı verir (`--resamples`, `--seed`).
- Komut ECE'yi on eşit kutu üzerinden hesaplar. Kutu `[i/10, (i+1)/10)` aralığıdır ve 1,0 son kutuya girer. Komut ECE için aralık vermez. Kutulu ECE küçük örneklemde tam kalibre bir bileşen için de sıfırdan büyük çıkar. Komut bu yüzden bir referans yazar: Aynı güven değerlerini taşıyan tam kalibre bir bileşen bu örneklem boyunda ortalama kaç ECE gösterir ve 100 örneklemin 95'inde en çok kaç gösterir.
- Komut her dolu kutu için karar sayısını, ortalama güveni ve doğru karar sayısını yazar.
- Komut Brier ve ECE değerlerini yalnız güven taşıyan en az 30 karar varsa yazar. Kutu tablosu her zaman çıkar.
- Komut `--high-confidence` düzeyindeki (varsayılan 0,90) ve üstündeki kararları ve bunların yanlış olanlarını sayar. Hiçbir karar güven taşımıyorsa bu sayı `null` olur ve komut `0` yazmaz. Komut güven taşımayan yanlış kararları ayrıca sayar. Komut düzeyi verildiği gibi yazar ve 0,995 düzeyini metinde 0.99'a yuvarlamaz.

Komut her koşunun türünü ilk satırda söyler:

- Koşu şu koşulların hepsi tutarsa G3 kararının girdisidir (`GATE_INPUT`): `--manifest` bir kilit manifesti verir, `--expect-sha256` manifestin özetini kilit anında not edilen özetle karşılaştırır, videolar manifestin `test` kümesindedir, o kümenin her videosu için en az bir kazanan etiketleyen bir gold dosyası ve bir tahmin dosyası vardır, tolerans 1000 ms'dir, tahmin dosyaları analizörü adlandırır, değerlendirilen videolar en az bir `HOME` ya da `AWAY` önerisi taşır ve `GOLD` katmanı boş değildir.
- Başka her koşu pilot koşudur (`PILOT`). Komut tutmayan her koşulu yazar ve pilot koşuyu hiçbir hedefle karşılaştırmaz.
- Komut değişmiş bir manifesti, beklenen özetle tutmayan manifesti, manifestte olmayan videoyu, manifestte kümesi yazılmamış videoyu ve iki ayrı kümeden video karıştıran koşuyu reddeder.
- G3 bölümü dışarıda kalan gold rallileri nedenleriyle yazar. G3 bölümü puanlanmayan otomatik kararları da iki türüyle yazar: kesin kazananı olmayan ralliye verilen karar ve hiçbir gold ralliyle eşleşmeyen karar. G3 bölümü `GOLD` katmanının rallilerinin kaç videodan ve kaç maç grubundan geldiğini de yazar (`goldLayerVideos`, `goldLayerMatchGroups`).
- G3 hedefleri precision için en az 0,95 ve kapsama için en az 0,60'tır. Komut her hedefi `GOLD` katmanında ve kendi paydasında denetler. Payda 30'un altındaysa komut o hedefi denetlemez.
- Komut her hedef için nokta tahminini ve aralığı hedefle karşılaştırır ve dört durumdan birini yazar: Nokta tahmini ve aralığın tamamı hedefin üstünde, nokta tahmini üstünde ama aralık hedefin altına iniyor, nokta tahmini altında ama aralık hedefe ulaşıyor, aralığın tamamı hedefin altında.
- Denetlenen bir hedef tutmuyorsa komut nokta tahminlerinin hedefleri karşılamadığını yazar. Çekimser kalarak precision paydasını 30'un altına düşüren bir bileşen kapsama hedefini böylece gizleyemez.
- Komut `PASS` ya da `FAIL` yazmaz. Kapı kararını bir kişi verir ve imzalar. Karşılaştırma tamamlandıysa komut sonuç ne olursa olsun `0` koduyla çıkar.

Çıktılar:

- `--json` çıktısı aynı sayıları taşır. Her oran `value`, `numerator`, `denominator`, `reliable` ve `interval` alanlarıyla gelir. Çıktı okunan her dosyanın SHA-256 özetini, koşu türünü, nedenleri ve `g3` bölümünü de taşır.
- `--out` seçeneği hata analizi için bir CSV dosyası yazar. Dosya doğru karar dışındaki her sonuç için bir satır taşır: `WRONG`, `UNKNOWN`, `NOT_PROPOSED`, `AUTO_ON_INELIGIBLE_GOLD` ve `AUTO_UNPAIRED`. Sütunlar şunlardır: `video_id`, `kind`, `layer`, `gold_start_ms`, `gold_end_ms`, `pred_start_ms`, `pred_end_ms`, `gold_winner`, `proposed_winner`, `confidence`, `reason`. Komut var olan bir dosyanın üstüne yazmaz.
- Deney kaydı şablonuyla eşleme şöyledir: `TP` doğru otomatik karar sayısıdır, `FP` yanlış otomatik karar sayısıdır ve `predicted_n` otomatik karar sayısıdır. `FN` seçici bir karar için tanımlı değildir.

Bu sürüm şunları içermez: güven eşiği seçeneği ve risk-kapsama eğrisi (B6 deneyiyle gelir), video kalitesine, kameraya ve sete göre alt grup metrikleri ve arka uçtan analizörün dokunulmamış önerilerini veren bir dışa aktarma. Bir G3 kararı alt grup metriklerini de ister. Video başına sayılar `--json` çıktısının `videos` alanında durur.

Komut video kimliklerini adlarıyla karşılaştırır ve video dosyalarının özetini doğrulamaz. Kimse bu komutu gerçek bir kazanan bileşeninin çıktısıyla çalıştırmadı.

### agreement

```bash
python -m volleystat_ml agreement --a anotator_a.csv --b anotator_b.csv
python -m volleystat_ml agreement --a A_V001.json A_V002.json --b anotator_b.csv --tolerance-ms 1000 --out uyusmazliklar.csv --json
```

Komut iki anotatörün aynı videolara verdiği etiketleri karşılaştırır. Ekip B0 deneyini bu komutla ölçer (`docs/AI_PLAN.md`, bölüm 3 ve 4.3). `--a` seçeneği A anotatörünün dosyalarını, `--b` seçeneği B anotatörünün dosyalarını alır. Her dosya bir V3 etiket JSON'u ya da `03_RALLI_GOLD.csv` düzeninde bir CSV olabilir. CSV dosyası `video_id`, `start_ms` ve `end_ms` sütunlarını taşır. Komut `serving_side`, `winning_side` ve `observation_status` sütunlarını dosyada bulursa kullanır. İki seçenek de birden çok dosya kabul eder ve kullanıcı iki biçimi bir arada verebilir.

Komut şu kuralları uygular:

- Komut iki anotatörün dosyalarına `evaluate` komutunun gold kurallarını uygular. Komut kuralı bozan satırların numarasını yazar ve `2` koduyla çıkar.
- Komut ralli çiftlerini `evaluate` komutunun eşlemesiyle kurar. İki ralli, başlangıçları ve bitişleri ayrı ayrı `--tolerance-ms` değerinin içinde kalırsa bir çift oluşturur. Varsayılan tolerans 1000 ms'dir ve eşleşme bire birdir. Komut A'nın ralli sayısını, B'nin ralli sayısını, çift sayısını, yalnız A'da kalan ve yalnız B'de kalan ralli sayısını yazar.
- Komut çiftlerde başlangıç farkının ve bitiş farkının medyanını ve 90. yüzdeliğini, ayrıca ortalama aralık IoU değerini yazar.
- Komut kazanan tarafı (`winning_side`, JSON'da `winningSide`) ve servis tarafını (`serving_side`, JSON'da `servingSide`) yalnız iki anotatörün de doldurduğu çiftlerde karşılaştırır. Komut değerleri dosyadaki yazımıyla karşılaştırır ve `UNKNOWN` değerini de `HOME` gibi bir değer olarak ele alır. Komut CSV dosyasında `serving_side`, `winning_side` ve `observation_status` sütunlarında yalnız şemanın değerlerini ve boş hücreyi kabul eder. Komut başka bir yazımı (`home` gibi) satır numarasıyla bildirir ve `2` koduyla çıkar. Komut her alan için karşılaştırdığı çift sayısını, aynı değeri taşıyan çift sayısını, uyum oranını ve Cohen's kappa değerini yazar. Komut kappa değerini videoların toplamında bütün çiftler üzerinden yeniden hesaplar.
- Bir anotatör alanı boş bıraktıysa komut o çifti karşılaştırmaz ve ayrı bir sayıda gösterir (`notComparable`). İki anotatör de alanı boş bıraktıysa komut o çifti başka bir sayıda gösterir (`bothEmpty`). `observation_status` (JSON'da `observationStatus`) değeri `NOT_ANNOTATED` olan rallide komut kazanan alanını boş sayar. Şema `winningSide` alanını zorunlu tutar, bu yüzden anotatör kazananı etiketlemediğinde de bu alana bir değer yazar.
- İki anotatör de her çifte aynı tek değeri verdiyse beklenen uyum 1 olur ve kappa tanımsız kalır. Komut bu durumda kappa yerine nedeni yazar. Komut nedeni çift sayısı 30'un altındayken de yazar. `--json` çıktısı `kappa` alanına `null` yazar ve nedeni `kappaUndefinedReason` alanında taşır. Komut tanımsız kappa için 0 ya da 1 yazmaz.
- Karşılaştırılan çift sayısı 30'un altındaysa komut uyum oranını ve kappa değerini yazmaz. Metin çıktısı bu durumda ham sayıyı (`18/20 agree` gibi) ve örneklemin küçük olduğunu söyleyen bir uyarıyı yazar. `--json` çıktısı `agreementRate` ve `kappa` alanlarına `null` yazar ve `percentagesReliable: false` ile `minReliable: 30` alanlarını taşır. Komut aynı kuralı `videos` altındaki her videoya ayrı uygular.
- Komut birden çok videoyu karşılaştırdığında metin çıktısına her video için ralli sayılarını ve alan başına ham sayıları yazar. Komut video başına sınır farklarını ve oranları yalnız `--json` çıktısındaki `videos` alanına yazar.
- Komut yalnız bir anotatörün etiketlediği videoyu listeler ve hiçbir sayıma katmaz. İki anotatörün ortak videosu yoksa komut `2` koduyla çıkar.
- `--out` seçeneği hakem için bir CSV dosyası yazar. Dosyanın her satırı bir uyuşmazlığı gösterir ve şu sütunları taşır: `video_id`, `kind`, `a_start_ms`, `a_end_ms`, `b_start_ms`, `b_end_ms`, `a_value`, `b_value`. `kind` sütunu `ONLY_A`, `ONLY_B`, `WINNER` ya da `SERVING` değerini taşır. Toleransın dışında kalan bir sınır farkı bir `ONLY_A` ve bir `ONLY_B` satırı üretir. Bir anotatörün boş bıraktığı alan satır üretmez. Komut satırları video kimliğine ve zamana göre sıralar.
- Komut var olan bir dosyanın üstüne yazmaz. Komut olmayan bir klasörü de reddeder ve `2` koduyla çıkar.
- Karşılaştırma çalıştıysa komut uyum düzeyi ne olursa olsun `0` koduyla çıkar. Komut bir eşik uygulamaz ve uyumu "iyi" ya da "yeterli" diye nitelemez. Kararı ürün sahibi verir.
- Argüman ayrıştırıcı `--tolerance-ms` (en az 0) değerini denetler.

### validate-labels

```bash
python -m volleystat_ml validate-labels V001.gold.json --duration-ms 5400000
```

Komut bir etiket dosyasını iki katmanda denetler. Birinci katman dosyanın yapısını `schemas/ANNOTATION_SCHEMA_V3.json` şemasına göre denetler. Bu katman şemayı kendi küçük yorumlayıcısıyla okur ve üçüncü taraf bir JSON Schema kütüphanesi kullanmaz. İkinci katman şemanın ifade edemediği kuralları uygular:

- Her rallide `0 <= startMs < endMs` koşulu geçerlidir. Kullanıcı `--duration-ms` verirse komut `endMs` değerinin video süresini aşmadığını da denetler. Komut bu durumda her aksiyonun `startMs` ve `endMs` değerlerini de video süresiyle karşılaştırır. Yalnız `startMs` taşıyan aksiyon da bu denetime girer.
- Ralliler dosyada zaman sırasıyla durur. Dosya `sequence` alanı taşıyorsa bu alan kesin artar. Dosya `setNumber` alanı da taşıyorsa komut sırayı her set için ayrı izler.
- Ralliler çakışmaz. Bir rallinin bitişi sonraki rallinin başlangıcına eşit olabilir.
- `winningSide`, `servingSide` ya da `observationStatus` alanı `UNKNOWN` olan ralli bir `uncertaintyReason` taşır.
- Bir rallide en çok bir aksiyon `endEvent: true` değerini taşır.
- Dosya `groundTruthStatus` alanı taşıyorsa her ralli bu alanı taşır ve alan `SINGLE_HUMAN`, `DOUBLE_AGREED`, `ADJUDICATED`, `UNRESOLVABLE` ya da `NEEDS_MORE_EVIDENCE` değerini alır. Komut CSV yazımını (`ground_truth_status`) JSON dosyasında reddeder, çünkü `evaluate-winner` komutu o adla yazılmış durumu görmez.
- Bir ralli `winnerEvidence` alanı taşıyorsa alan `VIDEO`, `SCOREBOARD`, `AUDIO`, `RULE_CONSTRAINT` ya da `EXTERNAL_NOTES` değerini alır ya da `null` kalır. Komut CSV yazımını (`winner_evidence`) JSON dosyasında aynı nedenle reddeder.

Komut her sorunu konumuyla birlikte ayrı bir satıra yazar. Şu satır bir örnektir: `$.rallies[2]: endMs (36000) is after the end of the video (35000 ms)`. Komut sorun bulursa `1` koduyla çıkar. Komut dosyayı okuyamazsa ya da dosya geçerli JSON değilse `2` koduyla çıkar, çünkü bu durumda hiçbir denetim çalışmadı.

`sequence`, `setNumber`, `endEvent` ve `groundTruthStatus` alanları şemada yoktur. `sequence`, `setNumber` ve `groundTruthStatus` alanları `03_RALLI_GOLD.csv` şablonundaki `sequence`, `set_number` ve `ground_truth_status` sütunlarının JSON yazımıdır. `endEvent` alanı devir paketindeki `04_AKSIYON_GOLD.csv` şablonunun `end_event` sütununa karşılık gelir.

### make-splits

```bash
python -m volleystat_ml make-splits --inventory 01_VIDEO_ENVANTERI.csv --out TEST_LOCK_V1.csv --seed 0
python -m volleystat_ml make-splits --inventory 01_VIDEO_ENVANTERI.csv --previous TEST_LOCK_V1.csv --out TEST_LOCK_V2.csv --seed 0
```

Komut bir video envanterini okur, videoları kümelere dağıtır ve kilit manifestini yazar:

- Komut videoları `match_group_id` değerine göre gruplar ve her grubu bütün olarak tek bir kümeye koyar. Aynı maçın kareleri böylece iki kümeye dağılmaz.
- Varsayılan paylar train için 0,70, validation için 0,15 ve test için 0,15 değeridir. `--ratios 0.70,0.15,0.15` seçeneği payları değiştirir. Argüman ayrıştırıcı negatif payı ve toplamı 1 etmeyen payları reddeder.
- Komut envanterin `split` sütununda kayıtlı kümeyi korur.
- `--previous` seçeneği önceki kilit manifestini verir. Komut o manifestteki her atamayı değiştirmeden korur ve yalnız manifestin tanımadığı videoları ve maç gruplarını dağıtır. Kilitli bir maç grubuna eklenen yeni video o grubun kümesine girer. Komut bu seçenek olmadan payları baştan hesaplar. Envantere eklenen tek bir maç o zaman kilitli bir test maçını train kümesine taşıyabilir.
- Komut özeti tutmayan, özet satırı taşımayan ya da başlıktan fazla hücreli satır içeren önceki manifesti reddeder. Komut önceki kilit ile envanterin `split` sütunu çelişirse de hata verir.
- Komut önceki kilitte bulunan ve envanterden çıkan videoların kimliklerini yazar. Yeni kilit bu videoları içermez.
- `training_allowed` değeri `true` olmayan bir video train kümesine girmez. Komut o videonun bütün maç grubunu validation ya da test kümesine koyar. Envanter böyle bir videoyu train kümesinde gösteriyorsa komut hata verir ve manifest yazmaz.
- Komut boş `match_group_id` değerini, 64 onaltılık hane taşımayan `sha256` değerini, iki kez geçen `video_id` değerini ve iki ayrı maç grubunda görünen aynı `sha256` değerini reddeder.
- Komut manifesti `11_TEST_MANIFEST.csv` sütunlarıyla yazar. Manifestin son satırı (`# content_sha256=...`) başlığın ve satırların SHA-256 özetini taşır. Komut aynı özeti ekrana da yazar. Ekip bu değeri dosyanın dışında saklar.
- Komut var olan bir dosyanın üstüne yazmaz. Değişen bir test kümesi yeni bir adla yeni bir sürüm olur.
- Aynı envanter ve aynı `--seed` aynı dağılımı verir.

### check-leakage

```bash
python -m volleystat_ml check-leakage TEST_LOCK_V1.csv --expect-sha256 <kilit anındaki özet>
python -m volleystat_ml check-leakage TEST_LOCK_V1.csv TEST_LOCK_V2.csv
python -m volleystat_ml check-leakage 01_VIDEO_ENVANTERI.csv
```

Komut bir ya da birden çok dosya okur. Her dosya bir kilit manifesti ya da bir envanterdir. Komut her dosyada iki kümede birden görünen her `match_group_id` ve her `sha256` değerini yazar. Envanter okurken komut train kümesinde duran ve `training_allowed` değeri `true` olmayan videoları da yazar.

Manifest okurken komut özeti yeniden hesaplar, her çalışmada `content_sha256` değerini yazar ve dosya kilitten sonra değiştiyse bunu bildirir. Komut başlıktan fazla hücre taşıyan manifest satırını da sorun sayar, çünkü özet yalnız manifest sütunlarını kapsar. Biri manifesti düzenleyip özet satırını da yeniden hesaplarsa dosya kendi içinde tutarlı kalır. Bu değişikliği yalnız ekibin kilit anında dosyanın dışına yazdığı özet ortaya çıkarır: `--expect-sha256` seçeneği o özeti alır ve dosya başka bir özet verirse komut `1` koduyla çıkar. Kullanıcı birden çok dosya verirse seçeneği her dosya için bir kez ve dosyalarla aynı sırada yazar.

Komut birden çok dosya alınca dosyaları birbiriyle de karşılaştırır. Komut bir dosyada bir kümede, başka bir dosyada başka bir kümede duran her `video_id` ve her `match_group_id` değerini yazar. Bu denetim eski kilitte test kümesinde duran bir maçın yeni kilitte train kümesine geçtiğini gösterir.

Komut sorun bulursa `1` koduyla çıkar.

## Arka uç adaptörü

`backend/app/analyzers/motion_baseline.py` dosyasındaki `MotionBaselineAnalyzer` sınıfı segmenter'ı arka ucun `Analyzer` arayüzüne bağlar:

- Analizörün kimliği `motion-baseline`, türü `BASELINE` değeridir. Sürüm `segmenter.ALGORITHM_VERSION` sabitinden gelir.
- Adaptör her öneride `proposed_winner` alanına `UNKNOWN`, `confidence` alanına `None` ve belirsizlik nedenine `NO_WINNER_MODEL` yazar.
- Adaptör ilerlemeyi `DETECTING_RALLIES` aşamasında `FRAMES` birimiyle bildirir.
- Adaptör çözemediği videoyu `VIDEO_UNREADABLE`, diğer segmenter hatalarını `ANALYZER_ERROR` koduyla bildirir. `ffmpeg` programının sonuna kadar çözemediği video da `VIDEO_UNREADABLE` koduyla başarısız olur ve adaptör segmenter'ın okuyabildiği bölüm için öneri döndürmez.
- Segmenter öneri zamanlarını videonun ilk karesinden sayar. Adaptör her öneriyi `[0, durationMs]` aralığına kırpar ve bu aralığın tamamen dışında kalan öneriyi atar.
- Segmenter aralık bulamazsa adaptör boş liste döndürür. Sözleşmeye göre iş bu durumda `NO_RALLIES_FOUND` koduyla başarısız olur.
- `volleystat_ml` paketi kurulu değilse adaptör kurulum komutunu söyleyen bir `ImportError` fırlatır. `app.analyzers.load_analyzer` işlevi bu hatayı `AnalyzerNotAvailable` hatasına çevirir.

`docs/API_V1.md` belgesinin 2. bölümüne göre sunucu bu adaptörü `VOLLEYSTAT_ANALYZER=motion-baseline` değeriyle seçer.

## Testler

```bash
cd ml
pip install -e ".[test]"
python -m pytest -q
```

Testler bir oturum fixture'ında `ffmpeg` ile sentetik klipler üretir. Klipler 320x180 boyutunu ve 45 saniyeyi aşmaz. `ffmpeg` kurulu değilse pytest klip isteyen testleri atlar. `VOLLEYSTAT_REQUIRE_FFMPEG=1` değişkeni bu atlamayı hataya çevirir.

Adaptörün testi de bu klasörde durur (`tests/test_backend_adapter.py`), çünkü arka uç testleri `volleystat_ml` paketini kurmaz.

GitHub Actions bu testleri `.github/workflows/ml-tests.yml` iş akışıyla çalıştırır. İş akışı `ml/` klasörüne, adaptör dosyasına ya da adaptörün dayandığı arayüz dosyalarına dokunan her PR'da başlar. Aynı dosyalara dokunan ve `main` dalına gelen her push da iş akışını başlatır.
