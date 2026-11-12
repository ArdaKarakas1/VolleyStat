# Yerel geliştirme API'si (v0.4)

**Kök:** `http://127.0.0.1:8000/v1`. Android emülatöründen `http://10.0.2.2:8000/v1`.
**Kapsam:** Yalnızca yerel geliştirme. Hesap sistemi ve TLS yoktur.

Makinece okunur sözleşme: [openapi-local-dev.json](openapi-local-dev.json). Dosya `create_app().openapi()` çıktısıdır.

Sürüm 0.4 analiz işlerini, rallileri, inceleme kartlarını, kanıt videosunu ve maç raporunu ekler. Bu uçların sözleşmesi [API_V1.md](API_V1.md) belgesindedir. Bu belge maç, yükleme ve video uçlarını anlatır.

| Uç | Görev | Başarı | Hatalar |
|---|---|---|---|
| `GET /health` | Sunucu, model ve ffprobe durumu, boyut sınırları | 200 | |
| `POST /matches` | İki farklı takım adıyla maç açar | 201 | 422 |
| `GET /matches/{id}` | Maçı okur | 200 | 404 |
| `POST /matches/{id}/uploads` | Yükleme oturumu açar | 201 | 404, 422 |
| `GET /uploads/{id}` | Sunucudaki offset ve durumu verir (devam için) | 200 | 404 |
| `PUT /uploads/{id}/chunks?offset=n` | En çok 4 MiB'lık parça yükler | 200 | 404, 409, 413 |
| `POST /uploads/{id}/complete` | SHA-256 ve dosya doğrulaması yapar, videoyu kaydeder | 200 | 404, 409, 422 |
| `DELETE /uploads/{id}` | Bitmemiş yüklemeden vazgeçer ve baytlarını siler | 204 | 404, 409 |
| `GET /videos/{id}` | Video durumu, hash ve kalite raporu | 200 | 404 |
| `GET /videos/{id}/quality` | Yalnız kalite raporu | 200 | 404 |
| `POST /videos/{id}/quality` | Doğrulanamamış videonun dosya kontrolünü yeniden çalıştırır | 200 | 404, 409 |
| `DELETE /videos/{id}` | Videoyu, kaydını ve o videonun analiz işlerini siler | 204 | 404 |
| `POST /videos/{id}/analyses` | Analiz işi açar. Analizör seçili değilse bunu bildirir | 202 | 404, 409, **503** |

## Yükleme

`POST /matches/{id}/uploads` gövdesi: `fileName`, `sizeBytes`, `mimeType`, `rightsAttested: true`, `allowTraining: false`, isteğe bağlı `clientSha256` (64 küçük hex karakter).

- `rightsAttested` kullanıcının beyanıdır, hak kanıtı değildir.
- `allowTraining: true` reddedilir. Eğitim izni ayrı bir onay akışı ister ve o akış henüz yoktur.
- Parça offset'i yanlışsa sunucu `409 OFFSET_MISMATCH` ve `details.expectedOffset` döner. İstemci o offset'ten devam eder.
- `complete` çağrısı dosyanın tamamı gelmeden `409 UPLOAD_INCOMPLETE` döner.
- `clientSha256` verildiyse sunucu sakladığı baytların özetini onunla karşılaştırır. Uyuşmazlıkta `422 UPLOAD_CHECKSUM_MISMATCH` döner, oturumu offset 0'a alır ve istemci dosyayı yeniden gönderir.
- Aynı oturum için tekrarlanan `complete` aynı videoyu döner.
- Doğrulama sürerken gelen ikinci `complete` çağrısı `409 UPLOAD_FINALIZING` döner. 15 dakikadan eski bir doğrulama terk edilmiş sayılır ve sonraki çağrı işi devralır.
- Sunucu bir parçayı yazarken kesilirse kayıtlı offset geçerlidir. Sonraki parça fazla baytların üzerine yazar. Diskteki dosya kayıtlı offset'ten kısaysa sunucu offset'i diskteki boyuta çeker ve `409 OFFSET_MISMATCH` ile bildirir.
- `DELETE /uploads/{id}` yarım kalan yüklemeyi kapatır (`ABORTED`). Videoya dönüşmüş yükleme için `409 UPLOAD_COMPLETED` döner; o durumda video silinir.

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
- Terk edilen yüklemeler kendiliğinden silinmez. İstemci `DELETE /uploads/{id}` çağırır.
- Dosya kontrolü videonun tamamını çözmez. Ortasındaki bozuk kareleri yakalamaz.
- Sunucu klip kesmez. `GET /videos/{id}/content` videonun tamamını `Range` desteğiyle sunar ve istemci klip aralığını kendisi oynatır.
- Nihai ürün sözleşmesi bundan geniştir. Fark [GAP_ANALYSIS.md](GAP_ANALYSIS.md) içindedir.
