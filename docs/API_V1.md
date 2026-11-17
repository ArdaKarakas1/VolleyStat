# Analiz, inceleme, rapor ve hesap sözleşmesi (API 0.8)

Bu belge arka uç, Android istemcisi ve analiz modülleri arasındaki tek sözleşmedir. Bir taraf bu belgeyi değiştirmeden davranışını değiştirmez. Kök: `/v1`. Yükleme uçları [API.md](API.md) içindedir ve aynen geçerlidir.

Değişmez kurallar:

- Model önerisi, kullanıcı kararı ve rapordaki değer ayrı kayıtlardır. Öneri tek başına istatistiğe girmez.
- Bilinmeyen değer `null` ve `status: "UNKNOWN"` olarak gider. Sunucu ölçülmeyen bir değeri `0` yazmaz.
- Sunucu güven puanı uydurmaz. `confidence` yalnızca `kind` değeri `MODEL` olan analizörden gelir, yoksa `null` kalır.
- Analizör ralli kaçırır, iki ralliyi tek aralıkta birleştirir ve tek ralliyi ikiye böler. Kullanıcı ralli listesini dört işlemle düzeltir: Kaçan ralliyi ekler, ralliyi böler, iki parçayı birleştirir ve sınırı taşır (bölüm 9). Kullanıcı listenin eksiksiz olduğunu onaylayana kadar rapordaki her sayı alt sınırdır (`ralliesMayBeMissing`).
- Bir işin rallileri zamanda çakışmaz. İki ralli uç uca değebilir. Kullanıcının "ralli değil" dediği ralli de aralığını tutar, çünkü tek bir karar o ralliyi sayıma geri getirir.
- Sunucu analizörün önerisini bir kez yazar ve bir daha değiştirmez (`rally_proposals` tablosu). Kullanıcının düzeltmesi çalışma listesini değiştirir ve öneri kaydına dokunmaz.
- Kullanıcının düzeltmeleri ve düzelttiği liste denetim kaydıdır. Bu kayıtlar altın etiket değildir ve eğitim verisi değildir. Ekip bu kayıtları ayrı izin ve ikinci etiketçi kontrolü olmadan hiçbir model ölçümüne sokmaz. Sunucu bu kayıtları dışa aktaran bir uç sunmaz.
- `mock: true` taşıyan iş gerçek analiz değildir. İstemci bu işi her ekranda DEMO olarak işaretler.
- Sunucu yalnız kişinin kendisi için verdiği görünen adı tutar (bölüm 10). Raporda oyuncu, taraf ve forma numarasıyla anılır. Telefon kadrosundaki adlar telefonda kalır.

## 1. Maç

`POST /v1/matches`

```json
{"homeTeamName": "A Takımı", "awayTeamName": "B Takımı",
 "name": null, "playedOn": "2026-11-12", "venue": null, "kind": "TRAINING"}
```

`homeTeamName` ve `awayTeamName` zorunludur. `name` (en çok 80), `playedOn` (`YYYY-AA-GG`), `venue` (en çok 80) isteğe bağlıdır. `kind` `TRAINING` ya da `OFFICIAL` olur, varsayılan `TRAINING`. `kind` yalnızca etikettir; sunucu resmî maç iddiası üretmez. `GET /v1/matches/{id}` aynı alanları döner.

`rules` da isteğe bağlıdır. Bu alan, sunucunun maçın setlerini hangi kuralla sayacağını söyler (ADR-0115):

```json
{"setsToWin": 2, "setTarget": 25, "decidingSetTarget": 15}
```

- `setsToWin`: Maçı kaç set alan takımın kazandığını söyler. Değer `2` ya da `3` olur.
- `setTarget`: Setin kaç sayıda bittiğini söyler. Değer `25`, `21` ya da `15` olur.
- `decidingSetTarget`: Karar setinin kaç sayıda bittiğini söyler. Değer `25`, `21` ya da `15` olur. Karar seti `2 × setsToWin − 1` numaralı settir.
- Üç alan da zorunludur ve tam sayı olur. Sunucu başka bir alanı ve başka bir değeri `422 VALIDATION_ERROR` ile reddeder. Uygulama da yalnız bu değerleri sunar. OpenAPI belgesi kabul edilen değerleri `enum` olarak listeler.
- Sunucu maç isteğinin bilinmeyen alanını da `422 VALIDATION_ERROR` ile reddeder. Kural `rules` nesnesinin dışında ya da başka bir adla gelirse sunucu onu sessizce düşürmez.
- Alan yoksa ya da `null` ise sunucu FIVB kuralını yazar: `{"setsToWin": 3, "setTarget": 25, "decidingSetTarget": 15}`. Sunucu 0.6 sürümünden önce açılan her maçı da bu kuralla sayar.
- `PUT /v1/matches/{id}` maçı yalnız ilk istekte yazar. Kimliği aynı olan sonraki istek başka bir kural taşısa bile sunucu ilk isteğin kuralını korur.

Yanıt kuralı iki alanla daha verir: `{"setsToWin": 2, "setTarget": 25, "decidingSetTarget": 15, "winBy": 2, "decidingSetNumber": 3}`. Her set iki farkla biter. Sunucu bu nesneyi maçta, ralli listesinde (bölüm 4) ve raporda (bölüm 7) aynı biçimde verir.

`PUT /v1/matches/{matchId}/rules`

Gövde kural nesnesidir ve istemcinin sunucudan son okuduğu kuralı da taşır (`expectedRules`). Sunucu `200` ile maçı `GET` yanıtındaki biçimde döner.

```json
{"setsToWin": 2, "setTarget": 21, "decidingSetTarget": 15, "expectedRules": {"setsToWin": 3, "setTarget": 25, "decidingSetTarget": 15}}
```

- Kural değiştiyse sunucu aynı işlemde maçın `NEEDS_REVIEW` ya da `COMPLETE` durumundaki her işinin `rallyListVersion` ve `revision` değerini bir artırır. Kullanıcı listeyi eski kuralın set skorlarıyla onayladı, bu yüzden onay düşer (bölüm 9). Eski sürümle yola çıkmış bir onay ya da düzeltme `409 RALLY_LIST_STALE` alır.
- Kararlar, kartlar ve düzeltmeler kalır. Sunucu kural değişikliğini düzeltme kaydına yazmaz ve rapordaki `rallyList.corrections` sayısı değişmez.
- Aynı kural yeniden gelirse sunucu hiçbir şey yazmaz, hiçbir sürümü artırmaz ve `expectedRules` alanına bakmaz. İstemci bu yüzden yanıtı kaybolan isteği güvenle yineler.
- Maçın kuralı `expectedRules` ile aynı değilse sunucu `409 MATCH_RULES_STALE` döner, maçın şimdiki kuralını `details.currentRules` alanında verir ve hiçbir şey yazmaz. İstemcinin beklemekten vazgeçtiği bir istek sonradan gelirse sunucu böylece kullanıcının başka bir kurala yaptığı sonraki değişikliği geri almaz. Sunucu yalnız kuralları karşılaştırır: Kural sonradan değişip isteğin `expectedRules` değerine geri döndüyse sunucu geç gelen isteği de uygular.
- `expectedRules` zorunludur ve kural nesnesinin kurallarına uyar. Sunucu eksik ya da geçersiz `expectedRules` alanını `422 VALIDATION_ERROR` ile reddeder.
- `expectedRules` yalnız üç giriş alanını taşır. İstemci, sunucunun yanıtlarda verdiği kural nesnesindeki `winBy` ve `decidingSetNumber` alanlarını geri göndermez.
- Sunucu kuralı işin durumuna ve liste onayına bakmadan kabul eder. Maç yoksa sunucu `404 MATCH_NOT_FOUND` döner ve hiçbir şey yazmaz.

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

Bu kuralı çiğneyen çıktı işi `ANALYZER_ERROR` koduyla düşürür. Analizörün önerdiği ralliler zamanda çakışamaz. Çakışan iki öneri de işi aynı kodla düşürür.

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
  "analyzer": {"id": "motion-baseline", "version": "0.2", "kind": "BASELINE"},
  "mock": false,
  "failure": null,
  "counts": {"rallies": 0, "proposed": 0, "reviewOpen": 0, "reviewTotal": 0},
  "rallyListVersion": 0, "listConfirmed": false,
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
- `counts.rallies` listedeki ralli sayısıdır ve kullanıcının düzeltmeleriyle değişir. `counts.proposed` analizörün önerdiği ralli sayısıdır ve hiçbir düzeltme onu değiştirmez. İstemci "sunucu şu kadar ralli önerdi" diyeceği yerde `proposed` değerini kullanır.
- `rallyListVersion` ralli listesinin sürümüdür. Her ralli düzeltmesi, yanıtı değiştiren her `RALLY_WINNER` kararı ve maç kuralının her değişikliği bu sayıyı bir artırır. `listConfirmed` kullanıcının listeyi bu sürümde eksiksiz ilan ettiğini söyler (bölüm 9).
- Aynı videonun yeni analiz işi kararsız ve düzeltmesiz başlar. Sunucu eski işin kararlarını ve düzeltmelerini yeni işe taşımaz.

## 4. Ralliler

`GET /v1/analyses/{jobId}/rallies` → `{"rallies": [Rally], "rallyListVersion": 4, "listConfirmed": false, "videoDurationMs": 5400000, "matches": [Part], "rules": Rules}`

```json
{"id": "…", "sequence": 3, "startMs": 41200, "endMs": 52800, "origin": "ANALYZER",
 "status": "PROPOSED", "proposedWinner": "UNKNOWN", "confidence": null,
 "winner": null, "terminalAction": null, "scorer": null,
 "uncertaintyReasons": ["NO_WINNER_MODEL"],
 "scoreAfter": null}
```

- `sequence`: Rallinin maç sırasındaki numarası. Sunucu her düzeltmeden sonra rallileri başlangıç zamanına göre yeniden numaralar. İstemci ralliyi `id` ile tanır, `sequence` ile tanımaz.
- `origin`: `ANALYZER` (ralli bir analizör önerisinden geliyor) ya da `USER` (ralliyi kullanıcı ekledi ya da bölerek oluşturdu). `USER` ralli öneri taşımaz: `proposedWinner` `UNKNOWN`, `confidence` `null` ve `uncertaintyReasons` boş gelir.
- `scoreAfter`: `{"match": 1, "set": 1, "home": 4, "away": 3}`. `match` rallinin videodaki hangi bölüme girdiğini söyler (bölüm 7, skor). Sunucu bu değeri raporun skor sayımıyla aynı yürüyüşten üretir. Değer `REJECTED` rallide, kazananı belli olmayan ilk rallide ve ondan sonraki her rallide `null` olur. Telefon skor hesaplamaz.
- `videoDurationMs`: Bir düzeltmenin verebileceği en büyük zaman.
- `matches`: Listenin bu sürümünün bölümleri ve set skorları, raporun `score.matches` alanıyla aynı sayımdan. Rapordan tek farkı bölüm başına istatistik taşımamasıdır (`teams`, `players` ve `unattributed` yoktur). Kullanıcı listeyi onaylamadan önce bu skorları maçta oynanan skorlarla karşılaştırır (bölüm 9). İstemci onay ekranında raporu değil bu alanı gösterir, çünkü onay isteği bu yanıttaki `rallyListVersion` değerini taşır.
- `rules`: Sunucunun `matches` ve `scoreAfter` değerlerini saydığı maç kuralı (bölüm 1). Sunucu `rules` ve `matches` alanını aynı okumadan verir. İstemci skor denetiminde bu kuralı bir cümleyle söyler: "Sunucu seti 21 sayıda, 3. seti 15 sayıda ve iki farkla bitirir."

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
- Kart zaten bu yanıtı taşıyorsa ve `expectedVersion` güncelse sunucu `200` döner, hiçbir şey yazmaz ve hiçbir sürümü artırmaz. `decisionId` `null` gelir. Kullanıcının seçili yanıta yeniden dokunması böylece liste onayını düşürmez.
- `RALLY_WINNER` kartının yanıtını değiştiren her karar ve o kartın her geri alınması `rallyListVersion` değerini bir artırır. Kullanıcının daha önce verdiği liste onayı böylece düşer (bölüm 9). `DETAIL` kararları bu sayıyı değiştirmez.

`POST /v1/review-cards/{cardId}/reopen` `{"expectedVersion": 2}` kartı `OPEN` durumuna alır (geri alma). Sunucu `200` ve `{"card": Card, "job": Job, "reportRevision": 5}` döner. Bu yanıt karar yanıtıyla aynı biçimdedir, yalnız `decisionId` taşımaz. `expectedVersion` eskiyse sunucu `409 REVIEW_STALE_VERSION` döner (`details.currentVersion`). Kart zaten `OPEN` ise ve `expectedVersion` güncelse sunucu `200` döner ve hiçbir şeyi değiştirmez. Sunucu her kararı ve geri almayı `review_decisions` tablosunda saklar: kart, önceki değer, yeni değer, zaman. Sunucu bu kaydı silmez.

## 6. Kanıt videosu

`GET /v1/videos/{videoId}/content` videonun baytlarını döner. `Range: bytes=…` desteklenir (`206`, `Content-Range`, `Accept-Ranges`). Bitiş konumu dosyanın sonunda ya da ötesindeyse sunucu dosyanın sonuna kadar gönderir. Başlangıç konumu dosyanın dışındaysa sunucu `416 RANGE_NOT_SATISFIABLE` döner (`details.sizeBytes`). Sunucu okuyamadığı ya da boş bir aralığa da aynı yanıtı verir: `bytes=abc`, `bytes=-`, `bytes=-0`, `bytes=10-5`. Video `READY` değilse `409 VIDEO_NOT_READY`, saklanan baytlar yoksa `409 VIDEO_FILE_MISSING`. İstemci klibi bu adresten `clipStartMs`–`clipEndMs` aralığında oynatır.

## 7. Rapor

`GET /v1/matches/{matchId}/report` → Rapor · hiçbir işi ralli üretmemiş maçta `404 REPORT_NOT_AVAILABLE` (iş kuyruktaysa, düştüyse ya da iptal edildiyse de)

```json
{
  "matchId": "…", "jobId": "…", "revision": 4,
  "analyzer": {"id": "motion-baseline", "version": "0.2", "kind": "BASELINE"}, "mock": false,
  "rallyList": {"confirmed": false, "confirmedAt": null, "corrections": 0},
  "verificationStatus": "PARTIALLY_VERIFIED",
  "rallies": {"proposed": 18, "total": 18, "userCreated": 0, "verified": 7, "rejected": 1, "unresolved": 0, "open": 10},
  "ralliesMayBeMissing": true,
  "rules": {"setsToWin": 3, "setTarget": 25, "decidingSetTarget": 15, "winBy": 2, "decidingSetNumber": 5},
  "score": {"status": "PARTIAL", "stoppedAtRally": 9, "unplacedRallies": 10,
            "matches": [{"number": 1, "kind": "STOPPED", "winner": null,
                         "setsWon": {"home": 0, "away": 0},
                         "sets": [{"number": 1, "home": 4, "away": 3, "complete": false}],
                         "firstRally": 1, "lastRally": 7,
                         "teams": {"…": "…"}, "players": ["…"], "unattributed": {"home": 2, "away": 3}}]},
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
- Değer nesnesi: `{"value": sayı ya da null, "status": "MEASURED" | "PARTIAL" | "UNKNOWN"}`. `PARTIAL` değerin alt sınır olduğunu söyler. `UNKNOWN` değerde `value` her zaman `null` olur. `MEASURED` şunu söyler: Kullanıcı sayıya giren her rallinin kazananını seçti ve ralli listesinin eksiksiz olduğunu onayladı. `MEASURED` bir modelin ölçümü değildir.
- `rallies`: `proposed` analizörün önerdiği ralli sayısıdır ve düzeltmelerle değişmez. `total` listedeki ralli sayısıdır. `userCreated` listedeki rallilerden kaçını kullanıcının oluşturduğunu söyler. Öbür dört sayı listedeki rallileri duruma göre sayar.
- `rallyList`: `confirmed` kullanıcının bugünkü listeyi eksiksiz ilan ettiğini söyler. `confirmedAt` o onayın zamanıdır ve onay düşünce `null` olur. `corrections` kullanıcının bu işte yaptığı ekleme, taşıma, bölme ve birleştirme sayısıdır. Sunucu liste onayını ve hiçbir şeyi değiştirmeyen taşımayı bu sayıya katmaz. Sayı sıfırdan büyükse rapordaki aralıklar yalnız analizörün değildir, istemci kaynağı buna göre yazar.
- Rapor maçın rallisi olan en yeni işini izler. `revision` o işe aittir. Eski bir işin kartına verilen karar o işin `revision` değerini artırır ve raporu değiştirmez.
- `ralliesMayBeMissing`: Bu alan, analizörün türü ne olursa olsun, kullanıcı ralli listesini onaylayana kadar `true` olur. Hiçbir analizör bütün rallileri bulmaz ve listeyi yalnız kullanıcının kendi denetimi kapatır. Deneme analizörünün işinde (`mock: true`) bu alan her zaman `true` kalır, çünkü sunucu o işin listesini onaylatmaz. Alan `true` iken sunucu `teams` altındaki hiçbir değeri ve `score.status` alanını `MEASURED` olarak bildirmez. Kullanıcı bütün kartları yanıtlamış olsa bile bu alanlar en fazla `PARTIAL` olur. `coverage[].status` bu kuralın dışındadır: O alan kullanıcının listedeki rallilerin ne kadarını karara bağladığını söyler ve kullanıcı bütün rallileri karara bağladığında `MEASURED` olur. `coverage` listede olmayan ralliyi saymaz.
- Skor: Sunucu rallileri sırayla sayar, reddedilen ralliyi atlar ve seti maçın kuralına göre kapatır (`rules`, bölüm 1): Set `setTarget` sayıda, `decidingSetNumber` numaralı karar seti `decidingSetTarget` sayıda ve iki farkla biter. Bir takım `setsToWin` sete ulaşınca maç biter (FIVB 6.3.1). Sunucu sonraki ralliyle aynı kuralla yeni bir maç açar ve o maçın setlerini yeniden 1'den sayar. Sunucu kuralı her raporda maçın kaydından okur, bu yüzden kural değişince rapor yeni kuralla sayar.
- `score.matches` videonun bölümleridir. `kind` dört değerden birini alır: `MATCH` (bir takım gereken setleri aldı, `winner` o takımdır), `AFTER_MATCH` (biten son maçtan sonra oynanan ve bir maçı bitirmeyen setler), `UNFINISHED` (hiçbir maç bitmediyse aynı setler) ve `STOPPED` (sayımın durduğu bölüm: Bu bölüm sonradan bir maça dönüşebilir ve sunucu bunu bilemez). `MATCH` dışındaki bir bölüm yalnız listenin sonunda durur ve `winner` taşımaz. `setsWon` bölümde biten setleri sayar. `firstRally` ve `lastRally` bölümün ilk ve son rallisinin sıra numarasıdır.
- Her bölüm kendi istatistiğini taşır: `teams`, `players` ve `unattributed` bölümün rallilerini videonun alanlarıyla aynı kurallarla sayar. Bir bölümün değerleri yalnız liste onaylıysa ve bölüm `STOPPED` değilse `MEASURED` olur. Videonun `teams`, `players` ve `unattributed` alanları bütün videonun toplamıdır.
- Sayım kazananı belli olmayan ilk rallide durur (kartı açık ya da yanıtı `UNSURE`). `score.stoppedAtRally` o rallinin sıra numarasını taşır, sayım sona ulaştıysa `null` olur. O ralliden önce biten setler `complete: true` kalır. Rallinin içinde bulunduğu set o ana kadarki sayılarla ve `complete: false` olarak gelir. Sayım bir setin ya da bir bölümün ilk rallisinde durduysa o set ya da bölüm için satır gelmez, çünkü orada sayılmış bir sayı yoktur. Sonraki ralliler hiçbir bölüme girmez. `score.unplacedRallies` sayımın durduğu ralliden listenin sonuna kadarki reddedilmemiş ralli sayısıdır. Hiç doğrulanmış ralli yoksa `score.status` `UNKNOWN` olur ve `matches` boş gelir.
- `teams.<taraf>.points` videonun doğrulanmış bütün rallilerini sayar, sayımın durduğu ralliden sonrakileri de. Hiç doğrulanmış ralli yokken iki taraf için de `null` ve `UNKNOWN` olur. Bir ralli doğrulandığı anda iki taraf da sayı taşır: Doğrulanan rallilerin hiçbirini kazanmamış taraf `0` taşır.
- `REJECTED` ralli hiçbir sayıma girmez. `eligibleN` reddedilenleri dışarıda tutar.
- Takımın sayı türleri (`attackPoints` …) yalnızca `TERMINAL_ACTION` kararı olan rallilerden gelir. Hiç karar yoksa `UNKNOWN`, bir kısmında varsa `PARTIAL` olur. Sunucu sıfır olan bir alt sınırı sayı olarak göndermez: O türde henüz karar yoksa ve sayım tamamlanmadıysa değer `null` ve `UNKNOWN` olur. `0` yalnızca sayım tamamlandığında (`MEASURED`) gelir.
- `players` yalnızca `SCORER` kararıyla forma numarası verilen sayıları içerir. Bu sayılar düz sayımdır ve doğrulama sürdükçe artabilir. Oyuncusu bilinmeyen sayılar `unattributed` altında kalır ve takım toplamına girer. `unattributed.<taraf>` o tarafın `points` değeri `null` iken `null`, değilse sayıdır.
- `coverage` ölçüleri: `RALLY_WINNER`, `TERMINAL_ACTION`, `SCORER`.
- Yanıtı değiştiren her karar `revision` değerini bir artırır. Saklı yanıtı yineleyen karar hiçbir sayıyı artırmaz (bölüm 5).

## 8. Hata kodları

`VIDEO_NOT_READY`, `VIDEO_FILE_MISSING`, `MODEL_NOT_CONFIGURED`, `ANALYSIS_NOT_FOUND`, `JOB_FINISHED`, `CARD_NOT_FOUND`, `REVIEW_STALE_VERSION`, `IDEMPOTENCY_KEY_REUSED`, `INVALID_SELECTION`, `REPORT_NOT_AVAILABLE`, `RANGE_NOT_SATISFIABLE`, `PAYLOAD_TOO_LARGE`. Ralli düzeltme uçları şu kodları da kullanır: `RALLY_NOT_FOUND`, `JOB_NOT_REVIEWABLE`, `RALLY_LIST_STALE`, `INVALID_INTERVAL`, `RALLY_OVERLAP`, `SPLIT_POINT_OUTSIDE`, `NO_PREVIOUS_RALLY`, `LIST_NOT_READY`, `MOCK_LIST_NOT_CONFIRMABLE`. Maç kuralı ucu (`PUT /v1/matches/{id}/rules`, bölüm 1) `MATCH_RULES_STALE` kodunu da kullanır. Hesap uçları (bölüm 10) şu kodları kullanır: `AUTH_REQUIRED`, `SIGNUP_CLOSED`, `SIGNUP_CODE_INVALID`, `LINK_NOT_FOUND`, `USERNAME_INVALID`, `DISPLAY_NAME_INVALID`, `PASSWORD_REJECTED`, `USERNAME_TAKEN`, `LOGIN_FAILED`, `PASSWORD_WRONG`, `TOO_MANY_ATTEMPTS`, `SERVER_BUSY`. Hepsi [API.md](API.md) içindeki hata zarfıyla döner.

JSON gövdesi alan uçlar en çok 64 KiB gövde kabul eder. Daha büyük gövdeye sunucu gövdeyi ayrıştırmadan `413 PAYLOAD_TOO_LARGE` döner. Parça yükleme ucu (`PUT /v1/uploads/{id}/chunks`) kendi sınırını uygular.

## 9. Ralli düzeltme

Kullanıcı ralli listesini bu uçlarla düzeltir. Bir düzeltme hangi aralığın ralli olduğunu değiştirir ve hiçbir kazanan seçmez.

| Uç | Gövde | Başarı |
|---|---|---|
| `POST /v1/analyses/{jobId}/rallies` | `{"startMs", "endMs", "expectedListVersion"}` | `201` Kaçan ralliyi ekler |
| `POST /v1/rallies/{rallyId}/move` | `{"startMs", "endMs", "expectedListVersion"}` | `200` Rallinin başını ve sonunu yazar |
| `POST /v1/rallies/{rallyId}/split` | `{"atMs", "expectedListVersion"}` | `200` Ralliyi `atMs` anında ikiye böler |
| `POST /v1/rallies/{rallyId}/merge-previous` | `{"expectedListVersion"}` | `200` Ralliyi kendinden önceki ralliyle birleştirir |
| `POST /v1/analyses/{jobId}/rally-list/confirm` | `{"expectedListVersion"}` | `200` Listeyi eksiksiz ilan eder |

Dört düzeltme şu yanıtı döner. Onay ucu yalnız `job` ve `reportRevision` döner.

```json
{"correctionId": "…", "affected": [Rally], "removed": ["…"], "job": Job, "reportRevision": 12}
```

Ortak kurallar:

- İş `NEEDS_REVIEW` ya da `COMPLETE` durumunda olmalıdır. Sunucu başka bir durumda `409 JOB_NOT_REVIEWABLE` döner (`details.state`).
- `expectedListVersion` kullanıcının baktığı listenin sürümüdür. Liste o günden beri değiştiyse sunucu `409 RALLY_LIST_STALE` döner (`details.currentListVersion`) ve hiçbir şeyi değiştirmez. İstemci listeyi yeniden okur.
- Düzeltme isteği yineleme anahtarı taşımaz. Yanıtı kaybolan bir isteği yineleyen istemci `409 RALLY_LIST_STALE` alır, çünkü ilk istek sürümü artırmıştır. İstemci listeyi yeniden okur ve sunucunun düzeltmeyi uyguladığını listeden görür. Sunucu aynı düzeltmeyi böylece iki kez uygulamaz. Sürümün ilerlemiş olması tek başına bunu göstermez, çünkü bir `RALLY_WINNER` kararı da sürümü artırır. İstemci listede kendi değişikliğini arar: eklediği aralığı, taşıdığı sınırı, kestiği noktayı ya da birleştirdiği başlangıcı. Yanıtı kaybolan bir liste onayını `listConfirmed` alanı gösterir.
- Aralık `0 ≤ startMs < endMs ≤ videoDurationMs` koşulunu sağlamalıdır, yoksa sunucu `422 INVALID_INTERVAL` döner. Aralık başka bir ralliyle çakışırsa sunucu `409 RALLY_OVERLAP` döner (`details.rallyId`). Sunucu en kısa ralli süresi dayatmaz.
- Sunucu listeyi değiştiren her düzeltmeden sonra rallileri yeniden numaralar, `rallyListVersion` ve `revision` değerlerini bir artırır ve işin durumunu yeniden kurar: Açık `RALLY_WINNER` kartı varsa `NEEDS_REVIEW`, yoksa `COMPLETE`.
- Sunucu her düzeltmeyi `rally_corrections` tablosuna yazar: işlem, rallilerin önceki ve sonraki hâli, zaman. Sunucu bu kaydı silmez. Bir düzeltmenin kaldırdığı ya da yeniden açtığı her yanıt `review_decisions` tablosunda o düzeltmenin kimliğini taşır.

Karar bir rallinin sonuna aittir, çünkü kanıt klibi rallinin sonunu gösterir. Düzeltmelerin kararlara etkisi bu kuraldan çıkar:

| İşlem | Kararlara etkisi |
|---|---|
| Ekleme | Yeni ralli `origin: "USER"` ve açık bir `RALLY_WINNER` kartıyla gelir. Öbür rallilerin kararları değişmez. |
| Taşıma, yalnız baş değişti | Bütün kartlar ve yanıtlar kalır. |
| Taşıma, son değişti | Sunucu `RALLY_WINNER` kartını yeniden açar, `DETAIL` kartlarını kaldırır ve kartın sürümünü artırır. Kart açık olsa bile sürümü artar. Eski klibi gösteren bir ekranın kararı böylece `409 REVIEW_STALE_VERSION` alır. Sunucu rallinin kazanan önerisini ve güven değerini de kaldırır (`proposedWinner: "UNKNOWN"`, `confidence: null`, kartta `suggestedValue: null`), çünkü hiçbir analizör yeni sonu görmedi. Analizörün ilk önerisi `rally_proposals` tablosunda kalır. |
| Taşıma, hiçbir şey değişmedi | Sunucu `200` döner, hiçbir şey yazmaz ve hiçbir sürümü artırmaz. `correctionId` `null` gelir. |
| Bölme | Sonraki parça rallinin kimliğini, önerisini, kartlarını ve yanıtlarını korur, çünkü sonu değişmedi. Önceki parça yeni bir `USER` rallidir ve açık bir kartla gelir. `atMs` rallinin içinde değilse sunucu `422 SPLIT_POINT_OUTSIDE` döner. |
| Birleştirme | Sonraki ralli kalır ve yanıtlarını korur. Başı önceki rallinin başına gider. Sunucu önceki ralliyi ve kartlarını kaldırır ve o rallinin yanıtlarını denetim kaydına `REMOVED` olarak yazar. Kalan ralli `REJECTED` ise sunucu onun `RALLY_WINNER` kartını yeniden açar, çünkü kullanıcı "ralli değil" dediği klibi bir rallinin sonu yaptı. İlk rallide sunucu `409 NO_PREVIOUS_RALLY` döner. |

Liste onayı:

- Onay kullanıcının beyanıdır: "Bu liste videodaki bütün rallileri içeriyor." Sunucu bu beyanı doğrulayamaz ve yalnızca kaydeder.
- Sunucu onayı ancak her rallinin kazananı belliyken kabul eder. Açık `RALLY_WINNER` kartı, `UNRESOLVED` ralli ya da hiç `VERIFIED` ralli yoksa sunucu `409 LIST_NOT_READY` döner (`details.open`, `details.unresolved`, `details.verified`).
- Sunucu deneme analizörünün işinde onayı reddeder (`409 MOCK_LIST_NOT_CONFIRMABLE`). O işin aralıkları uydurmadır ve kesin bir skor üretemez.
- Onay, kullanıcının onu verdiği liste sürümüne bağlıdır. Sonraki her düzeltme, yanıtı değiştiren her `RALLY_WINNER` kararı ve maç kuralının her değişikliği sürümü artırır ve onayı düşürür. Sunucu onayı geri alan ayrı bir uç sunmaz.
- Aynı sürümde yinelenen onay `200` döner ve hiçbir şey yazmaz.
- Onaydan sonra rapor `ralliesMayBeMissing: false` taşır. Bütün kazananlar belliyse takım sayıları (`points`) ve set skoru `MEASURED` olur. Sayı türü alanları ancak kullanıcı her sayının türünü seçtiyse `MEASURED` olur (bölüm 7).

Kullanıcı maçın kuralını seçer (bölüm 1). Sunucu süreyle biten seti tanımaz ve set sonunu kullanıcıdan almaz.

## 10. Hesap

Bir kişi kullanıcı adı, şifre ve görünen adla hesap açar (ADR-0119). Sunucu e-posta ve telefon numarası istemez. Oturum isteyen her uç `Authorization: Bearer <token>` başlığını okur. Başlık yoksa ya da oturum bittiyse sunucu `401 AUTH_REQUIRED` ve `WWW-Authenticate: Bearer` döner. Sürüm 0.8'de yalnız bu bölümün `/auth/logout` ve `/me` uçları oturum ister.

| Uç | Gövde | Başarı | Hatalar |
|---|---|---|---|
| `POST /v1/auth/register` | `{username, displayName, password, link?: {token} \| {code}, operatorCode?}` | `201 {user, session, deviceToken}` | `403 SIGNUP_CLOSED`, `403 SIGNUP_CODE_INVALID`, `404 LINK_NOT_FOUND`, `422 USERNAME_INVALID`, `422 DISPLAY_NAME_INVALID`, `422 PASSWORD_REJECTED`, `409 USERNAME_TAKEN`, `503 SERVER_BUSY` |
| `POST /v1/auth/login` | `{username, password, deviceToken?}` | `200 {user, session, deviceToken}` | `401 LOGIN_FAILED`, `429 TOO_MANY_ATTEMPTS`, `503 SERVER_BUSY` |
| `POST /v1/auth/logout` | `{}` | `204` | `401 AUTH_REQUIRED` |
| `GET /v1/me` | | `200 {user, teams}` | `401 AUTH_REQUIRED` |
| `PUT /v1/me/display-name` | `{displayName}` | `200 {user}` | `401`, `422 DISPLAY_NAME_INVALID` |
| `PUT /v1/me/password` | `{currentPassword, newPassword}` | `200 {user}` | `401`, `403 PASSWORD_WRONG`, `422 PASSWORD_REJECTED`, `429`, `503` |
| `POST /v1/me/delete` | `{password}` | `204` | `401`, `403 PASSWORD_WRONG`, `429`, `503` |

- `user`: `{id, username, displayName, createdAt, passwordTemporary}`. `passwordTemporary` operatör şifreyi sıfırladıktan sonra `true` olur ve kişi yeni şifre seçince `false` olur. `teams` takım dilimi gelene kadar boş bir listedir.
- `session`: `{token, expiresAt}`. `expiresAt` oturumun kullanılmazsa biteceği andır. Oturum 30 gün kullanılmayınca, her durumda da açıldıktan 180 gün sonra biter. Sunucu kullanımı en çok saatte bir yazar.
- `deviceToken`: Telefonun bu hesap için sakladığı anahtar. Telefon sonraki girişte bu anahtarı gönderir. Sunucu o telefonun hatalı denemelerini kendi anahtarıyla sayar, bu yüzden kullanıcı adını bilen bir yabancı sahibin telefonunu kilitleyemez. Anahtar bu hesaba ait değilse ya da 180 gündür kullanılmadıysa sunucu yeni bir anahtar verir. Şifre değişikliği ve operatörün sıfırlaması bütün telefon anahtarlarını siler.
- Kullanıcı adı 3 ile 30 karakter arasıdır, küçük harfle başlar ve yalnız `a-z`, `0-9`, `.` ve `_` taşır. Sunucu kullanıcı adını değiştirmez ve girişte birebir karşılaştırır. Uygulama yazılanı küçük harfe çevirir.
- Görünen ad NFC biçimine çevrilir, baştaki ve sondaki boşluk atılır, art arda boşluklar teke iner. Sonuç 2 ile 40 karakter arasıdır ve yalnız harf (Türkçe harfler dahil), rakam, boşluk ve `.'-` taşır. Sunucu kontrol ve biçim karakterlerini (yön ve sıfır genişlikli işaretler dahil) reddeder. Görünen adın eşsiz olması gerekmez.
- Şifre NFKC biçiminde sayılır ve karşılaştırılır. `PASSWORD_REJECTED` yanıtı `details.reason` taşır: `TOO_SHORT` (10 karakterden az), `TOO_LONG` (128 karakterden çok), `CONTAINS_USERNAME`, `COMMON` (en yaygın 10 000 şifreden biri). Sunucu büyük harf ya da sembol istemez.
- Kayıt sırası: Sunucu önce kayıt kapısına bakar, sonra alanları denetler, en son kullanıcı adının boş olup olmadığına bakar. Kapı kapalıyken sunucu kullanıcı adını sorgulamaz, bu yüzden alınmış ve boş kullanıcı adına aynı yanıtı döner.
- Kayıt kapısı: `GET /v1/health` yanıtındaki `accounts.signup` değeri `LINK` ise sunucu kaydı ancak bir davet bağlantısıyla ya da operatörün kodu (`operatorCode`) ile açar. Davet bağlantısı takım diliminde gelir. O zamana kadar her bağlantı `404 LINK_NOT_FOUND` alır. Kod 16 karakterdir, dört dörtlü grupla yazılır ve 7 gün geçerlidir. Sunucu kodu büyük küçük harf, boşluk ve tire farkı gözetmeden okur. Kayıt kodu harcar. `409 USERNAME_TAKEN` ve `422` yanıtları kodu harcamaz. `OPEN` değeri yalnız testler ve CI içindir.
- Giriş: Yanlış şifre ve bilinmeyen kullanıcı adı aynı yanıtı alır (`401 LOGIN_FAILED`) ve aynı süreyi harcar.
- Deneme sınırı: Bir kullanıcı adının ilk beş hatalı denemesi serbesttir. Sonraki her hatadan sonra bekleme 30 saniyeden başlar ve 15 dakikaya kadar ikiye katlanır. Sayaç son hatadan bir saat sonra ya da başarılı girişte sıfırlanır. Bir saatte 50 hata o kullanıcı adını 15 dakika kilitler. `429 TOO_MANY_ATTEMPTS` yanıtı `Retry-After` başlığını ve `details: {retryAfterSeconds, scope}` taşır. `scope` değeri `USER` (bu kullanıcı adı) ya da `DEVICE` (bu telefon) olur. Kilitli bir deneme şifre özeti hesaplamaz. Şifre değişikliğinde ve hesap silmede yanlış şifre de bu sayaca girer.
- Sunucu şifre özetlerini ayrı bir havuzda hesaplar. Havuzda iki özet çalışır ve en çok 16 özet bekler. Sunucu fazlasına `503 SERVER_BUSY` ve `Retry-After: 1` döner.
- Çıkış yalnız o oturumu kapatır. Şifre değişikliği o andaki oturum dışındaki bütün oturumları kapatır.
- Hesap silme kullanıcıyı, oturumlarını ve telefon anahtarlarını siler. Sunucu silmeden sonra günlük dosyasını boşaltır, bu yüzden kullanıcı adı ve görünen ad veritabanı dosyalarında kalmaz.
- Sunucu oturum anahtarının, telefon anahtarının ve operatör kodunun yalnız SHA-256 özetini, şifrenin de yalnız scrypt özetini tutar.
