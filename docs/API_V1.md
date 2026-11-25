# Analiz, inceleme, rapor ve hesap sözleşmesi (API 0.10)

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
- Sunucu kişinin kendisi için seçtiği kullanıcı adını ve görünen adı tutar (bölüm 10). Rapor oyuncuyu taraf ve forma numarasıyla anar. Telefon kadrosundaki adlar telefonda kalır.
- Sunucu her maçı tek bir hesaba bağlar ve maçı yalnız o hesaba gösterir (bölüm 10, Erişim).

## 1. Maç

`POST /v1/matches`

```json
{"homeTeamName": "A Takımı", "awayTeamName": "B Takımı",
 "name": null, "playedOn": "2026-11-12", "venue": null, "kind": "TRAINING"}
```

`homeTeamName` ve `awayTeamName` zorunludur. `name` (en çok 80), `playedOn` (`YYYY-AA-GG`), `venue` (en çok 80) isteğe bağlıdır. `kind` `TRAINING` ya da `OFFICIAL` olur, varsayılan `TRAINING`. `kind` yalnızca etikettir; sunucu resmî maç iddiası üretmez. `GET /v1/matches/{id}` aynı alanları döner.

`POST /v1/matches` ve yeni bir kimlikle gelen `PUT /v1/matches/{id}` isteği gönderen hesabın kişisel maçını açar. Yanıttaki `status` alanı `PERSONAL` olur. Sunucu maçı yalnız o hesaba gösterir (bölüm 10, Erişim).

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
- Kimliği var olan bir maça gelen `PUT` isteğine sunucu maçın sahibine `200` ile maçı döner ve hiçbir şeyi değiştirmez. Başka bir hesap `403 NOT_A_MEMBER` alır. Sunucu hiçbir hesaba bağlı olmayan bir maçta (`LEGACY`) da aynı yanıtı verir.

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
- Sunucu kuralı işin durumuna ve liste onayına bakmadan kabul eder. Maç yoksa sunucu `404 MATCH_NOT_FOUND` döner ve hiçbir şey yazmaz. Maç başka bir hesabınsa sunucu `403 NOT_A_MEMBER` döner. Sunucu bu denetimi kural karşılaştırmasından önce yapar, bu yüzden başka bir hesap `MATCH_RULES_STALE` yanıtından maçın kuralını öğrenemez.

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
- Yinelenen istekte sunucu önce anahtarla saklı kararı bulur, sonra kararın işinden maçı bulur ve erişimi denetler. Sunucu saklı yanıtı ancak bu denetimden sonra döner. Bir anahtar sunucunun bütününde tek bir karar isteğini adlandırır. Anahtarı başka bir hesap kullandıysa sunucu `403 NOT_A_MEMBER` döner ve saklı yanıtı göstermez. İstek kişinin kendi kartını adlandırdığında da yanıt aynıdır. Anahtar yoksa ya da sunucu anahtarı ilk kez görüyorsa sunucu erişimi kartın sürümünden ve seçimden önce denetler.
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

`VIDEO_NOT_READY`, `VIDEO_FILE_MISSING`, `MODEL_NOT_CONFIGURED`, `ANALYSIS_NOT_FOUND`, `JOB_FINISHED`, `CARD_NOT_FOUND`, `REVIEW_STALE_VERSION`, `IDEMPOTENCY_KEY_REUSED`, `INVALID_SELECTION`, `REPORT_NOT_AVAILABLE`, `RANGE_NOT_SATISFIABLE`, `PAYLOAD_TOO_LARGE`. Ralli düzeltme uçları şu kodları da kullanır: `RALLY_NOT_FOUND`, `JOB_NOT_REVIEWABLE`, `RALLY_LIST_STALE`, `INVALID_INTERVAL`, `RALLY_OVERLAP`, `SPLIT_POINT_OUTSIDE`, `NO_PREVIOUS_RALLY`, `LIST_NOT_READY`, `MOCK_LIST_NOT_CONFIRMABLE`. Maç kuralı ucu (`PUT /v1/matches/{id}/rules`, bölüm 1) `MATCH_RULES_STALE` kodunu da kullanır. Hesap uçları (bölüm 10) şu kodları kullanır: `AUTH_REQUIRED`, `SIGNUP_CLOSED`, `SIGNUP_CODE_INVALID`, `LINK_NOT_FOUND`, `USERNAME_INVALID`, `DISPLAY_NAME_INVALID`, `PASSWORD_REJECTED`, `USERNAME_TAKEN`, `LOGIN_FAILED`, `PASSWORD_WRONG`, `TOO_MANY_ATTEMPTS`, `SERVER_BUSY`. Hesap silme ucu `409 UPLOAD_FINALIZING` kodunu da kullanır. Takım uçları (bölüm 10, Takımlar) şu kodları kullanır: `TEAM_NOT_FOUND`, `TEAM_ID_INVALID`, `TEAM_ID_TAKEN`, `TEAM_LIMIT_REACHED`, `NAME_INVALID`, `NOT_A_TEAM_MEMBER`, `CAPTAIN_ONLY`, `SEATS_INVALID`, `SEATS_BELOW_HOLDERS`. Takım kurma ucu `429 TOO_MANY_ATTEMPTS` yanıtında `scope` alanına `TEAMS` yazar. Sürüm 0.9'dan sonra her uç oturumsuz isteğe `401 AUTH_REQUIRED`, hesabın göremediği bir kayda `403 NOT_A_MEMBER` döner (bölüm 10, Erişim). Yükleme uçları `409 QUOTA_EXCEEDED` ve `507 STORAGE_FULL` kodlarını da kullanır (bölüm 10, Depolama sınırları). Hepsi [API.md](API.md) içindeki hata zarfıyla döner.

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

Bir kişi kullanıcı adı, şifre ve görünen adla hesap açar (ADR-0119). Sunucu e-posta ve telefon numarası istemez. Oturum isteyen her uç `Authorization: Bearer <token>` başlığını okur. Başlık yoksa ya da oturum bittiyse sunucu `401 AUTH_REQUIRED` ve `WWW-Authenticate: Bearer` döner. Sürüm 0.9'dan sonra `GET /v1/health`, `POST /v1/auth/register` ve `POST /v1/auth/login` dışındaki her uç oturum ister. OpenAPI belgesi bu uçları `HTTPBearer` güvenlik şemasıyla işaretler.

| Uç | Gövde | Başarı | Hatalar |
|---|---|---|---|
| `POST /v1/auth/register` | `{username, displayName, password, link?: {token} \| {code}, operatorCode?}` | `201 {user, session, deviceToken}` | `422 VALIDATION_ERROR`, `403 SIGNUP_CLOSED`, `403 SIGNUP_CODE_INVALID`, `404 LINK_NOT_FOUND`, `422 USERNAME_INVALID`, `422 DISPLAY_NAME_INVALID`, `422 PASSWORD_REJECTED`, `409 USERNAME_TAKEN`, `503 SERVER_BUSY` |
| `POST /v1/auth/login` | `{username, password, deviceToken?}` | `200 {user, session, deviceToken}` | `401 LOGIN_FAILED`, `429 TOO_MANY_ATTEMPTS`, `503 SERVER_BUSY` |
| `POST /v1/auth/logout` | `{}` | `204` | `401 AUTH_REQUIRED` |
| `GET /v1/me` | | `200 {user, teams}` | `401 AUTH_REQUIRED` |
| `PUT /v1/me/display-name` | `{displayName}` | `200 {user}` | `401`, `422 DISPLAY_NAME_INVALID` |
| `PUT /v1/me/password` | `{currentPassword, newPassword}` | `200 {user}` | `401`, `403 PASSWORD_WRONG`, `422 PASSWORD_REJECTED`, `429`, `503` |
| `POST /v1/me/delete` | `{password}` | `204` | `401`, `403 PASSWORD_WRONG`, `409 UPLOAD_FINALIZING`, `429`, `503` |

- `user`: `{id, username, displayName, createdAt, passwordTemporary}`. Operatör şifreyi sıfırlayınca sunucu `passwordTemporary` alanını `true` yapar. Kişi yeni şifre seçince sunucu alanı `false` yapar. `teams` hesabın takımlarını `GET /v1/teams` yanıtındaki listeyle aynı biçimde ve aynı sırada taşır (Takımlar).
- `session`: `{token, expiresAt}`. `expiresAt` oturumun kullanılmazsa biteceği andır. Sunucu oturumu 30 gün kullanılmayınca, her durumda da açıldıktan 180 gün sonra kapatır. Sunucu kullanımı en çok saatte bir yazar.
- `deviceToken`: Telefonun bu hesap için sakladığı anahtar. Telefon sonraki girişte bu anahtarı gönderir. Sunucu o telefonun hatalı denemelerini telefonun kendi anahtarıyla sayar, bu yüzden kullanıcı adını bilen bir yabancı sahibin telefonunu kilitleyemez. Anahtar başka bir hesaba aitse ya da 180 gündür kullanılmadıysa sunucu yeni bir anahtar verir. Şifre değişikliği hesabın bütün telefon anahtarlarını siler. Operatörün sıfırlaması telefon anahtarlarını silmez, çünkü bir telefon anahtarı tek başına hiçbir şey açmaz.
- Kullanıcı adı 3 ile 30 karakter arasıdır, küçük harfle başlar ve yalnız `a-z`, `0-9`, `.` ve `_` taşır. Sunucu kullanıcı adını değiştirmez ve girişte birebir karşılaştırır. Uygulama yazılanı küçük harfe çevirir.
- Sunucu görünen adı NFC biçimine çevirir, baştaki ve sondaki boşluğu atar ve art arda boşlukları teke indirir. Sonuç 2 ile 40 karakter arasıdır, en az bir harf ya da rakam taşır ve yalnız harf (Türkçe harfler dahil), rakam, boşluk ve `.'-` taşır. Sunucu kontrol ve biçim karakterlerini (yön ve sıfır genişlikli işaretler dahil) reddeder. Görünen adın eşsiz olması gerekmez.
- Sunucu şifreyi NFKC biçiminde sayar. `PASSWORD_REJECTED` yanıtı `details.reason` taşır: `TOO_SHORT` (10 karakterden az), `TOO_LONG` (129 ile 1024 karakter arası), `CONTAINS_USERNAME`, `REPETITIVE` (dörtten az farklı karakter, örneğin `aaaaaaaaaa` ya da `1212121212`), `COMMON`. Sunucu karşılaştırmadan önce büyük harfi küçültür, harf işaretlerini atar ve `ı` harfini `i` sayar. Bu yüzden `İLKER` ve `ılker` de `ilker` kullanıcı adını içerir. Sunucu büyük harf ya da sembol istemez.
- `COMMON` listesi SecLists projesinden gelir (MIT). Liste en yaygın 10 000 şifrenin ve xato.net'in bir milyonluk listesinin 10 ile 128 karakter arasındaki 118 545 girdisini tutar. Daha kısa şifreleri uzunluk kuralı zaten reddeder. Liste Türkçe şifrelerin hepsini tanımaz: `galatasaray` ve `fenerbahce` listededir, `sifre12345` listede değildir.
- Sunucu gövdenin biçimini kapıdan önce denetler. Bilinmeyen ya da eksik alan, 200 karakteri aşan kullanıcı adı ya da görünen ad ve 1024 karakteri aşan şifre `422 VALIDATION_ERROR` alır. Bu denetim kullanıcı adını sorgulamaz.
- Kayıt sırası: Sunucu önce kayıt kapısına bakar, sonra alanları denetler, en son kullanıcı adının boş olup olmadığına bakar. Kapı kapalıyken sunucu kullanıcı adını sorgulamaz, bu yüzden alınmış ve boş kullanıcı adına aynı yanıtı döner.
- Kayıt kapısı: Sunucu 0.10 sürümünden başlayarak bağlantı ve kod olmadan yapılan kaydı varsayılan olarak kabul eder. `GET /v1/health` yanıtındaki `accounts.signup` değeri o zaman `OPEN` olur. Operatör sunucuyu `VOLLEYSTAT_SIGNUP=link` ayarıyla başlatırsa değer `LINK` olur ve sunucu kaydı ancak bir davet bağlantısıyla ya da operatörün kodu (`operatorCode`) ile açar. Sunucu bu ayarda bağlantı ve kod taşımayan bir kayıt isteğine `403 SIGNUP_CLOSED` döner ve `details.reason` alanına `NO_LINK` yazar. Ayar `open` (varsayılan) ya da `link` değerini alır. Sunucu boş değer dahil başka bir değerle başlamaz. Davet bağlantısı sonraki bir sürümle gelir. O zamana kadar sunucu her bağlantıya `404 LINK_NOT_FOUND` döner. Operatörün kodu 16 karakterdir ve 7 gün geçerlidir. Komut kodu dört dörtlü grupla yazar. Sunucu kodu iki ayarda da büyük küçük harf, boşluk ve tire farkı gözetmeden okur ve geçerli bir kodla açılan hesabı kayıt koduyla açılmış sayar. Geçersiz ya da süresi geçmiş bir kod iki ayarda da `403 SIGNUP_CODE_INVALID` alır ve sunucu o kayıtla kodsuz bir hesap açmaz. Kayıt kodu harcar. `409 USERNAME_TAKEN` ve `422` yanıtları kodu harcamaz.
- Giriş: Yanlış şifre ve bilinmeyen kullanıcı adı aynı yanıtı alır (`401 LOGIN_FAILED`) ve aynı süreyi harcar. Süresi geçen geçici şifre de `401 LOGIN_FAILED` alır.
- Deneme sınırı: Sunucu bir kullanıcı adının beşinci hatalı denemesinden sonra o adı 30 saniye bekletir. Sonraki her hata bu süreyi 15 dakikaya kadar ikiye katlar. Sunucu sayacı son hatadan bir saat sonra ya da başarılı girişte sıfırlar. Bir saatte 50 hata o kullanıcı adını 15 dakika kilitler. `429 TOO_MANY_ATTEMPTS` yanıtı `Retry-After` başlığını ve `details: {retryAfterSeconds, scope}` taşır. `scope` değeri `USER` (bu kullanıcı adı), `DEVICE` (bu telefon) ya da `ACCOUNT` (oturumun içindeki şifre denetimi) olur. Kilitli bir deneme şifre özeti hesaplamaz.
- Sunucu bir denemeyi şifre özetini hesaplamadan önce sayar ve şifre doğru çıkınca sayacı sıfırlar. Aynı anda gelen denemeler bu yüzden birbirini görür ve bir tahmin dalgası sınırı aşamaz. Aynı hesaba aynı anda beşten fazla deneme gelirse doğru şifre de `429` alabilir.
- Şifre değişikliği ve hesap silme yanlış şifreyi hesabın kendi sayacında sayar (`ACCOUNT`). O sayaca yalnız hesabın bir oturumunu tutan kişi ulaşır, bu yüzden giriş ekranındaki bir yabancı sahibin şifre değişikliğini ve hesap silmesini engelleyemez.
- Sunucu şifre özetlerini ayrı bir havuzda hesaplar. Havuzda iki özet çalışır ve en çok 16 özet bekler. Sunucu fazlasına `503 SERVER_BUSY` ve `Retry-After: 1` döner.
- Çıkış yalnız o oturumu kapatır. Şifre değişikliği o andaki oturum dışındaki bütün oturumları kapatır. Yazan her uç oturumu kendi yazma işleminin içinde yeniden okur. Bir oturum özet hesaplanırken kapandıysa uç `401 AUTH_REQUIRED` döner ve hiçbir şey yazmaz. Şifre aynı sırada değiştiyse şifre değişikliği ve hesap silme `403 PASSWORD_WRONG` döner ve hiçbir şey yazmaz.
- Sunucu hesabı silerken önce oturumu ve şifreyi yazma işleminin içinde yeniden denetler. Sonra hesaba ait kişisel maçları yüklemeleri, videoları, analizleri ve baytlarıyla siler. En son kullanıcıyı, oturumlarını ve telefon anahtarlarını siler. Silinen dosyalar geri gelmez, bu yüzden sunucu şifreyi her silmeden önce denetler. Şifre özet hesaplanırken değiştiyse sunucu `403 PASSWORD_WRONG` döner ve hiçbir baytı silmez. Sunucu o anda hesabın bir yüklemesini doğruluyorsa `409 UPLOAD_FINALIZING` döner ve hiçbir şey silmez. Sunucu süren bir doğrulamayı başlangıcından sonra 15 dakika açık kaldığında terk edilmiş sayar. Kapanış ya da çökme bir doğrulamayı yarıda keserse sunucu açılışta o doğrulamayı hemen terk edilmiş sayar. Sunucu çalışan analiz işlerini durdurur. Hiçbir hesaba bağlı olmayan maçlar (`LEGACY`) sunucuda kalır ve operatör onları siler.
- Sunucu silmeden sonra günlük dosyasını boşaltır. Bir okuyucu o sırada işlem içindeyse sunucu bir saniyeye kadar yeniden dener. Okuyucu daha uzun sürerse günlük bir sonraki silmede ya da son bağlantı kapanınca boşalır.
- Operatör `python -m app.admin reset-password <kullanıcı adı>` ile 7 gün geçerli bir geçici şifre verir. Sunucu o hesabın bütün oturumlarını kapatır.
- Sunucu şifre özetinin maliyetini özetin içine yazar. Sunucu maliyeti değiştirirse eski bir özet girişte yine doğrulanır ve sunucu o girişte özeti yeni maliyetle yeniden yazar.
- Sunucu oturum anahtarının, telefon anahtarının ve operatör kodunun yalnız SHA-256 özetini, şifrenin de yalnız scrypt özetini tutar.

### Erişim

- Bir hesabın açtığı maç o hesabın kişisel maçıdır (`status: "PERSONAL"`, bölüm 1). Sunucu maçı, yüklemelerini, videolarını, analizlerini, kartlarını, rallilerini ve raporunu yalnız maçın sahibine gösterir. Maçın sahibi bu uçları sürüm 0.8'deki gibi kullanır. Kişisel maç hiçbir takım ya da kişi istatistiğine girmez.
- Sunucu oturumu gövdenin, sorgunun ve başlıkların alanlarını denetlemeden önce okur. Parça yükleme ucu oturumu gövdeyi okumadan önce denetler. İki ret oturumdan önce gelir: Sunucu 64 KiB üstündeki bir JSON gövdesine `413 PAYLOAD_TOO_LARGE`, JSON olarak okunamayan bir gövdeye `422 VALIDATION_ERROR` döner. Sunucu bu iki ret için veritabanını okumaz ve hiçbir kaydı adlandırmaz.
- Oturumdan sonraki sıra şudur: alanların biçimi (`422`), kayıt (`404`), erişim (`403`), sonra ucun öteki denetimleri. Sunucu var olmayan bir kaydı kaydın kendi koduyla bildirir: `404 MATCH_NOT_FOUND`, `UPLOAD_NOT_FOUND`, `VIDEO_NOT_FOUND`, `ANALYSIS_NOT_FOUND`, `CARD_NOT_FOUND` ya da `RALLY_NOT_FOUND`. Sunucu hesabın göremediği bir kayda `403 NOT_A_MEMBER` döner. Bu yanıtın `details` alanı boştur. Sunucu var olan bir kaydı hiçbir zaman `404` ile bildirmez, çünkü uygulama `404` yanıtını silinmiş kayıt diye okur.
- Sunucu erişimi sürüm, durum, offset, anahtar ve kota denetimlerinden önce yapar. Başka bir hesap bu yüzden `REVIEW_STALE_VERSION`, `RALLY_LIST_STALE`, `MATCH_RULES_STALE`, `OFFSET_MISMATCH`, `JOB_FINISHED`, `VIDEO_NOT_READY` ya da `SPLIT_POINT_OUTSIDE` yanıtından bir kaydın sürümünü, durumunu ya da sınırını öğrenemez. Parça ucunun `400 INVALID_OFFSET` ve `413 CHUNK_SIZE_INVALID` yanıtları erişimden önce gelir, çünkü bu iki yanıt yalnız isteğin kendisini anar.
- Yazan her uç oturumu kendi yazma işleminin içinde yeniden okur. Oturum arada kapandıysa uç `401 AUTH_REQUIRED` döner ve hiçbir şey yazmaz. Yükleme tamamlama ucu (`POST /v1/uploads/{id}/complete`) dosyayı doğrularken oturum kapanırsa sunucu video kaydı yazmaz ve yüklemeyi yeniden `UPLOADING` durumuna alır.
- Sunucu 0.9 öncesi sürümlerin sakladığı maçları hiçbir hesaba bağlamaz (`LEGACY`). Bu maçlara her uç `403 NOT_A_MEMBER` döner. Uygulama 0.8'in giriş yapmış bir hesapla yüklediği maçlar da bunlara dahildir, çünkü sunucu 0.8 maç uçlarında oturumu okumuyordu. Sunucu bir maçı istekle sahiplenmeye izin vermez, çünkü maç kimliği gizli değildir. Sunucu sırada bekleyen bir analiz işini maç `LEGACY` olsa da çalıştırır, ama sonucu kimse okuyamaz.
- Operatör bu maçları `python -m app.admin legacy --list` komutuyla listeler. Liste her maçın kimliğini, tarihlerini ve takım adlarını gösterir. `legacy --assign <kullanıcı adı> --match <kimlik>` komutu adı geçen maçları o hesabın kişisel maçı yapar. `--assign` her maçı `--match` ile adlandırmayı ister, çünkü sunucu 0.8 hangi hesabın hangi maçı yüklediğini kaydetmiyordu. `legacy --assign <kullanıcı adı> --all` bütün `LEGACY` maçları o hesaba bağlar ve yalnız tek hesaplı bir sunucuda çalışır. Birden çok hesap varsa komut hiçbir şeyi değiştirmez ve `1` ile çıkar. `legacy --delete` komutu maçları yüklemeleri, videoları, analizleri ve baytlarıyla siler ve `--match <kimlik>` ile yalnız adı geçen maçlara dokunur. Komut adı geçen bir maç `LEGACY` değilse ya da kullanıcı adını hiçbir hesap taşımıyorsa hiçbir şeyi değiştirmez ve `1` ile çıkar. Silme komutu sunucu maçın bir yüklemesini doğrularken de hiçbir şeyi değiştirmez. Komutlar sunucu çalışırken de çalışır.

### Depolama sınırları

- Sunucu bir yüklemeyi istemcinin bildirdiği boyutla (`sizeBytes`) sayar. Sunucu yüklemeyi `UPLOADING`, `FINALIZING` ya da `COMPLETED` durumundayken sayar. Sunucu karantinadaki videoyu da sayar, çünkü baytları diskte durur. Sunucu silinen videoyu (`DELETED`), bırakılan yüklemeyi (`ABORTED`) ve silinen maçı saymaz. Sunucu bildirilen boyuttan fazlasını kabul etmez (`413 UPLOAD_OVERFLOW`), bu yüzden küçük bir boyut bildiren istemci sınırı aşamaz.
- Sunucu operatörün kayıt koduyla açılan bir hesabın kişisel maç videolarına ve yarım yüklemelerine en çok 8 GiB ayırır. Sunucu kayıt kodu olmadan açılan bir hesaba en çok 4 GiB ayırır ve bu hesaptan aynı anda tek bir yarım yükleme kabul eder. Bu yer sunucunun kabul ettiği en büyük boyuttaki bir videoya yeter. Açık kayıtla açılan hesap ve sonraki dilimlerde davet ya da açık maç bağlantısıyla açılacak hesap bu küçük sınırı alır. Sunucu tanımadığı bir açılış yolunu da bu küçük sınırla sayar. Sunucu 0.10 sürümünden başlayarak kodsuz kaydı varsayılan olarak kabul eder. Operatör bu kaydı `VOLLEYSTAT_SIGNUP=link` ayarıyla kapatabilir.
- Sunucu bütün hesapların kişisel maç videolarına en çok 40 GiB ayırır. Sunucu `LEGACY` maçların baytlarını bu sayıya katmaz. Operatörün bir hesaba bağladığı `LEGACY` maç o hesabın sayısına girer.
- Sunucu sınırı aşan bir yüklemeye `409 QUOTA_EXCEEDED` döner ve hiçbir kayıt yazmaz. Sunucu sınırları şu sırayla denetler: önce yarım yüklemelerin sayısını (`details: {scope: "PERSONAL", reason: "UNFINISHED_UPLOAD", usedBytes, limitBytes}`), sonra hesabın sınırını (`{scope: "PERSONAL", usedBytes, limitBytes}`), sonra sunucunun sınırını (`{scope: "SERVER", pool: "PERSONAL", limitBytes}`). Sunucunun sınırında yanıt `usedBytes` taşımaz, çünkü bu sayı bir hesaba öteki hesapların ne kadar yer tuttuğunu söylerdi. `usedBytes` ile `sizeBytes` toplamı sınıra eşitse sunucu yüklemeyi kabul eder.
- İstemci aynı hesapla aynı maç için aynı dosyanın yüklemesini yeniden başlatırsa (aynı `clientSha256`, `sizeBytes`, `fileName` ve `mimeType`) ve o yükleme `UPLOADING` durumundaysa sunucu yeni kayıt yazmaz. Sunucu var olan yüklemeyi `200` ile ve `nextOffset` değeriyle döner ve sınırları denetlemez, çünkü yükleme zaten sayılıdır. Yükleme başlatan isteğin yanıtını kaybeden telefon bu yüzden aynı dosyayı iki kez saydırmaz ve tek yarım yükleme sınırına takılmaz. `clientSha256` göndermeyen istemci her istekte yeni bir yükleme açar.
- Boş disk sınırı: Sunucu diskteki boş yerden bitmemiş her yüklemenin henüz gelmeyen baytlarını düşer. `LEGACY` maçların bitmemiş yüklemeleri de buna girer. Yeni yükleme kalan yeri `VOLLEYSTAT_DISK_FLOOR_GIB` sınırının altına indirecekse sunucu `507 STORAGE_FULL` döner (`details.floorBytes`). Sınır varsayılan olarak 20 GiB'tır. Bir parça boş yeri bu sınırın yarısının altına indirecekse sunucu o parçayı yazmaz ve `507 STORAGE_FULL` döner. Bu yanıtta `details.floorBytes` sınırın yarısıdır ve `nextOffset` değişmez. Sunucu boş yerin kendisini istemciye göndermez. Ayar sıfır ya da daha büyük bir tam sayıdır. Sunucu başka bir değerle başlamaz.
- Sunucu bu denetimleri yükleme kaydını yazan işlemin içinde yapar. Art arda ya da aynı anda başlayan iki yükleme bu yüzden aynı boş yeri ikisi birden alamaz. Başka bir program diski bu arada doldurursa sunucuyu yalnız parçadaki denetim korur. Sunucu `409` sınırlarını yalnız yükleme başlarken denetler. Süren bir yükleme bildirdiği boyutla zaten sayılıdır. Sunucu disk sınırını her parçada da denetler.

### Yarım yüklemeler

- Sunucu her yüklemenin son dokunuşunu tutar. Sunucu dokunuşu yükleme başlarken, istemci aynı yüklemeyi yeniden başlatınca ve her parçayı yazınca günceller. Sunucu her dokunuşa o ana kadar açık kaldığı toplam süreyi yazar.
- Sunucu yalnız açık kaldığı süreyi sayar, çünkü telefon kapalı bir sunucuya parça gönderemez. Yarım yükleme son dokunuştan sonra sunucunun açık kaldığı toplam bir gün boyunca parça almazsa sunucu yüklemeyi kapatır (`ABORTED`) ve aldığı baytları siler. Sunucu her gece kapanıyorsa yüklemeyi takvimde bir günden daha geç kapatır. Sunucu aynı kuralla bitmeyen bir doğrulamayı da (`FINALIZING`) kapatır. Doğrulamada sunucu doğrulamanın başladığı andan sonra açık kaldığı süreyi sayar. 0.9 öncesi sürümlerin sakladığı bir yükleme dokunuş zamanı taşımaz ve sunucu onu açılıştaki ilk taramada kapatır. Sunucu 0.8'in bitiremediği bir doğrulamayı ilk açılıştaki toplamda başlamış sayar ve ona o andan sonra tam bir gün verir. Sunucu bu taramayı açılışta ve sonra dakikada bir yapar. Yükleme kaydı kalır, bu yüzden `GET /v1/uploads/{id}` yüklemenin durumunu yine gösterir.
- Sunucu açık kaldığı süreyi bilgisayarın takvim saatine bakmadan kendisi sayar. Tarama bu toplamı her seferinde veritabanına yazar ve sunucu kapanırken toplamı bir kez daha yazar. Sunucu iki yazma arasında geçen süreyi işletim sisteminin monoton saatinden okur. Bu saat yalnız ileri gider ve saat ayarı onu ileri ya da geri atlatmaz. Sunucu açılınca son yazdığı toplamdan devam eder ve monoton saati o andan sonra okur, bu yüzden kapalı kaldığı süre toplama girmez. Takvim saati yanlış gösterse, ileri ya da geri atlasa ya da pili biten bir bilgisayarda her açılışta eski bir tarihe dönse de tarama yüklemeyi saat doğru olsaydı kapatacağı anda kapatır. Sunucu veritabanına henüz bir toplam yazmadıysa saymaya sıfırdan başlar.
- Sunucunun çalıştığı bilgisayar kapanmak yerine uykuya da geçebilir. Bilgisayar uyurken sunucu kapanmaz, ama tarama yapamaz. Hiçbir telefon da o sırada sunucuya ulaşamaz. Bazı bilgisayarların monoton saati uykuda durur ve sunucu bu bilgisayarlarda yalnız çalıştığı saniyeleri sayar. Bazı bilgisayarların monoton saati uykuyu da sayar. Sunucu bu yüzden iki yazma arasında üç dakikadan uzun süre geçtiyse o arayı hiç saymaz. Sunucu üç dakikayı geçmeyen bir arayı tam sayar.
- Sunucu bir yüklemeyi açık kaldığı bir gün dolmadan kapatmaz. Monoton saati uykuyu da sayan bir bilgisayarda ise iki yazma arasına sığan kısa bir uyku açık süreye girer. Sunucu son dokunuştan sonraki bu kısa uykuların hepsini açık süreye katar, bu yüzden tarama yüklemeyi en çok bu uykuların toplamı kadar erken kapatabilir. Sunucu açık kaldığı süreyi şu durumlarda eksik sayar ve yüklemeyi o kadar geç kapatır. Sunucu çökerse ya da elektrik giderse son taramadan sonraki süreyi kaybeder. Monoton saati uykuyu da sayan bir bilgisayar uyursa sunucu uykudan önceki son yazma ile uykudan sonraki ilk yazma arasında çalıştığı saniyeleri kaybeder. Bu iki kayıp çoğu zaman bir dakikayı geçmez, çünkü sunucu dakikada bir tarar. Veritabanını ya da diski bekleyen bir tarama son yazmadan üç dakikadan geç gelirse sunucu o arayı kaybeder. Sunucu açıldıktan sonra kayıtlı toplamı okuyana kadar geçen süreyi de kaybeder. Sunucu iki yazma arasında gelen bir parçayı yazarken son yazdığı toplama o ana kadar geçen saniyeleri ekler. Sunucu o arayı sonra saymazsa bu dokunuş toplamın önünde kalır ve sunucu yüklemeyi yine geç kapatır. Sonraki tarama günü dolan yüklemeyi kapatır ve bu tarama çoğu zaman bir dakika içinde gelir. Veritabanını ya da diski bekleyen bir tarama son yazmadan en çok üç dakika sonra gelirse sunucu bu arayı yine tam sayar. Tarama bu yüzden yüklemeyi günün dolmasından en çok üç dakikalık açık süre sonra kapatır.
- Sunucu bir doğrulamanın başlangıcına da o anki toplamı yazar. Tamamlama isteği doğrulamayı işaretlerken rastgele bir anahtar yazar ve sonucu yalnız yükleme kaydı hâlâ o anahtarı taşırken yazar. Sunucu süren bir doğrulamayı başlangıcından sonra 15 dakika açık kaldığında terk edilmiş sayar ve ilk tamamlama isteğinde devralır. Sunucu yüklemeyi bırakma, maçı silme ve hesabı silme isteklerini de bu doğrulama yüzünden yalnız o 15 dakika boyunca reddeder. Kapanış ya da çökme bir doğrulamayı yarıda keserse sunucu açılışta o doğrulamayı hemen terk edilmiş sayar. Sunucu açılışta depolama klasörünün kilidini alır, bu yüzden klasörde başka bir sunucu çalışmaz. Sunucu kayıtlı toplamı okuduğu yazma işleminde önceki sunucu süreçlerinin bıraktığı doğrulamaların anahtarlarını siler. Açılıştan sonraki ilk tamamlama isteği böyle bir doğrulamayı hemen devralır. Sunucu yukarıdaki üç isteği de böyle bir doğrulama yüzünden reddetmez. `python -m app` ile açılan sunucu kilidi ancak süren istekleri bittikten sonra bırakır. Operatör böyle bir sunucuyu zorla kapatırsa işletim sistemi kilidi süreçle birlikte bırakır. Operatör sunucuyu kilidi daha erken bırakan başka bir yolla çalıştırırsa sonraki sunucu açılışta eski sunucunun süren isteğinin anahtarını siler. Doğrulamayı önceden işaretlemiş böyle bir istek bu yüzden sonucu yazmaz. Sunucu açılışta veritabanına yazamazsa tarama kayıtlı toplamı sonra okur ve anahtarları o zaman siler. Sunucu anahtarı yalnız terk edilmiş bir doğrulamayı devralırken, kapatırken ya da açılışta serbest bırakırken değiştirir. Operatörün silme komutu (`legacy --delete`) sunucunun son yazdığı toplamı okur. Sunucu çalışıyorsa komut bir doğrulamayı son taramadan bu yana geçen süre kadar genç görür ve onu erken silmez. Operatörün silme komutu anahtar taşımayan bir doğrulamayı hemen terk edilmiş sayar. 0.8 sürümündeki sunucu doğrulamalara anahtar yazmıyordu, bu yüzden komut 0.8'in bıraktığı bir doğrulamanın maçını hemen siler.
- Kapanan bir yüklemeye gelen parça ve `complete` çağrısı `409 UPLOAD_CLOSED` alır. Uygulama böyle bir yüklemeyi sonraki denemede baştan başlatır.

### Takımlar

Sürüm 0.10'dan başlayarak oturum açmış her hesap takım kurabilir (ADR-0120). Takım kurmak için kayıt kodu ya da operatör ayarı gerekmez. Bu sürümde takıma katılmanın bir yolu yoktur, bu yüzden bir takımın tek üyesi kaptanıdır. Katılma bağlantısı sonraki bir sürümle gelir.

| Uç | Gövde | Başarı | Hatalar |
|---|---|---|---|
| `GET /v1/teams` | | `200 {teams: [Team]}` | `401` |
| `PUT /v1/teams/{teamId}` | `{name, substituteSeats?, captainSeat?, schedule?, jersey?}` | `201` Team, `200` Team | `401`, `422 VALIDATION_ERROR`, `422 TEAM_ID_INVALID`, `422 NAME_INVALID`, `422 SEATS_INVALID`, `409 TEAM_ID_TAKEN`, `409 TEAM_LIMIT_REACHED`, `429 TOO_MANY_ATTEMPTS` |
| `GET /v1/teams/{teamId}` | | `200` Team | `401`, `404 TEAM_NOT_FOUND`, `403 NOT_A_MEMBER` |
| `PUT /v1/teams/{teamId}/settings` | `{name, schedule}` | `200` Team | `401`, `422 VALIDATION_ERROR`, `404 TEAM_NOT_FOUND`, `403 NOT_A_MEMBER`, `403 CAPTAIN_ONLY`, `422 NAME_INVALID` |
| `PUT /v1/teams/{teamId}/seats` | `{substituteSeats, moves?: [{userId, seat}]}` | `200` Team | `401`, `422 VALIDATION_ERROR`, `404 TEAM_NOT_FOUND`, `403 NOT_A_MEMBER`, `403 CAPTAIN_ONLY`, `422 SEATS_INVALID`, `422 NOT_A_TEAM_MEMBER`, `409 SEATS_BELOW_HOLDERS` |

Team:

```json
{"id": "6f0c…", "poolId": "p41…", "name": "Kuzey Spor", "tag": "K7Q2",
 "schedule": {"weekday": 2, "time": "20:00"}, "logo": null,
 "myRole": "CAPTAIN", "mySeat": "STARTER", "myJersey": 7, "newJoins": 0,
 "members": [{"userId": "a1…", "displayName": "Deniz", "jersey": 7, "role": "CAPTAIN", "seat": "STARTER",
              "joinedAt": "2026-11-25T18:00:00Z"}],
 "seats": {"starter": {"seats": 6, "taken": 1, "left": 5, "deciding": 0},
           "substitute": {"seats": 2, "taken": 0, "left": 2, "deciding": 0},
           "waiting": 0},
 "invite": null}
```

- Takım kimliğini istemci üretir. Kimlik küçük harfli ve tireli bir UUID olmalıdır. Sunucu başka bir biçimi `422 TEAM_ID_INVALID` ile reddeder.
- O kimlikte takım yoksa sunucu takımı kurar ve `201` döner. Sunucu takımın etiketini rastgele çeker, takımın yer havuzunu açar ve isteği gönderen hesabı takımın kaptanı yapar. O kimlikte bir takım varsa sunucu takımı değiştirmeden üyesine `200` ile döner. Başka bir hesap o kimlik için `409 TEAM_ID_TAKEN` alır. Bu yanıtın `details` alanı boştur ve yanıt takımın adını ya da etiketini taşımaz.
- Sunucu takım adını NFC biçimine çevirir, art arda boşlukları teke indirir ve baştaki ve sondaki boşluğu atar. Sonuç 2 ile 40 karakter arasıdır ve en az bir harf ya da rakam taşır. Sunucu kontrol ve biçim karakterlerini (sekme, satır sonu, yön ve sıfır genişlikli işaretler) reddeder. Noktalama işaretleri ve semboller adda kalabilir. Sunucu kurala uymayan bir ada `422 NAME_INVALID` döner.
- Etiket kod alfabesinin dört karakteridir (`K7Q2` gibi). Sunucu etiketi kurma işleminin içinde rastgele çeker. Çektiği etiket başka bir takımınsa sunucu yeniden çeker. İstemcinin seçtiği kimlik etiketi etkilemez.
- `schedule` takımın her zamanki maç günü ve saatidir: `{weekday, time}` ya da `null`. `weekday` 1 (pazartesi) ile 7 (pazar) arasında bir tam sayıdır. `time` `SS:DD` biçiminde 00:00 ile 23:59 arasında bir saattir. `jersey` kaptanın forma numarasıdır ve 1 ile 99 arasında bir tam sayı ya da `null` olur. Sunucu bu iki alanın başka bir değerine `422 VALIDATION_ERROR` döner.
- Bir takımın her zaman 6 as yeri vardır. Yedek yeri sayısı 0 ile 6 arasındadır ve varsayılan değeri 2'dir. Sunucu bu aralığın dışındaki bir sayıya `422 SEATS_INVALID` döner ve `details.reason` alanına `SUBSTITUTES_RANGE` yazar. İstek gövdesi as yeri sayısı taşımaz. Sunucu `starterSeats` taşıyan bir gövdeye `422 VALIDATION_ERROR` döner.
- Kaptan da takımın yerlerinden birini tutar ve bu yer varsayılan olarak bir as yeridir. İstek `captainSeat: "SUBSTITUTE"` taşırsa kaptanın yeri bir yedek yeri olur. Sunucu bu değeri 0 yedek yeriyle birlikte taşıyan bir isteğe `422 SEATS_INVALID` döner ve `details.reason` alanına `CAPTAIN_SEAT` yazar.
- Bir hesap en çok 20 takımın üyesi ve en çok 3 takımın kaptanı olabilir. Sunucu bu iki sınırı veritabanından okur. Sınırdaki bir hesap yeni takım için `409 TEAM_LIMIT_REACHED` alır. Yanıtın `details` alanı `scope` (`MEMBER` ya da `CAPTAIN`) ve `limit` değerlerini taşır.
- Bir hesap bir günde en çok 3 takım kurabilir. Sunucu son 24 saatteki kurulumları sayar ve dördüncü isteğe `429 TOO_MANY_ATTEMPTS` döner. Yanıt `Retry-After` başlığını ve `details: {scope: "TEAMS", retryAfterSeconds}` değerlerini taşır. Bekleme süresi bu kurulumların en eskisinin üzerinden 24 saat geçince biter. Sunucu bu sayıyı yalnız belleğinde tutar ve yeniden başlayınca saymaya sıfırdan başlar.
- Sunucu kurma isteğini şu sırayla denetler: gövdenin biçimi (`422 VALIDATION_ERROR`), kimliğin biçimi (`422 TEAM_ID_INVALID`), ad (`422 NAME_INVALID`), yer sayıları (`422 SEATS_INVALID`), kimliğin sahibi (`200` ya da `409 TEAM_ID_TAKEN`), üyelik sınırı, kaptanlık sınırı ve en son günlük sayı. Sunucu reddettiği bir isteği hiçbir yere yazmaz ve günlük sayıya katmaz. Bir kurulum hatayla yarıda kalırsa sunucu o kurulumu da günlük sayıdan düşer.
- `members` üyeleri katılma sırasıyla verir. `GET /v1/teams` ve `GET /v1/me` takımları hesabın katıldığı sırayla verir. `myRole`, `mySeat` ve `myJersey` isteği gönderen hesabın rolünü, yerini ve forma numarasını taşır.
- `newJoins` ve `seats.waiting` alanlarını yalnız kaptan görür. Öteki üyeler bu alanlarda `null` alır. `newJoins` kaptanın yeni üyeleri son gördüğü andan sonra katılan üyelerin sayısıdır. Sunucu takımı kurarken bu anı kurulum anı olarak yazar.
- `seats.starter` ve `seats.substitute` her yer türü için yer sayısını (`seats`), dolu yerleri (`taken`), boş yerleri (`left`) ve karar bekleyen yerleri (`deciding`) verir. Katılma bağlantısı gelene kadar sunucu `deciding` alanına ve kaptanın `seats.waiting` alanına 0, `invite` alanına `null` yazar. Takımın logosu yoksa `logo` alanı `null` olur.
- `PUT /v1/teams/{teamId}/settings` takımın adını ve maç gününü değiştirir. Gövde iki alanı da taşır. Kaptan maç gününü `schedule: null` ile siler. Sunucu bu ucu yalnız kaptana açar. Öteki bir üye `403 CAPTAIN_ONLY` alır.
- `PUT /v1/teams/{teamId}/seats` yedek yeri sayısını değiştirir ve üyeleri as ile yedek yerleri arasında taşır. Sunucu bu ucu da yalnız kaptana açar. Kaptan kendisini de taşıyabilir. Gövde kaptanın istediği son durumu bildirir: `substituteSeats` yeni yedek yeri sayısıdır ve `moves` yer türü değişen üyeleri listeler. Bir üye `moves` içinde en çok bir kez geçer ve liste en çok 12 taşıma tutar. Sunucu aynı üyeyi iki kez anan bir gövdeye `422 VALIDATION_ERROR` döner.
- Sunucu taşımaları ve sayıyı birlikte denetler ve taşımalardan sonraki son duruma bakar. Taşınan kişi takımın üyesi değilse sunucu `422 NOT_A_TEAM_MEMBER` döner (`details.userId`). Taşımalardan sonra bir yer türünde yerden fazla üye kalırsa sunucu `409 SEATS_BELOW_HOLDERS` döner (`details.seat`, `details.holders`, `details.seats`). Sunucu önce as yerlerine, sonra yedek yerlerine bakar ve bu durumlarda hiçbir şeyi değiştirmez. Kaptan bu yüzden bir as ile bir yedeğin yerini tek istekle değiştirir. Sunucu ise dolu bir türe tek başına giden bir taşımayı reddeder.
- Sunucu yalnız taşınan üyelerin yer türünü ve havuzun yedek yeri sayısını yazar. Aynı gövdeyi ikinci kez gönderen istemci `200` alır ve sunucu hiçbir şeyi değiştirmez.
- Takım uçlarında sıra şudur: oturum (`401`), gövdenin biçimi (`422 VALIDATION_ERROR`), takım (`404 TEAM_NOT_FOUND`), üyelik (`403 NOT_A_MEMBER`), kaptanlık (`403 CAPTAIN_ONLY`), sonra ucun öteki denetimleri. Sunucu üye olmayan bir hesaba `403 NOT_A_MEMBER` döner. Bu yanıtın ve `403 CAPTAIN_ONLY` yanıtının `details` alanı boştur. Yazan her takım ucu oturumu kendi yazma işleminin içinde yeniden okur.
