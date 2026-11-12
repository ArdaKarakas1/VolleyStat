# Analiz, inceleme ve rapor sözleşmesi (API 0.4)

Bu belge arka uç, Android istemcisi ve analiz modülleri arasındaki tek sözleşmedir. Bir taraf bu belgeyi değiştirmeden davranışını değiştirmez. Kök: `/v1`. Yükleme uçları [API.md](API.md) içindedir ve aynen geçerlidir.

Değişmez kurallar:

- Model önerisi, kullanıcı kararı ve rapordaki değer ayrı kayıtlardır. Öneri tek başına istatistiğe girmez.
- Bilinmeyen değer `null` ve `status: "UNKNOWN"` olarak gider. Sunucu ölçülmeyen bir değeri `0` yazmaz.
- Sunucu güven puanı uydurmaz. `confidence` yalnızca kalibre edilmiş bir modelden gelir, yoksa `null` kalır.
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
| `motion-baseline` | `BASELINE` | Videodaki hareket enerjisinden ralli aralığı önerir. Kazanan önermez. Doğruluğu ölçülmemiştir. |

`GET /v1/health` yanıtına `analyzer` eklenir: `null` ya da `{"id", "version", "kind"}`.

## 3. Analiz işi

| Uç | Sonuç |
|---|---|
| `POST /v1/videos/{videoId}/analyses` | `202` yeni iş · `200` süren iş ya da aynı `Idempotency-Key` · `409 VIDEO_NOT_READY` · `503 MODEL_NOT_CONFIGURED` · `404` |
| `GET /v1/analyses/{jobId}` | İş |
| `GET /v1/videos/{videoId}/analyses/latest` | Videonun son işi · `404 ANALYSIS_NOT_FOUND` |
| `POST /v1/analyses/{jobId}/cancel` | `CANCELLED` iş · bitmiş işte `409 JOB_FINISHED` |

Bir videonun aynı anda tek süren işi olur. `Idempotency-Key` başlığı isteğe bağlıdır.

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
- `progress`: Analizör ölçtüyse dolar, ölçmediyse `null` kalır. İstemci `null` için belirsiz gösterge kullanır, yüzde uydurmaz.
- `NEEDS_REVIEW`: En az bir açık `SCORE` kartı var. Son `SCORE` kartı kapanınca iş `COMPLETE` olur. Kart yeniden açılırsa iş `NEEDS_REVIEW` durumuna döner.
- `failure.code`: `ANALYZER_ERROR`, `VIDEO_UNREADABLE`, `NO_RALLIES_FOUND`, `JOB_TIMEOUT`.
- İşçi tek süreçte arka plan iş parçacığıdır. Yarım kalan işi nabız (heartbeat) eskiyince yeniden kuyruğa alır.

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
- `409 REVIEW_STALE_VERSION` (`details.currentVersion`). Aynı `idempotencyKey` aynı yanıtı döner ve ikinci kayıt oluşturmaz.
- `422 INVALID_SELECTION`: Seçim kartın `options` listesinde yok.
- Yanıtlanmış karta yeni karar verilebilir (düzeltme). Kazanan değişirse sunucu o rallinin `DETAIL` kartlarını ve yanıtlarını kaldırır.

`POST /v1/review-cards/{cardId}/reopen` `{"expectedVersion": 2}` kartı `OPEN` durumuna alır (geri alma). Sunucu `200` ve `{"card": Card, "job": Job, "reportRevision": 5}` döner. Bu yanıt karar yanıtıyla aynı biçimdedir, yalnız `decisionId` taşımaz. `expectedVersion` eskiyse sunucu `409 REVIEW_STALE_VERSION` döner (`details.currentVersion`). Kart zaten `OPEN` ise ve `expectedVersion` güncelse sunucu `200` döner ve hiçbir şeyi değiştirmez. Sunucu her kararı ve geri almayı `review_decisions` tablosunda saklar: kart, önceki değer, yeni değer, zaman. Kayıt silinmez.

## 6. Kanıt videosu

`GET /v1/videos/{videoId}/content` videonun baytlarını döner. `Range: bytes=…` desteklenir (`206`, `Content-Range`, `Accept-Ranges`). Video `READY` değilse `409 VIDEO_NOT_READY`. İstemci klibi bu adresten `clipStartMs`–`clipEndMs` aralığında oynatır.

## 7. Rapor

`GET /v1/matches/{matchId}/report` → Rapor · işi olmayan maçta `404 REPORT_NOT_AVAILABLE`

```json
{
  "matchId": "…", "jobId": "…", "revision": 4,
  "analyzer": {"id": "motion-baseline", "version": "0.1", "kind": "BASELINE"}, "mock": false,
  "verificationStatus": "PARTIALLY_VERIFIED",
  "rallies": {"proposed": 18, "verified": 7, "rejected": 1, "unresolved": 0, "open": 10},
  "rules": {"setTarget": 25, "decidingSetTarget": 15, "winBy": 2, "bestOf": 5},
  "score": {"status": "PARTIAL", "setsWon": {"home": 0, "away": 0},
            "sets": [{"number": 1, "home": 4, "away": 3, "complete": false}]},
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
- Skor: Sunucu doğrulanmış rallileri sırayla sayar ve seti kurala göre kapatır (25 sayı, karar setinde 15, iki fark, beş setin üçü). Bir setin içinde kararı olmayan ralli varsa o set `complete: false` kalır ve `score.status` `PARTIAL` olur. Hiç doğrulanmış ralli yoksa `score.status` `UNKNOWN`, `setsWon` alanları `null` olur.
- `REJECTED` ralli hiçbir sayıma girmez. `eligibleN` reddedilenleri dışarıda tutar.
- Takımın sayı türleri (`attackPoints` …) yalnızca `TERMINAL_ACTION` kararı olan rallilerden gelir. Hiç karar yoksa `UNKNOWN`, bir kısmında varsa `PARTIAL` olur.
- `players` yalnızca `SCORER` kararıyla forma numarası verilen sayıları içerir. Oyuncusu bilinmeyen sayılar `unattributed` altında kalır ve takım toplamına girer.
- `coverage` ölçüleri: `RALLY_WINNER`, `TERMINAL_ACTION`, `SCORER`.
- Her karar `revision` değerini bir artırır.

## 8. Hata kodları

`VIDEO_NOT_READY`, `MODEL_NOT_CONFIGURED`, `ANALYSIS_NOT_FOUND`, `JOB_FINISHED`, `CARD_NOT_FOUND`, `REVIEW_STALE_VERSION`, `INVALID_SELECTION`, `REPORT_NOT_AVAILABLE`. Hepsi [API.md](API.md) içindeki hata zarfıyla döner.
