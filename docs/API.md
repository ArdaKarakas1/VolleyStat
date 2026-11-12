# Yerel geliştirme API'si (v0.2)

**Kök:** `http://127.0.0.1:8000/v1`. Android emülatöründen `http://10.0.2.2:8000/v1`.
**Kapsam:** Yalnızca yerel geliştirme. Hesap sistemi ve TLS yoktur.

Makinece okunur sözleşme: [openapi-local-dev.json](openapi-local-dev.json). Dosya `create_app().openapi()` çıktısıdır.

| Uç | Görev | Başarı | Hatalar |
|---|---|---|---|
| `GET /health` | Sunucu, model ve ffprobe durumu | 200 | |
| `POST /matches` | İki farklı takım adıyla maç açar | 201 | 422 |
| `GET /matches/{id}` | Maçı okur | 200 | 404 |
| `POST /matches/{id}/uploads` | Yükleme oturumu açar | 201 | 404, 422 |
| `GET /uploads/{id}` | Sunucudaki offset ve durumu verir (devam için) | 200 | 404 |
| `PUT /uploads/{id}/chunks?offset=n` | En çok 4 MiB'lık parça yükler | 200 | 404, 409, 413 |
| `POST /uploads/{id}/complete` | SHA-256 ve dosya doğrulaması yapar, videoyu kaydeder | 200 | 404, 409, 422 |
| `GET /videos/{id}` | Video durumu, hash ve kalite raporu | 200 | 404 |
| `GET /videos/{id}/quality` | Yalnız kalite raporu | 200 | 404 |
| `DELETE /videos/{id}` | Videoyu ve kaydını siler | 204 | 404 |
| `POST /videos/{id}/analyses` | Model olmadığını bildirir | **503** | 404, 409 |

## Yükleme

`POST /matches/{id}/uploads` gövdesi: `fileName`, `sizeBytes`, `mimeType`, `rightsAttested: true`, `allowTraining: false`, isteğe bağlı `clientSha256` (64 küçük hex karakter).

- `rightsAttested` kullanıcının beyanıdır, hak kanıtı değildir.
- `allowTraining: true` reddedilir. Eğitim izni ayrı bir onay akışı ister ve o akış henüz yoktur.
- Parça offset'i yanlışsa sunucu `409 OFFSET_MISMATCH` ve `details.expectedOffset` döner. İstemci o offset'ten devam eder.
- `complete` çağrısı dosyanın tamamı gelmeden `409 UPLOAD_INCOMPLETE` döner.
- `clientSha256` verildiyse sunucu sakladığı baytların özetini onunla karşılaştırır. Uyuşmazlıkta `422 UPLOAD_CHECKSUM_MISMATCH` döner, oturumu offset 0'a alır ve istemci dosyayı yeniden gönderir.
- Aynı oturum için tekrarlanan `complete` aynı videoyu döner.

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
    "probeVersion": "ffprobe-v1",
    "sceneChecks": {"courtVisible": {"value": null, "status": "NOT_MEASURED"}, "…": "…"}
  }
}
```

| `status` | Anlamı |
|---|---|
| `READY` | Dosya oynatılabilir bir video. Analize uygun. |
| `QUARANTINED` | Dosya saklandı ama analiz edilemez. Neden `rejectCode` alanındadır. |
| `STORED_UNVALIDATED` | Sunucuda ffprobe yok. Dosya doğrulanmadı ve analiz edilemez. |

| `rejectCode` | Neden |
|---|---|
| `NOT_A_VIDEO` | İmza MP4/MOV ya da WebM/MKV değil, ya da dosyada video akışı yok |
| `VIDEO_CORRUPT` | ffprobe dosyayı okuyamadı (bozuk ya da yarım dosya) |
| `UNSUPPORTED_CODEC` | Codec H.264, HEVC, VP8, VP9, AV1 dışında |
| `VIDEO_DURATION_INVALID` | Süre okunamadı ya da 12 saati aşıyor |
| `PROBE_UNAVAILABLE` | ffprobe kurulu değil |

Uyarılar videoyu reddetmez, daha düşük bir kalite katmanına koyar: `LOW_RESOLUTION` (kısa kenar 720 pikselden az), `LOW_FRAME_RATE` (24 fps altı), `PORTRAIT_ORIENTATION`, `VARIABLE_FRAME_RATE`.

`sceneChecks` altındaki her alan `NOT_MEASURED` döner. Sunucu sahanın, topun ya da kamera sabitliğinin görünürlüğünü ölçmez. Bu alanlar ölçüm eklenene kadar değer taşımaz.

## Analiz

`POST /videos/{id}/analyses`:

- Video `READY` değilse `409 VIDEO_NOT_READY`.
- Video `READY` ise `503 MODEL_NOT_CONFIGURED`. Model yoktur ve sunucu tahmin üretmez.

## Hata biçimi

```json
{"error": {"code": "OFFSET_MISMATCH", "message": "…", "details": {"expectedOffset": 3}}}
```

## Bilinen sınırlar

- Tek süreç, tek bilgisayar. Eşzamanlı çok süreçli kullanım desteklenmez.
- İndirme ve klip sunma ucu yoktur.
- Nihai ürün sözleşmesi (`/api/v1/videos`, analiz işleri, inceleme kartları, rapor) bundan geniştir. Fark [GAP_ANALYSIS.md](GAP_ANALYSIS.md) içindedir.
