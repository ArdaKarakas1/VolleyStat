# Analiz, inceleme ve rapor sözleşmesi (API 0.4)

Bu belge arka uç, Android istemcisi ve analiz modülleri arasındaki tek sözleşmedir. Bir taraf bu belgeyi değiştirmeden davranışını değiştirmez. Kök: `/v1`. Yükleme uçları [API.md](API.md) içindedir ve aynen geçerlidir.

Değişmez kurallar:

- Model önerisi, kullanıcı kararı ve rapordaki değer ayrı kayıtlardır. Öneri tek başına istatistiğe girmez.
- Bilinmeyen değer `null` ve `status: "UNKNOWN"` olarak gider. Sunucu ölçülmeyen bir değeri `0` yazmaz.
- Sunucu güven puanı uydurmaz. `confidence` yalnızca `kind` değeri `MODEL` olan analizörden gelir, yoksa `null` kalır.
- İnceleme yalnızca analizörün önerdiği ralliyi onaylar ya da reddeder. İnceleme analizörün kaçırdığı ralliyi ekleyemez. Analizör ölçümü kayıtlı bir model değilse rapordaki her sayı alt sınırdır (`ralliesMayBeMissing`).
- `mock: true` taşıyan iş gerçek analiz değildir. İstemci bu işi her ekranda DEMO olarak işaretler.
- Sunucu oyuncu adı tutmaz. Oyuncu, taraf ve forma numarasıyla anılır; adları telefon kendi kadrosundan eşler.

## 1. Maç

`POST /v1/matches`

```json
{"homeTeamName": "A Takımı", "awayTeamName": "B Takımı",
 "name": null, "playedOn": "2026-11-12", "venue": null, "kind": "TRAINING"}
```

`homeTeamName` ve `awayTeamName` zorunludur. `name` (en çok 80), `playedOn` (`YYYY-AA-GG`), `venue` (en çok 80) isteğe bağlıdır. `kind` `TRAINING` ya da `OFFICIAL` olur, varsayılan `TRAINING`. `kind` yalnızca etikettir; sunucu resmî maç iddiası üretmez. `GET /v1/matches/{id}` aynı alanları döner.

## 2. Analizör

Sunucu `VOLLEYSTAT_ANALYZER` ortam değişkenini (ya da `python -m app --analyzer`) okur:

| Değer | `kind` | Davranış |
|---|---|---|
| (boş) ya da `none` | | Analiz yok. `POST …/analyses` `503 MODEL_NOT_CONFIGURED` döner. |
| `demo` | `MOCK` | Videonun süresinden sabit aralıklı uydurma ralli aralıkları üretir. Kazanan önermez. Her çıktı `mock: true` taşır. Yalnız arayüz denemesi içindir. |
| `motion-baseline` | `BASELINE` | Videodaki hareket enerjisinden ralli aralığı önerir. Kazanan önermez. Kimse doğruluğunu ölçmedi. Bu değer deponun `ml` paketini ister. Paket kurulu değilse sunucu açık bir hata mesajıyla başlamayı reddeder. |

Analizörün `kind` değeri tam olarak `MOCK`, `BASELINE` ya da `MODEL` olur. Sunucu başka bir değerle başlamaz. Analizörün ne öne sürebileceği türüne bağlıdır:

| `kind` | Kazanan önerisi | `confidence` |
|---|---|---|
| `MOCK` | Öneremez (`UNKNOWN`) | Veremez |
| `BASELINE` | Önerebilir. İstemci öneriyi ipucu olarak gösterir ve seçili getirmez. | Veremez |
| `MODEL` | Önerebilir | Verebilir |

Bu kuralı çiğneyen çıktı işi `ANALYZER_ERROR` koduyla düşürür.

`GET /v1/health` yanıtına `analyzer` eklenir: `null` ya da `{"id", "version", "kind"}`.

## 3. Analiz işi

| Uç | Sonuç |
|---|---|
| `POST /v1/videos/{videoId}/analyses` | `202` yeni iş · `200` süren iş ya da aynı `Idempotency-Key` · `409 VIDEO_NOT_READY` · `503 MODEL_NOT_CONFIGURED` · `404` |
| `GET /v1/analyses/{jobId}` | İş |
| `GET /v1/videos/{videoId}/analyses/latest` | Videonun son işi · `404 ANALYSIS_NOT_FOUND` |
| `POST /v1/analyses/{jobId}/cancel` | `CANCELLED` iş · bitmiş işte `409 JOB_FINISHED` |

Bir videonun aynı anda tek süren işi olur. `Idempotency-Key` başlığı isteğe bağlıdır. Sunucu bir işle yanıtladığı her anahtarı saklar: işi oluşturan anahtarı da, süren işe katılan anahtarı da. Aynı anahtar o iş bittikten sonra bile aynı işi döner ve ikinci bir analiz başlatmaz. Sunucu analizörsüz yeniden başlasa bile saklı anahtar işini döner. `503 MODEL_NOT_CONFIGURED` yalnızca yeni bir iş başlatacak ya da süren işe katılacak isteğe gider.

```json
{
  "id": "…", "videoId": "…", "matchId": "…",
  "state": "PROCESSING",
  "stage": "DETECTING_RALLIES",
  "progress": {"done": 1200, "total": 5400, "unit": "FRAMES"},
  "analyzer": {"id": "motion-baseline", "version": "0.1", "kind": "BASELINE"},
  "mock": false,
  "failure": null,
  "counts": {"rallies": 0, "reviewOpen": 0, "reviewTotal": 0},
  "createdAt": "2026-11-12T21:40:00Z", "startedAt": "…", "finishedAt": null
}
```

- `state`: `QUEUED` → `PROCESSING` → `NEEDS_REVIEW` → `COMPLETE`. Ayrıca `FAILED` ve `CANCELLED`.
- `stage`: `null`, `PREPROCESSING`, `DETECTING_RALLIES`, `GENERATING_REVIEW`. Yalnızca analizörün gerçekten bildirdiği aşama yazılır.
- `progress`: Analizör ölçtüyse dolar, ölçmediyse `null` kalır. Analizör ölçümü geri çekerse sunucu `progress` alanını hemen `null` yapar. İstemci `null` için belirsiz gösterge kullanır, yüzde uydurmaz.
- `NEEDS_REVIEW`: En az bir açık `SCORE` kartı var. Son `SCORE` kartı kapanınca iş `COMPLETE` olur. Kart yeniden açılırsa iş `NEEDS_REVIEW` durumuna döner.
- `failure.code`: `ANALYZER_ERROR`, `VIDEO_UNREADABLE`, `NO_RALLIES_FOUND`, `JOB_TIMEOUT`.
- İşçi tek süreçte arka plan iş parçacığıdır. Yarım kalan işi nabız (heartbeat) eskiyince yeniden kuyruğa alır.
- İşçi kuyruktaki işi yalnızca `id`, `version` ve `kind` değerleri işle aynı olan analizörle çalıştırır. Sunucu başka bir analizörle yeniden başladıysa iş `ANALYZER_ERROR` koduyla düşer.

## 4. Ralliler

`GET /v1/analyses/{jobId}/rallies` → `{"rallies": [Rally]}`

```json
{"id": "…", "sequence": 3, "startMs": 41200, "endMs": 52800,
 "status": "PROPOSED", "proposedWinner": "UNKNOWN", "confidence": null,
 "winner": null, "terminalAction": null, "scorer": null,
 "uncertaintyReasons": ["NO_WINNER_MODEL"]}
```

- `status`: `PROPOSED` (karar yok), `VERIFIED` (kullanıcı kazananı seçti), `UNRESOLVED` (kullanıcı emin değil), `REJECTED` (kullanıcı "ralli değil" dedi).
- `winner`: `HOME`, `AWAY` ya da `null`. Yalnız kullanıcı kararıyla dolar.
- `terminalAction`: `ATTACK`, `BLOCK`, `ACE`, `OPPONENT_ERROR` ya da `null`.
- `scorer`: `{"side": "HOME", "jersey": 7}` ya da `null`.

## 5. İnceleme kartları

`GET /v1/analyses/{jobId}/review-cards?scope=SCORE|DETAIL|ALL` (varsayılan `SCORE`)

```json
{"cards": [Card], "position": {"answered": 7, "total": 18}}
```

```json
{"id": "…", "rallyId": "…", "rallySequence": 3,
 "questionType": "RALLY_WINNER", "scope": "SCORE",
 "clipStartMs": 46800, "clipEndMs": 54800,
 "suggestedValue": null, "uncertaintyReasons": ["NO_WINNER_MODEL"],
 "options": ["HOME", "AWAY", "NOT_A_RALLY", "UNSURE"],
 "status": "OPEN", "answer": null, "version": 1}
```

| `questionType` | `scope` | `options` | Ne zaman oluşur |
|---|---|---|---|
| `RALLY_WINNER` | `SCORE` | `HOME`, `AWAY`, `NOT_A_RALLY`, `UNSURE` | Her ralli önerisi için |
| `TERMINAL_ACTION` | `DETAIL` | `ATTACK`, `BLOCK`, `ACE`, `OPPONENT_ERROR`, `UNSURE` | Kazanan `HOME` ya da `AWAY` seçilince |
| `SCORER` | `DETAIL` | `JERSEY:<1–99>`, `UNKNOWN_PLAYER` | Sayı `ATTACK`, `BLOCK` ya da `ACE` ile gelince |

- Kartlar ralli sırasıyla gelir. Bir kart tek karar ister.
- Klip rallinin sonunu gösterir: `clipStartMs = max(startMs, endMs − 6000)`, `clipEndMs = min(videoSüresi, endMs + 2000)`.
- `suggestedValue` yalnızca model bir değer önerdiyse dolar. İstemci öneriyi seçili getirmez.
- `DETAIL` kartları isteğe bağlıdır. Açık `DETAIL` kartı işi `NEEDS_REVIEW` durumunda tutmaz.

`POST /v1/review-cards/{cardId}/decisions`

```json
{"selection": "AWAY", "expectedVersion": 1, "idempotencyKey": "…"}
```

- `200` → `{"decisionId", "card": Card, "job": Job, "reportRevision": 4}`
- `409 REVIEW_STALE_VERSION` (`details.currentVersion`).
- Aynı `idempotencyKey` aynı kart, aynı seçim ve aynı `expectedVersion` ile gelirse sunucu saklı yanıtı döner ve ikinci kayıt oluşturmaz. Aynı anahtar başka bir kart, seçim ya da `expectedVersion` ile gelirse sunucu `409 IDEMPOTENCY_KEY_REUSED` döner ve hiçbir şeyi değiştirmez. İstemci her karar için yeni bir anahtar üretir.
- `422 INVALID_SELECTION`: Seçim kartın `options` listesinde yok.
- Yanıtlanmış karta yeni karar verilebilir (düzeltme). Kazanan değişirse sunucu o rallinin `DETAIL` kartlarını ve yanıtlarını kaldırır.

`POST /v1/review-cards/{cardId}/reopen` `{"expectedVersion": 2}` kartı `OPEN` durumuna alır (geri alma). Sunucu `200` ve `{"card": Card, "job": Job, "reportRevision": 5}` döner. Bu yanıt karar yanıtıyla aynı biçimdedir, yalnız `decisionId` taşımaz. `expectedVersion` eskiyse sunucu `409 REVIEW_STALE_VERSION` döner (`details.currentVersion`). Kart zaten `OPEN` ise ve `expectedVersion` güncelse sunucu `200` döner ve hiçbir şeyi değiştirmez. Sunucu her kararı ve geri almayı `review_decisions` tablosunda saklar: kart, önceki değer, yeni değer, zaman. Kayıt silinmez.

## 6. Kanıt videosu

`GET /v1/videos/{videoId}/content` videonun baytlarını döner. `Range: bytes=…` desteklenir (`206`, `Content-Range`, `Accept-Ranges`). Bitiş konumu dosyanın sonunda ya da ötesindeyse sunucu dosyanın sonuna kadar gönderir. Başlangıç konumu dosyanın dışındaysa sunucu `416 RANGE_NOT_SATISFIABLE` döner (`details.sizeBytes`). Sunucu okuyamadığı ya da boş bir aralığa da aynı yanıtı verir: `bytes=abc`, `bytes=-`, `bytes=-0`, `bytes=10-5`. Video `READY` değilse `409 VIDEO_NOT_READY`, saklanan baytlar yoksa `409 VIDEO_FILE_MISSING`. İstemci klibi bu adresten `clipStartMs`–`clipEndMs` aralığında oynatır.

## 7. Rapor

`GET /v1/matches/{matchId}/report` → Rapor · hiçbir işi ralli üretmemiş maçta `404 REPORT_NOT_AVAILABLE` (iş kuyruktaysa, düştüyse ya da iptal edildiyse de)

```json
{
  "matchId": "…", "jobId": "…", "revision": 4,
  "analyzer": {"id": "motion-baseline", "version": "0.1", "kind": "BASELINE"}, "mock": false,
  "verificationStatus": "PARTIALLY_VERIFIED",
  "rallies": {"proposed": 18, "verified": 7, "rejected": 1, "unresolved": 0, "open": 10},
  "ralliesMayBeMissing": true,
  "rules": {"setTarget": 25, "decidingSetTarget": 15, "winBy": 2, "decidingSetNumber": 5},
  "score": {"status": "PARTIAL", "setsWon": {"home": 0, "away": 0},
            "sets": [{"number": 1, "home": 4, "away": 3, "complete": false}],
            "stoppedAtRally": 9},
  "teams": {
    "home": {"points": {"value": 4, "status": "PARTIAL"},
             "attackPoints": {"value": 2, "status": "PARTIAL"},
             "blockPoints": {"value": null, "status": "UNKNOWN"},
             "aces": {"value": null, "status": "UNKNOWN"},
             "opponentErrors": {"value": null, "status": "UNKNOWN"}},
    "away": {"…": "…"}
  },
  "players": [{"side": "HOME", "jersey": 7, "points": 2, "attackPoints": 2, "blockPoints": 0, "aces": 0}],
  "unattributed": {"home": 2, "away": 3},
  "coverage": [{"metricId": "RALLY_WINNER", "eligibleN": 17, "observedN": 7, "verifiedN": 7, "status": "PARTIAL"}]
}
```

- `verificationStatus`: `DRAFT` (hiç karar yok), `PARTIALLY_VERIFIED`, `VERIFIED` (açık `SCORE` kartı yok ve `UNRESOLVED` ralli yok).
- Değer nesnesi: `{"value": sayı ya da null, "status": "MEASURED" | "PARTIAL" | "UNKNOWN"}`. `PARTIAL` değerin alt sınır olduğunu söyler. `UNKNOWN` değerde `value` her zaman `null` olur.
- Rapor maçın rallisi olan en yeni işini izler. `revision` o işe aittir. Eski bir işin kartına verilen karar o işin `revision` değerini artırır ve raporu değiştirmez.
- `ralliesMayBeMissing`: Analizörün `kind` değeri `MODEL` değilse `true` olur. Kimse o analizörün kaç ralliyi bulduğunu ölçmedi ve inceleme kaçan ralliyi ekleyemez. Bu durumda sunucu `teams` altındaki hiçbir değeri ve `score.status` alanını `MEASURED` olarak bildirmez. Bu alanlar en fazla `PARTIAL` olur, bütün kartlar yanıtlanmış olsa bile. `coverage[].status` bu kuralın dışındadır: O alan kullanıcının önerilen rallilerin ne kadarını karara bağladığını söyler ve bütün öneriler karara bağlandığında `MEASURED` olur. `coverage` analizörün kaçırdığı ralliyi saymaz.
- Skor: Sunucu rallileri sırayla sayar, reddedilen ralliyi atlar ve seti kurala göre kapatır: Set 25 sayıda, beşinci set 15 sayıda ve iki farkla biter. Maçı bitiren bir kural yoktur. Ralliler sürdükçe sunucu yeni set açar, çünkü antrenman maçları çoğu zaman sabit sayıda set oynar. Beşinci setten sonraki set yine 25 sayıya gider.
- Sayım kazananı belli olmayan ilk rallide durur (kartı açık ya da yanıtı `UNSURE`). `score.stoppedAtRally` o rallinin sıra numarasını taşır, sayım sona ulaştıysa `null` olur. O ralliden önce biten setler `complete: true` kalır. Rallinin içinde bulunduğu set o ana kadarki sayılarla ve `complete: false` olarak gelir. Sayım o setin ilk rallisinde durduysa set için satır gelmez, çünkü o sette sayılmış bir sayı yoktur. Sonraki ralliler hiçbir set satırına girmez. `setsWon` yalnızca biten setleri sayar. Hiç doğrulanmış ralli yoksa `score.status` `UNKNOWN`, `setsWon` alanları `null` olur.
- `teams.<taraf>.points` maçın doğrulanmış bütün rallilerini sayar, sayımın durduğu ralliden sonrakileri de. Hiç doğrulanmış ralli yokken iki taraf için de `null` ve `UNKNOWN` olur. Bir ralli doğrulandığı anda iki taraf da sayı taşır: Doğrulanan rallilerin hiçbirini kazanmamış taraf `0` taşır.
- `REJECTED` ralli hiçbir sayıma girmez. `eligibleN` reddedilenleri dışarıda tutar.
- Takımın sayı türleri (`attackPoints` …) yalnızca `TERMINAL_ACTION` kararı olan rallilerden gelir. Hiç karar yoksa `UNKNOWN`, bir kısmında varsa `PARTIAL` olur. Sunucu sıfır olan bir alt sınırı sayı olarak göndermez: O türde henüz karar yoksa ve sayım tamamlanmadıysa değer `null` ve `UNKNOWN` olur. `0` yalnızca sayım tamamlandığında (`MEASURED`) gelir.
- `players` yalnızca `SCORER` kararıyla forma numarası verilen sayıları içerir. Bu sayılar düz sayımdır ve doğrulama sürdükçe artabilir. Oyuncusu bilinmeyen sayılar `unattributed` altında kalır ve takım toplamına girer. `unattributed.<taraf>` o tarafın `points` değeri `null` iken `null`, değilse sayıdır.
- `coverage` ölçüleri: `RALLY_WINNER`, `TERMINAL_ACTION`, `SCORER`.
- Her karar `revision` değerini bir artırır.

## 8. Hata kodları

`VIDEO_NOT_READY`, `VIDEO_FILE_MISSING`, `MODEL_NOT_CONFIGURED`, `ANALYSIS_NOT_FOUND`, `JOB_FINISHED`, `CARD_NOT_FOUND`, `REVIEW_STALE_VERSION`, `IDEMPOTENCY_KEY_REUSED`, `INVALID_SELECTION`, `REPORT_NOT_AVAILABLE`, `RANGE_NOT_SATISFIABLE`, `PAYLOAD_TOO_LARGE`. Hepsi [API.md](API.md) içindeki hata zarfıyla döner.

JSON gövdesi alan uçlar en çok 64 KiB gövde kabul eder. Daha büyük gövdeye sunucu gövdeyi ayrıştırmadan `413 PAYLOAD_TOO_LARGE` döner. Parça yükleme ucu (`PUT /v1/uploads/{id}/chunks`) kendi sınırını uygular.
