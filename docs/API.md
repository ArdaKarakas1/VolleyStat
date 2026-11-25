# Yerel geliştirme API'si (v0.9)

**Kök:** `http://127.0.0.1:8000/v1`. Android emülatöründen `http://10.0.2.2:8000/v1`.
**Kapsam:** Yalnızca yerel geliştirme. TLS yoktur. Sağlık, kayıt ve giriş dışındaki her uç oturum ister ve sunucu her maçı yalnız sahibi olan hesaba gösterir.

Makinece okunur sözleşme: [openapi-local-dev.json](openapi-local-dev.json). Dosya `create_app().openapi()` çıktısıdır.

Sürüm 0.4 analiz işlerini, rallileri, inceleme kartlarını, kanıt videosunu ve maç raporunu ekler. Bu uçların sözleşmesi [API_V1.md](API_V1.md) belgesindedir. Bu belge maç, yükleme ve video uçlarını anlatır.

Sürüm 0.4.1 `PUT /matches/{id}` ve `DELETE /matches/{id}` uçlarını ekler. Daha eski bir sunucu bu iki çağrıya `405 METHOD_NOT_ALLOWED` döner. Android uygulamasının 0.4.2 sürümü bu iki ucu kullanır ve bu yüzden sunucunun 0.4.1 sürümünü ister.

Sürüm 0.5.0 ralli düzeltme uçlarını ve liste onayını ekler ([API_V1.md](API_V1.md), bölüm 9). Ralli listesi yanıtı o sürümün set skorlarını, rapor da kullanıcının düzeltme sayısını taşır. Android uygulamasının 0.5.0 sürümü bu uçları kullanır ve bu yüzden sunucunun 0.5.0 sürümünü ister.

Sürüm 0.6.0 maç kuralını ekler (`rules` alanı ve `PUT /matches/{id}/rules`, [API_V1.md](API_V1.md) bölüm 1). Android uygulamasının 0.6.0 sürümü sunucunun 0.6.0 sürümünü ister. Daha eski bir sunucu `rules` alanını yok sayar ve maçı FIVB kuralıyla sayar. Uygulama o sunucunun ralli listesini okuyamaz ve bunu ralli listesi ekranında söyler.

Sürüm 0.7.0 bir videoyu maçlara böler ([API_V1.md](API_V1.md) bölüm 7, ADR-0118). Rapor `score.sets` ve `score.setsWon` yerine `score.matches` ve `score.unplacedRallies` taşır. Ralli listesi `sets` yerine `matches` taşır ve her `scoreAfter` rallinin maç numarasını da taşır. Android uygulamasının 0.7.0 sürümü sunucunun 0.7.0 sürümünü ister. Uygulama daha eski bir sunucunun ralli listesini ve raporunu okuyamaz, çünkü o sunucu bu alanları göndermez. Uygulama bu durumda ralli listesi ve rapor ekranında sunucunun güncel sürümle çalışıp çalışmadığını denetlemeyi söyler.

Sürüm 0.8.0 hesapları ekler ([API_V1.md](API_V1.md) bölüm 10, ADR-0119): kayıt, giriş, çıkış, görünen ad, şifre değişikliği ve hesap silme. Oturum isteyen uçlar `Authorization: Bearer <oturum anahtarı>` başlığını ister. Başlık yoksa ya da oturum bittiyse sunucu `401 AUTH_REQUIRED` ve `WWW-Authenticate: Bearer` döner. Bu sürümde yalnız `/auth/logout` ve `/me` uçları oturum ister. Maç, yükleme, analiz ve rapor uçları oturumsuz çalışmayı sürdürür, bu yüzden uygulamanın 0.7.0 sürümü 0.8.0 sunucusuyla çalışır. `GET /health` yanıtı `accounts` alanını da taşır: `{"signup": "LINK" | "OPEN", "teams": "OPERATOR" | "OPEN"}`.

Sürüm 0.9.0 erişimi ve depolama sınırlarını ekler ([API_V1.md](API_V1.md) bölüm 10, ADR-0119). `GET /health`, `POST /auth/register` ve `POST /auth/login` dışındaki her uç oturum ister. Bir hesabın açtığı maç o hesabın kişisel maçıdır ve sunucu maçı, yüklemelerini, videolarını, analizlerini ve raporunu yalnız o hesaba gösterir. Başka bir hesap `403 NOT_A_MEMBER` alır. Sunucu 0.9 öncesi sürümlerin sakladığı maçları hiçbir hesaba bağlamaz (`LEGACY`). Operatör bu maçları `python -m app.admin legacy` komutuyla bir hesaba bağlar ya da siler. Sunucu kayıt koduyla açılan bir hesabın kişisel videolarına 8 GiB, kodsuz açılan bir hesabınkine 4 GiB ve bütün hesaplarınkine 40 GiB ayırır. Sunucu diskteki boş yeri 20 GiB'ın altına indirecek bir yüklemeyi reddeder ve bu denetimde bitmemiş yüklemelerin henüz gelmeyen baytlarını da sayar. Sunucu açık kaldığı toplam bir gün boyunca parça almayan yarım yüklemeyi kapatır ve baytlarını siler. Sunucu hesabı silerken hesaba ait maçları da siler. Uygulamanın 0.9.0 sürümü sunucunun 0.9.0 sürümünü ister, çünkü uygulamanın hesap silme ve gizlilik metinleri yalnız bu sunucu için doğrudur. Uygulama 0.9 kota ve disk retlerini sunucunun gönderdiği sayılarla yazar ve sunucunun göstermediği bir maçı silinmiş saymaz. Uygulamanın 0.8.0 sürümü her isteğe oturumunu ekler, ama ekip onu 0.9.0 sunucusuyla kullanmayı önermez. Uygulama 0.8'in hesap silme ekranı sunucunun maç listesindeki maçları hesapla birlikte silmeyeceğini yazar. Sunucu 0.9 ise uygulama 0.8'in bu sunucuya yüklediği maçları hesapla birlikte siler. Uygulama 0.8 analiz işini sorunca aldığı `403 NOT_A_MEMBER` yanıtını bir bağlantı hatası sayar. Uygulama bu durumda sunucudan iş durumunu alamadığını yazar ve ekran açık kaldıkça işi beş saniyede bir yeniden sorar. Uygulamanın 0.7.0 sürümü oturum göndermez ve 0.9.0 sunucusuyla artık çalışmaz. Sunucu `/docs`, `/redoc` ve `/openapi.json` adreslerini sunmaz. Makinece okunur sözleşme yalnız depodaki dosyadadır.

| Uç | Görev | Başarı | Hatalar |
|---|---|---|---|
| `GET /health` | Sunucu, model ve ffprobe durumu, boyut sınırları. Oturum istemez | 200 | |
| `POST /matches` | İki farklı takım adıyla maç açar. Sunucu kimliği üretir | 201 | 401, 422 |
| `PUT /matches/{id}` | Maçı istemcinin seçtiği kimlikle açar ya da o kimlikteki maçı döner | 201, 200 | 401, 403, 422 |
| `GET /matches/{id}` | Maçı okur | 200 | 401, 403, 404 |
| `PUT /matches/{id}/rules` | Sunucunun maçın setlerini saydığı kuralı değiştirir. İstek istemcinin sunucudan son okuduğu kuralı da taşır | 200 | 401, 403, 404, 409, 422 |
| `DELETE /matches/{id}` | Maçı ve sunucunun o maç için tuttuğu her şeyi siler | 204 | 401, 403, 404, 409 |
| `POST /matches/{id}/uploads` | Yükleme oturumu açar. Hesabın aynı dosya için bitmemiş yüklemesi varsa onu döner | 201, 200 | 401, 403, 404, 409, 422, 507 |
| `GET /uploads/{id}` | Sunucudaki offset ve durumu verir (devam için) | 200 | 401, 403, 404 |
| `PUT /uploads/{id}/chunks?offset=n` | En çok 4 MiB'lık parça yükler | 200 | 400, 401, 403, 404, 409, 413, 507 |
| `POST /uploads/{id}/complete` | SHA-256 ve dosya doğrulaması yapar, videoyu kaydeder | 200 | 401, 403, 404, 409, 422 |
| `DELETE /uploads/{id}` | Bitmemiş yüklemeden vazgeçer ve baytlarını siler | 204 | 401, 403, 404, 409 |
| `GET /videos/{id}` | Video durumu, hash ve kalite raporu | 200 | 401, 403, 404 |
| `GET /videos/{id}/quality` | Yalnız kalite raporu | 200 | 401, 403, 404 |
| `POST /videos/{id}/quality` | Doğrulanamamış videonun dosya kontrolünü yeniden çalıştırır | 200 | 401, 403, 404, 409 |
| `DELETE /videos/{id}` | Videoyu, kaydını ve o videonun analiz işlerini siler. Maç kaydı kalır | 204 | 401, 403, 404 |
| `POST /videos/{id}/analyses` | Analiz işi açar. Analizör seçili değilse bunu bildirir | 202 | 401, 403, 404, 409, **503** |

## Maç kimliği

`PUT /matches/{id}` gövdesi `POST /matches` gövdesiyle aynıdır. Kimlik küçük harfli ve tireli bir UUID olmalıdır. Sunucu başka bir biçimi `422 MATCH_ID_INVALID` ile reddeder.

- Sunucu o kimlikte maç yoksa maçı isteği gönderen hesabın kişisel maçı olarak açar ve `201` döner. Maç varsa sunucu maçı değiştirmeden sahibine `200` ile döner. İstemci aynı çağrıyı güvenle yineleyebilir. Başka bir hesap o kimliğe `403 NOT_A_MEMBER` alır ve sunucu hiçbir şey yazmaz.
- Android uygulaması kimliği kendisi üretir ve ilk isteği göndermeden önce telefona kaydeder. İlk isteğin yanıtı kaybolsa da uygulama maçı adresleyebilir: Uygulama aynı kimlikle çağrıyı yineler ya da `DELETE /matches/{id}` ile maçı siler.
- `POST /matches` kimliği yalnız yanıtta verir. Yanıtı kaybolan bir `POST` sunucuda kimsenin adresleyemediği bir maç bırakır. Yeni istemciler bu yüzden `PUT` kullanır.

## Yükleme

`POST /matches/{id}/uploads` gövdesi: `fileName`, `sizeBytes`, `mimeType`, `rightsAttested: true`, `allowTraining: false`, isteğe bağlı `clientSha256` (64 küçük hex karakter).

- `rightsAttested` kullanıcının beyanıdır, hak kanıtı değildir.
- `allowTraining: true` reddedilir. Eğitim izni ayrı bir onay akışı ister ve o akış henüz yoktur.
- Parça offset'i yanlışsa sunucu `409 OFFSET_MISMATCH` ve `details.expectedOffset` döner. İstemci o offset'ten devam eder.
- `complete` çağrısı dosyanın tamamı gelmeden `409 UPLOAD_INCOMPLETE` döner.
- `clientSha256` verildiyse sunucu sakladığı baytların özetini onunla karşılaştırır. Uyuşmazlıkta `422 UPLOAD_CHECKSUM_MISMATCH` döner, oturumu offset 0'a alır ve istemci dosyayı yeniden gönderir.
- Aynı oturum için tekrarlanan `complete` aynı videoyu döner.
- Doğrulama sürerken gelen ikinci `complete` çağrısı `409 UPLOAD_FINALIZING` döner. Sunucu süren bir doğrulamayı başlangıcından sonra 15 dakika açık kaldığında terk edilmiş sayar ve sonraki çağrıda işi devralır. Kapanış ya da çökme bir doğrulamayı yarıda keserse sunucu açılıştan sonraki ilk çağrıda işi hemen devralır. Sunucu bir doğrulamayı devralınca eski isteğe `409 UPLOAD_FINALIZING` döner. Sunucu eski istek için video yazmaz, yükleme kaydını değiştirmez ve özet uyuşmazlığında dosyayı boşaltmaz.
- Sunucu bir parçayı yazarken kesilirse kayıtlı offset geçerlidir. Sonraki parça fazla baytların üzerine yazar. Diskteki dosya kayıtlı offset'ten kısaysa sunucu offset'i diskteki boyuta çeker ve `409 OFFSET_MISMATCH` ile bildirir.
- `DELETE /uploads/{id}` yarım kalan yüklemeyi kapatır (`ABORTED`). Videoya dönüşmüş yükleme için `409 UPLOAD_COMPLETED` döner; o durumda video silinir.
- İstemci aynı hesapla aynı maç için aynı dosyanın yüklemesini yeniden isterse (aynı `clientSha256`, `sizeBytes`, `fileName` ve `mimeType`) ve o yükleme hâlâ `UPLOADING` durumundaysa sunucu yeni bir yükleme açmaz. Sunucu var olan yüklemeyi `200` ile ve `nextOffset` değeriyle döner ve sınırları yeniden denetlemez. Yükleme başlatan isteğin yanıtı kaybolsa da sunucu dosyayı böylece bir kez sayar.
- Sunucu yüklemeyi istemcinin bildirdiği boyutla sayar. Sunucu kayıt koduyla açılan bir hesabın kişisel maç videolarına ve yarım yüklemelerine en çok 8 GiB, kodsuz açılan bir hesabınkine en çok 4 GiB ayırır. Sunucu kodsuz açılan bir hesaptan aynı anda tek bir yarım yükleme kabul eder. Sunucu bütün hesapların kişisel maç videolarına birlikte en çok 40 GiB ayırır. Sınırı aşan yükleme başlamaz ve sunucu `409 QUOTA_EXCEEDED` döner. Yanıt `details.scope` ve `details.limitBytes` taşır. Hesabın sınırında yanıt `details.usedBytes` de taşır ([API_V1.md](API_V1.md) bölüm 10).
- Sunucu yeni yüklemenin boyutunu ve bitmemiş yüklemelerin henüz gelmeyen baytlarını diskteki boş yerden düşer. Kalan yer `VOLLEYSTAT_DISK_FLOOR_GIB` sınırının (varsayılan 20 GiB) altına inecekse sunucu `507 STORAGE_FULL` döner. Bir parça boş yeri bu sınırın yarısının altına indirecekse sunucu parçayı yazmaz ve yine `507 STORAGE_FULL` döner. Yanıt yalnız sınırı taşır (`details.floorBytes`).
- Sunucu yüklemenin son dokunuş zamanını yükleme başlarken ve her parçada günceller. Sunucu son dokunuştan sonra açık kaldığı toplam bir gün boyunca parça almayan yüklemeyi kapatır ve kapalı kaldığı süreyi saymaz (bölüm "Silme ve saklama").

## Video durumu ve kalite raporu

`complete` yanıtı ve `GET /videos/{id}`:

```json
{
  "id": "…", "videoId": "…", "matchId": "…", "status": "READY",
  "sizeBytes": 123456, "sha256": "…",
  "quality": {
    "videoId": "…", "status": "READY", "rejectCode": null,
    "media": {"container": "video/mp4", "codec": "h264", "durationMs": 5000,
              "width": 1280, "height": 720, "rotation": 0, "fpsAverage": 30.0, "fpsNominal": 30.0},
    "warnings": [],
    "probeVersion": "ffprobe-v2",
    "sceneChecks": {"courtVisible": {"value": null, "status": "NOT_MEASURED"}, "…": "…"}
  }
}
```

| `status` | Anlamı |
|---|---|
| `READY` | Dosya oynatılabilir bir video. Analize uygun. |
| `QUARANTINED` | Dosya saklandı ama analiz edilemez. Neden `rejectCode` alanındadır. |
| `STORED_UNVALIDATED` | Dosya kontrolü çalışamadı (ffprobe yok ya da zaman aşımı). Video analiz edilemez. `POST /videos/{id}/quality` kontrolü yeniden dener. |

| `rejectCode` | Neden |
|---|---|
| `NOT_A_VIDEO` | İmza MP4/MOV ya da WebM/MKV değil, ya da dosyada video akışı yok |
| `VIDEO_CORRUPT` | Dosya bozuk ya da yarım. Sunucu başlıkları, MP4 kutu boyutlarını ve videonun son saniyelerindeki paketleri kontrol eder. |
| `UNSUPPORTED_CODEC` | Codec H.264, HEVC, VP8, VP9, AV1 dışında |
| `VIDEO_DURATION_INVALID` | Süre okunamadı ya da 12 saati aşıyor |
| `PROBE_UNAVAILABLE` | ffprobe kurulu değil |
| `PROBE_TIMEOUT` | Dosya kontrolü süresinde bitmedi. Dosya bozuk sayılmaz, kontrol yeniden denenebilir. |

Uyarılar videoyu reddetmez, daha düşük bir kalite katmanına koyar: `LOW_RESOLUTION` (kısa kenar 720 pikselden az), `LOW_FRAME_RATE` (23,5 fps altı; 23,976 fps kabul edilir), `PORTRAIT_ORIENTATION`, `VARIABLE_FRAME_RATE`, `CONTAINER_MISMATCH` (beyan edilen tür dosyanın gerçek kapsayıcısıyla uyuşmuyor).

`sceneChecks` altındaki her alan `NOT_MEASURED` döner. Sunucu sahanın, topun ya da kamera sabitliğinin görünürlüğünü ölçmez. Bu alanlar ölçüm eklenene kadar değer taşımaz.

## Silme ve saklama

Sunucu aldığı veriyi bir silme çağrısı gelene kadar saklar. Tek istisna yarım kalan yüklemedir: Sunucu son dokunuştan sonra açık kaldığı toplam bir gün boyunca parça almayan yüklemeyi kapatır ve baytlarını siler. Sunucu kapalı kaldığı süreyi saymaz.

| Çağrı | Sunucunun sildiği | Sunucuda kalan |
|---|---|---|
| `DELETE /matches/{id}` | Maç kaydı (takım adları, maç adı, tarih, salon, tür), maçın bütün yükleme kayıtları ve baytları, videolar, analiz işleri, ralliler, inceleme kartları, kararlar ve istek anahtarları | Hiçbir şey |
| `DELETE /videos/{id}` | Video kaydı ve baytları, o videonun analiz işleri, rallileri, kartları ve kararları | Maç kaydı ve `DELETED` durumundaki yükleme kaydı |
| `DELETE /uploads/{id}` | Bitmemiş yüklemenin baytları | Maç kaydı ve `ABORTED` durumundaki yükleme kaydı |
| Dakikalık tarama (çağrı yok) | Sunucunun açık kaldığı toplam bir gün boyunca parça almayan yüklemenin ve aynı süre boyunca bitmeyen doğrulamanın baytları | Maç kaydı ve `ABORTED` durumundaki yükleme kaydı |
| `POST /me/delete` | Hesap, oturumları, telefon anahtarları ve hesaba ait kişisel maçların her şeyi (`DELETE /matches/{id}` gibi) | Hiçbir hesaba bağlı olmayan (`LEGACY`) maçlar |

`DELETE /matches/{id}` kuralları:

- Sunucu bütün satırları tek veritabanı işleminde siler. Sunucu dosyaları işlemi onaylamadan hemen önce kaldırır, çünkü silme geride dosya bırakmamalıdır. Onay başarısız olursa satırlar dosyaları olmadan geri gelir ve istemci aynı çağrıyı yineleyince sunucu onları da siler.
- Sunucu silinen satırların üzerine veritabanı dosyasında sıfır yazar (`PRAGMA secure_delete`) ve silmeden sonra veritabanı günlüğünü boşaltır. Silinen takım adları ve kararlar veritabanı dosyalarından okunamaz. O anda başka bir istek veritabanını okuyorsa günlük bir sonraki silmede ya da son bağlantı kapanınca boşalır.
- Sunucu maçın bir yüklemesini doğruluyorsa `409 UPLOAD_FINALIZING` döner ve hiçbir şeyi silmez. İstemci doğrulama bitince yeniden dener. Sunucu süren bir doğrulamayı başlangıcından sonra 15 dakika açık kaldığında terk edilmiş sayar ve o durumda maçı siler. Kapanış ya da çökme bir doğrulamayı yarıda keserse sunucu açılıştan sonra gelen silme isteğinde maçı hemen siler.
- Sunucu çalışan analiz işini durdurur. Sunucu iş satırını sildiği için iş hiçbir şey yazamaz.
- Silinen maç için `GET /matches/{id}` ve ikinci bir `DELETE /matches/{id}` çağrısı `404 MATCH_NOT_FOUND` döner.
- Yalnız maçın sahibi maçı silebilir. Başka bir hesap `403 NOT_A_MEMBER` alır. Hiçbir hesaba bağlı olmayan (`LEGACY`) bir maçı silmek isteyen her hesap da aynı yanıtı alır. Operatör `LEGACY` maçları `python -m app.admin legacy --delete` komutuyla siler.
- Android uygulaması "Maçı sil" ve "Yüklemeyi iptal et" işlerinde yalnız bu ucu çağırır. Uygulama maçı telefondan yalnız `204` ya da `404 MATCH_NOT_FOUND` yanıtından sonra kaldırır. Uygulama başka her yanıtı ve yanıtsız kalan çağrıyı silinmemiş sayar ve nedenini ekrana yazar. Uygulama `403 NOT_A_MEMBER` yanıtını da silinmemiş sayar. Uygulama erişimi olmayan bir maçı yalnız telefondan kaldırabilir ve bunun için sunucuya istek göndermez.
- Sunucu 0.2 önizlemesinin eski dosya düzenindeki baytları da siler (`videos/<id>.bin` ve `uploads/<id>.part`).

## Analiz

`POST /videos/{id}/analyses`:

- Video `READY` değilse `409 VIDEO_NOT_READY`.
- Sunucuda analizör seçili değilse `503 MODEL_NOT_CONFIGURED`. Sunucu bu durumda tahmin üretmez. Varsayılan durum budur.
- Analizör seçiliyse sunucu bir analiz işi açar. İşin durumları, inceleme kartları ve rapor [API_V1.md](API_V1.md) belgesindedir.

## Hata biçimi

Bütün hatalar bu zarfla döner. Tanımsız adres `NOT_FOUND`, yanlış yöntem `METHOD_NOT_ALLOWED` kodunu taşır.

```json
{"error": {"code": "OFFSET_MISMATCH", "message": "…", "details": {"expectedOffset": 3}}}
```

## Bilinen sınırlar

- Tek süreç, tek bilgisayar. Eşzamanlı çok süreçli kullanım desteklenmez.
- Sunucu istemcinin bıraktığı bir yüklemeyi ancak açık kaldığı toplam bir günün sonunda siler. Sunucu her gece kapanıyorsa yüklemeyi takvimde bir günden daha geç siler. O zamana kadar sunucu yüklemeyi istemcinin bildirdiği boyutla hesabın ve sunucunun sınırına sayar ve henüz gelmeyen baytlarını diskteki boş yerden düşer. İstemci yüklemeden daha önce vazgeçerse `DELETE /uploads/{id}` ya da `DELETE /matches/{id}` çağırır.
- Sunucu boş disk yerini yükleme başlarken ve her parçada okur. Başka bir program diski bu arada doldurursa yükleme yarıda kalabilir.
- `POST /matches` kullanan bir istemci yanıtı kaybederse sunucuda adresleyemediği bir maç bırakır. `PUT /matches/{id}` bu boşluğu kapatır ve Android uygulaması yalnız onu kullanır.
- Sunucu silinen dosyanın diskteki bloklarının üzerine yazmaz. Dosya sistemi o blokları yeniden kullanana kadar bir disk kurtarma aracı video baytlarını bulabilir.
- Dosya kontrolü videonun tamamını çözmez. Ortasındaki bozuk kareleri yakalamaz.
- Sunucu klip kesmez. `GET /videos/{id}/content` videonun tamamını `Range` desteğiyle sunar ve istemci klip aralığını kendisi oynatır.
- Nihai ürün sözleşmesi bundan geniştir. Fark [GAP_ANALYSIS.md](GAP_ANALYSIS.md) içindedir.
