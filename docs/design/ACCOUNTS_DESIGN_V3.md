> **Taslak.** Bu belge hesap, takım, davet bağlantısı ve kaptan isteği tasarımının üçüncü sürümüdür. Ürün sahibi tasarımı henüz onaylamadı. Ekip bu belgeye göre henüz kod yazmadı.

# VolleyStat: accounts, teams, the join link, captain match requests and statistics (design v3)

**Status:** design proposal. Nothing in it is built yet. It replaces design v2 (`design_v2.md`) and keeps every part of v2 that no v2 critique contradicts.
**Date:** 16 November 2026. **Revised:** 17 November 2026 after four checkers (concurrency, security, product, fidelity). Section 13.1 lists their 37 points and where each fix lives.
**Base:** branch `claude/volleystat-multi-match` at b2ce9b2 (API 0.7.0, app 0.7.0, ADR-0118). `main` is still at 2397f19. The report carries its parts in `score.matches`, and each part carries its own `teams`, `players` and `unattributed`. The accounts work starts after the multi-match branch merges to `main`, and every slice rebases on that merge.
**Expected numbers:** ADR-0119 and API 0.8. Check both again at merge time.
**Inputs:** design v2; `critiques_v1.json` (already handled in v2); `critiques_v2.json` (concurrency C1-C7, security P1-P8, product F1-F8); the critics' probes in `critique_v2/`, `critique_v2c/` and `probe_v3/`; the code at b2ce9b2.

## 0. Summary

### 0.1 What the owner asked

16 November, first message (paraphrased):
- Users can log in.
- People can create a team and join a team.
- Every person and every team has their own statistics.
- When a match is set up, the team captain sends a request to the captain of the other team. When the other captain accepts, the match is confirmed and a video can be uploaded or recorded live.
- The video part stays as it is. The owner does not want to give details.

16 November, second message (join link, paraphrased):
- The captain sends a link, for example to a WhatsApp group of 100. A player opens it, logs in or signs up, and then a pop-up shows the team's logo, name, match day and time, the question "Katılmak istiyor musun?", Reddet on the left and Katıl on the right, and a 10-second countdown that closes the pop-up at zero. Whoever accepts joins.
- Many people open the link at once. The first one in starts the timer. Joining must be fast. Speed matters most, and the user must never be told something false.
- Example: 9 people accepted, 1 person is deciding, and the 11th person is told that one place is free and someone is deciding on it. If the decider rejects or runs out of time, the first person waiting gets the decision screen, and so on.

Earlier answers that bind this design: a video may hold several matches (done, API 0.7); the owner deferred the KVKK review, and the app is not opened to other teams before it (ADR-0117); the feedback address is voleybolveveleybol@gmail.com; the licence is closed commercial; there is no NVIDIA GPU; the owner uses free tools only.

### 0.2 The design in fifteen lines

1. **Login.** Username, password and display name. stdlib scrypt, an opaque bearer token stored as SHA-256, no e-mail, no phone, no new dependency. A bundled list refuses the 10 000 most common passwords (v1 and v2, plus P4).
2. **Who may sign up and who may lead a team.** A person signs up with a live team invite link or with a one-time operator code. Before the KVKK review, creating a team needs a one-time captain code from the operator (`VOLLEYSTAT_TEAMS=operator`, the default). Before the review the operator prints captain codes only for the owner's own teams, which ADR-0119 lists by name; a code creates only the team it names and serves only the account it registered. That turns ADR-0117 into a server rule (P1, S8, fidelity V1).
3. **Join link.** A team has at most one live invite: an unguessable https link and an 8-character code (`K7QF-2M9X`). The server stores only a hash of the token and a keyed hash of the code, under a key kept outside the storage folder (S9). The invite lives 7 days, and the server closes it on its own when the team is full (P2, P8, F7).
4. **Seat holds.** A team has a capacity (default 14, captain included). A person who reaches the decision point while a seat is free and nobody waits gets that seat for 10 seconds (HOLDING). Every seat change is one SQLite write transaction, so two people never get the same seat.
5. **FIFO queue in arrival order.** With no free seat but someone deciding, the person waits in a queue. An in-process FIFO ticket lock in front of every SQLite writer makes the queue follow the order in which requests reach the server (C4). When a seat frees, the server offers it to the head of the queue. The waiting phone acknowledges through its own route within 8 seconds, and the 10-second pop-up starts then (C5, F2).
6. **One function for time.** `settle(team)` does every time-based transition. Write routes and a 250 ms clock call it inside a write transaction, after the pause recovery that every write transaction runs first when it is due. A long-poll never calls it and never writes (C1, conc V2).
7. **Decisions count when they arrive.** The server stamps every request on arrival, before any queue or lock. An ACCEPT counts when its headers arrive before the deadline plus the 2-second grace, however long its body or the lock then take, for at most 5 s more. A late ACCEPT or ack closes its own attempt at once (C2, conc V1, conc V3, S1).
8. **Live channel.** An async long-poll answers from an in-memory team snapshot that every writer publishes after its commit. It wakes only when the caller's own screen would change, and heartbeats are written in one batch per clock tick. Measured: 100 phones claiming while every phone polls finished in 0.66-0.70 s instead of 6.6-7.1 s (C1).
9. **Never false.** The phone's countdown comes from the server's `remainingMs` and `waitedMs` and the phone's elapsed time only, so it never outlasts the server's (C3). Every text states only what the server or the app knows (C6, F6). The app keeps the screen on while a person waits or decides (C6, F2).
10. **Match requests by link.** A captain creates the request and sends a single-use request link to the other captain, who accepts with one of their teams. Before the requesting side's first upload, its captain confirms the opponent's name and tag once (P1). Before the KVKK review requests run only between the owner's own teams. A request that the other captain declines or never answers turns into a one-sided record, so the recording still counts for the own team (R1).
11. **Recording never waits.** The recorder stays open as today. A team recording waits on the phone and uploads once the request is accepted (F5).
12. **Each side owns its numbers.** Each side may add its own video and reviews it. Each side's captain confirms a result for their own side, and the server keeps one snapshot per side. A team's statistics read only its own side's snapshot, so no captain can change, erase or hold back the other team's numbers (F3). A side that did not film confirms the result that the filming side's reviewer offered, even after that side left (R7).
13. **Rosters.** Each side's captain writes who played with which jersey (at most 12) and confirms it. A person's statistics come only from confirmed rosters. Unknown is never 0.
14. **Hosting and the field test.** Everything works on one computer today. Real use between teams needs an internet-reachable HTTPS server, which is an owner decision. A field-test slice runs before the request and statistics slices (F8).
15. **Defaults without the owner.** Every point that needs the owner has a default that works without the owner's action and a stated cost (16.1).

### 0.3 What changed from v2

| Topic | v2 | v3 | Finding |
|---|---|---|---|
| Long-poll | woke on every team change; two thread-pool reads per wake; heartbeat write per poll | answers from a published team snapshot in the event loop; wakes only when the caller's view changes; heartbeats batched per tick | C1 |
| Write order | SQLite busy handler (random order) | in-process FIFO ticket lock in front of `BEGIN IMMEDIATE` for every writer | C4 |
| Grace and offer deadline | judged at lock time | judged at arrival time; a decision in flight is never expired by `settle` | C2 |
| Countdown | `remainingMs − (elapsed − sentAt)` per answer; TIMEOUT closed the hold at once | `waitedMs` on every answer; deadline is the maximum lower bound and never moves down; an early TIMEOUT only moves the close to `expiresAt` | C3 |
| Offer acknowledgement | the claim acknowledged; a late ack silently re-queued | its own route `POST …/ack`; resume by stored attempt id | C5 |
| Pauses | recovery only at start-up | the clock detects a pause and runs the same recovery; recovery offer window 10 s | C6 |
| Logo in join answers | base64 in the claim | never in claim, ack or poll answers; a 128 px logo in resolve, fetched in parallel | C7, F4 |
| Team creation | any account | one-time captain code before the KVKK review (default) | P1 |
| Sign-up through a request link | allowed | refused; only team invites and operator codes open sign-up | P1 |
| Team tag | computed from the client-chosen id | drawn at random by the server, stored, unique | P1 |
| Opponent | accepted at once | the requesting captain confirms the opponent before their side's first upload | P1 |
| Video ownership | the remaining side kept everything | a video belongs to the side that uploaded it and leaves with that side | P1, F3 |
| Full team | invite stayed live | invite closes on its own when members reach capacity | P2 |
| Removed member | could rejoin at once | blocked until the captain lifts the block | P2 |
| Past team videos | every member saw every record | a member reads a record's content only if it was played on or after the day they joined, or if they are on its roster | P2 |
| Sign-ups per link | unlimited | at most 200 per invite (the queue limit) | P3 |
| Storage | no quota | quotas per account and team, a free-disk floor, abandoned uploads swept after 24 h | P3 |
| Throttles | server-wide failure caps refused everyone | per-source keys refuse; global counters are high circuit breakers that never touch a request with a live token or a session | P4, F7 |
| Anonymous link view | schedule, seats, venue, date | kind, team name, tag and small logo only | P5 |
| Hosting privacy | "no tunnel ever holds the token" | where TLS ends is named; backups have a fixed retention that the texts state | P6 |
| Logo format | VP8, VP8L and VP8X with alpha | one lossy `VP8 ` chunk only | P7 |
| Link secrets | plaintext token and code | SHA-256 token, HMAC code with a key outside the database; shown only at creation | P8 |
| Newcomer without the app | no path | fallback page with the code, install steps, re-tap after install; register-first; paste the WhatsApp message | F1 |
| Waiting place | lost on screen timeout | screen kept on, retry every second, offer window 8 s | F2 |
| Result counting | a snapshot both captains confirmed | one snapshot per side; one video per side; optional one-sided team matches | F3 |
| When the 10 s start | at the claim, possibly under a system sheet | the claim goes out only when the pop-up's window has focus | F4 |
| Recorder | locked until acceptance | always open; uploads wait for acceptance | F5 |
| Texts | some stated causes the server cannot know | split by reason; per viewer; only known facts | F6, C6 |
| Code | 6 characters | 8 characters (owner decision, default 8) | F7, P4 |
| Slices | real phones first in B10 | exposure minimum B6a and field test B6b before requests | F8 |

v3 keeps from v2: the password and token rules, the hash executor, the access helper and route walk, 403 `NOT_A_MEMBER` instead of 404 for strangers, no cookies or CORS, the statistics value rule (`report._value`), unknown never 0, no rankings, the per-account phone folders, the operator CLI for legacy matches, the state machine's seat invariant and its transitions T1-T16 (changed only where a finding says so), the owner's pop-up, and the privacy limits toward the other team.
Section 13.1 lists what the four checkers of v3 found and what this revision changed for each point.

### 0.4 Why sign-up and team creation keep a gate

The owner's flow says a brand-new person gets a sign-up flow. That person arrives through a team invite, so a live invite opens sign-up. `VOLLEYSTAT_SIGNUP=link` (the default) refuses a registration that carries neither a live team invite nor a one-time operator code; `open` is for tests and CI only. A match-request link no longer opens sign-up, because the captain who accepts a request must already lead a team (P1).

Team creation has its own gate. `VOLLEYSTAT_TEAMS=operator` (the default before the KVKK review) refuses `PUT /v1/teams/{id}` without a one-time captain code that the operator prints with `python -m app.admin captain-code`. A captain code also opens sign-up, so a new captain needs one code only. Accepting a match request needs a team, so only captains the operator approved can create teams, invite people or accept requests. Before the KVKK review the operator prints captain codes only for the owner's own teams, and ADR-0119 lists their names. `python -m app.admin captain-code --team-name <name>` prints the ADR-0117 rule, refuses a name outside that list unless `--after-kvkk` is given, and stores the name with the code; team creation with the code then accepts only that name after NFC and casefold. A CAPTAIN code that opened a registration serves only the account it registered (S8). This turns ADR-0117 ("Uygulama başka takımlara KVKK incelemesinden önce açılmaz") into a rule the server enforces: before the review every team on the server is one of the owner's own teams, so match requests run only between them, and invites bring people only into them (fidelity V1). A captain can still invite anyone into their own team; that is the owner's join flow. `VOLLEYSTAT_TEAMS=open` exists for tests and for the time after the KVKK review. Whether the gate stays on is an owner decision (16.1, item 3).

---

## 1. Terms

| Term | Meaning |
|---|---|
| **Share link** | A captain's link: a team invite or a match request. It has an unguessable token and an 8-character code. |
| **Invite** | The one live share link of kind `TEAM_INVITE` of a team. |
| **Tombstone** | A closed share link's row. It keeps the token hash, the code's HMAC (`closed_code_hmac`), the team and the reason for 30 days so the server can say why a link or a code no longer works (R3). |
| **Capacity** | The most members a team can have, the captain included. Default 14, range 2 to 50. |
| **Seats left** | capacity − members. |
| **Free seats** | capacity − members − offered − holding. Never negative. |
| **Decision point** | The moment a logged-in person's phone asks the server for a seat. The phone sends that request only when the join screen is on screen and its window has focus (F4). |
| **Join attempt** | One person's try to join one team, from the decision point to an outcome. At most one is open per person and team, and at most three per person. |
| **Hold** | An attempt in state HOLDING: one seat is reserved for that person until `expiresAt`. |
| **Grace deadline** | `grace_until` of a hold: `expires_at` + 2 s. Only pause recovery moves it later (2.11). |
| **Offer** | An attempt in state OFFERED: a seat is reserved for the head of the queue until the phone acknowledges. |
| **Queue** | The WAITING attempts of a team ordered by `queue_no`. |
| **Arrival** | The server's wall-clock time when a request's headers reached the ASGI app. The outermost middleware stamps it before the body is read, before the thread pool and before any lock (2.9). |
| **In flight** | A decision or acknowledgement whose arrival is stamped and whose request has not finished. The stamping middleware registers it (2.9). |
| **Pause** | A stretch in which the server process did not run (a stop or a sleep), or in which no request reached it while phones should have polled (a tunnel or home connection outage, called a silence). Recovery follows it (2.11). |
| **Team snapshot** | The in-memory view of one team's join state that a writer builds inside its transaction and publishes after its commit. |
| **View tag** | A short string that changes exactly when what one caller's join screen shows changes. The long-poll cursor. |
| **Match record** | One row of `matches`. A team match record has two sides; a one-sided team match has one; a personal match has an owner. |
| **Side** | HOME (the team that requested) or AWAY (the team that accepted) of a team match record. |
| **Reviewer** | The captain of the side that uploaded a video. Each video has one reviewer. |
| **Side result** | The `match_results` snapshot that one side's captain confirmed. That side's team statistics read only this. |
| **Played match** | A part of the report with `kind: "MATCH"` (ADR-0118). |
| **Operator** | The person who runs the server and its CLI. Today that is the owner. |

---

## 2. The join flow

### 2.1 Overview

```
 Captain                              Server                                  Joiner's phone
 ───────                              ──────                                  ──────────────
 Takım Kur (captain code) ────────▶ team + invite (token, code, 7 days)
 Paylaş (WhatsApp) ──────────────────────────────────────────────────────────▶ opens link or pastes the message
                                                                              │ no session?
                                      POST /v1/links/resolve (anonymous) ◀────┤ name, tag, small logo on Hesap Oluştur
                                      POST /v1/auth/register|login    ◀───────┤ autofill commit on the submit tap
                                      POST /v1/links/resolve (session) ◀──────┤ in parallel: schedule, small logo
                                      POST /v1/join-attempts           ◀──────┘ decision point: join screen focused
                                       ├─ free seat, empty queue ──▶ HOLDING ──▶ pop-up, countdown from 10
                                       ├─ no free seat, someone deciding ──▶ WAITING ──▶ waiting screen, screen kept on
                                       ├─ already a member ──▶ 409 ALREADY_MEMBER
                                       └─ link closed ──▶ 410 LINK_CLOSED (reason: FULL, EXPIRED, RENEWED, CLOSED)
 waiting phone: GET /v1/join-attempts/{id}?view=<tag> (long-poll from the snapshot; its arrival is the heartbeat)
 seat frees ──▶ settle: head WAITING → OFFERED ──▶ poll answers ──▶ POST /v1/join-attempts/{id}/ack ──▶ HOLDING ──▶ pop-up
 pop-up: Katıl → POST …/decision ACCEPT (judged by arrival) → JOINED → "Takıma Katıldın" → jersey prompt
         Reddet → REJECTED;   local zero → TIMEOUT (closes at expiresAt) → EXPIRED;   both pass the seat on
```

A logged-in member whose app has verified App Links: link tap, then Katıl. The app sends the claim and a resolve in parallel. The claim answer (about 1 KB) opens the pop-up; the resolve answer brings the 128 px logo, and the pop-up shows the team's initials until it arrives. That is one round trip from tap to pop-up.

### 2.2 The share link and the code

**Token.** `secrets.token_urlsafe(16)`: 22 characters, 128 bits. The server stores SHA-256 of the token (P8).

**Code.** 8 characters from `23456789ABCDEFGHJKMNPQRSTUVWXYZ` (31 characters, no 0/O/1/I/L), shown as `K7QF-2M9X` (owner decision 16.1 item 5, default 8; F7, P4). `GET /v1/health` reports the length as `links.codeLength`, so no text hard-codes it. Typed input ignores case, spaces and hyphens; pasted text follows the stricter extraction rule of 10.1. The server stores HMAC-SHA256 of the normalized code under a 32-byte link key (P8). The key file lives outside the storage folder (S9): by default in the operator's configuration folder (`~/Library/Application Support/VolleyStat/link.key` on macOS, `$XDG_CONFIG_HOME/volleystat/link.key` on Linux) or at `VOLLEYSTAT_LINK_KEY_FILE`, with mode 0600, created at first start and never in the database. The server refuses to start when the key path lies inside the storage folder, and no admin command that packs or copies the storage folder takes the key. A copied storage folder, a debugging snapshot or a backup therefore hands out no code. The server draws a new code until its HMAC is unused both as a live `code_hmac` and as a tombstone's `closed_code_hmac`, inside the write transaction that stores it. A closing link moves its HMAC from `code_hmac` (unique among live links) to `closed_code_hmac` for the tombstone's 30 days (R3). Losing the key makes every live code unknown; links keep working, and a captain renews.

**Guessing.** 31^8 ≈ 8.5 × 10^11 codes. A code miss on any route that accepts a code (resolve, register, claim, accept, decline) counts: anonymous misses pass a server-wide circuit breaker of 1 000 per 10 minutes, which typos never reach; logged-in misses have 10 per hour per account; after B6a every source address (an IPv6 address by its /64) has its own key (3.6, S2). A code that hits a tombstone is not a miss. Even at the circuit breaker's full rate (144 000 guesses a day), a week of guessing against 100 live codes succeeds with a probability of about 1.2 × 10^-4. The token path is never affected by code limits.

**URL.** `https://<link host>/j/#t=<token>&c=<code>` for an invite and `https://<link host>/m/#t=<token>&c=<code>` for a match request.
- Browsers never send the fragment, so no access log and no WhatsApp preview fetch holds the token or the code.
- The code sits next to the token because the share message already carries both. The fallback page reads it from the fragment and shows it without asking any server (F1), and the server still stores only the HMAC (P8).
- The app reads the token from `intent.data.fragment` and sends it in request bodies. A proxy or tunnel that ends TLS sees those bodies. Section 12 therefore names where TLS ends, and v2's sentence "no proxy or tunnel ever holds the token" is withdrawn (P6).

**What makes the link open the app.** Android App Links need `https://<link host>/.well-known/assetlinks.json` with the package name and the release signing certificate's SHA-256. The link host is an owner decision (16.1 item 2):
- **The owner's website domain** survives any change of the API's host or tunnel. It needs four static files on the website and the owner's explicit permission (12.4). This project changes no file of the website.
- **The API server's own host** (the default once hosting exists, B6a). The server serves `assetlinks.json` and the fallback pages. Links break whenever that host changes.
- **Before hosting** the server has no public address: `GET /v1/health` reports `links.publicUrl: false`, the invite has no `url`, and the app shares the code with the paste-friendly message of 2.12.

**The fallback page** at `/j/` and `/m/` (F1) is static HTML with one external script, because a strict CSP allows no inline script. Its CSP is `default-src 'none'; script-src 'self'; style-src 'self'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'`. It shows no team data. Anyone can write the fragment, so the script checks it before it uses it (S6):
- It accepts `t` only when it matches `^[A-Za-z0-9_-]{22}$`, and `c` only when, after removing `-` and uppercasing, it is 8 characters of the code alphabet. Anything else shows the no-code state without an Uygulamada Aç button.
- It writes text only with `textContent`, creates no element from the fragment, and builds every URL from the checked values only.

With checked values the script:
- shows the code large with a Kodu Kopyala button;
- builds `intent://<link host>/j/#t=<t>&c=<c>#Intent;scheme=https;package=com.voleybolveveleybol.volleystat;S.browser_fallback_url=<f>;end` for Uygulamada Aç (`/m/` for a request), where `<f>` is `encodeURIComponent(location.origin + location.pathname + "#t=" + t + "&c=" + c)`. Android's `Intent.parseUri` reads the intent from the last `#` and takes the data from the text before it, with `intent:` replaced by the scheme. The data is therefore `https://<link host>/j/#t=…&c=…`, which the app's intent filter (10.6) matches with the fragment kept. v3's `intent://j/…` gave the host `j`, which no filter matches, so the button only reloaded the page; and an unencoded fallback URL with its own `#` would have moved the last `#` (R6). A phone without the app returns to this page, with the code, instead of a missing Play Store entry;
- shows the install steps of the chosen distribution (default: ask the captain for the APK file, allow the install, then tap the WhatsApp link again).

The texts are in 2.12.

**Expiry and control.**
- An invite lives 7 days.
- `POST /v1/teams/{id}/invite` ("Bağlantıyı Yenile") closes the live invite with reason RENEWED and creates a new one. `DELETE /v1/teams/{id}/invite` ("Daveti Kapat") closes it with reason CLOSED. Expiry closes it with reason EXPIRED. Each closes every open attempt of the team with the matching reason (T14); a hold whose decision arrived before the renewing or closing request stays open until that decision's transaction runs (2.9).
- **The invite closes on its own when the team is full** (P2). Any transaction that ends with members = capacity closes the live invite with reason FULL. At that moment nobody is offered or holding (seat invariant), so `settle` closes every waiting attempt as TEAM_FULL (T13). T14 does not apply to FULL, so a waiter never gets the invite's reason instead of TEAM_FULL (fidelity V7). The captain card then offers "Yeni Bağlantı Oluştur", and an old link answers `410 LINK_CLOSED` with reason FULL.
- Creating a team creates its first invite.
- A closed link stays as a tombstone for 30 days: token hash, the code's HMAC in `closed_code_hmac`, team, kind, reason and `closed_at`. Resolve, claim, register, accept and decline, by token or by code, then answer `410 LINK_CLOSED` with `details.reason` and `details.teamName`, so every text can say what really happened (F6). A code lookup tries the live `code_hmac` first and then the newest tombstone with that `closed_code_hmac`. A tombstone hit counts toward no throttle key, so a person who types the right code of a closed invite gets `link_team_full`, `link_renewed`, `link_expired` or `link_closed`, and never `code_wrong` (R3, fidelity V2). After 30 days the row goes and the token or the code answers `404 LINK_NOT_FOUND`.

**Showing the token and the code (P8).** The server returns the token, the code and the URL only in the answers of team creation, `POST …/invite` and `PUT /v1/match-requests/{id}`. `GET …/invite` returns only state, expiry and seats. The captain's phone keeps the URL and the code encrypted (10.3). A captain on another phone sees "Bu telefonda davet bağlantısının bir kopyası yok" and can renew.

### 2.3 Seats

For one team at any instant, inside any transaction:

```
members + offered + holding ≤ capacity          (the seat invariant)
free = capacity − members − offered − holding
```

- A seat is reserved from the moment an attempt becomes OFFERED or HOLDING.
- After `settle` returns, free > 0 and a non-empty queue never both hold. On the waiting screen seats left therefore always equals the number of people deciding.
- The queue exists only while someone can still get a seat. When free = 0 and nobody is offered or holding, every WAITING attempt closes with reason TEAM_FULL, and if members = capacity the invite closes with reason FULL (2.2).
- The captain cannot lower capacity below members + offered + holding: `409 CAPACITY_IN_USE` (`details.deciding`) or `409 CAPACITY_BELOW_MEMBERS` (`details.members`). Lowering it to exactly members closes the invite (FULL). A raised capacity frees seats, and `settle` offers them at once; a closed invite stays closed until the captain creates a new one.
- A queue holds at most 200 attempts (`409 QUEUE_FULL`). A person holds at most 3 open attempts across teams (`409 ATTEMPT_LIMIT`, P3).

### 2.4 The server state machine

```
                  claim, free seat, empty queue
   (decision point) ─────────────────────────────▶ HOLDING ──ACCEPT arrived < grace_until────────▶ JOINED
        │                                            │  ├──REJECT or DELETE─────────────────────▶ REJECTED
        │ claim, no free seat,                       │  ├──grace_until passed, no protected decision,
        │ someone deciding                           │  │  or its own ACCEPT arrived late─────────▶ EXPIRED (NO_DECISION)
        ▼                                            ▲  └──TIMEOUT reported, single session,
     WAITING ──settle: seat free, head of queue──▶ OFFERED      at expiresAt───────────────────▶ EXPIRED (TIME_UP)
        │                                 ack arrived < offerDeadline ─┘
        │                                            └──offerDeadline passed (clock, or its own late ack)──▶ SKIPPED
        ├──no heartbeat for 30 s, outside a silence──▶ DROPPED
        ├──DELETE───────────────────────────────────▶ LEFT   (OFFERED + DELETE ▶ LEFT too)
        └──free 0 and nobody deciding──────────────▶ CLOSED (TEAM_FULL)
   any open state: invite renewed, closed or expired ──▶ CLOSED (LINK_RENEWED, LINK_CLOSED, LINK_EXPIRED)
```

Every transition, its trigger, its guard and its effect. "Arrival" is the request's arrival stamp (2.9); "now" is read after the write lock is taken and after pause recovery (2.11). "Protected" and "silence" are defined in 2.9 and 2.11.

| # | From → to | Trigger | Guard (checked in the same write transaction) | Effect |
|---|---|---|---|---|
| T1 | none → HOLDING | `POST /v1/join-attempts` | invite live; not a member; not blocked (`403 JOIN_BLOCKED`); no open attempt for this team; fewer than 3 open attempts; free > 0 and queue empty (after settle); fewer than 20 teams | `queue_no = ++queue_seq`, `arrived_at`, `opened_session`, `hold_started_at = now`, `expires_at = now + 10`, `grace_until = expires_at + 2`, `due_at = grace_until` |
| T2 | none → WAITING | same | free = 0 and offered + holding > 0; queue shorter than 200 | `queue_no = ++queue_seq`, `arrived_at`, `opened_session`, `seen_at = now`, `due_at = now + 30` |
| T3 | none → (refused) | same | free = 0 and nobody offered or holding | `409 TEAM_FULL`, no row. Defensive only: members = capacity has already closed the invite, so the claim answers `410 LINK_CLOSED` (FULL) first. |
| T4 | WAITING → WAITING | long-poll arrival or claim | | heartbeat kept in memory, also for a poll that a cap answers at once (2.7); the clock writes `seen_at` and `due_at = seen_at + 30` for all of them in one UPDATE per tick (C1) |
| T5 | WAITING → OFFERED | `settle` | free > 0 and the attempt is among the `free` lowest `queue_no` | `offered_at = now`, `offer_deadline = now + 8`, `due_at = offer_deadline` |
| T6 | OFFERED → HOLDING | its own `POST /v1/join-attempts/{id}/ack` by the same person | arrival < `offer_deadline` | `hold_started_at = now`, `expires_at = now + 10`, `grace_until = expires_at + 2`, `due_at = grace_until` |
| T6b | OFFERED → SKIPPED | its own `POST …/ack` | arrival ≥ `offer_deadline` | closed; `settle` offers the seat to the next person in the same transaction; answer `409 ATTEMPT_CLOSED` (SKIPPED) with `details.offerWindowS` (conc V3) |
| T7 | OFFERED → SKIPPED | `settle` | now ≥ `offer_deadline`; not protected; not in a silence | closed |
| T8 | HOLDING → JOINED | its own `decision ACCEPT` | arrival < `grace_until`; fewer than 20 teams (else T8b) | insert membership (MEMBER, `joined_at = now`), `decided_session`, closed; if members = capacity, close the invite (FULL) |
| T8b | HOLDING → CLOSED (TEAM_LIMIT) | its own `decision ACCEPT` | arrival < `grace_until`; the person reached 20 teams meanwhile | closed, `409 TEAM_LIMIT_REACHED` |
| T8c | HOLDING → EXPIRED (NO_DECISION) | its own `decision ACCEPT` | arrival ≥ `grace_until` | closed, `late_accept_at = arrival`; `settle` offers the seat in the same transaction; answer `409 ATTEMPT_CLOSED` with `details.lateAccept: true` (conc V3) |
| T9 | HOLDING → REJECTED | `decision REJECT` or `DELETE` | | `decided_session`, closed |
| T10a | HOLDING → EXPIRED (NO_DECISION) | `settle`; or its own TIMEOUT from any session that arrived at or after `grace_until` (S1) | now ≥ `grace_until`; in `settle` also not protected and not in a silence | closed |
| T10b | HOLDING → EXPIRED (TIME_UP) | `decision TIMEOUT`, then `settle` | `other_session = 0` | The TIMEOUT sets `timeout_reported = 1`. If it arrived at or after `expires_at`, the hold closes at once. Otherwise `due_at = expires_at`, and `settle` closes the hold at `expires_at` unless it is protected. With `other_session = 1` a TIMEOUT that arrived before `grace_until` only records itself, and T10a applies (C3); one that arrived at or after `grace_until` closes the hold by T10a (S1). |
| T11 | WAITING or OFFERED → LEFT | `DELETE` | not protected | closed |
| T12 | WAITING → DROPPED | `settle` | now ≥ `seen_at + 30`; not in a silence | closed |
| T13 | WAITING → CLOSED (TEAM_FULL) | `settle` | free = 0 and offered + holding = 0 | closed |
| T14 | any open → CLOSED (LINK_RENEWED, LINK_CLOSED, LINK_EXPIRED) | renew, close or expiry of the invite; never FULL, whose waiters T13 closes (fidelity V7) | a hold protected by a decision that arrived before the closing request's own arrival (for expiry, before the invite's `expires_at`) stays open; its own decision transaction or the next `settle` closes it (2.9, conc V1) | closed |
| T15 | any open → (row gone) | team or account deleted | | `ON DELETE CASCADE`; the deleting transaction runs `settle` for every affected team and publishes their snapshots |
| T16 | closed → (row gone) | minute sweep | `closed_at + 24 h` passed | deleted, then `empty_write_ahead_log()` |
| T17 | closed → closed | `decision ACCEPT` on a closed attempt | | `late_accept_at = arrival` is recorded. For an EXPIRED attempt the answer carries `details.lateAccept: true` when the arrival is at or after the deadline that closed it (`expires_at` for TIME_UP, `grace_until` for NO_DECISION), and `details.acceptArrivedInTime: true` otherwise; the second happens only when an in-time ACCEPT waited longer than its 5 s protection (2.9). Every other closed state answers with both false. `409 ATTEMPT_CLOSED` with `details.state` and `details.reason` (conc V1). |

**Order inside a write route (conc V3).** A claim runs pause recovery when it is due (2.11), then `settle`, then T1, T2 or T3. An ack or a decision runs pause recovery when it is due, then judges its own attempt by its arrival (T6, T6b, T8, T8b, T8c, T9, T10a, T10b, T17), and only then runs `settle`, which skips only other protected attempts (2.9). Every other write route makes its change and runs `settle`.

Every closing transition sets `closed_at = now` and `due_at = NULL`, runs `settle` in the same transaction (so a freed seat is offered before the commit), and builds the team snapshot that the route publishes after the commit (2.7). Every write transaction that changes a team's join state moves `teams.queue_version` by one (2.7, conc V7). A repeat of the transition that already happened answers 200 with the current state (an ACCEPT after JOINED, a DELETE after LEFT, an ack after HOLDING). An ack on a WAITING attempt answers `409 NOT_OFFERED`. A decision on a WAITING or OFFERED attempt answers `409 NOT_DECIDING` with `details.state` (S1). Any other request on a closed attempt answers `409 ATTEMPT_CLOSED` with `details.state` and `details.reason`; a SKIPPED attempt's answer also carries `details.offerWindowS`, the whole seconds from `offered_at` to `offer_deadline` (fidelity V8).

**Same person, same team.** The partial unique index `join_attempts_one_open` allows one open attempt. A claim from any device returns that attempt and never acknowledges an offer (C5). A claim from a session other than `opened_session` sets `other_session = 1`, which keeps the full grace for every device of that person (T10b). Only a fresh link tap or "Yeniden Sıraya Gir" creates a new attempt after a closed one; the app resumes an existing attempt with `GET /v1/join-attempts/{id}` (10.2).

**Why the early TIMEOUT moves the close to `expiresAt` instead of being ignored (C3, refined).** The phone's deadline lies before the server's by about the one-way delay of the request that set it (2.6), and the TIMEOUT then needs the same delay to arrive. It therefore reaches the server close to `expiresAt`, before it about half the time. The critic's measured fix ignores a TIMEOUT that arrives before `expiresAt`, so in those cases the next person waits the full 2-second grace. Marking the hold and closing it at `expiresAt` keeps the protection the critic measured (a wrong early TIMEOUT never shortens the visible 10 seconds) and still saves the grace. The grace exists for a tap that the network delays; after the only session reported its zero, no tap can follow. A second session (`other_session = 1`) keeps the grace, so a tap on the other phone is never lost.

### 2.5 `settle`: the only place time moves state

```python
def settle(db, team_id, now, own_attempt=None):
    # Inside the FIFO lock and BEGIN IMMEDIATE, after pause recovery when it was due (2.11); now read after both.
    # protected(a, deadline): another request's in-flight entry on attempt a arrived before deadline,
    #                         and now < deadline + PROTECT_MAX (2.9). own_attempt was judged by its route.
    # silent: the server is in a silence (2.11); closings that wait for a phone's message wait too.
    close HOLDING  with timeout_reported and other_session = 0 and expires_at <= now,
                   not protected(expires_at)                               -> EXPIRED TIME_UP     (T10b)
    close HOLDING  with grace_until <= now, not protected(grace_until), not silent
                                                                           -> EXPIRED NO_DECISION (T10a)
    close OFFERED  with offer_deadline <= now, not protected(offer_deadline), not silent
                                                                           -> SKIPPED             (T7)
    close WAITING  with seen_at + HEARTBEAT <= now, not silent             -> DROPPED             (T12)
    if the invite is missing, closed as RENEWED, CLOSED or EXPIRED, or past its expires_at:
        close every open attempt with that reason, except a hold protected(closing arrival) (T14)
    free = capacity - members - offered - holding;  assert free >= 0
    if free > 0:  offer to the `free` lowest queue_no WAITING attempts                    (T5)
    elif offered + holding == 0:  close every WAITING attempt -> CLOSED TEAM_FULL         (T13)
    if members == capacity and the invite is live: close it with reason FULL              (2.2)
```

The closing arrival of a renewed or closed invite is the arrival of the request that closed it, which the server stores as the link's `closed_at`; for an expired invite it is `expires_at`. A FULL invite closes no attempt through T14: by the seat invariant nobody is offered or holding then, and T13 closes the waiters as TEAM_FULL. In v3 the T14 line ran first and closed them with the invite's reason FULL, which `join_attempts.reason` does not know and no text covers (fidelity V7).

Who calls it:
- **Every write route** that changes a team's join state, in its own write transaction and in the order of 2.4: claim, ack, decision, leave, settings, member removal, invite changes, team and account deletion.
- **The clock**, an asyncio task started in the app's lifespan next to the analysis worker. Every 250 ms it (1) flushes the batched heartbeats in one transaction, (2) runs one indexed query `SELECT DISTINCT team_id FROM join_attempts WHERE due_at <= ?`, and (3) runs `settle` for each team it finds, each in its own write transaction in the thread pool, then publishes the snapshot and wakes that team's long-polls. A protected attempt keeps its `due_at`, so the clock looks at it again on the next tick. Like every writer, each of these transactions runs pause recovery first when it is due (2.11).
- **Start-up**, once, after recovery (2.11).

**Never:** a long-poll, a resolve, the captain's live view or any other read. Reads answer from the snapshot or from a read transaction and never write (C1).

Why there is no race:
- The FIFO lock (2.8) and `BEGIN IMMEDIATE` serialize every writer of the process, and `now` is read after both. Two `settle` calls for one team run one after the other, and the second sees the first one's result.
- Every transition is conditional on the state it leaves, and no transition reopens a closed attempt.
- The clock holds no deadline in memory. It reads `due_at` from the database on every tick, so a restart or a missed wake-up loses at most 250 ms.
- The in-flight entries (2.9) only ever delay a closing transition, by at most `PROTECT_MAX` (5 s) after the deadline they protect; they never open or move a seat.

Tests run the clock by hand, as they run the analysis worker today: `create_app(..., start_worker=False, clock=fake_clock)` and `app.state.queue.tick()`.

### 2.6 Timing and the phone's countdown

| Constant | Value | Why |
|---|---|---|
| `HOLD` | 10 s | The owner's window. It starts when the pop-up can open: at T1 for a person who reaches the decision point with a free seat, at T6 for a person who comes from the queue. |
| `GRACE` | 2 s | A tap made in time but delayed by the network still lands. Judged by arrival (2.9) against `grace_until`, which is `expires_at` + 2 s and which only pause recovery moves later (2.11). The phone never shows it. |
| `PROTECT_MAX` | 5 s | An in-flight decision or ack protects its attempt for at most 5 s after the deadline it protects. The bodies of these two routes must arrive within 5 s of their headers (`408 BODY_TIMEOUT`), so an honest request never needs more (conc V1, S1). |
| `OFFER_ACK` | 8 s | A waiting phone with an open poll learns of the offer within a round trip and acknowledges at once. A failed poll is retried after 0.5 s. A waiter that may be offered next (its `ahead` is below the number of people deciding) polls with `wait=2` and a 5 s read timeout, so a poll whose answer got lost without a reset is replaced within about 6.5 s, inside the 8 s (F2, conc V4). A phone that is gone costs the next person 8 s. |
| `HEARTBEAT` | 30 s | A waiting person whose app has not polled for 30 s leaves the queue, unless every phone went quiet at once (a silence, 2.11). The app keeps the screen on, retries a failed poll within 1 s and replaces a poll whose answer got lost within about 19.5 s (`POLL_WAIT` + the 3 s read margin + one retry). So 30 s without a poll from one phone, while other phones still reach the server, means that this phone's app stopped or lost its connection (F2, C6, conc V4). |
| `POLL_WAIT` | 15 s | The longest a long-poll waits before it answers unchanged. The phone's read timeout is `wait` + 3 s. With v3's 20 s and a 30 s read timeout, one answer lost without a reset (a carrier NAT or a tunnel dropping the flow) made the next poll arrive after `HEARTBEAT` (conc V4). It stays below common proxy idle limits (60 s). |
| Clock tick | 250 ms | a timed-out hold or offer passes on at most 250 ms late |
| `PAUSE_GAP` | 2 s | a gap in the database-free 250 ms beat, or a drift between wall and monotonic time, above this triggers recovery (2.11) |
| `SILENCE` | 3 s | no request of any kind for 3 s while at least two phones hold open attempts: the server answers every waiting poll at once, and if again nothing arrives for 3 s, it treats the time until the next arrival as a pause (2.11, R8) |
| `RECOVERY_OFFER` | 10 s | after a restart or pause an offer gets at least 10 s: the phone's longest retry interval (4 s outside the queue) plus two round trips |
| Decision and ack rate | 5 a second per attempt; claims 5 a second per account | above it `429 TOO_MANY_ATTEMPTS` with `details.scope: RATE`; the app's own repeats stay at or below 2 a second (S1) |
| Phone retry while WAITING or OFFERED | 0.5 s, then every 1 s | the first poll after a reconnect goes out at once (F2) |
| Phone retry elsewhere | 0.5, 1, 2, then every 4 s | |
| Retention | 24 h after closing | the phone can still read an outcome after a reconnect |

**The phone's countdown (C3).** Every join answer carries `waitedMs`: the server time between the request's arrival and the moment the server built the answer. A HOLDING answer also carries `decision.remainingMs`, computed at that moment. The phone records `sentAt = SystemClock.elapsedRealtime()` before it sends the request, and keeps one deadline per attempt:

```
D = max over every HOLDING answer of (sentAt + waitedMs + remainingMs)       never lowered
remaining shown = ceil((D − elapsedRealtime()) / 1000)
```

Why D never outlasts the server: the server's deadline E equals arrival + `waitedMs` + `remainingMs`. The request arrived after the phone sent it, so in the phone's time base E is at least `sentAt + waitedMs + remainingMs`. Every term is a lower bound of E, and so is their maximum. Unlike v2's rule, a long-poll answer that waited 15 s no longer moves the countdown down, because its `waitedMs` adds back the time it waited. `elapsedRealtime` keeps counting while the screen is off, and the phone's wall clock plays no part.

At its own zero the decision part of the pop-up closes, as the owner asked: the countdown and both buttons go, and a phone that has not tapped sends `decision: "TIMEOUT"`, shows `join_time_up` and keeps polling until the outcome replaces it. A phone that already tapped sends no TIMEOUT and keeps showing `join_sending`. The server never closes a hold early because of a TIMEOUT (T10b).

### 2.7 The live channel

**Choice: long-poll with a view tag.** `GET /v1/join-attempts/{id}?view=<tag>&wait=15` answers at once when the caller's current view tag differs from `view` (or when `view` is absent), and otherwise waits until it differs or 15 s pass. The app sends `wait=2` while its `ahead` is below the number of people deciding (2.6). The v2 reasons against SSE and WebSockets stand: proxies buffer streams, the app's `ApiClient.request()` serves a long-poll with no new code path, and every poll arrival is the heartbeat.

**The view tag (C1).** The tag is a short hash of exactly what the caller's screen shows:
- WAITING: state, `ahead`, seats left, deciding, invite state.
- OFFERED: state and `offer_deadline`.
- HOLDING: state and `expires_at`. Queue changes do not touch it, so the pop-up's poll sleeps through other people joining the queue.
- closed: state and reason.
A claim behind someone changes nothing on that person's screen, because the waiting screen does not show the total, so it answers nobody.

**The team snapshot (C1).** Every write transaction that changes a team's join state builds a snapshot before COMMIT: `version` (= `queue_version`), capacity, members, seats left, deciding, waiting count, invite state, the ordered WAITING ids, and for each open attempt and each attempt closed in the last 10 minutes its user id, state, reason, `queue_no`, `expires_at`, `grace_until`, `offered_at`, `offer_deadline`, `decided_session` and `late_accept_at`. After COMMIT the route hands it to the notifier with `loop.call_soon_threadsafe`.

Every write transaction that builds a snapshot moves `queue_version` by one, whatever it changed: a claim behind others, an ack, a decision, a leave, a settings or membership change, an invite change, and `settle` when it changed something. No two published snapshots of a team therefore share a version. The notifier replaces a team's snapshot only with a strictly higher version, and a snapshot that a poll's read transaction built never replaces one with an equal or higher version. Without the bump per claim, 1 999 of 2 000 burst snapshots were dropped as equal versions, and the waiters missing from the kept snapshot fell back to database reads on every poll (conc V7, `verify_v3/publish_order.py`); no publication arrived out of commit order. The notifier then sets the team's `asyncio.Event`, which wakes that team's long-polls.

**How a poll runs.** The route is `async def` and stays in the event loop:
1. Authenticate from the in-memory session cache (3.2). On a cache miss it reads the session once in the thread pool.
2. Find the attempt in the team's snapshot and check that it belongs to the caller (`403 NOT_YOUR_ATTEMPT`). An attempt missing from the snapshot (after a restart, or closed more than 10 minutes ago) costs one read transaction in the thread pool; its snapshot is published only when no snapshot of that team exists yet.
3. Record the heartbeat in memory: `last_seen[attempt] = arrival`. This comes before any cap check, so every poll that authenticates is a heartbeat (conc V5, S5).
4. A poll on a closed attempt answers at once and never waits. A poll over a cap (below) also answers at once, with the current view, `"pollLimited": true` and `Retry-After: 1`; the app waits that second and polls again. A refused poll therefore still shows true numbers and still delivers an offer.
5. Compute the view from the snapshot and its tag. If the tag differs from `view`, answer. Otherwise take the team's event, check once more, and wait on it with the remaining time. On wake, repeat from step 5.
6. Every answer carries `waitedMs` (C3) and is about 0.5-1 KB. No answer of claim, ack, decision or poll carries logo bytes (C7).
The poll holds no thread and no database connection while it waits, never runs `settle` and never writes. The clock flushes all heartbeats in one `executemany` per tick.

**Limits (P3).**
- One live long-poll per attempt and session. A newer poll from the same session answers the older one at once with the current view. Two sessions of the same person may each keep one, so two phones never push each other out.
- At most 4 waiting long-polls per account, capacity + 200 per team, and 400 per server. Over any of them a poll answers at once as in step 4, never with `429` or `503`. One link holder's accounts can then neither take other teams' share nor cost a real waiter its place, and `queue_dropped` stays true (conc V5, S5).
- At start-up on macOS and Linux the server raises its soft open-file limit to min(hard limit, 4096). macOS starts shell processes at 256, and 100 phones with two connections each already need 200 sockets. If the resulting limit is below 1 024, the server lowers the server-wide poll cap to a quarter of it and logs that once; polls over it answer at once as above.

**One process.** The snapshot, the notifier, the session cache, the in-flight entries and the FIFO lock assume one server process, which is today's model. A second process would stay correct for every write (SQLite still serializes them, and every guard re-reads the database) but its polls would answer only when their 15 s run out. The ADR records this constraint.

**Measured.** The critic's probe (`critique_v2c/crit_server.py` with `driver_raw.py`) on a 4 vCPU container at an idle start, raw-socket generator with two keep-alive connections per phone, 100 claims arriving within 12-19 ms, every phone polling as the app does:

| Run | Wall time for 100 claims | Claim p95 | Poll answers during the burst | Server CPU | Decision wait | Reject → head offered |
|---|---|---|---|---|---|---|
| v2 design (3 runs) | 6.6-7.1 s | 5.1-5.2 s | 1 868-2 013 | 11.0-12.0 s | up to 1.7 s | 0.64-1.26 s |
| FIFO lock only | 4.7 s | 3.8 s | 1 353 | 7.7 s | up to 3.5 s | 3.3 s |
| snapshot only | 1.5 s | 0.98 s | 242 | 0.49 s | 1 ms | 5 ms |
| **FIFO lock + snapshot + batched heartbeats (v3, 3 runs)** | **0.66-0.70 s** | **0.63-0.67 s** | **145** | **0.58-0.60 s** | **≤ 7 ms** | **6 ms** |
| v2 design, 100 people over 10 s | 14.4 s (last answer) | 5.4-6.9 s, single claims up to 9.8 s | | 24 s | up to 3.0 s | |
| v3 model, 100 people over 10 s | every claim within 10 ms | 9 ms | | 1.2 s | ≤ 6 ms | |

The probe answered polls without a session check. v3 keeps the poll free of database reads with the in-memory session cache (3.2), and the B5 concurrency test measures the full route (14.3).

**The captain's live view.** `GET /v1/teams/{id}/live?view=&wait=15` is the same long-poll for the captain's team screen, answered from the same snapshot. It returns counts only (members, deciding, waiting, invite state, new joins), never who is waiting or deciding.

### 2.8 Arrival order and the FIFO writer lock (C4)

Python's `sqlite3` with `timeout=30` installs SQLite's busy handler, which sleeps 1, 2, 5 … 100 ms and retries. Whichever writer wakes first goes next. Measured with 100 claims arriving within 12-17 ms: 2 124-2 391 of the 4 950 pairs got queue numbers out of arrival order, 6-9 of the first 10 arrivals got no seat, and lock waits reached 0.9-1.4 s while the server used only 0.25 s of CPU.

v3 puts an in-process FIFO ticket lock in `db.connector()`. `connect(write=True)` takes a ticket before it opens the connection, waits on a `threading.Condition` until its ticket is served, and only then opens the connection and runs `BEGIN IMMEDIATE`. It releases the ticket in the connector's outermost `finally`, after `close()`, on every path: after COMMIT, after ROLLBACK, and when `sqlite3.connect` or `BEGIN IMMEDIATE` itself fails. Today's connector runs no ROLLBACK when `BEGIN IMMEDIATE` fails, because `conn.in_transaction` is false (`db.py` lines 243-245), so a release tied to COMMIT or ROLLBACK would never happen after a `database is locked` from the admin CLI or an `EMFILE` from `sqlite3.connect`, and every later writer of the process would wait forever (conc V6, `verify_v3/fifo_release.py`). A writer waits for its ticket at most 30 s. Then it marks the ticket abandoned, the release of the ticket before it skips abandoned tickets, and the writer raises the same `database is locked` error that a 30 s busy timeout raises today. `threading.Lock` promises no order, so a plain lock is not enough. Every writer of the process passes through it: routes, the clock, the heartbeat flush, the minute sweep, the analysis worker and its heartbeat thread. SQLite's busy timeout stays as the guard against other processes such as the admin CLI.

Rules that keep it safe:
- The lock is not re-entrant. A thread that already holds it and asks again raises at once, so a nested write transaction fails in tests instead of hanging. Today's code nests none.
- No `async def` route opens a write transaction on the event loop thread; all of them go through `run_in_threadpool`, as `upload_chunk` does today. A test asserts that the lock is never taken on the loop thread.

Measured with the lock: inversions fell to 2-10 pairs, none more than 2.2 ms apart (the thread pool's start order), the first 10 arrivals always got the seats, and lock waits stayed at or below 0.18-0.28 s. The lock is for order and for removing the busy-handler sleeps, not for p95; v2's sentence that it "did not help reliably" measured p95 with a client that spread its requests over 0.8 s.

**What "order" means.** The queue follows the order in which requests reach the server. Mobile network jitter can still swap two people who tap within about 100 ms of each other; nothing on the server can see the moment of the tap.

A wal checkpoint (`empty_write_ahead_log()`, called by the minute sweep and by deletions) does not take the FIFO lock. Measured with `probe_v3/e5_checkpoint.py`: a TRUNCATE checkpoint took 0-56 ms with 0-40 concurrent readers and delayed no writer by more than 44 ms.

### 2.9 Decisions are judged by their arrival (C2)

In v2 the grace and the offer deadline were compared with `now` read after the write lock, and the clock could expire a hold while its ACCEPT still waited in the thread pool. Measured with 100 people arriving over 10 s: in two runs 3 and 7 of 10 ACCEPTs that reached the server 0.2-1.2 s before the grace ended came back EXPIRED, after 0.3-2.7 s inside the server, and the phone would have said "too late".

v3:
1. **Arrival stamp.** A pure ASGI middleware, outermost (outside `BodyLimit`), writes `scope["state"]["arrival"] = time.time()` when the headers arrive, before anything else runs.
2. **In-flight entries (conc V1).** The same middleware, for `POST /v1/join-attempts/{id}/ack` and `POST /v1/join-attempts/{id}/decision` (matched on the raw path before routing), adds an entry for the attempt id before it calls the inner app and removes that entry in its own `finally`. The process-wide table (a dict guarded by a `threading.Lock`) keeps, per attempt id, the arrival of each live entry. One request that finishes early therefore never unprotects another request on the same attempt, such as an early TIMEOUT from one phone next to an ACCEPT from the other, or a repeat. Body reading, routing, body parsing, dependencies (a sync `current_user` adds a thread-pool hop) and the transaction all happen inside the entry. v3 took the entry inside the route, after `BodyLimit` had read the whole body. Measured with `verify_v3/v3_model.py` and `drive.py split`: headers 99 ms before the grace ended and the body 300 ms later lost 6 of 6 in-time ACCEPTs (the seat went to the next person, and the phone showed `join_late`); with the entry in the middleware all 6 were JOINED.
3. **Body time.** For these two routes `BodyLimit` answers `408 BODY_TIMEOUT` when the body takes longer than `PROTECT_MAX` (5 s) after the headers. Their bodies are under 100 bytes. Other routes keep today's behaviour, because a 64 KiB logo on a slow link may need longer.
4. **Judging (conc V3).** The route judges its own attempt by its arrival before it runs `settle`. An ACCEPT joins when arrival < `grace_until` (T8) and closes the hold as late otherwise (T8c). An ack moves the offer to HOLDING when arrival < `offer_deadline` (T6) and closes it as SKIPPED otherwise (T6b). A TIMEOUT follows T10b and T10a. In v3 the route's own entry made its own `settle` skip the attempt, so an ack 50 ms after `offer_deadline` found the attempt still OFFERED in 10 of 12 runs, and no transition said what to answer (`verify_v3/late_ack.py`).
5. **Protection.** `settle`, the clock and T14 skip an attempt only while it has an entry whose arrival is before that attempt's deadline: `grace_until` for a hold, `offer_deadline` for an offer, `expires_at` for a reported single-session TIMEOUT, and for T14 the closing request's own arrival (or the invite's expiry). The skip never lasts past that deadline + `PROTECT_MAX`. A request that arrives after the deadline protects nothing, and `settle` never skips the route's own attempt, which step 4 already judged.
6. **Rate (S1).** Ack, decision and DELETE on one attempt are limited to 5 a second per attempt, and claims to 5 a second per account. An in-memory counter checks this before the entry is added and answers `429 TOO_MANY_ATTEMPTS` with `details.scope: RATE` and `Retry-After: 1`. A flood of TIMEOUTs from a second session can then neither keep a hold open (steps 4 and 5, T10a) nor fill the FIFO lock.
7. **Restart.** A restart empties the table. That is harmless: the phone repeats its decision with the same body until its zero plus 3 s.

Measured with only the arrival rule: 0 of 10 in-time ACCEPTs lost under the same load; with the FIFO lock and the snapshot the decisions waited 7 ms or less.

Tests (B5):
- A second process holds the SQLite write lock for 2.5 s, and an ACCEPT arrives at `expires_at + 1.0`: JOINED, and the clock offered the seat to nobody meanwhile.
- Headers before `grace_until` and the body 300 ms after it: JOINED, and nobody is offered.
- Two overlapping requests on one attempt, where the first finishes early, leave the second protected.
- A renew 50 ms after an in-time ACCEPT arrived: JOINED; the other open attempts close as LINK_RENEWED.
- A holder with two sessions sends TIMEOUT every 20 ms for 60 s: the next waiter is offered within 2.5 s after `grace_until`.
- With the clock stopped, an ack at `offer_deadline + 0.05` answers `409 ATTEMPT_CLOSED` (SKIPPED), and the head of the queue is offered in the same transaction; an ACCEPT at `grace_until + 0.05` answers 409 with `lateAccept: true`.
- An ACCEPT whose transaction waits longer than `PROTECT_MAX` after `grace_until` answers 409 with `acceptArrivedInTime: true`.

### 2.10 Concurrency cases

| Case | What happens | What each screen shows |
|---|---|---|
| 100 claims in the same second | The FIFO lock serializes the write transactions in arrival order. Each runs `settle`, then T1 or T2. `queue_no` follows arrival. Waiting phones start polling; a claim behind them wakes nobody. | holders get the pop-up, the rest get their true position |
| One person on two phones | One open attempt; the second phone's claim returns it and sets `other_session`. Both phones show the same state and the same deadline. An ACCEPT from either joins; the other phone's poll then returns JOINED with `decidedHere: false`. Two decisions at once: the FIFO order decides, the second answers with the final state. A TIMEOUT from one phone never shortens the grace for the other. | each phone says which session decided |
| The captain lowers capacity mid-queue | Refused below members + offered + holding. Otherwise `settle` may close the queue as TEAM_FULL, and capacity = members closes the invite. | captain: `team_capacity_in_use`; waiters: `queue_team_filled` |
| The captain raises capacity | `settle` offers the new seats to the queue head at once. | the head's waiting screen turns into the pop-up |
| The captain renews or closes the link mid-queue | Every open attempt closes with LINK_RENEWED or LINK_CLOSED, holds included. A later ACCEPT gets `409 ATTEMPT_CLOSED`. | `join_link_renewed`, `join_link_closed`, `queue_link_renewed`, `queue_link_closed` |
| The invite expires mid-queue | `settle` closes everything with LINK_EXPIRED. | `join_link_expired`, `queue_link_expired` |
| The last seat is taken | T8 fills the team; the invite closes (FULL); `settle`'s T13 closes the waiters as TEAM_FULL (T14 does not apply to FULL). | joiner: `join_joined_*`; waiters: `queue_team_filled`; newcomers: `link_team_full` |
| A member leaves or the captain removes one while a queue exists | The membership delete frees a seat, and `settle` in the same transaction offers it. A removal also writes a block (4.3). | the head gets the pop-up |
| A removed member opens the old link | T1 refuses: `403 JOIN_BLOCKED`. | `join_blocked` |
| A holder's account is deleted | The attempt goes by cascade; the deleting transaction runs `settle` and publishes. | the head gets the pop-up |
| The team is deleted | Attempts and the invite tombstone go by cascade. Polls and decisions get `404 ATTEMPT_NOT_FOUND`. | `join_gone` |
| A holder taps the WhatsApp link again | The claim returns the same HOLDING attempt with the true `remainingMs` and `waitedMs`; D stays or moves up. | the pop-up continues |
| A holder loses the network after tapping Katıl | The phone repeats the same ACCEPT until its zero plus 3 s, then asks `GET` for the state. The first ACCEPT that arrived in time wins, whenever its transaction runs. | `join_sending`, then the outcome or `join_unknown` |
| A head waiter's poll fails just before the offer | The phone retries after 0.5 s, then every 1 s; the next poll returns OFFERED; the ack arrives well inside 8 s. | `queue_reconnecting`, then `queue_your_turn` and the pop-up |
| A poll answer is lost without a reset (a carrier NAT or a tunnel drops the flow) | The phone's read timeout (`wait` + 3 s) replaces the poll. A waiter far back polls again after about 19.5 s, inside `HEARTBEAT`; the head polls with `wait=2`, so it learns of an offer within about 6.5 s, inside `OFFER_ACK` (conc V4). | unchanged |
| More polls than a cap allows | Each poll still records its heartbeat and answers at once with the current view and `Retry-After: 1` (2.7). | true numbers; nobody DROPPED |
| A waiter's phone dies or the person leaves the app | Polls stop; T12 after 30 s; an offer that arrives meanwhile is SKIPPED after 8 s. | on return: `queue_dropped` or `queue_skipped` with Yeniden Sıraya Gir |
| The screen would time out | The join screen keeps the screen on while WAITING, OFFERED or HOLDING, so nothing stops. | unchanged |
| An acknowledgement arrives 0.1 s after the deadline | T6b: `409 ATTEMPT_CLOSED` (SKIPPED), and the head of the queue is offered in the same transaction; no new attempt (C5, conc V3). | `queue_skipped` |
| An ACCEPT arrives 0.05 s after `grace_until` while the hold is still HOLDING | T8c: EXPIRED with `late_accept_at`; the seat is offered in the same transaction; `409 ATTEMPT_CLOSED` with `lateAccept: true` (conc V3). | `join_late`; the head gets the pop-up |
| A holder floods TIMEOUT from two sessions | Requests above 5 a second per attempt get `429`. A TIMEOUT that arrives at or after `grace_until` closes the hold by T10a, and a request that arrives after the deadline protects nothing (S1). | the next waiter is offered on time |
| The captain renews the link 50 ms after a holder's ACCEPT arrived | T14 leaves that hold open; the ACCEPT joins; `settle` in the same transaction closes the rest with LINK_RENEWED (conc V1). | the holder: `join_joined_body`; the others: `join_link_renewed` or `queue_link_renewed` |
| The app restarts after REJECTED | The app finds the stored attempt id, asks `GET`, and shows REJECTED. It sends no claim (C5). | `join_rejected` |
| A person at 20 teams accepts | T8b. | `join_team_limit` |
| Phone and server clocks differ | No effect: the countdown uses only elapsed time. | |
| The server process pauses or the computer sleeps | The beat sees the gap; the first write transaction after the wake, whichever writer it is, runs recovery before any judgment or `settle` (2.11, conc V2). | `queue_reconnecting` while the server is silent |
| The tunnel or the home connection drops while the process keeps running | Every phone goes quiet at once; the server treats it as a silence, closes nobody for a missing message, and runs recovery when requests return (2.11, R8). | `queue_reconnecting`; nobody DROPPED or SKIPPED |

### 2.11 Restart and pause recovery (C6)

The attempts live in SQLite with absolute times, so a restart loses nothing. Recovery runs at start-up before the server serves, and after every pause. Two kinds of pause exist:
- **The process stopped** (SIGSTOP, a sleeping computer, a wall-clock jump). A database-free asyncio beat task stamps `beat_wall` and `beat_mono` every 250 ms. When it sees its own gap above `PAUSE_GAP` (2 s) by `time.monotonic()`, or a change above 2 s in `time.time() − time.monotonic()`, it sets `recovery_due` under a small lock.
- **A silence: the process ran, but nothing reached it (R8).** With the server on the owner's computer behind a free tunnel, the likelier outage is the tunnel or the home connection, while the process keeps ticking. The middleware notes the arrival of every request. When no request of any kind arrived for 3 s (`SILENCE`) while at least two phones hold open attempts, the server answers every waiting long-poll at once with its current view. Each of those phones re-polls the moment its answer arrives, so a healthy server sees a request within a round trip. If again no request of any kind arrives within 3 s, the server is in a silence from that moment until the next arrival. While it lasts, `settle` runs no T7, T10a or T12, so nobody is closed for a message that could not arrive. The first arrival after it sets `recovery_due`. One phone that goes quiet while other phones still reach the server is no silence, so a single dead phone is still dropped after 30 s. A hold or offer whose deadline falls within the first 6 s of an outage still closes, because the server cannot yet tell the outage from a quiet minute.

**Where recovery runs (conc V2).** Every write transaction, right after it reads `now` under the FIFO lock and before any judgment or `settle`, runs recovery when `recovery_due` is set or `now − beat_wall > PAUSE_GAP`. It then clears the flag, ends a silence and sets `beat_wall = now`. The first writer after a wake therefore runs it, whichever writer that is: a thread that was already waiting for its FIFO ticket when the process stopped, a request the kernel buffered during the stop, or the clock. In v3 only the clock ran recovery, after it noticed the gap, so another writer's `settle` could run first. Measured with `verify_v3/v3_model.py` and `drive.py pause` (heartbeat scaled to 3 s, `PAUSE_GAP` to 1 s, SIGSTOP for 5 s): a writer queued at the stop wiped 3 of 3 queues (the waiters DROPPED, the offer SKIPPED, then recovery on an empty queue), a claim buffered in the kernel wiped 1 of 3, and an ACCEPT tapped with about 1 s left and buffered during the stop was lost 3 of 3 times. With recovery in the write path and `grace_until`, 9 of 9 runs were correct.

Recovery, in that one write transaction, for every team with an open attempt:
1. Every WAITING attempt gets `seen_at = now`. The waiting phones could not reach the server, and nobody leaves the queue because the server was stopped or unreachable.
2. Every OFFERED attempt gets `offer_deadline = max(offer_deadline, now + 10)`, and `offered_at = now` when the deadline moved. No phone could acknowledge an offer the server never delivered.
3. HOLDING attempts keep their `expires_at`; the phone has shown that countdown, and extending it would make the countdown false. Their `grace_until` becomes `max(grace_until, now + GRACE)` and `due_at` follows it. A tap that the operating system had already received is stamped at the wake, before the new `grace_until`, and counts. In v3 recovery kept only `expires_at`, and T8 and T10a read only `expires_at + 2`, so the promised extra grace could not be built (conc V2, fidelity V3). Nothing on the phone changes: its countdown already ended.
4. The in-memory heartbeats are cleared, `settle` runs for each team in the same transaction, and every such team's snapshot is published after the commit.

While the server is silent the phones retry (2.6) and show `queue_reconnecting` or `queue_no_network`, which hide the numbers they can no longer vouch for. A holder who tapped Katıl keeps repeating the ACCEPT until zero plus 3 s, then shows `join_unknown` with Sonucu Kontrol Et; a holder whose app sent its own TIMEOUT shows `join_unknown_timeout`.

Tests:
- The fake clock freezes for 60 s with waiters and an offer open, then ticks: nobody is DROPPED or SKIPPED, and the offer gets 10 s (B5).
- Slow-marked real-server variants stop the process with SIGSTOP for 35 s: (a) with waiters polling and an offer open; (b) with a writer queued behind a 0.6 s write when the stop begins; (c) with a claim sent during the stop; (d) with an ACCEPT sent during the stop about 1 s before the phone's zero. In each, nobody is DROPPED or SKIPPED, no queue is wiped, and the ACCEPT is JOINED (conc V2).
- Through the B6b delay proxy, all traffic is blocked for 45 s with 5 waiters and an open offer: nobody is DROPPED or SKIPPED, and the order is kept (R8). A single waiter whose polls stop while another waiter keeps polling is DROPPED after 30 s.

### 2.12 Screens, states and the exact texts

Rules the app follows so that nothing false appears:
1. The app says "joined" only after the server's JOINED.
2. The countdown never outlasts the server's (2.6).
3. Numbers on the waiting screen come only from the latest server answer. When a poll fails, the app hides them.
4. At its own zero the app shows `join_time_up` and waits for the server's outcome.
5. When the app lost the answer to a decision, it says that it does not know the result, and it asks the server again.
6. A text states only what someone knows. The server knows arrivals, its own states and reasons, which session decided, and whether an ACCEPT arrived late. The app knows what it tried to send, whether the phone has a validated network (`NET_CAPABILITY_VALIDATED`), and whether an answer came. No text guesses a cause ("olabilir" guesses are gone, C6, F6).
7. Every sentence names its actor: "Sunucu", "Uygulama", "Kaptan", "Telefonun", or "sen" through the verb. The predicate comes last. No dash joins clauses. No passive voice. A text in the app that names a button takes the label through a format argument.
8. Every new string goes through the `turkce-insansi-metin` review before it ships. The texts below already follow its rules.

**A. Link landing, registration with a link, code entry**

| Exact state in which it shows | Key | Text |
|---|---|---|
| a link was opened; resolve or claim has not answered; or the session exists and the join screen has no window focus yet | `link_loading` | Uygulama bağlantıyı açıyor. |
| a code was typed or pasted in Kod Gir; resolve has not answered | `code_checking` | Uygulama kodu kontrol ediyor. |
| resolve, claim or register answered `404 LINK_NOT_FOUND` | `link_unknown_title` / `link_unknown_body` | Bağlantı Geçersiz / Sunucu bu bağlantıyı tanımıyor. Kaptandan yeni bir bağlantı isteyebilirsin. |
| `410 LINK_CLOSED`, reason EXPIRED | `link_expired` | Bu davetin süresi doldu. Kaptandan yeni bir bağlantı isteyebilirsin. |
| `410 LINK_CLOSED`, reason RENEWED | `link_renewed` | Kaptan yeni bir davet bağlantısı oluşturdu. Senin açtığın bağlantı artık geçersiz. Kaptandan yeni bağlantıyı isteyebilirsin. |
| `410 LINK_CLOSED`, reason CLOSED | `link_closed` | Kaptan bu daveti kapattı. Kaptandan yeni bir bağlantı isteyebilirsin. |
| `410 LINK_CLOSED`, reason FULL, or `409 TEAM_FULL` (%1$s = `details.teamName`) | `link_team_full` | %1$s takımı doldu ve sunucu daveti kapattı. Kaptan yer açıp yeni bir bağlantı gönderirse takıma katılabilirsin. |
| a request failed and the phone has no validated network | `net_offline` | Telefonun internete bağlı değil. İnternete bağlanınca yeniden deneyebilirsin. |
| the network is validated but no JSON answer came: a timeout, a refused connection, or a non-JSON answer from a proxy or tunnel | `net_no_answer` | Uygulama sunucudan yanıt alamadı. Biraz sonra yeniden deneyebilirsin. |
| `503 SERVER_BUSY`; the app waits `Retry-After` and repeats after each `Retry-After` while the screen that sent the request is open (R10) | `server_busy` | Sunucu şu an çok sayıda istek işliyor. Uygulama birkaç saniye sonra yeniden dener. |
| `GET /v1/health` lacks `accounts` | `server_older` | Sunucunun sürümü bu uygulamadan eski. Uygulama bu sunucuda daveti açamaz. |
| WELCOME passed; no account has ever logged in on this phone (`accountSeen` false); no link is pending, for example after the installer's Aç button (R6); %1$s = the `Kod Gir` label | `first_start_body` + primary button `Kod Gir` (with `Panodaki Kodu Kullan` when the clipboard yields one code) + secondary button `Giriş Yap` | Bir takıma katılmak için WhatsApp'taki davet bağlantısına yeniden dokunabilirsin. Kaptanın kodunu kopyaladıysan %1$s düğmesine dokunabilirsin. |
| register screen opened without a pending link or code (R6) | `register_code_first` | Hesap oluşturmak için önce kaptanın gönderdiği kodu yaz ya da WhatsApp mesajını yapıştır. |
| no session; resolve answered; no account has ever logged in on this phone (register first, F1) | `link_register_intro` + secondary button `Hesabım Var` | %1$s takımına katılmak için hesap oluştur. |
| no session; resolve answered; an account has logged in on this phone before | `link_login_intro` | %1$s takımına katılmak için giriş yap ya da hesap oluştur. |
| register form; the username field is pre-filled from the display name | `register_username_suggested` | Uygulama kullanıcı adını görünen adından önerdi. İstersen kullanıcı adını değiştirebilirsin. |
| register answered `409 USERNAME_TAKEN`; the app appended digits to its suggestion | `username_taken` | Bu kullanıcı adını başka biri kullanıyor. Uygulama yeni bir ad önerdi. İstersen başka bir ad seçebilirsin. |
| register answered `403 SIGNUP_LINK_LIMIT` | `register_link_limit` | Sunucu bu bağlantıyla yeni hesap açmayı durdurdu. Hesabın varsa giriş yapabilirsin. Hesabın yoksa kaptandan yeni bir bağlantı isteyebilirsin. |
| register answered `429 TOO_MANY_ATTEMPTS` with `details.scope: REGISTER_SOURCE` (%1$s = the wait) (S2) | `register_wait_source` | Sunucu bu internet adresinden kısa sürede kabul etmediği çok sayıda kayıt isteği aldı ve bu adresten kaydı %1$s süreyle durdurdu. |
| claim answered `409 ALREADY_MEMBER` | `join_already_member` + button `Takıma Git` | Zaten bu takımdasın. |
| claim answered `403 JOIN_BLOCKED` | `join_blocked` | Kaptan seni bu takımdan çıkardı. Kaptan engeli kaldırırsa takıma yeniden katılabilirsin. |
| claim or ACCEPT answered `409 TEAM_LIMIT_REACHED` (%1$d = `details.limit`) | `join_team_limit` | En çok %1$d takımda olabilirsin. Yeni bir takıma katılmak için önce bir takımdan ayrılmalısın. |
| claim answered `409 QUEUE_FULL` (%1$d = `details.waiting`) | `join_queue_full` | Bu takımın sırasında %1$d kişi var. Sunucu bu sıraya şu an kimseyi almıyor. Biraz sonra bağlantıyı yeniden açabilirsin. |
| claim answered `409 ATTEMPT_LIMIT` (%1$d = `details.limit`) | `join_attempt_limit` | Şu an %1$d takımın sırasındasın. Yeni bir sıraya girmek için önce bir sıradan çıkmalısın. |
| a match request link opened in Kod Gir or through an invite route | `link_is_request` | Bu bağlantı bir maç isteği. Uygulama isteği açıyor. |
| Kod Gir opened; %1$d = `links.codeLength` from health (fidelity V8) | `code_title` / `code_body` | Kod Gir / Kaptanın gönderdiği %1$d karakterlik kodu yaz ya da WhatsApp mesajını buraya yapıştır. |
| Kod Gir has focus and the clipboard text yields exactly one code by the extraction rule of 10.1 | `code_paste_offer` + button `Panodaki Kodu Kullan` | Panoda %1$s kodu var. |
| pasted text yields no code by the rule of 10.1 | `code_not_found` | Uygulama yapıştırdığın metinde bir kod bulamadı. |
| pasted text yields more than one distinct code by the rule of 10.1 | `code_many` | Uygulama yapıştırdığın metinde birden çok kod buldu. Kaptanın gönderdiği kodu yazabilirsin. |
| resolve by code answered `404 LINK_NOT_FOUND`: no live link and no tombstone of the last 30 days holds this code. A tombstone answers `410 LINK_CLOSED` and shows the link texts above (R3). | `code_wrong` | Sunucu bu kodu tanımıyor. Kodu kontrol edip yeniden deneyebilirsin. |
| `429 TOO_MANY_ATTEMPTS` with `details.scope: ACCOUNT_CODE` (%1$s = the wait, for example "12 dakika") | `code_wait_account` | Sunucu bu hesaptan tanımadığı çok sayıda kod aldı ve bu hesabın kod girişini %1$s süreyle durdurdu. |
| `429 TOO_MANY_ATTEMPTS` with `details.scope: CODE_GLOBAL` or `CODE_SOURCE` | `code_wait` | Sunucu kısa sürede tanımadığı çok sayıda kod aldı ve kod girişini %1$s süreyle durdurdu. |
| added after `code_wait_account` or `code_wait` only while health says `links.publicUrl: true`; without a link host no link exists to fall back on (R3) | `code_wait_link` | Davet bağlantısı bu beklemeden etkilenmez. |

**B. Decision pop-up (HOLDING).** A full-screen dialog over the waiting screen or the landing screen. Back and an outside tap do nothing while it counts. The screen stays on.

| Exact state | Element and key | Text |
|---|---|---|
| HOLDING | logo | the 128 px logo once resolve has brought it; until then, or when decoding fails, a circle with the initials of the team name |
| HOLDING | title | the team name |
| HOLDING, `team.schedule` present | `join_day` / `join_time` | Maç günü / Salı (the weekday name) · Saat / 20.00 |
| HOLDING, `team.schedule` null | | both rows are left out |
| HOLDING | `join_question` | Katılmak istiyor musun? |
| HOLDING | countdown | the number `ceil((D − now) / 1000)`; content description `join_countdown_a11y`: %1$d saniye kaldı |
| HOLDING | left button `join_reject` | Reddet |
| HOLDING | right button `join_accept` | Katıl |
| a button was tapped; the decision has not been answered | `join_sending` | Uygulama kararını sunucuya iletiyor. |
| the phone's own zero passed without a tap; the countdown and both buttons are gone; TIMEOUT was sent; no outcome yet | `join_time_up` | Karar süren doldu. Uygulama sunucunun yanıtını bekliyor. |

**C. Outcomes.** "This phone sent ACCEPT" comes from the resume record that the app writes before it sends a decision (10.2). `decidedHere` and `lateAccept` come from the server.

| Exact state | Key | Text |
|---|---|---|
| JOINED, `decidedHere: true` | `join_joined_title` / `join_joined_body` | Takıma Katıldın / Sunucu seni %1$s takımına üye olarak kaydetti. |
| JOINED, `decidedHere: false` | `join_joined_elsewhere` | Sen bu daveti hesabının başka bir oturumunda kabul ettin. Sunucu seni %1$s takımına üye olarak kaydetti. |
| REJECTED, `decidedHere: true` | `join_rejected` | Daveti reddettin. Takıma katılmadın. |
| REJECTED, `decidedHere: false` | `join_rejected_elsewhere` | Sen bu daveti hesabının başka bir oturumunda reddettin. Takıma katılmadın. |
| EXPIRED (NO_DECISION or TIME_UP); this phone sent no ACCEPT | `join_expired` | Karar süren doldu. Takıma katılmadın. |
| EXPIRED; this phone sent ACCEPT; `lateAccept: true` (the ACCEPT arrived at or after the deadline that closed the hold, T8c or T17) | `join_late` | Sunucu kararını süre bittikten sonra aldı. Takıma katılmadın. |
| EXPIRED; this phone sent ACCEPT; `lateAccept: false` and `acceptArrivedInTime: false` (no ACCEPT reached the server) | `join_not_received` | Uygulama kararını göndermeyi denedi ama sunucu karar süresi içinde kararını almadı. Takıma katılmadın. |
| EXPIRED; this phone sent ACCEPT; `acceptArrivedInTime: true` (the ACCEPT arrived in time but waited longer than its 5 s protection, 2.9) | `join_closed_in_time` | Sunucu kararını süre içinde aldı ama katılma isteğini kararını işlemeden önce kapattı. Takıma katılmadın. |
| CLOSED, LINK_RENEWED | `join_link_renewed` | Kaptan yeni bir davet bağlantısı oluşturdu ve sunucu bu daveti kapattı. Takıma katılmadın. Kaptandan yeni bağlantıyı isteyebilirsin. |
| CLOSED, LINK_CLOSED | `join_link_closed` | Kaptan bu daveti kapattı. Takıma katılmadın. |
| CLOSED, LINK_EXPIRED | `join_link_expired` | Davetin süresi doldu. Takıma katılmadın. |
| CLOSED, TEAM_LIMIT | `join_team_limit` | (table A) |
| `404 ATTEMPT_NOT_FOUND` (the attempt is older than 24 h, or the team or the account was deleted) | `join_gone` | Sunucu bu katılma isteğini bulamadı. Kaptandan yeni bir bağlantı isteyebilirsin. |
| an ACCEPT or REJECT the person tapped got no answer after its repeats until the phone's zero plus 3 s, and the following `GET` got none either | `join_unknown` + button `Sonucu Kontrol Et` | Uygulama sunucudan yanıt alamadı. Uygulama kararının sonucunu henüz bilmiyor. |
| the TIMEOUT the app sent at its own zero, without a tap, got no answer after its repeats, and the following `GET` got none either (fidelity V8) | `join_unknown_timeout` + button `Sonucu Kontrol Et` | Uygulama sunucudan yanıt alamadı ve katılma isteğinin sonucunu henüz bilmiyor. |

Buttons: `Yeniden Sıraya Gir` appears on EXPIRED, REJECTED, `join_late`, `join_not_received`, `join_closed_in_time`, SKIPPED and DROPPED when the answer says `link.live: true` and the resume record holds the link (10.2). `link.live` is true while the team's live invite is the one this attempt came through, that is, an invite created before the attempt's `arrived_at` that is still open. The button sends a fresh claim with the stored link, which joins the end of the queue or gets a free seat. When the resume record holds no link, the button is hidden, and `join_reopen_link` shows (fidelity V6; %1$s = the `Kod Gir` label): "Takıma yeniden katılmak için kaptanın gönderdiği bağlantıyı yeniden açabilir ya da kodu %1$s ekranına yazabilirsin." `Kapat` appears everywhere. JOINED shows `Takıma Git` after the jersey prompt.

**D. Waiting screen (WAITING and OFFERED).** The screen stays on.

| Exact state | Key | Text |
|---|---|---|
| WAITING | `queue_title` | Sıradasın |
| WAITING, numbers from the latest answer; seats left equals deciding (2.3); plural on seats left | `queue_seats_deciding` | one: Takımda 1 yer kaldı. Bu yer şu an bir kişinin kararını bekliyor. · other: Takımda %1$d yer kaldı. Bu yerler şu an %1$d kişinin kararını bekliyor. |
| WAITING, `ahead` = 0 | `queue_next` | Sıradaki sensin. |
| WAITING, `ahead` > 0 | `queue_ahead` | Önünde %1$d kişi var. |
| WAITING | `queue_rule` | Karar veren kişi reddederse ya da karar süresi biterse sunucu yeri sıradaki kişiye ayırır. |
| WAITING | `queue_keep_open` | Sıranı korumak için bu ekranı açık tut. Uygulama bu ekranda telefonunun ekranını açık tutar. Ekrandan çıkarsan sıranı kaybedebilirsin. |
| WAITING | button `queue_leave` | Sıradan Çık |
| WAITING, back pressed | `queue_leave_title` + buttons `Sıradan Çık` / `Sırada Kal` | Sıradan çıkmak istiyor musun? |
| a poll failed and the phone has no validated network; numbers hidden | `queue_no_network` | Telefonun internete bağlı değil. Uygulama bağlantı gelince yeniden dener. |
| a poll failed and the network is validated; numbers hidden | `queue_reconnecting` | Uygulama sunucudan yanıt alamadı. Uygulama her saniye yeniden deniyor. |
| OFFERED from a poll; the ack has not been answered | `queue_your_turn` | Sıra sende. Uygulama karar ekranını açıyor. |
| DROPPED | `queue_dropped` | Sunucu 30 saniye boyunca uygulamandan haber almadı ve seni sıradan çıkardı. |
| SKIPPED (from a poll, the ack's `409`, or `GET` on resume); %1$d = `details.offerWindowS`, 8 normally and at least 10 after a pause (fidelity V8, R10) | `queue_skipped` | Sıra sana geldi. Sunucu %1$d saniye içinde uygulamandan yanıt almadı ve seni sıradan çıkardı. |
| CLOSED, TEAM_FULL | `queue_team_filled` | Takım doldu ve sunucu seni sıradan çıkardı. Kaptan yer açıp yeni bir bağlantı gönderirse takıma katılabilirsin. |
| CLOSED, LINK_RENEWED | `queue_link_renewed` | Kaptan yeni bir davet bağlantısı oluşturdu ve sunucu eski bağlantının sırasını kapattı. Kaptandan yeni bağlantıyı isteyebilirsin. |
| CLOSED, LINK_CLOSED | `queue_link_closed` | Kaptan bu daveti kapattı ve sunucu seni sıradan çıkardı. |
| CLOSED, LINK_EXPIRED | `queue_link_expired` | Davetin süresi doldu ve sunucu seni sıradan çıkardı. |
| LEFT, `decidedHere: true` | | the screen closes without a text |
| LEFT, `decidedHere: false` | `queue_left_elsewhere` | Sen hesabının başka bir oturumunda sıradan çıktın. |

**E. After joining: jersey (v2, kept)**

| Exact state | Key | Text |
|---|---|---|
| right after JOINED | `jersey_title` | Forma Numaran |
| same | `jersey_body` | Takım arkadaşların forma numaranı görür. Kaptan maç kadrosunu bu numarayla hazırlar. |
| `409 JERSEY_TAKEN` | `jersey_taken` | Takımda %1$d numaralı formayı başka bir üye taşıyor. Başka bir numara seçebilirsin. |
| buttons | | Kaydet / Şimdi Değil |

**F. Captain: invite card and team screen**

| Exact state | Key | Text |
|---|---|---|
| invite live | `invite_title` | Davet Bağlantısı |
| invite live; %1$s = `join_accept` | `invite_body` | Bağlantıyı ya da kodu takıma katılacak kişilere gönder. Bağlantıyı açıp %1$s düğmesine dokunan kişi senin onayın olmadan takıma girer. |
| invite live, this phone holds its URL and code | `invite_code_label`, `invite_expires`, buttons | Kod · Bağlantı %1$s tarihine kadar geçerli. · Paylaş / Kodu Kopyala / Bağlantıyı Yenile / Daveti Kapat |
| invite live, this phone holds no copy (P8) | `invite_not_on_this_phone` | Bu telefonda davet bağlantısının bir kopyası yok. Bu telefondan paylaşmak için bağlantıyı yenileyebilirsin. Yeni bağlantı eski bağlantıyı geçersiz kılar. |
| Bağlantıyı Yenile tapped | `invite_renew_body` | Yeni bağlantı eski bağlantıyı geçersiz kılar. Sunucu sırada bekleyenlerin ve karar verenlerin katılma isteğini kapatır. |
| Daveti Kapat tapped | `invite_close_title` | Daveti kapatmak istiyor musun? |
| invite live; deciding or waiting above 0 (from the live view) | `invite_live` | Şu an %1$d yer karar bekliyor ve %2$d kişi sırada bekliyor. |
| invite closed, reason FULL; button `Yeni Bağlantı Oluştur` | `invite_closed_full` | Takım doldu ve sunucu daveti kapattı. Yer açarsan yeni bir bağlantı oluşturabilirsin. |
| invite closed, reason EXPIRED | `invite_closed_expired` | Davetin süresi doldu. Yeni bir bağlantı oluşturabilirsin. |
| invite closed, reason CLOSED | `invite_closed` | Davet kapalı. Yeni bir bağlantı oluşturabilirsin. |
| health `links.publicUrl: false` | `invite_no_public_address` | Sunucunun internet adresi yok. Uygulama bu yüzden yalnızca kodu paylaşır. |
| Paylaş with a link host; %3$s = `code_title` | `invite_share_text` | %1$s takımına katılmak için bu bağlantıyı aç: %2$s ⏎ Bağlantı uygulamayı açmazsa VolleyStat uygulamasında %3$s ekranına bu kodu yaz: %4$s ⏎ Uygulama telefonunda yoksa bağlantının açtığı sayfa kurulum adımlarını gösterir. |
| Paylaş without a link host; %2$s = `code_title` | `invite_share_text_code` | %1$s takımına katılmak için VolleyStat uygulamasında %2$s ekranına bu kodu yaz: %3$s ⏎ Uygulama telefonunda yoksa kaptandan uygulama dosyasını isteyebilirsin. |
| settings, capacity field | `team_capacity_body` | Yer sayısı seni de sayar. Takım bu sayıya ulaşınca sunucu daveti kapatır. |
| `409 CAPACITY_IN_USE` | `team_capacity_in_use` | Şu an %1$d yer karar bekliyor. Yer sayısı üye sayısıyla karar bekleyen yerlerin toplamından az olamaz. |
| `409 CAPACITY_BELOW_MEMBERS` | `team_capacity_below_members` | Takımda %1$d üye var. Yer sayısı üye sayısından az olamaz. |
| team screen, members since the captain's last look | `team_recent_joins`; badge content description `team_new_joins_a11y` | Son katılanlar · %1$d yeni üye |
| captain taps remove on a member (S3) | `member_remove_body` | Bir üyeyi çıkarınca sunucu bu hesabın takıma yeniden katılmasını engeller. Çıkardığın kişi davet bağlantısıyla yeni bir hesap açarsa takıma yeniden katılabilir. Bunu önlemek için bağlantıyı da yenileyebilirsin. Engeli Çıkarılanlar listesinden kaldırabilirsin. |
| same dialog, invite live; a switch, on by default (4.3) | `member_remove_renew` + `invite_renew_body` below it | Bağlantıyı da Yenile |
| invite card after a removal that renewed the invite | `invite_after_removal` | Yeni bağlantıyı çıkardığın kişinin göremeyeceği bir yerde paylaşabilirsin. |
| blocked list | `blocked_title`, button | Çıkarılanlar · Engeli Kaldır |

(⏎ is a line break in the shared message.)

**G. Fallback web page on the link host** (static HTML, one external script; no team data)

| Exact state | Element | Text |
|---|---|---|
| `/j/` loaded | title | VolleyStat daveti |
| `/j/` loaded | body | Bu bağlantı VolleyStat uygulamasında bir takım davetini açar. Uygulama telefonunda kuruluysa Uygulamada Aç düğmesine dokun. |
| `/m/` loaded | body | Bu bağlantı VolleyStat uygulamasında bir maç isteğini açar. Uygulama telefonunda kuruluysa Uygulamada Aç düğmesine dokun. |
| always | button | Uygulamada Aç |
| the fragment holds a code that passed the check of 2.2; the code shown large with Kodu Kopyala | code text | Uygulama bağlantıyı açmazsa VolleyStat uygulamasında Kod Gir ekranına bu kodu yaz. |
| `/j/`, the fragment holds no code that passed the check | code text | Kaptandan davet kodunu isteyebilirsin. |
| `/m/`, the fragment holds no code that passed the check (fidelity V8) | code text | İsteği gönderen kaptandan istek kodunu isteyebilirsin. |
| always, default distribution (APK by hand) | install title / body | Uygulama telefonunda yoksa / Kaptandan VolleyStat uygulama dosyasını iste. Dosyayı açınca telefon bu kaynaktan kurulum için senden izin ister. Kurulumdan sonra WhatsApp'taki bağlantıya yeniden dokun. |

### 2.13 The owner's example, step by step

Team "Kuzey Spor": the captain and capacity 11, so 10 seats are open when the link goes out. M = members, H = holding, O = offered, W = waiting, free = 11 − M − O − H. P1 … P13 are people from the WhatsApp group, numbered by when their claims reach the server. Times are seconds after P10's claim reached the server; network round trips are 0.3 s.

| Step | Event | Server state after the step | Screens |
|---|---|---|---|
| 0 | The captain creates the team (captain code) and shares the link. | M 1, H 0, O 0, W 0, free 10; invite live | captain: invite card |
| 1 | P1 … P9 reach the decision point at different moments, and each taps Katıl within 10 s. | each: T1 HOLDING, then T8 JOINED. After P9: M 10, H 0, free 1; invite live | each: pop-up, `join_joined_body`, the jersey prompt |
| 2 | t = 0: P10's claim arrives. | T1: P10 HOLDING, `queue_no` 10, `expires_at` 10.0, `grace_until` and `due_at` 12.0. M 10, H 1, free 0 | P10: pop-up showing 10 (D − now ≈ 9.7 s) |
| 3 | t = 3: P11's claim arrives. | T2: P11 WAITING, `queue_no` 11, ahead 0. W 1 | P11: "Takımda 1 yer kaldı. Bu yer şu an bir kişinin kararını bekliyor." "Sıradaki sensin." P10's pop-up poll stays asleep, because P10's view (HOLDING, 10.0) did not change, and P10's countdown is untouched (C3). |
| 4 | t = 4 and t = 5: P12 and P13 claim. | T2: P12 WAITING ahead 1, P13 WAITING ahead 2. W 3 | P12: same seats line, "Önünde 1 kişi var." P13: "Önünde 2 kişi var." P11 is not woken. |
| 5 | t = 6: P10 taps Reddet. | T9: P10 REJECTED. `settle`: free 1 → T5: P11 OFFERED, `offer_deadline` 14.0. H 0, O 1, W 2 | P10: `join_rejected` with Yeniden Sıraya Gir. P11's poll answers within milliseconds: `queue_your_turn`. P12: "Sıradaki sensin." P13: "Önünde 1 kişi var." The seats line stays "1 yer … bir kişinin kararını bekliyor". |
| 6 | t ≈ 6.3: P11's ack arrives. | T6 (6.3 < 14.0): P11 HOLDING, `expires_at` 16.3, `grace_until` and `due_at` 18.3. O 0, H 1 | P11: pop-up showing 10. P12 and P13 are not woken (their views did not change). |
| 7a | t = 10: P11 taps Katıl. | T8 (arrival 10.1 < 18.3): P11 JOINED. M 11 = capacity: the invite closes (FULL), and `settle` runs T13, not T14: P12 and P13 CLOSED (TEAM_FULL). M 11, H 0, O 0, W 0 | P11: `join_joined_body`, the jersey prompt. P12, P13: `queue_team_filled`. Captain: `invite_closed_full`. P14 opening the old link now: `link_team_full`. |
| 7a′ | After 7a, P14 has no working link and types the code from the old message in Kod Gir. | The code's HMAC is the tombstone's `closed_code_hmac`: `410 LINK_CLOSED` (FULL) with `teamName`; no throttle key moves (R3, fidelity V2). | P14: `link_team_full`, however often P14 retries. |
| 7b | P11 lets the time run out. | P11's phone reaches zero at about 16.15 (its deadline lies one uplink delay before the server's) and sends TIMEOUT. If it arrives before 16.3: `timeout_reported`, `due_at` 16.3, and the first tick after 16.3 runs T10b (EXPIRED, TIME_UP) and T5: P12 OFFERED with `offer_deadline` about 24.5. If it arrives after 16.3: the same at once. If no TIMEOUT arrives: T10a at 18.3. | P11: `join_time_up`, then `join_expired` with Yeniden Sıraya Gir. P12: `queue_your_turn`, then the pop-up after its ack. P13: "Sıradaki sensin." |
| 7c | P11 had pressed Home before step 5, so the app stopped polling and sent no ack. | t = 14.0 (first tick ≤ 14.25): T7 P11 SKIPPED, T5 P12 OFFERED (`offer_deadline` ≈ 22.25). | P11 on return: the app reads the stored attempt id, `GET` answers SKIPPED, the app shows `queue_skipped` with Yeniden Sıraya Gir and sends no claim (C5). P12: the pop-up. |
| 7d | P11 taps Katıl when the pop-up shows 1. On a slow network the request's headers reach the server at 17.8 (1.5 s after `expires_at`), and a long analysis write holds the lock until 18.7. | Judged by arrival: 17.8 < 18.3 → T8 JOINED at 18.7. The middleware registered the entry at 17.8, before `grace_until`, so the ticks at 18.3-18.5 skipped the hold; the protection would have ended at 23.3 (C2, conc V1). End state as in 7a. | P11: `join_sending`, then `join_joined_body`. |
| 7e | Same, but the request arrives at 18.8. | It protects nothing (18.8 ≥ 18.3). If the tick at 18.3-18.55 ran first: T10a (EXPIRED, NO_DECISION) and T5, then the ACCEPT runs T17. If the ACCEPT's transaction ran first: T8c closes the hold and offers P12 in the same transaction (conc V3). Either way `late_accept_at` 18.8 and `409 ATTEMPT_CLOSED` with `lateAccept: true`; P12 OFFERED. | P11: `join_late`. P12: the pop-up. |
| 7f | P11's phone loses the network from t = 5.5 to t = 9.2. | Polls fail; the app retries at 6.0 and then every second. When the network returns at 9.2 the app polls at once and gets OFFERED at about 9.5; the ack arrives at about 9.65 < 14.0 → T6 HOLDING, `expires_at` ≈ 19.65. | P11: `queue_no_network` without numbers, then `queue_your_turn` and the pop-up (F2). |
| 7g | The owner's computer sleeps from t = 5 to t = 65; P10 has not tapped. | At the wake the beat sees a 60 s gap and sets `recovery_due`. The first write transaction after the wake, whether a buffered request or the clock takes the first ticket, runs recovery (2.11) before any judgment or `settle`: P11, P12, P13 `seen_at` = 65; P10's `grace_until` = max(12.0, 67.0) = 67.0. At 67.0 T10a: P10 EXPIRED, T5: P11 OFFERED (`offer_deadline` 75.0). | During the sleep: P11-P13 `queue_reconnecting` without numbers; P10 `join_time_up`, then `join_unknown_timeout`. After the wake: P10 `join_expired` after Sonucu Kontrol Et; P11 the pop-up. Nobody was DROPPED (C6, conc V2). |
| 7h | Same as 7g, but the owner's tunnel drops from t = 5 to t = 50 while the computer stays awake. | At t = 8 no request has arrived for 3 s: the server answers every waiting poll at once. At t = 11 still nothing has arrived: a silence, which starts before P10's `grace_until` (12.0). The clock runs no T7, T10a or T12. At 50 the first arrival sets `recovery_due`, and the next write transaction runs recovery: `seen_at` = 50, P10's `grace_until` = 52.0. At 52.0 T10a and T5: P11 OFFERED (R8). | As in 7g. Nobody was DROPPED. |

Every row was checked against T1-T17, the timing table and the texts above.

### 2.14 Accessibility of the pop-up

- When the pop-up opens, TalkBack hears the team name, "Katılmak istiyor musun?" and "10 saniye kaldı" once, through a polite live region. It hears the countdown again at 5 seconds only, so the reading does not drown the buttons.
- Both buttons are at least 56 dp high and span half the width each. Katıl is the primary button.
- A short haptic (`performHapticFeedback`, no permission) marks the pop-up opening from the waiting screen.
- The join screen keeps the screen on while WAITING, OFFERED or HOLDING (`view.keepScreenOn` in a `DisposableEffect`, as `Record.kt` line 346 and `Status.kt` line 122 do).
- The time limit is the essence of the feature (WCAG 2.2.1 real-time exception). A longer window for screen-reader users is an owner decision (16.1 item 9).

---

## 3. Accounts and sign-in

### 3.1 What the user gives (v1, kept)

| Field | Rule |
|---|---|
| `username` | 3-30 characters from `[a-z0-9._]`, starting with a letter. The app lowercases with `Locale.ROOT`. No other user sees it; the operator sees it through the CLI (11.1). |
| `displayName` | 2-40 characters after trim and NFC. Letters including Turkish ones, digits, spaces and `.'-`. No Cc or Cf character (control, bidi and zero-width characters). Need not be unique. |
| `password` | 10-128 characters after NFKC. It must not contain the username and must not be on the bundled list of the 10 000 most common passwords (compared after NFKC and casefold; `PASSWORD_REJECTED` with `details.reason: COMMON`, P4). No composition rules. |

The same character rule (NFC, trim, no Cc or Cf, internal whitespace collapsed) applies to team names, match names, opponent names and venues from 0.8 on.

The common-password list is a free list whose licence allows bundling in a closed product (for example SecLists' 10k list under MIT; the slice checks the licence file and records it in the third-party notices). It ships inside the backend package, so no network call is needed.

Google sign-in comes later, when the owner creates a free OAuth client. It will add a `user_identities` table and change nothing here.

### 3.2 Passwords, sessions and the session cache

- `hashlib.scrypt(n=2**15, r=8, p=3, dklen=32, maxmem=64 MiB)`, about 165 ms on this container, stored as `scrypt$32768$8$3$<salt>$<key>`, compared with `hmac.compare_digest`. An unknown username still costs one scrypt against a dummy hash (v1, kept).
- Token `secrets.token_urlsafe(32)`, only in `Authorization: Bearer`, stored as SHA-256. 30 days idle and 180 days absolute; the server writes `last_used_at` at most once an hour (v1, kept).
- No cookies, so no CSRF; no CORS, because the only client is the app.
- **Device token (S2).** A successful login or registration also answers with a `deviceToken` (`secrets.token_urlsafe(16)`), which the server stores as SHA-256 in `login_devices` with the user id. The phone keeps it in `device.bin`, keyed by the SHA-256 of the username, and keeps it across logout. A login that carries a device token valid for its username skips the username keys of 3.6 and counts its failures under `login:device:<hash>` instead. A stranger who knows a username can therefore lock that name out only on phones that never logged in with it, never on the owner's own phone (the OWASP device-cookie rule). A password change or an operator reset deletes the user's device tokens; a token unused for 180 days goes.
- Logout deletes the session row. A password change deletes every other session.
- **Session cache (C1).** The process keeps a map from token hash to (user id, expiry, time read), filled by every database session lookup. Only the long-poll and the captain's live view read it, so they never touch the database while they wait. Logout, password change, account deletion and session expiry in this process remove the entry at once. An entry older than 60 s is checked against the database again on its next use, so a session the operator CLI deletes from another process stops serving polls within 60 s. Every write route still reads the session inside its own transaction.

### 3.3 Registration order and the gates

`POST /v1/auth/register` checks, in this order, and answers the first failure:
1. throttle (3.6): only the `register:src` source key and, for a code, the code keys apply;
2. the sign-up gate: `403 SIGNUP_CLOSED` when the body has no link and no code; `404 LINK_NOT_FOUND`, `410 LINK_CLOSED` (with the reason) or `422 WRONG_LINK_KIND` (a match-request link, P1) for a link; `403 SIGNUP_LINK_LIMIT` when the invite has opened 200 sign-ups (P3); `403 SIGNUP_CODE_INVALID` for an operator code. Nothing about the username is looked up before this step.
3. field validation (`422 USERNAME_INVALID`, `422 DISPLAY_NAME_INVALID`, `422 PASSWORD_REJECTED` with `details.reason`);
4. username uniqueness (`409 USERNAME_TAKEN`).

The invite's `signups` counter moves in the transaction that inserts the user; a `409 USERNAME_TAKEN` does not move it. The user row records how the account opened (`signup_via`: INVITE, SIGNUP_CODE, CAPTAIN_CODE or OPEN), which sets its personal storage quota (6.5, S7). A registration with a code that no live link and no tombstone holds counts as a code miss under the code keys of 3.6, so registration cannot serve as an uncounted code oracle (S2). Renewing the invite starts a new counter. The limit of 200 equals the queue limit, so the owner's WhatsApp group of 100 newcomers can all sign up, wait and get a truthful answer, while a leaked link can create at most 200 accounts before the captain renews it. P3 proposed capacity − members + 5; with capacity 11 that refuses the 16th newcomer of the owner's own example before the queue is even full, so v3 ties the limit to the queue instead (owner number, 16.1 item 11).

**Operator codes.** 16 characters from the code alphabet (about 79 bits), shown as four groups of four, valid 7 days, stored as SHA-256 in `operator_codes` with a kind:
- `SIGNUP` (`python -m app.admin signup-code`) opens one registration. The registration consumes it; a `409 USERNAME_TAKEN` does not.
- `CAPTAIN` (`python -m app.admin captain-code --team-name <name>`) opens one registration and allows the creation of one team of that name (0.4, fidelity V1). A registration with it marks it `signup_used` and stores the new user's id in `used_by_user_id` in the same transaction; from then on only that user may create the team with it (S8). Creating a team consumes it (4.1).

Tests: with `link` mode and no link, a taken and a free username get byte-identical answers; a second account cannot spend a CAPTAIN code that another account used to register (S8); the CLI refuses a team name outside the ADR-0119 list without `--after-kvkk`, and team creation refuses a name other than the code's (fidelity V1); a registration with an unknown code moves `code:anon:global` (S2).

### 3.4 Forgotten password (v2, kept)

Until B10 the operator resets a password in person: `python -m app.admin reset-password <username>` prints a one-time temporary password, deletes every session of the user and sets `password_changed_at`. The app suggests a new password after such a login. Self-service recovery codes come with B10.

### 3.5 Hashing never blocks the API (v2, kept)

- Register, login, password change and account deletion are `async def` routes. They run scrypt on a dedicated `ThreadPoolExecutor(max_workers=2)`, never in the shared pool, and open their write transactions through `run_in_threadpool` (2.8).
- At most 16 hashes may wait. A seventeenth request gets `503 SERVER_BUSY` with `Retry-After: 1`, and the app retries after that second on its own.
- Test: during a flood of 120 logins, `GET /v1/health` and a claim each answer within 100 ms.

The join burst does not depend on hashing. A logged-in person reaches the pop-up with one session lookup and one write transaction.

### 3.6 Throttling (P4, F7, S2, R4)

`__main__.py` passes `access_log=False` and `forwarded_allow_ips=""` explicitly, so uvicorn ignores `X-Forwarded-For`. From B6a the server reads the client address from one named header, and only when the peer is the trusted proxy (`--trusted-proxy <address>` with `--client-ip-header <name>`):
- Caddy overwrites `X-Forwarded-For` for untrusted clients; the server reads its last entry.
- Cloudflare Tunnel sends `CF-Connecting-IP`.
- Tailscale Funnel: the header that B6b verifies on real phones. If the tunnel passes no client address, the ADR states that source keys stay off for that option.

A peer that is not the trusted proxy counts by its own address. The source of an IPv4 address is the address; the source of an IPv6 address is its /64, because one client can rotate addresses inside a /64 (S2). A loopback peer without a client-address header is the source "local", which never gets a source key: every tunnel connects from loopback, and a shared key would refuse everyone at once, the server-wide lockout that P4 removed (R4). With `VOLLEYSTAT_PUBLIC_URL` set, the server refuses to start without `--trusted-proxy`.

Turkish mobile carriers put many phones behind one public IPv4 address (carrier-grade NAT), so 100 people from one WhatsApp group may share a few addresses. The source limits are sized for that (S2).

| Key | Applies | Rule | `details.scope` of the 429 |
|---|---|---|---|
| `login:device:<device token hash>` | a login that carries a device token valid for its username (3.2) | 5 failures free, then 30 s doubling to 15 min; resets 1 h after the last failure or on success | DEVICE |
| `login:user:<username>:<source>` | a login without a valid device token | 5 failures free, then 30 s doubling to 15 min; resets 1 h after the last failure or on success | USER |
| `login:user:<username>` | a login without a valid device token | 50 failures in one hour from any sources lock that username for 15 min; this guards against guessing spread over many addresses; the operator reset still works | USER |
| `login:src:<source>` | after B6a; never "local" | 100 failures in 10 min lock that source for 15 min | SOURCE |
| `register:src:<source>` | after B6a; never "local" | 100 gate failures in 10 min lock registration from that source for 15 min. A gate failure is `404 LINK_NOT_FOUND` by token, `422 WRONG_LINK_KIND`, `403 SIGNUP_CLOSED` or `403 SIGNUP_CODE_INVALID`. A field-validation 422 (`PASSWORD_REJECTED`, `USERNAME_INVALID`, `DISPLAY_NAME_INVALID`), which the app cannot check in advance, `USERNAME_TAKEN`, a tombstone hit (410) and a code miss (counted under the code keys) are not. | REGISTER_SOURCE |
| `code:user:<user id>` | logged-in code misses on any route that accepts a code: resolve, claim, accept, decline | 10 misses in one hour | ACCOUNT_CODE |
| `code:src:<source>` | after B6a; never "local"; anonymous code misses on resolve and register | 100 misses in 10 min | CODE_SOURCE |
| `code:anon:global` | anonymous code misses on resolve and register | circuit breaker: 1 000 misses in 10 min stop anonymous code lookups for 10 min | CODE_GLOBAL |
| `join:attempt:<attempt id>`, `join:user:<user id>` | ack, decision and DELETE per attempt; claims per account (2.9) | 5 a second | RATE |
| `request:team:<team id>` | | 20 match requests a day | REQUESTS |

Every 429 is `429 TOO_MANY_ATTEMPTS` with `Retry-After`, `details.retryAfterSeconds` and `details.scope`, so the app names the right subject: this username, this phone, this internet address, this account's code entry, or code entry for everyone (fidelity V10, 10.8).

Rules:
- A code miss is a code that no live link and no tombstone of the last 30 days holds. A code that hits a tombstone answers `410 LINK_CLOSED` and counts toward no key (R3).
- No per-source key and no server-wide counter refuses a request that carries a live link token or a valid session (P4, S2). A logged-in code lookup counts only under its account key, and the per-attempt rate key applies to everyone.
- There is no server-wide login cap. The hash executor's queue (16, then `503 SERVER_BUSY`) protects the processor.
- The limiter lives in process memory (a dict and a lock, at most 10 000 keys), so nothing about addresses or usernames reaches the disk. It never evicts a locked key; it evicts the oldest unlocked key; when every slot holds a locked key it tracks no new key and logs that once a minute.
- Before B6a the server listens on 127.0.0.1 only, and only the per-username, per-device, per-account and per-attempt keys can trigger.

Tests: 61 failures from source X do not block a login from source Y; 1 000 anonymous code failures do not block a logged-in lookup or a claim with a token; a registration that ends in `USERNAME_TAKEN` or in `PASSWORD_REJECTED` moves no counter; a registration with a live token is never refused by `register:src`; a spoofed `X-Forwarded-For` changes no key before B6a; behind a loopback proxy that sends no client address, 10 failed registrations from one client block neither another client's registration nor its login (R4); two IPv6 addresses in one /64 share a key; with `VOLLEYSTAT_PUBLIC_URL` set the server refuses to start without `--trusted-proxy`; a login with a valid device token succeeds while `login:user:<username>` is locked (S2).

### 3.7 Account deletion

`POST /v1/me/delete {password}` → `204`. One write transaction:
1. For every team the user captains with other members, captaincy moves to the earliest-joined member who is not a member of the other side of any PENDING or ACCEPTED record of that team. If no member qualifies, the call answers `409 CAPTAIN_MUST_TRANSFER` (`details.teamId`) and deletes nothing. A team with no other member is deleted (4.6).
2. Personal matches are deleted like `delete_match`, bytes before the commit.
3. The user row goes. Sessions, memberships, blocks, join attempts and roster rows go by cascade. Every team that lost an open attempt runs `settle` and publishes its snapshot.
4. Videos the person uploaded as a captain belong to their side, not to them. They stay with the team, whose new captain becomes their reviewer.
5. Commit, then `empty_write_ahead_log()`, then the session cache entry goes.

The byte-search test of `test_match_delete.py` runs on the username, display name and user id, also while a second connection holds the WAL open.

---

## 4. Teams

### 4.1 Create and settings

- `PUT /v1/teams/{teamId}` with a client-generated lowercase UUID (ADR-0113 pattern) and `{name, capacity?, schedule?, jersey?, captainCode?}`. Under `VOLLEYSTAT_TEAMS=operator` (the default before the KVKK review) a missing or spent captain code answers `403 CAPTAIN_CODE_REQUIRED` or `403 CAPTAIN_CODE_INVALID` (P1). The code is accepted only for a team whose name equals the code's `team_name` after NFC and casefold, and, once a registration used it, only from that registered account; anything else answers `403 CAPTAIN_CODE_INVALID` (fidelity V1, S8). The server consumes the code, creates the team with a random tag (4.4), the caller's CAPTAIN membership and the first invite, and answers `201` with the invite's token, code and URL. A repeat by a member answers `200` without the token or code; anyone else gets `409 TEAM_ID_TAKEN`.
- `PUT /v1/teams/{teamId}/settings {name, capacity, schedule}` (captain). A capacity change runs `settle` (2.3).
- `schedule` is `{weekday: 1..7, time: "HH:MM"}` or null. It is the team's usual match day and time, which the pop-up shows.
- Limits: a person is a member of at most 20 teams and captain of at most 10 (`409 TEAM_LIMIT_REACHED`, `details.limit`).

### 4.2 Logo (P7, C7, F4)

- The app picks the image with the photo picker (no permission), crops it square, draws it on an opaque background (so no alpha is ever needed) and encodes two lossy WebP files at quality 80: 256 px for members (about 10-25 KB, at most 36 KiB) and 128 px for link views (about 4-6 KB, at most 8 KiB).
- `PUT /v1/teams/{teamId}/logo` takes `{"large": "<base64>", "small": "<base64>"}`. Both together stay under the 64 KiB `BodyLimit`, which does not change.
- The server accepts only a simple lossy WebP for each: `RIFF` whose size field equals the decoded length minus 8, then `WEBP`, then exactly one `VP8 ` chunk whose frame header is a valid key frame of at most 512 × 512 (256 for large, 128 for small), and no byte after it. `VP8L`, `VP8X`, `ALPH`, EXIF, XMP, ICC, animation and trailing bytes answer `422 LOGO_INVALID`. The lossless decoder path, where the libwebp bugs of 2023 lived, never reaches a phone through this server, and no location or camera data is stored.
- The bytes live in `teams.logo` and `teams.logo_small` (BLOBs), so deletion follows `secure_delete` and the WAL rule with no file. `logo_version` moves on each change.
- Members read either size with `GET /v1/teams/{id}/logo?size=large|small` (session, ETag on the version).
- `POST /v1/links/resolve` embeds the small logo as base64 when the request's `knownLogoVersion` differs. Claim, ack, decision and poll answers never carry logo bytes (C7); they carry `logoVersion` only. A logged-in phone sends resolve in parallel with the claim, and the pop-up shows initials until the small logo arrives. Test: with maximum-size logos every claim, ack and poll answer stays under 2 KB.
- The app decodes logos with explicit bounds: `ImageDecoder` with `setTargetSize` on API 28 and later, `BitmapFactory` with an `inJustDecodeBounds` check and `inSampleSize` on API 26-27. A failed decode shows the initials.

### 4.3 Members, jerseys, leaving, removal

- Members see the team: name, tag, capacity, schedule, members with display name, jersey and role. The captain also sees the invite state and the live counts.
- A member sets their own jersey; the captain may set anyone's. A jersey is unique among the members of one team (`409 JERSEY_TAKEN`, `details.jersey`).
- `DELETE /v1/teams/{id}/members/{userId}` leaves (self) or removes (captain). A captain with other members gets `409 CAPTAIN_MUST_TRANSFER`. The delete runs `settle`.
- **Removal blocks (P2).** A captain's removal writes `team_blocks(team_id, user_id)` in the same transaction. T1 refuses a blocked person with `403 JOIN_BLOCKED`, whatever link they hold. The captain sees the blocked people under "Çıkarılanlar" (display names, since they were members) and can lift a block with `DELETE /v1/teams/{id}/blocks/{userId}`. Leaving by oneself writes no block. Team or account deletion removes the rows by cascade.
- **Removal and the live invite (S3).** A block stops one account, not the person: a removed person who still holds the live invite can register a new account with it and join at once. When the invite is live, the removal dialog therefore shows the switch Bağlantıyı da Yenile, on by default, with `member_remove_body` and `invite_renew_body`. With the switch on, `DELETE /v1/teams/{id}/members/{userId}?renewInvite=true` removes, blocks and renews in one transaction (T14 RENEWED and a new invite) and answers `200 {invite}` with the new token, code and URL. The new invite card then shows `invite_after_removal`. Test: a removed user who registers a new account with the old link is JOINED when the switch is off; with the switch on, the same user gets `410 LINK_CLOSED` (RENEWED).
- **New-join badge (P2).** `GET /v1/teams` and `GET /v1/teams/{id}` carry `newJoins` for the captain: members whose `joined_at` is later than the captain's `joins_seen_at`. The Teams tab shows the sum as a badge. Opening the team screen calls `POST /v1/teams/{id}/joins-seen {upTo}`.
- `POST /v1/teams/{id}/captain {userId}`: transfer to a member, refused with `409 CAPTAIN_CONFLICT` (`details.matchId`) when the new captain is a member of the other side of a PENDING or ACCEPTED record of this team. Both role updates happen in one transaction, so the partial unique index never sees two captains.

### 4.4 The tag (P1)

The server draws a team's tag at random: 4 characters of the code alphabet from `secrets.choice`, stored in `teams.tag` with a UNIQUE index, redrawn inside the creating transaction until unused (31^4 ≈ 923 000 tags). A client-chosen team id no longer influences it, so the forgery that `critique_v2/tag_grind.py` showed (a matching tag in 0.5-1.4 s) is gone. Match request screens and the team screen show the tag next to the name (`Kuzey Spor #K7Q2`). Captains can tell each other their tags, so a team of the same name is recognizable.

### 4.5 Who sees what of a team

| Data | Member | Captain | Link holder without a session | Link holder with a session | Other team in a match |
|---|---|---|---|---|---|
| name, tag, small logo | yes | yes | yes (P5) | yes | name and tag |
| large logo | yes | yes | no | no | no |
| schedule, capacity and seat counts | yes | yes | no (P5) | yes | no |
| member list with display names | yes | yes | no | no | no |
| invite token and code | no | only in the creation answer and on the phone that created it (P8) | | | no |
| who is waiting or deciding | no | counts only | no | no | no |
| blocked people | no | yes | no | no | no |
| content of past team match records | only records played on or after the day the member joined, or records with the member on the roster (P2, 6.1) | all | no | no | 6.1 |

### 4.6 Delete a team

`DELETE /v1/teams/{id}` (captain) → `204`. One transaction:
1. PENDING match records requested by the team are deleted with their links. No video can exist in them on the server.
2. In ACCEPTED records, the team's side leaves exactly as in 6.4: its videos, jobs and bytes, its roster rows and its side result go, and `match_sides.team_id` becomes NULL. A record with no side left is deleted like `delete_match`.
3. One-sided records of the team are deleted like `delete_match`.
4. The team row goes; memberships, blocks, the invite, its tombstones and join attempts go by cascade.
5. `empty_write_ahead_log()`.

The byte-search test covers the team name, tag, logo bytes, code HMACs, token hashes and the members' links.

---

## 5. Matches between two teams

### 5.1 The request link

The captain of team A creates the request and sends its link to the other captain, the same way as an invite. No static match code, no search and no directory exist.

`PUT /v1/match-requests/{matchId}` (client UUID, so the phone can create it offline and send it later; 10.1):

```json
{"teamId": "6f0c…", "playedOn": "2026-11-22", "startTime": "14:00", "venue": "Okul Spor Salonu",
 "name": null, "kind": "TRAINING", "rules": {"setsToWin": 3, "setTarget": 25, "decidingSetTarget": 15}}
```

- `playedOn` and `startTime` are required, because the captains agree on a time and the statistics and the member read rule (6.1) read the date.
- The other fields follow `MatchInput`, with `extra="forbid"` and the 3.1 character rule.
- The caller must be the captain of `teamId` (`403 CAPTAIN_ONLY`). Before the KVKK review both teams of a request are among the owner's own teams (0.4).
- The answer is `201 {match, link}` with the token, the 8-character code and the URL. The record is PENDING; A is HOME. The link is a share link of kind `MATCH_REQUEST`, valid 7 days, single use. It never opens sign-up (P1).
- A repeat with the same id answers `200` to members of A, without the token and code, and `403 NOT_A_MEMBER` to anyone else.

### 5.2 Opening and accepting

1. Captain B opens the link. Without a session, B logs in. A person without an account cannot use a request link to sign up; under the default gate a new captain signs up with their captain code.
2. `POST /v1/links/resolve` shows the requesting team (name, tag, small logo) to anyone with the token. Date, start time, venue, kind and rule, and the list of B's teams with their eligibility, come only to a caller who captains a team (P5).
3. B captains no team: `request_need_captain` explains that a captain accepts requests.
4. B picks a team and taps Kabul Et: `POST /v1/match-requests/accept {link, teamId}`.

Checks at acceptance, in one write transaction:

| # | Check | Answer |
|---|---|---|
| 1 | the link is live and of kind MATCH_REQUEST | `404 LINK_NOT_FOUND`, `410 LINK_CLOSED`, `422 WRONG_LINK_KIND` |
| 2 | the record is PENDING | `409 REQUEST_CLOSED` (`details.status`) |
| 3 | the caller is captain of `teamId` | `403 CAPTAIN_ONLY` |
| 4 | `teamId` is not A | `422 SAME_TEAM` |
| 5 | the two current team names differ after NFC and casefold | `422 SAME_TEAM_NAME` |
| 6 | B's captain is not a member of A, and A's captain is not a member of B | `422 SAME_PERSON_BOTH_SIDES` with `details.who`: ACCEPTER when the caller is a member of A, REQUESTER when A's captain is a member of B (fidelity V9) |

Effect: status ACCEPTED, `closed_at = now`, `match_sides` rows for HOME and AWAY, AWAY's `opponent_checked_at = now` (accepting is B's check of A), `away_team` = B's current name, the link closes as a tombstone with reason USED.

`POST /v1/match-requests/decline {link, teamId}` by a captain closes the record as DECLINED and stores the declining team's name and tag in `matches.declined_by`, so A sees who declined. A code miss on accept or decline counts under the code keys (3.6, S2).

### 5.3 The requesting captain confirms the opponent (P1)

After acceptance, A's record card shows the accepting team's name, tag and small logo with `request_accepted` and a Rakibi Onayla button: `POST /v1/matches/{id}/opponent/confirm` (HOME captain) sets HOME's `opponent_checked_at`. Until then the server refuses HOME's upload with `409 OPPONENT_NOT_CHECKED`, and A's phone keeps any recording it holds (5.5). If the opponent is wrong, A taps Rakibim Değil (R1): A's side leaves the record (6.4), the app creates a SOLO record for A with a new client id (5.7), and the phone moves the held recording to it. When the other side has left the record, HOME needs no opponent check, because no other team receives HOME's video.

This adds one tap to the owner's flow for the requesting captain only. It stops the case that P1 describes: a leaked request link accepted by another team whose captain would otherwise receive A's video at once. Before the KVKK review every team on the server is one of the owner's own teams (0.4), so the check matters after the review; it costs one tap either way.

### 5.4 States

```
PENDING ──accept──▶ ACCEPTED ──(each side leaves; the last one out deletes)──▶ deleted
   ├──decline──▶ DECLINED ──────┐
   ├──cancel (HOME captain)──▶ CANCELLED ◀──cancel (HOME captain, no video and no side result)── ACCEPTED
   ├──7 days──▶ EXPIRED ────────┤
   └────────────────────────────┴──solo (HOME captain, no video on the server)──▶ SOLO
```

- Repeating the transition that already happened answers `200`. Any other transition from a closed state answers `409 REQUEST_CLOSED`.
- On an ACCEPTED record, AWAY's cancel is AWAY's leave (6.4); only HOME cancels the record (R1).
- `POST /v1/matches/{id}/solo {opponentName}` (HOME captain) turns a PENDING, DECLINED, EXPIRED or CANCELLED record without a video on the server into a SOLO record with HOME only. It closes a live link with reason CANCELLED, keeps the record id, and sets `status = SOLO`, `away_team = opponentName` and HOME's `opponent_checked_at = now`. The app pre-fills the opponent name from `declined_by`. The record id stays, so the held recording's `teamMatchId` stays valid and the upload starts without any `UploadEngine` change. A match between two teams still needs the other captain's acceptance; the SOLO record counts only for the own team (R1, owner decision 16.1 item 7).
- The minute sweep marks PENDING records past their link's expiry as EXPIRED and deletes DECLINED, CANCELLED and EXPIRED records 30 days after `closed_at`. None of them holds a video on the server. If the sweep deleted a record whose recording a phone still holds, Yalnız Takımım İçin Kaydet creates a new SOLO record with a new client id and moves the recording to it.
- Only ACCEPTED (with the side's opponent check) unlocks the upload. The recorder never waits (5.5).
- HOME and AWAY are only labels; the app always shows team names.

### 5.5 Recording never waits (F5)

The owner said the video part stays as it is. The recorder opens from New Match as today, with nothing in front of it:
- A captain picks the team match the recording belongs to: an accepted record, a pending request, a new request ("Yeni İstek", created with a client id, so it works offline and is sent when the server answers), or, if the owner keeps the default, an opponent who does not use the app (5.7). Then today's `new/video`, `new/record` and `new/check` screens run with the record's names and rule.
- The recording and its local MatchRecord wait on the phone. `UploadEngine` starts the upload on its own once `GET /v1/matches/{id}` says the record is ACCEPTED or SOLO and the own side has checked the opponent (or the other side left); the app asks on start, on resume, on pull and after every action. The server rule "upload only after acceptance" stays.
- A second recording of the same match (battery, storage, a break) uses "Aynı Rakiple Yeni İstek". From a PENDING or closed record the app creates a pre-filled request, opens the share sheet and then the recorder at once; the other captain can accept after the match. From an ACCEPTED record it creates a sibling record that is ACCEPTED at once, with the same teams, date, start time, venue, kind and rule and both opponent checks, because the other captain already accepted this match: `PUT /v1/match-requests/{newId}` with `siblingOf`. A record has at most 3 siblings (`409 SIBLING_LIMIT`), and either captain can leave a sibling like any record (R1).
- When the other captain declines, the request expires or a captain cancels, `tm/{id}` shows that state with `request_held_options` and two buttons, Yalnız Takımım İçin Kaydet (5.4) and Aynı Rakiple Yeni İstek. `video_waiting_accept` shows only while the record is PENDING (R1).
- A personal match whose upload has not completed can move to a team match with "Takım Maçına Bağla": the app aborts the unfinished upload, deletes the empty personal record on the server and attaches the recording to the chosen team record.
- `video_locked` becomes `video_waiting_accept`: "Rakip kaptan isteği kabul edince ve sen rakibi onaylayınca uygulama videoyu sunucuya gönderir." Only HOME can hold a recording against a PENDING record, and HOME's upload also waits for Rakibi Onayla (5.3, fidelity V9).

### 5.6 Personal matches (v2, kept)

Today's single-user flow stays as the personal match: `PUT /v1/matches/{id}` and `POST /v1/matches` create `status = PERSONAL` with `owner_user_id = caller`. Only the owner sees it, upload and review work as today, and it feeds no team or person statistic.

### 5.7 One-sided team matches (F3, owner decision 16.1 item 7, default yes)

Before the KVKK review the app is not opened to other teams (ADR-0117, 0.4), so the opponents of the owner's teams are not on the app, and a one-sided match is the only way to count those matches. Without it a team's statistics would stay almost empty, which defeats the owner's request (fidelity V1).

`PUT /v1/solo-matches/{matchId} {teamId, opponentName, playedOn, startTime?, venue, name, kind, rules}` (captain of `teamId`) creates a record with `status = SOLO`, one side (HOME = the own team, `opponent_checked_at = now`) and `away_team = opponentName`. The own captain uploads, reviews and confirms; the record counts only for the own team and its rostered members. The opponent's name is free text and is never a team. Nothing else changes: the record has the same content rules, roster, side result and leave (which deletes it). A PENDING or closed request without a video becomes a SOLO record through `POST /v1/matches/{id}/solo` (5.4, R1).

---

## 6. Shared control of a team match

The rule: a team's statistics change only through its own captain's confirmation. Neither the other captain nor any member can change, erase or hold them back (F3).

### 6.1 Who may do what

Each side of a team record may hold one live video (`409 SIDE_HAS_VIDEO`). The **reviewer** of a video is the captain of the side that uploaded it (`uploads.side`).

| Action | Who | Refusals |
|---|---|---|
| see the record in lists (teams, date, states) | members of a side's team; PENDING: members of HOME | `403 NOT_A_MEMBER` |
| read content: videos, quality, analyses, rallies, cards, report, roster | both captains; members of either side's team whose `joined_at` date (UTC+3, Türkiye's fixed offset) is on or before `played_on`; anyone on that record's roster (P2) | `403 NOT_A_MEMBER` |
| confirm the opponent | HOME captain, once, ACCEPTED (AWAY's acceptance is its check) | `403 CAPTAIN_ONLY` |
| turn a request into a SOLO record (`POST …/solo`) | HOME captain; PENDING, DECLINED, EXPIRED or CANCELLED; no video on the server (R1) | `403 CAPTAIN_ONLY`, `409 REQUEST_CLOSED`, `409 MATCH_HAS_CONTENT` |
| start an upload (`POST …/uploads`) | the captain of side S: ACCEPTED or SOLO, S checked the opponent or the other side left, S has no live video, quotas hold (6.5) | `409 MATCH_NOT_CONFIRMED`, `409 OPPONENT_NOT_CHECKED`, `409 SIDE_HAS_VIDEO`, `409 QUOTA_EXCEEDED`, `507 STORAGE_FULL` |
| chunks, complete, abort, upload status | that upload's reviewer | `403 REVIEWER_ONLY` |
| quality recheck, request or cancel an analysis, decisions, reopen, the five corrections, list confirm | the reviewer of that video | `403 REVIEWER_ONLY` |
| rule change (`PUT …/rules`) | the reviewer of either live video. Today's code then lapses the list confirmation of every job of the record; no side result changes. | `403 REVIEWER_ONLY` |
| confirm the result for side S | S's captain, from any job of the record with a confirmed list, or from the result offer for S (6.2, R7) | `403 CAPTAIN_ONLY`, `409 LIST_NOT_CONFIRMED`, `409 MOCK_RESULT`, `409 RESULT_STALE` |
| roster of S: edit and confirm | S's captain | |
| leave the roster | the person | |
| delete a video | its reviewer, at any time; side results stay | |
| cancel | PENDING: HOME captain; ACCEPTED: HOME captain while no video and no side result exists. AWAY's cancel on an ACCEPTED record is AWAY's leave (6.4, R1) | `409 MATCH_HAS_CONTENT` |
| leave the match (6.4) | either captain, for their own side | |
| `DELETE /v1/matches/{id}` | personal matches only | `409 TEAM_MATCH` |

- `GET /v1/matches/{id}/report?jobId=J` resolves J with `access.match_of(db, "job", J)` and answers `404 ANALYSIS_NOT_FOUND` unless J belongs to record `{id}`; then the content rule above applies to `{id}`. Without `jobId` it reports the newest job of the record, as `review.py` lines 289-326 do today. Match and list payloads leave out `video.id`, `analysis.jobId` and `result.jobId` when `me.canReadContent` is false, so a member who may only see the record learns no id of its content (S4).
- Members cannot upload, analyse or cancel on a team record (v1 S1). A person who is a member of both teams of a record is read-only on it and can be on at most one roster side.
- `review_decisions` and `rally_corrections` stay untouched. Every row of a job was written by the reviewer of that job's video: a job dies with its video (cascade), and a side's new video can only start after its old one is gone. So `uploads.side` of the job's video names the acting side of every audit row (8.4).
- The decision route's idempotent replay reads the stored row by key, resolves its job to its match, runs the access check, and only then returns the stored answer (v2, kept).

### 6.2 Each side confirms its own result (F3)

1. The reviewer answers the cards and confirms the rally list as today.
2. `POST /v1/matches/{id}/result/confirm {jobId, revision}` by side S's captain writes S's **side result**: the report exactly as `GET /v1/matches/{id}/report` builds it for that job at that revision, jersey-level and without names, with the side, job id, revision and time. It replaces S's earlier side result. It needs a job of this record with a confirmed list (`409 LIST_NOT_CONFIRMED`), a non-demo job (`409 MOCK_RESULT`) and the job's current revision (`409 RESULT_STALE`, `details.currentRevision`).
3. On the reviewer's phone, "Listeyi Onayla" on a team record sends the list confirmation and then the reviewer's own side result. In the transaction of each list confirmation on a team record, the server also stores the same frozen report as a **result offer** for every other side of the record that has no side result yet: `match_result_offers(match_id, side, job_id, revision, report_json, offered_at)`, with no foreign key to jobs or videos. Each new list confirmation replaces it (R7).
4. The other captain reads the report on `tm/{id}/report` and taps Sonucu Onayla for their side. They may confirm the other side's job, their own job if their side filmed too, or the offer for their side (`{jobId, revision, fromOffer: true}`). The offer works also after its job, its video or the other side is gone. It goes when that side confirms a result, when that side leaves, or with the record (R7).
5. Later decisions, corrections and rule changes move the job's revision (existing code). Side results never change with them; each line shows `changedSinceCounted` until that side's captain confirms again.

Consequences:
- Team T's statistics read only T's side result. Only T's captain writes it, and only T's leave or T's deletion removes it. `match_results` has no foreign key to a job or a video, so no deletion of a video or a job, by anyone, reaches it (P1, F3).
- A captain who never confirms keeps only their own team's line out of their own statistics. The other team counts its own result (F3).
- A team that films its match reviews its own video and never depends on the other captain. A team that does not film depends on the filming team's review for its numbers, and still decides alone whether to count them. Once the filming team's reviewer confirmed the list, the filming captain can no longer take that result away: the offer stays when the video, the job or the filming side goes (R7).
- The two teams' numbers may differ: A's `pointsAgainst` may differ from B's `pointsFor`, because each team shows what its own captain confirmed. The statistics line shows the other side's state (confirmed, not confirmed, left) without blocking.
- SCORER answers for the other side's points come from the reviewer and count for the other side only after its captain confirms. The other captain can also keep points from persons by leaving jerseys off the roster.

### 6.3 Per-match rosters (v2, kept)

- `match_rosters(match_id, side, jersey, user_id)`: who played for a side and with which jersey. The side's captain writes it with `PUT /v1/matches/{id}/roster/{side} {entries, expectedVersion}` at any time after acceptance, and confirms it with `POST …/roster/{side}/confirm {version}`.
- Nothing is copied at acceptance. The roster editor starts from the team's member list with the members' jerseys as a suggestion on the phone, and the captain ticks who played. Only what the captain saves reaches the server.
- Rules (`422 ROSTER_INVALID`, `details.reason`): at most 12 entries per side (FIVB 4.1.1); jersey 1-99 unique per side; a person once per record; the person is a current member of that side's team; a person who is a member of both teams appears on one side only.
- Every change moves `match_sides.roster_version`; a confirmation names the version it confirms (`409 ROSTER_STALE`). An edit lapses that side's confirmation, and only that side's persons stop counting until the captain confirms again.
- `POST /v1/matches/{id}/roster/leave` lets a person take themselves off, which also lapses the side's confirmation.
- One roster serves every played match of the video (ADR-0118). Players changing between played matches in one video is a known limit.

### 6.4 Leaving and deleting (P1, F3)

- `POST /v1/matches/{id}/leave` by S's captain, in one transaction: S's videos (`uploads.side = S`) with their jobs (cascade) and bytes (deleted before the commit), S's roster rows and S's side result go; `match_sides.team_id = NULL`, `left_at = now`. S's members lose access at once.
- The other side keeps its own videos and its own side result, including a side result that came from S's job, because the snapshot is self-contained. It also keeps the result offer from S's last list confirmation, which its captain can still confirm (R7). The other side never inherits S's video (P1).
- AWAY's cancel on an ACCEPTED record runs this leave for AWAY (R1).
- When no side remains, the server deletes the record exactly like `delete_match`, bytes before the commit, then `empty_write_ahead_log()`, and answers `204`. While one side remains, the answer is `200` with the record. A SOLO record is deleted at once.
- `DELETE /v1/videos/{id}` by its reviewer works at any time; v2's `RESULT_COUNTED` refusal is gone, because no side result depends on a video. It also blanks the upload row: `file_name = 'deleted'` and `client_sha256 = NULL`.
- No step waits for the other captain, so nothing can get stuck.
- A person seen in a video of a team that left can ask the operator for removal (`python -m app.admin delete-match <id>`) until the KVKK review defines a process. `privacy_delete` says so.
- The byte-search test covers video deletion, leave on each side, the one-sided case and team deletion.

### 6.5 Storage quotas and abandoned uploads (P3, owner decision 16.1 item 4)

The server is the owner's own computer, and today's code accepts uploads up to 4 GiB (`MAX_VIDEO_BYTES`, `main.py` line 30) with no quota and no cleanup of abandoned uploads.

| Limit | Default | Checked in | Answer |
|---|---|---|---|
| personal video per account: live personal videos plus unfinished personal uploads at their declared size | 8 GiB for an account opened with an operator code (or in `open` mode); 2 GiB and at most one unfinished personal upload for an account opened through an invite (S7) | `initiate_upload` | `409 QUOTA_EXCEEDED` (`details.usedBytes`, `details.limitBytes`, `details.scope: PERSONAL`; `details.reason: UNFINISHED_UPLOAD` for a second unfinished upload) |
| personal video on the whole server: every account's live personal videos plus unfinished personal uploads | 100 GiB (S7) | `initiate_upload` | `409 QUOTA_EXCEEDED` with `details.scope: SERVER` |
| team video per team: live videos plus unfinished uploads of the team's sides | 40 GiB | `initiate_upload` | same with `scope: TEAM` |
| free-disk floor: free space on the storage disk minus the declared size | 20 GiB | `initiate_upload`; `store_chunk` refuses below half the floor | `507 STORAGE_FULL` |
| abandoned uploads: an UPLOADING row whose `touched_at` is 24 h old | 24 h | minute sweep | marked ABORTED, bytes deleted before the commit |

`touched_at` moves with `initiate_upload` and every stored chunk, in the statement that already moves `next_offset`. `UploadEngine.openOrResume` already starts a new upload when the old one is ABORTED or missing, so a phone that was off for a day resumes from zero without any app change. Texts are in 10.8. A team that reaches its quota deletes old videos; its statistics stay, because side results do not depend on videos.

One leaked invite opens up to 200 accounts. With v3's 8 GiB each that allowed about 1.6 TiB of personal video, and once the free-disk floor was reached every team's upload would answer `507 STORAGE_FULL` until the operator cleaned up. The server-wide personal pool keeps the team uploads their space, a member who joined through an invite has little need for personal video before the KVKK review, and `python -m app.admin storage --top` lists the largest personal users for the operator (S7). Test: 30 accounts created through one invite fill the personal pool, and a team upload still succeeds.

---

## 7. Statistics

### 7.1 Principles

- Only user-verified decisions count; a model proposal never counts. Demo jobs never count.
- Unknown is `{"value": null, "status": "UNKNOWN"}`, never 0. A positive lower bound is PARTIAL. MEASURED means complete. Every total uses `report._value`.
- A point reaches a person only through a SCORER decision (side and jersey) plus a row of that side's roster, confirmed at its current version, for that jersey.
- A team's numbers come only from its own side results (6.2).
- No ranking, no composite score, no prediction, no percentage without its counts.
- `app/stats.py` is a pure function over side results and rosters under a thin route.

### 7.2 Scope and record states

**Team T:** ACCEPTED and SOLO records with a side whose `team_id = T`. **Person U:** those records where U has a roster row; optional `teamId`.

| Record state for team T | Condition | Counted for the team | Counted for persons |
|---|---|---|---|
| `COUNTED` | T's side result exists | yes, from T's side result | yes, where the own side's roster is confirmed at its current version |
| `COUNTED`, `changedSinceCounted: true` | the job the side result came from still exists and its revision moved | yes, the side result | same |
| `NOT_CONFIRMED` | no side result for T, and a job of the record has a confirmed list or the record holds a result offer for T | no | no |
| `IN_REVIEW` | a live video exists, no job has a confirmed list | no | no |
| `NO_VIDEO` | no live video and no side result for T | no | no |
| `ROSTER_WAITING` | person view only: the own side's roster is not confirmed at its current version | | no |

Each line also carries `otherSide: CONFIRMED | NOT_CONFIRMED | LEFT | NONE` (NONE for SOLO), which the app shows and which never changes T's numbers.

Totals are complete when every record of T with a live video is COUNTED for T. A record without a video does not make a total partial, because the totals describe filmed and confirmed matches, and the field names say so.

### 7.3 Fields (v2, kept)

**Team** (per part of the side result, then summed), reported twice: `inMatches` (parts of kind MATCH) and `outsideMatches` (AFTER_MATCH and UNFINISHED; a STOPPED part cannot occur in a confirmed list):

| Field | From |
|---|---|
| `countedMatches`, `won`, `lost` | MATCH parts and their `winner` (inMatches only) |
| `setsWon`, `setsLost` | the part's `setsWon` |
| `pointsFor`, `pointsAgainst` | the part's `teams.<own>.points` and `teams.<other>.points` |
| `attackPoints`, `blockPoints`, `aces`, `opponentErrors` | the part's `teams.<own>.*`; complete only when the part's field is MEASURED |

**Person**: `rosteredRecords` (a plain count; not "matches played", because the video does not show who was on court), `teamCountedMatches`, `teamWon`, `teamLost` ("while you were on the roster"), and `points`, `attackPoints`, `blockPoints`, `aces` from the own `players[]` line of the own side result. A person's count in a part is complete when the part's `teams.<side>.attackPoints` is MEASURED and `unattributed.<side>` equals `teams.<side>.opponentErrors.value`. A rostered person without a `players[]` line in a complete part scored 0, MEASURED. In an incomplete part that 0 becomes UNKNOWN.

**Not counted:** personal and legacy matches, demo jobs, unconfirmed results, the other side's result, opponent errors per person, serves, receptions, digs, assists, rotations, time on court, win rates, rankings.

Every statistics answer carries `records` (accepted, withVideo, counted, notConfirmed, inReview, withoutVideo) and `lines[]`, one per record with its state, `otherSide` and per-part values, so the app links each line to its report.

### 7.4 What one captain can and cannot do to the other team's numbers

| Action of captain A | Effect on team B's statistics |
|---|---|
| edits, reopens, corrections, re-analysis, rule change on A's video | none; B's side result stays; B's line may show `changedSinceCounted` if it came from A's job |
| deletes A's video, leaves the match, deletes team A, deletes the account | none on a result B already confirmed. If B has not confirmed yet, B can still confirm the result offer that A's last list confirmation stored for B (6.2, R7). Only a team whose list A never confirmed has nothing to confirm. |
| never confirms, never reviews, never uploads | none on B's own video and result; if only A filmed, B has nothing to confirm until A's list is confirmed |
| edits B's roster or B's side result | impossible: `403 CAPTAIN_ONLY` |
| declines B's request, or never answers it (EXPIRED after 7 days) | B's recording waits on B's phone; B taps Yalnız Takımım İçin Kaydet, which turns the record into a SOLO record that counts for B (5.4), or sends a new request (R1) |
| as AWAY, cancels an ACCEPTED record | the cancel is A's leave; the record stays for B, and B uploads without an opponent check (5.3, R1) |
| accepts B's request link that leaked to A | before the KVKK review every team is one of the owner's own teams (0.4). After it, B's own upload waits until B confirms the opponent (5.3); B taps Rakibim Değil, which leaves the record and moves B's recording to a SOLO record; A keeps only A's own video (R1) |

The rows on declining, cancelling and a leaked link rest on SOLO records (owner decision 16.1 item 7, default yes). If the owner answers no, a requesting team's recording counts only through a request that the other captain accepts (16.2).

---

## 8. Data model

### 8.1 Rules from `backend/app/db.py` (v2, kept)

- New tables go in `SCHEMA` with `CREATE TABLE IF NOT EXISTS`. Columns added to existing tables go both in `SCHEMA` and in `ADDED_COLUMNS`; a NOT NULL one needs a DEFAULT.
- No comment inside a `CREATE TABLE` contains a comma (db.py lines 15-17).
- Columns added to existing tables carry no `REFERENCES`, `UNIQUE` or index that a rebuild test would have to drop. The migration test drops the new indexes first and the new columns second. The code keeps their integrity in the same transaction.
- The append-only tables (`rally_proposals`, `review_decisions`, `rally_corrections`) get no column, index or trigger change.
- Every connection keeps `foreign_keys=ON` and `secure_delete=ON`. Every deletion ends with `empty_write_ahead_log()`.
- New in v3: every write transaction goes through the FIFO ticket lock inside `connector()` (2.8).

### 8.2 New tables

```sql
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  -- Lowercase ASCII exactly as stored. Login compares it exactly.
  username TEXT NOT NULL UNIQUE,
  display_name TEXT NOT NULL,
  -- scrypt$32768$8$3$salt$key with salt and key in base64url
  password_hash TEXT NOT NULL,
  created_at TEXT NOT NULL,
  password_changed_at TEXT NOT NULL,
  -- How the account opened: INVITE or SIGNUP_CODE or CAPTAIN_CODE or OPEN. It sets the personal video quota.
  signup_via TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
  -- SHA-256 hex of the bearer token. The token itself is never stored.
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  last_used_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);

-- Phones that logged in successfully (3.2). A login with a valid device token skips the username throttle keys.
CREATE TABLE IF NOT EXISTS login_devices (
  -- SHA-256 hex of the device token. The token itself is never stored.
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  last_used_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS login_devices_user ON login_devices(user_id);

-- One-time codes the operator prints
CREATE TABLE IF NOT EXISTS operator_codes (
  -- SHA-256 hex of the code
  code_hash TEXT PRIMARY KEY,
  -- SIGNUP opens one registration. CAPTAIN opens one registration and allows one team.
  kind TEXT NOT NULL,
  -- 1 once a registration used a CAPTAIN code
  signup_used INTEGER NOT NULL DEFAULT 0,
  -- The account that registered with this CAPTAIN code. Only that account may create the team.
  used_by_user_id TEXT REFERENCES users(id) ON DELETE CASCADE,
  -- The team name a CAPTAIN code allows. Compared after NFC and casefold.
  team_name TEXT,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS teams (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  -- Four characters of the code alphabet that the server draws at random
  tag TEXT NOT NULL UNIQUE,
  -- The most members the team holds with the captain counted. The captain sets 2 to 50.
  capacity INTEGER NOT NULL DEFAULT 14,
  -- 1 for Monday to 7 for Sunday or NULL
  match_weekday INTEGER,
  -- HH:MM or NULL
  match_time TEXT,
  -- Lossy WebP of 256 px for members or NULL
  logo BLOB,
  -- Lossy WebP of 128 px for link views or NULL
  logo_small BLOB,
  logo_version INTEGER NOT NULL DEFAULT 0,
  -- The queue number the next claim takes. Only a write transaction moves it.
  queue_seq INTEGER NOT NULL DEFAULT 0,
  -- Moves by one in every write transaction that changes the join state of the team. The snapshot carries it.
  queue_version INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS memberships (
  team_id TEXT NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  -- CAPTAIN or MEMBER
  role TEXT NOT NULL DEFAULT 'MEMBER',
  -- 1 to 99 or NULL
  jersey INTEGER,
  joined_at TEXT NOT NULL,
  -- The captain's mark for the new-member badge. Members who joined later count as new.
  joins_seen_at TEXT,
  PRIMARY KEY (team_id, user_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS memberships_one_captain ON memberships(team_id) WHERE role = 'CAPTAIN';
CREATE UNIQUE INDEX IF NOT EXISTS memberships_jersey ON memberships(team_id, jersey) WHERE jersey IS NOT NULL;
CREATE INDEX IF NOT EXISTS memberships_user ON memberships(user_id);

-- People a captain removed. They cannot claim a seat of this team until the captain lifts the block.
CREATE TABLE IF NOT EXISTS team_blocks (
  team_id TEXT NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  PRIMARY KEY (team_id, user_id)
);

-- A link a captain shares: a team invite or a match request. A closed link stays 30 days as a tombstone.
CREATE TABLE IF NOT EXISTS share_links (
  -- SHA-256 hex of the token. The token itself is never stored.
  token_hash TEXT PRIMARY KEY,
  -- HMAC-SHA256 hex of the normalized code under the link key. NULL once closed.
  code_hmac TEXT UNIQUE,
  -- The same HMAC once the link closed. Kept for the 30 days of the tombstone so a code can say why it no longer works.
  closed_code_hmac TEXT,
  -- TEAM_INVITE or MATCH_REQUEST
  kind TEXT NOT NULL,
  -- The team that invites or the team that requests the match
  team_id TEXT NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
  -- The requested match record for MATCH_REQUEST and NULL for TEAM_INVITE
  match_id TEXT REFERENCES matches(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  -- Server clock in seconds since 1970
  expires_at REAL NOT NULL,
  -- Registrations this invite opened
  signups INTEGER NOT NULL DEFAULT 0,
  -- For a renewed or closed invite the arrival of the request that closed it. Otherwise the time it closed.
  closed_at REAL,
  -- EXPIRED or RENEWED or CLOSED or FULL or USED or DECLINED or CANCELLED
  closed_reason TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS share_links_one_invite ON share_links(team_id)
  WHERE kind = 'TEAM_INVITE' AND closed_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS share_links_one_request ON share_links(match_id)
  WHERE match_id IS NOT NULL AND closed_at IS NULL;
CREATE INDEX IF NOT EXISTS share_links_closed ON share_links(closed_at) WHERE closed_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS share_links_closed_code ON share_links(closed_code_hmac) WHERE closed_code_hmac IS NOT NULL;

-- One person's try to join one team through its invite (docs/API_V1.md section 10)
CREATE TABLE IF NOT EXISTS join_attempts (
  id TEXT PRIMARY KEY,
  team_id TEXT NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  -- WAITING or OFFERED or HOLDING while open. JOINED or REJECTED or EXPIRED or SKIPPED or DROPPED or LEFT or CLOSED once closed.
  state TEXT NOT NULL,
  -- NO_DECISION or TIME_UP for EXPIRED. TEAM_FULL or LINK_RENEWED or LINK_CLOSED or LINK_EXPIRED or TEAM_LIMIT for CLOSED.
  reason TEXT,
  -- Place in the queue of the team in the order the claims reached the server
  queue_no INTEGER NOT NULL,
  -- Every time below is server clock in seconds since 1970. arrived_at is the claim's arrival stamp.
  arrived_at REAL NOT NULL,
  -- The last heartbeat of a waiting person as the clock flushed it
  seen_at REAL NOT NULL,
  -- When the seat was offered or when pause recovery last moved the offer deadline
  offered_at REAL,
  offer_deadline REAL,
  hold_started_at REAL,
  expires_at REAL,
  -- expires_at plus the 2 s grace. Only pause recovery moves it later.
  grace_until REAL,
  -- When the clock must look at this attempt again. NULL once closed.
  due_at REAL,
  closed_at REAL,
  -- The first 16 hex characters of the hash of the session that created the attempt
  opened_session TEXT NOT NULL,
  -- 1 once a second session of the same person claimed the attempt
  other_session INTEGER NOT NULL DEFAULT 0,
  -- 1 once a TIMEOUT arrived while HOLDING
  timeout_reported INTEGER NOT NULL DEFAULT 0,
  -- The first 16 hex characters of the hash of the session that decided or left
  decided_session TEXT,
  -- The arrival of an ACCEPT that came after the attempt had closed
  late_accept_at REAL
);
CREATE UNIQUE INDEX IF NOT EXISTS join_attempts_one_open ON join_attempts(team_id, user_id)
  WHERE state IN ('WAITING', 'OFFERED', 'HOLDING');
CREATE INDEX IF NOT EXISTS join_attempts_queue ON join_attempts(team_id, state, queue_no);
CREATE INDEX IF NOT EXISTS join_attempts_due ON join_attempts(due_at) WHERE due_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS join_attempts_user ON join_attempts(user_id, state);

-- The sides of a team match record. A side that left or whose team was deleted keeps its row with team_id NULL.
CREATE TABLE IF NOT EXISTS match_sides (
  match_id TEXT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
  -- HOME for the team that requested and AWAY for the team that accepted
  side TEXT NOT NULL,
  team_id TEXT REFERENCES teams(id) ON DELETE SET NULL,
  -- When this side's captain confirmed the opponent. Accepting counts for AWAY.
  opponent_checked_at TEXT,
  -- Moves with every change of this side's roster
  roster_version INTEGER NOT NULL DEFAULT 0,
  -- The roster_version the side's captain confirmed or NULL
  roster_confirmed_version INTEGER,
  roster_confirmed_at TEXT,
  left_at TEXT,
  PRIMARY KEY (match_id, side)
);
CREATE INDEX IF NOT EXISTS match_sides_team ON match_sides(team_id);

-- Who played for one side of one team match record and with which jersey. The side's captain writes it.
CREATE TABLE IF NOT EXISTS match_rosters (
  match_id TEXT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
  side TEXT NOT NULL,
  -- 1 to 99
  jersey INTEGER NOT NULL,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  PRIMARY KEY (match_id, side, jersey),
  UNIQUE (match_id, user_id)
);

-- One side's confirmed result: the report of one job at one revision. That side's team statistics read only this row.
CREATE TABLE IF NOT EXISTS match_results (
  match_id TEXT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
  side TEXT NOT NULL,
  -- The job and revision the snapshot came from. No foreign key: the snapshot outlives the video and its jobs.
  job_id TEXT NOT NULL,
  revision INTEGER NOT NULL,
  -- The report as GET /v1/matches/{id}/report built it. Jersey numbers only and no names.
  report_json TEXT NOT NULL,
  confirmed_at TEXT NOT NULL,
  PRIMARY KEY (match_id, side)
);

-- The report that a side's reviewer confirmed as a list. Offered to every other side without a side result (6.2).
CREATE TABLE IF NOT EXISTS match_result_offers (
  match_id TEXT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
  -- The side the offer is for
  side TEXT NOT NULL,
  -- No foreign key: the offer outlives the video and its jobs.
  job_id TEXT NOT NULL,
  revision INTEGER NOT NULL,
  -- Jersey numbers only and no names
  report_json TEXT NOT NULL,
  offered_at TEXT NOT NULL,
  PRIMARY KEY (match_id, side)
);
```

### 8.3 New columns on existing tables

In `SCHEMA` and in `ADDED_COLUMNS`, none with a foreign key:

| Table | Column | Type / default | Meaning |
|---|---|---|---|
| `matches` | `status` | `TEXT NOT NULL DEFAULT 'LEGACY'` | `LEGACY`, `PERSONAL`, `PENDING`, `ACCEPTED`, `SOLO`, `DECLINED`, `CANCELLED`, `EXPIRED`. The default marks every row stored before accounts. |
| `matches` | `owner_user_id` | `TEXT` | the owner of a PERSONAL match, NULL otherwise |
| `matches` | `start_time` | `TEXT` | `HH:MM` of a team match |
| `matches` | `closed_at` | `TEXT` | when a request was accepted, declined, cancelled or expired |
| `matches` | `declined_by` | `TEXT` | the declining team's name and tag (`Güney Gençlik #M4XR`) |
| `matches` | `sibling_of` | `TEXT` | the ACCEPTED record a sibling was created from; at most 3 siblings per record (5.5, R1) |
| `uploads` | `side` | `TEXT` | `HOME` or `AWAY` for a team record, NULL otherwise. Names the video's reviewer. |
| `uploads` | `touched_at` | `REAL` | the last `initiate_upload` or stored chunk; the sweep aborts uploads idle for 24 h |

Indexes: `matches_owner ON matches(owner_user_id) WHERE owner_user_id IS NOT NULL`, `matches_status ON matches(status)` and `uploads_touched ON uploads(touched_at) WHERE status = 'UPLOADING'`.

`matches.home_team` and `away_team` keep the team names as they were at acceptance (and the typed opponent name of a SOLO record), so a renamed or deleted team still shows by its old name in the match.

v2's `analysis_jobs.home_confirmed_revision` and `away_confirmed_revision` are not added; `match_results` per side replaces them.

### 8.4 The audit without touching append-only tables (v2, kept)

Only the reviewer of a video writes decisions and corrections on its jobs, and a job's rows die with its video. The acting side of every audit row is therefore `uploads.side` of the job's video. A rule change records nothing per row today and keeps that.

### 8.5 Not stored

| Not stored | Why |
|---|---|
| e-mail, phone, birth date, photo of a person, real name | display names are free text the user chooses |
| IP addresses | the access log is off; the throttle keeps keys in memory only |
| link tokens, link codes, session tokens, operator codes in clear | SHA-256 or HMAC only (P8) |
| who is waiting, as seen by anyone but the server | the captain gets counts only |
| device name or id | the attempt keeps a 16-character prefix of a session hash for 24 h at most; a device token is a random value stored as a hash (3.2), not an identifier of the phone |
| user ids in append-only tables | 8.4 |
| precomputed totals | only side results; totals are computed on read |

**In memory only** (lost on restart, rebuilt from the database): team snapshots, the session cache, heartbeats not yet flushed, the in-flight entries, the throttle and rate keys, the FIFO lock, the beat stamps, the recovery flag and the silence state.

### 8.6 Migration (v2, kept)

| Data | Migration |
|---|---|
| `matches` rows from before accounts | `status = 'LEGACY'`; nobody sees them; every route answers `403 NOT_A_MEMBER`. |
| assigning them | `python -m app.admin legacy --list`, `--assign <username>` (they become PERSONAL of that user) and `--delete`. No claim endpoint exists, because a match id is not a secret. The server holds test data only today. |
| `review_decisions`, `rally_corrections` | unchanged |
| phone data | 10.3 |

Migration test: build pre-accounts storage by dropping the new indexes, then the new columns and tables, run `migrate()`, assert `LEGACY`. The three existing rebuild tests keep passing.

### 8.7 Retention

| Data | Kept until |
|---|---|
| closed join attempts | 24 h after closing (minute sweep) |
| live share links | renewal, closing, use, team full or expiry |
| share link tombstones (token hash, code HMAC, team, reason) | 30 days after closing |
| PENDING requests | EXPIRED after 7 days; closed requests deleted 30 days after closing |
| sessions | logout, password change or expiry |
| device tokens (`login_devices`) | 180 days after the last use, a password change, an operator reset or account deletion |
| result offers | that side confirms a result, that side leaves, or the record is deleted |
| operator codes | use or expiry |
| team blocks | the captain lifts the block, or the team or the account is deleted |
| logo | replaced, removed or team deleted |
| unfinished uploads | 24 h after the last chunk |
| accounts, teams, team records, videos, side results | until their owners delete them, a side leaves, or the team is deleted (the KVKK review sets periods) |
| backups (B10) | 7 days (16.1 item 10) |

The minute sweep ends with `empty_write_ahead_log()` when it deleted anything.

---

## 9. API 0.8

### 9.1 Conventions

- Root `/v1`. API_V1.md gains section 10 *Hesap, takım ve katılma*, section 11 *Takım maçı*, section 12 *İstatistik* and the new error codes in section 8. API.md gains the `Authorization` header and the 0.8 paragraph. Both change with the code.
- Errors use the existing envelope through `errors.fail`; a 401 also sets `WWW-Authenticate: Bearer`.
- Every new input model has `extra="forbid"`. JSON bodies stay under the 64 KiB `BodyLimit`.
- Every route except `GET /v1/health`, `POST /v1/auth/register`, `POST /v1/auth/login`, `POST /v1/links/resolve` and the B6a static pages needs a session. A route-walk test fails when a route outside that list answers without one.
- An outermost ASGI middleware stamps every request's arrival (2.9). Every join answer (claim, ack, decision, poll, live view) carries `waitedMs` and `serverTime`.
- Times the phone computes with are integers in milliseconds (`remainingMs`, `waitedMs`, `serverTime`). Other times are ISO strings as today.
- `GET /v1/health` adds `"accounts": {"signup": "LINK" | "OPEN", "teams": "OPERATOR" | "OPEN"}` and `"links": {"publicUrl": true | false, "codeLength": 8}`.
- Every 429 carries `details.scope` (3.6). `BodyLimit` answers `408 BODY_TIMEOUT` to an ack or decision body that takes longer than 5 s (2.9). An older server lacks both, and the app then shows `server_older`.
- `docs/openapi-local-dev.json` is regenerated and `test_openapi.py` lists every new route.

### 9.2 Endpoints

**Accounts (B1)**

| Method and path | Body | Success | Errors |
|---|---|---|---|
| `POST /v1/auth/register` | `{username, displayName, password, link?: {token} \| {code}, operatorCode?}` | `201 {user, session, deviceToken}` | `403 SIGNUP_CLOSED`, `404 LINK_NOT_FOUND`, `410 LINK_CLOSED`, `422 WRONG_LINK_KIND`, `403 SIGNUP_LINK_LIMIT`, `403 SIGNUP_CODE_INVALID`, `409 USERNAME_TAKEN`, `422 USERNAME_INVALID`, `422 DISPLAY_NAME_INVALID`, `422 PASSWORD_REJECTED`, `429 TOO_MANY_ATTEMPTS`, `503 SERVER_BUSY` |
| `POST /v1/auth/login` | `{username, password, deviceToken?}` | `200 {user, session, deviceToken}` | `401 LOGIN_FAILED`, `429` (`details.scope` DEVICE, USER or SOURCE), `503` |
| `POST /v1/auth/logout` | `{}` | `204` | |
| `GET /v1/me` | | `200 {user, teams[]}` | `401 AUTH_REQUIRED` |
| `PUT /v1/me/display-name` | `{displayName}` | `200 {user}` | `422` |
| `PUT /v1/me/password` | `{currentPassword, newPassword}` | `200 {user}` | `403 PASSWORD_WRONG`, `422`, `429`, `503` |
| `POST /v1/me/delete` | `{password}` | `204` | `403 PASSWORD_WRONG`, `409 CAPTAIN_MUST_TRANSFER`, `429`, `503` |

**Teams (B4)**

| Method and path | Body | Success | Errors |
|---|---|---|---|
| `GET /v1/teams` | | `200 {teams[]}` with `newJoins` for captains | |
| `PUT /v1/teams/{teamId}` | `{name, capacity?, schedule?, jersey?, captainCode?}` | `201` Team with the new invite's token, code and URL / `200` Team | `403 CAPTAIN_CODE_REQUIRED`, `403 CAPTAIN_CODE_INVALID`, `409 TEAM_ID_TAKEN`, `409 TEAM_LIMIT_REACHED`, `422 TEAM_ID_INVALID`, `422 NAME_INVALID` |
| `GET /v1/teams/{teamId}` | | `200` Team | `403 NOT_A_MEMBER`, `404 TEAM_NOT_FOUND` |
| `PUT /v1/teams/{teamId}/settings` | `{name, capacity, schedule}` | `200` Team | `403 CAPTAIN_ONLY`, `409 CAPACITY_BELOW_MEMBERS`, `409 CAPACITY_IN_USE`, `422` |
| `PUT /v1/teams/{teamId}/logo` | `{large, small}` (base64 WebP) | `200 {logo: {version}}` | `403`, `413 PAYLOAD_TOO_LARGE`, `422 LOGO_INVALID` |
| `DELETE /v1/teams/{teamId}/logo` | | `204` | `403` |
| `GET /v1/teams/{teamId}/logo?size=large\|small` | | `200 image/webp` | `403`, `404 LOGO_NOT_FOUND` |
| `PUT /v1/teams/{teamId}/members/{userId}/jersey` | `{jersey: 1..99 \| null}` | `200` Member | `403`, `409 JERSEY_TAKEN`, `422` |
| `DELETE /v1/teams/{teamId}/members/{userId}?renewInvite=` | | `204`; with `renewInvite=true` `200 {invite}` with the new token, code and URL (a captain's removal also blocks; 4.3) | `403`, `404 MEMBER_NOT_FOUND`, `409 CAPTAIN_MUST_TRANSFER` |
| `GET /v1/teams/{teamId}/blocks` | | `200 {blocked[]}` | `403 CAPTAIN_ONLY` |
| `DELETE /v1/teams/{teamId}/blocks/{userId}` | | `204` | `403 CAPTAIN_ONLY` |
| `POST /v1/teams/{teamId}/joins-seen` | `{upTo}` | `204` | `403 CAPTAIN_ONLY` |
| `POST /v1/teams/{teamId}/captain` | `{userId}` | `200` Team | `403 CAPTAIN_ONLY`, `409 CAPTAIN_CONFLICT`, `422 NOT_A_TEAM_MEMBER` |
| `DELETE /v1/teams/{teamId}` | | `204` | `403 CAPTAIN_ONLY` |

**Invites and joining (B5)**

| Method and path | Body | Success | Errors |
|---|---|---|---|
| `GET /v1/teams/{teamId}/invite` | | `200 {state, closedReason, expiresAt, seats}` (no token, no code) | `403 CAPTAIN_ONLY`, `404 INVITE_NOT_FOUND` |
| `POST /v1/teams/{teamId}/invite` | `{}` | `201` Invite with token, code and URL (closes the live one as RENEWED, T14) | `403 CAPTAIN_ONLY` |
| `DELETE /v1/teams/{teamId}/invite` | | `204` (T14, reason CLOSED) | `403 CAPTAIN_ONLY` |
| `GET /v1/teams/{teamId}/live?view=&wait=` | | `200 {seats, invite, newJoins, view, waitedMs, serverTime}` | `403 CAPTAIN_ONLY` |
| `POST /v1/links/resolve` | `{token} \| {code}`, `knownLogoVersion?` | `200` LinkView (session optional; 9.3) | `404 LINK_NOT_FOUND`, `410 LINK_CLOSED`, `422`, `429` |
| `POST /v1/join-attempts` | `{link: {token} \| {code}}` | `201` new / `200` existing Attempt | `404 LINK_NOT_FOUND`, `410 LINK_CLOSED`, `403 JOIN_BLOCKED`, `409 ALREADY_MEMBER` (`details.teamId`), `409 TEAM_FULL`, `409 QUEUE_FULL`, `409 ATTEMPT_LIMIT`, `409 TEAM_LIMIT_REACHED`, `422 WRONG_LINK_KIND`, `429` |
| `GET /v1/join-attempts/{id}?view=&wait=` | | `200` Attempt (long-poll; its arrival is the heartbeat). Over a cap: `200` at once with `pollLimited: true` and `Retry-After` (2.7) | `403 NOT_YOUR_ATTEMPT`, `404 ATTEMPT_NOT_FOUND` |
| `POST /v1/join-attempts/{id}/ack` | `{}` | `200` Attempt (HOLDING) | `403`, `404`, `408 BODY_TIMEOUT`, `409 NOT_OFFERED`, `409 ATTEMPT_CLOSED` (`details.state`, `details.reason`; SKIPPED by T6b with `details.offerWindowS`), `429` |
| `POST /v1/join-attempts/{id}/decision` | `{decision: "ACCEPT" \| "REJECT" \| "TIMEOUT"}` | `200` Attempt | `403`, `404`, `408 BODY_TIMEOUT`, `409 NOT_DECIDING` (`details.state`: WAITING or OFFERED), `409 ATTEMPT_CLOSED` (`details.state`, `details.reason`, `details.lateAccept`, `details.acceptArrivedInTime`), `409 TEAM_LIMIT_REACHED`, `429` |
| `DELETE /v1/join-attempts/{id}` | | `204` (T9 or T11; a closed attempt also answers 204) | `403`, `404`, `429` |

`wait` is 0 to 15 seconds, default 15. `view` absent means "answer at once". The phone's read timeout is `wait` + 3 s (2.6).

**Match requests and team records (B7)**

| Method and path | Body | Success | Errors |
|---|---|---|---|
| `PUT /v1/match-requests/{matchId}` | 5.1, or `{siblingOf}` for a sibling of an ACCEPTED record (5.5) | `201` / `200 {match, link}`; a sibling is ACCEPTED at once and has no link | `403 CAPTAIN_ONLY`, `409 SIBLING_LIMIT`, `422 MATCH_ID_INVALID`, `422 VALIDATION_ERROR`, `429` |
| `POST /v1/match-requests/accept` | `{link, teamId}` | `200` Match | 5.2 table |
| `POST /v1/match-requests/decline` | `{link, teamId}` | `200 {status: "DECLINED"}` | `403 CAPTAIN_ONLY`, `404 LINK_NOT_FOUND`, `410 LINK_CLOSED`, `409 REQUEST_CLOSED` |
| `POST /v1/matches/{id}/opponent/confirm` | `{}` | `200` Match | `403 CAPTAIN_ONLY`, `409 MATCH_NOT_CONFIRMED` |
| `PUT /v1/solo-matches/{matchId}` | 5.7 | `201` / `200` Match | `403 CAPTAIN_ONLY`, `422 MATCH_ID_INVALID`, `422 VALIDATION_ERROR` |
| `GET /v1/matches?scope=team\|personal\|all&status=&limit=&cursor=` | | `200 {matches[], nextCursor}` | |
| `POST /v1/matches/{id}/cancel` | `{}` | `200` Match (AWAY's cancel on an ACCEPTED record is AWAY's leave) | `403 CAPTAIN_ONLY`, `409 REQUEST_CLOSED`, `409 MATCH_HAS_CONTENT` |
| `POST /v1/matches/{id}/solo` | `{opponentName}` | `200` Match (SOLO, 5.4) | `403 CAPTAIN_ONLY`, `409 REQUEST_CLOSED`, `409 MATCH_HAS_CONTENT`, `422 VALIDATION_ERROR` |
| `POST /v1/matches/{id}/leave` | `{}` | `200` Match, or `204` when no side remains | `403 CAPTAIN_ONLY`, `409 MATCH_NOT_CONFIRMED` |

**Side results and rosters (B8)**

| Method and path | Body | Success | Errors |
|---|---|---|---|
| `GET /v1/matches/{id}/report?jobId=` | | `200` Report of that job, or of the newest job without `jobId` | `403 NOT_A_MEMBER`, `404 ANALYSIS_NOT_FOUND` (the job does not belong to this record, S4), `404 REPORT_NOT_AVAILABLE` |
| `POST /v1/matches/{id}/result/confirm` | `{jobId, revision, fromOffer?}` | `200` Match (writes the caller's side result, from a job or from the result offer for the caller's side, 6.2) | `403 CAPTAIN_ONLY`, `409 LIST_NOT_CONFIRMED`, `409 MOCK_RESULT`, `409 RESULT_STALE` (`details.currentRevision`) |
| `GET /v1/matches/{id}/roster` | | `200` Roster | `403 NOT_A_MEMBER` |
| `PUT /v1/matches/{id}/roster/{side}` | `{entries: [{jersey, userId}], expectedVersion}` | `200` Roster | `403 CAPTAIN_ONLY`, `409 ROSTER_STALE` (`details.currentVersion`), `422 ROSTER_INVALID` (`details.reason`: `TOO_MANY`, `DUPLICATE_JERSEY`, `DUPLICATE_PERSON`, `NOT_A_TEAM_MEMBER`, `ON_OTHER_SIDE`, `JERSEY_RANGE`) |
| `POST /v1/matches/{id}/roster/{side}/confirm` | `{version}` | `200` Roster | `403 CAPTAIN_ONLY`, `409 ROSTER_STALE` |
| `POST /v1/matches/{id}/roster/leave` | `{}` | `200` Roster | `403 NOT_ON_ROSTER` |

**Statistics (B9)**

| Method and path | Success | Errors |
|---|---|---|
| `GET /v1/teams/{teamId}/stats` | `200` TeamStats | `403 NOT_A_MEMBER` |
| `GET /v1/me/stats?teamId=` | `200` PersonStats | `403 NOT_A_MEMBER` (teamId) |

**Exposure (B6a, static, no session)**

| Path | Content |
|---|---|
| `GET /.well-known/assetlinks.json` | package name and `VOLLEYSTAT_ANDROID_CERT_SHA256`, when the API host is the link host |
| `GET /j/`, `GET /m/`, `GET /static/link.js` | the fallback pages of 2.2 and their one external script, with `Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'` (S6) |

### 9.3 Main shapes

**Attempt** (every join call and every long-poll answer; about 0.5-1 KB, no logo bytes):

```json
{"id": "8b1c…", "state": "WAITING", "reason": null,
 "team": {"id": "6f0c…", "name": "Kuzey Spor", "tag": "K7Q2", "logoVersion": 3,
          "schedule": {"weekday": 2, "time": "20:00"}},
 "seats": {"capacity": 11, "members": 10, "left": 1, "deciding": 1},
 "queue": {"ahead": 0},
 "offer": null,
 "decision": null,
 "outcome": null,
 "link": {"live": true},
 "view": "W.0.1.1.L",
 "waitedMs": 3,
 "serverTime": 1763320000123}
```

- OFFERED: `"offer": {"remainingMs": 7990}`; the phone acknowledges at once with `POST …/ack`.
- HOLDING: `"queue": null`, `"decision": {"remainingMs": 9984}`.
- Closed: `state` and `reason` give the outcome; `"outcome": {"decidedHere": true | false | null, "lateAccept": false, "acceptArrivedInTime": false}` (T17), plus `"offerWindowS": 8` for SKIPPED; `seats` and `link.live` are still current, so the app knows whether to offer Yeniden Sıraya Gir. `link.live` is true while the team's live invite is the one this attempt came through (2.12 C).
- A poll over a cap carries `"pollLimited": true` (2.7).
- `seats.left` = capacity − members; `deciding` = offered + holding. The joiner's view carries no waiting total.
- `view` is opaque; the phone sends it back as `?view=`.

**LinkView** (`POST /v1/links/resolve`)

Without a session, or for a match request to a caller who captains no team (P5):

```json
{"kind": "TEAM_INVITE",
 "team": {"name": "Kuzey Spor", "tag": "K7Q2", "logo": {"version": 3, "small": "<base64 or null>"}}}
```

With a session, for an invite:

```json
{"kind": "TEAM_INVITE", "expiresAt": "2026-11-23T20:00:00Z",
 "team": {"id": "6f0c…", "name": "Kuzey Spor", "tag": "K7Q2", "logo": {"version": 3, "small": null},
          "schedule": {"weekday": 2, "time": "20:00"}},
 "seats": {"capacity": 11, "members": 10, "left": 1, "deciding": 1},
 "availability": "QUEUE",
 "me": {"member": false, "blocked": false, "attempt": null}}
```

`availability` is `OPEN` (a free seat and no queue), `QUEUE` or `FULL`. For a match request and a caller who captains a team:

```json
{"kind": "MATCH_REQUEST", "expiresAt": "…",
 "match": {"id": "c3…", "playedOn": "2026-11-22", "startTime": "14:00", "venue": "Okul Spor Salonu",
           "name": null, "kind": "TRAINING", "rules": {"…": "…"}},
 "requestingTeam": {"name": "Kuzey Spor", "tag": "K7Q2", "logo": {"version": 3, "small": null}},
 "myTeams": [{"id": "9d1e…", "name": "Güney Gençlik", "tag": "M4XR", "eligible": true, "reason": null}]}
```

A closed link answers `410 LINK_CLOSED` with `details: {"reason": "FULL", "kind": "TEAM_INVITE", "teamName": "Kuzey Spor"}`.

**Invite** (captain; token, code and URL only in the creation answer): `{"state": "LIVE", "closedReason": null, "expiresAt": "…", "seats": {…}, "token": "…", "code": "K7QF2M9X", "url": "https://…/j/#t=…&c=K7QF2M9X" | null}`.

**Team**

```json
{"id": "6f0c…", "name": "Kuzey Spor", "tag": "K7Q2", "capacity": 14,
 "schedule": {"weekday": 2, "time": "20:00"}, "logo": {"version": 3},
 "myRole": "CAPTAIN", "myJersey": 7, "newJoins": 2,
 "members": [{"userId": "a1…", "displayName": "Deniz", "jersey": 7, "role": "CAPTAIN", "joinedAt": "…"}],
 "seats": {"capacity": 14, "members": 9, "left": 5, "deciding": 1, "waiting": 0},
 "invite": {"state": "LIVE", "closedReason": null, "expiresAt": "…"}}
```

`invite`, `newJoins` and `seats.waiting` are for the captain only, else null.

**Match** (a team record; the old fields stay, and `status` carries the real state instead of the constant `"DRAFT"` that no client reads):

```json
{"id": "c3…", "homeTeamName": "Kuzey Spor", "awayTeamName": "Güney Gençlik",
 "name": null, "playedOn": "2026-11-22", "startTime": "14:00", "venue": "Okul Spor Salonu",
 "kind": "TRAINING", "rules": {"…": "…"}, "status": "ACCEPTED",
 "sides": {"HOME": {"teamId": "6f0c…", "name": "Kuzey Spor", "tag": "K7Q2", "left": false, "opponentChecked": true,
                    "roster": {"version": 2, "confirmed": true},
                    "video": {"id": "v7…", "status": "READY"},
                    "analysis": {"jobId": "j2…", "state": "COMPLETE", "revision": 14, "listConfirmed": true, "mock": false},
                    "result": {"jobId": "j2…", "revision": 12, "confirmedAt": "…", "changedSinceCounted": true}},
           "AWAY": {"teamId": "9d1e…", "name": "Güney Gençlik", "tag": "M4XR", "left": false, "opponentChecked": true,
                    "roster": {"version": 0, "confirmed": false},
                    "video": null, "analysis": null,
                    "result": {"jobId": "j2…", "revision": 14, "confirmedAt": "…", "changedSinceCounted": false}}},
 "me": {"side": "HOME", "captain": true, "reviewerOf": ["v7…"], "readOnly": false, "canReadContent": true},
 "request": null}
```

- `request` is `{"expiresAt", "url", "code"}` for the HOME captain while PENDING, and only in the creation answer; else null.
- A SOLO record has `status: "SOLO"`, one side and `awayTeamName` = the typed opponent name.
- A personal match has `sides: null` and `me.owner: true`.
- The other team's members never appear in any of these shapes.
- With `me.canReadContent: false` the shape leaves out `video.id`, `analysis.jobId` and `result.jobId` (S4).
- A sibling record carries `"siblingOf": "<record id>"` (5.5).

**Roster** (the caller is on HOME):

```json
{"sides": {"HOME": {"version": 2, "confirmed": true,
                    "entries": [{"jersey": 7, "userId": "a1…", "displayName": "Deniz"}]},
           "AWAY": {"version": 1, "confirmed": false, "entries": [{"jersey": 4}, {"jersey": 9}]}}}
```

**The report does not change.** It stays jersey-level. The app joins names from the roster for its own side only.

**TeamStats** (PersonStats has the same frame with the person fields of 7.3):

```json
{"teamId": "6f0c…",
 "records": {"accepted": 10, "withVideo": 4, "counted": 3, "notConfirmed": 1, "inReview": 0, "withoutVideo": 6},
 "inMatches": {"countedMatches": {"value": 4, "status": "PARTIAL"}, "won": {"value": 3, "status": "PARTIAL"},
               "lost": {"…": "…"}, "setsWon": {"…": "…"}, "pointsFor": {"…": "…"}, "attackPoints": {"…": "…"}},
 "outsideMatches": {"setsWon": {"…": "…"}, "pointsFor": {"…": "…"}},
 "lines": [{"matchId": "c3…", "playedOn": "2026-11-22", "opponentName": "Güney Gençlik", "side": "HOME",
            "state": "COUNTED", "changedSinceCounted": false, "otherSide": "CONFIRMED",
            "parts": [{"number": 1, "kind": "MATCH", "result": "WON", "setsWon": 3, "setsLost": 1}]}]}
```

### 9.4 Changes to the existing operations

| Change | Detail |
|---|---|
| session on every operation except health | `401 AUTH_REQUIRED` |
| access check after the object is found | `404 <OBJECT>_NOT_FOUND` for an object that does not exist; `403 NOT_A_MEMBER` for one the caller may not see, including content outside the member read rule (6.1). The app reads 404 as "deleted" (`AppViewModel.removeMatch`), so a stranger never gets it. |
| `access.match_of(db, kind, id)` | resolves upload, video, job, card (or the decision row of a removed card) and rally to the match record and, for team records, to the side of the video |
| `PUT /v1/matches/{id}`, `POST /v1/matches` | create PERSONAL; an existing record answers only to a reader |
| `initiate_upload` | team record: 6.1 upload row; stores `uploads.side` and `touched_at`; quotas and the disk floor (6.5) for every upload |
| `store_chunk` | moves `touched_at`; refuses below half the disk floor |
| chunks, complete, abort, upload status; quality recheck; `request_analysis`; cancel | team record: the reviewer of that video only (`403 REVIEWER_ONLY`); a defensive check also in `jobs.request_analysis` |
| decisions, reopen, the five corrections, list confirm, `PUT …/rules` | team record: the reviewer of that video (rules: of either live video) |
| `decide` | the key lookup runs, then the access check on the stored row's match, then the replay |
| `DELETE /v1/videos/{id}` | team record: its reviewer at any time; every record: blank the upload row |
| `DELETE /v1/matches/{id}` | personal only; a team or SOLO record answers `409 TEAM_MATCH` |
| `match_payload` | real `status`, plus the fields of 9.3; no content ids without `canReadContent` (S4) |
| `GET /v1/matches/{id}/report` | optional `jobId`, resolved by `access.match_of`; `404 ANALYSIS_NOT_FOUND` unless the job belongs to `{id}`; then the content rule (6.1, S4) |
| `db.connector()` | FIFO ticket lock around every write transaction, taken before the connection opens and released in the outermost `finally` on every path; at most 30 s of waiting (2.8) |
| `BodyLimit` | `408 BODY_TIMEOUT` for ack and decision bodies slower than 5 s (2.9) |
| app factory | outermost middleware for arrival stamps and in-flight entries; the beat task; the queue clock next to the analysis worker; the notifier; the session cache |
| `__main__.py` | `access_log=False`, `forwarded_allow_ips=""`, the open-file limit (2.7); from B6a `--host`, `--trusted-proxy` and `--client-ip-header` (3.6) |

### 9.5 New error codes

| Group | Codes |
|---|---|
| Session and sign-up | `AUTH_REQUIRED`, `LOGIN_FAILED`, `PASSWORD_WRONG`, `PASSWORD_REJECTED`, `USERNAME_INVALID`, `USERNAME_TAKEN`, `DISPLAY_NAME_INVALID`, `SIGNUP_CLOSED`, `SIGNUP_LINK_LIMIT`, `SIGNUP_CODE_INVALID`, `TOO_MANY_ATTEMPTS`, `SERVER_BUSY`, `BODY_TIMEOUT` (408) |
| Access | `NOT_A_MEMBER`, `CAPTAIN_ONLY`, `REVIEWER_ONLY` |
| Teams | `TEAM_NOT_FOUND`, `TEAM_ID_INVALID`, `TEAM_ID_TAKEN`, `TEAM_LIMIT_REACHED`, `NAME_INVALID`, `CAPTAIN_CODE_REQUIRED`, `CAPTAIN_CODE_INVALID`, `MEMBER_NOT_FOUND`, `NOT_A_TEAM_MEMBER`, `JERSEY_TAKEN`, `CAPTAIN_MUST_TRANSFER`, `CAPTAIN_CONFLICT`, `CAPACITY_BELOW_MEMBERS`, `CAPACITY_IN_USE`, `LOGO_INVALID`, `LOGO_NOT_FOUND` |
| Links and joining | `LINK_NOT_FOUND`, `LINK_CLOSED` (410), `WRONG_LINK_KIND`, `INVITE_NOT_FOUND`, `ALREADY_MEMBER`, `JOIN_BLOCKED`, `TEAM_FULL`, `QUEUE_FULL`, `ATTEMPT_LIMIT`, `NOT_YOUR_ATTEMPT`, `ATTEMPT_NOT_FOUND`, `NOT_OFFERED`, `NOT_DECIDING`, `ATTEMPT_CLOSED` |
| Team matches | `REQUEST_CLOSED`, `SIBLING_LIMIT`, `SAME_TEAM`, `SAME_TEAM_NAME`, `SAME_PERSON_BOTH_SIDES`, `MATCH_NOT_CONFIRMED`, `OPPONENT_NOT_CHECKED`, `SIDE_HAS_VIDEO`, `MATCH_HAS_CONTENT`, `TEAM_MATCH`, `RESULT_STALE`, `LIST_NOT_CONFIRMED`, `MOCK_RESULT` |
| Storage | `QUOTA_EXCEEDED`, `STORAGE_FULL` (507) |
| Rosters | `ROSTER_STALE`, `ROSTER_INVALID`, `NOT_ON_ROSTER` |

Removed from v2: `MATCH_HAS_VIDEO` (now per side: `SIDE_HAS_VIDEO`), `RESULT_COUNTED` (video deletion never touches a side result), `SIGNUP_LINK_INVALID` (replaced by `LINK_NOT_FOUND`, `LINK_CLOSED` and `WRONG_LINK_KIND`). Removed in the revision: `POLL_LIMIT` (a poll over a cap answers at once instead, 2.7).

Status rules: only a lost session answers 401, so a wrong password on a logged-in action answers `403 PASSWORD_WRONG` and does not log the user out. `403 NOT_A_MEMBER` is never `404 MATCH_NOT_FOUND`.

### 9.6 Idempotency

| Operation | Mechanism |
|---|---|
| team create, match request, SOLO record | client UUID and PUT; a repeat answers 200 to readers, without link secrets |
| claim | one open attempt per person and team; a repeat returns it and never acknowledges |
| ack | arrival before the deadline; a repeat after HOLDING answers 200 |
| decision | repeating the reached outcome answers 200; anything else `409 ATTEMPT_CLOSED` |
| leave queue, accept, decline, cancel, opponent confirm, leave match, solo | state check; the reached state answers 200 (204 where the record is gone) |
| sibling record | client UUID and PUT, like a match request |
| roster edit and confirm, result confirm | `expectedVersion`, `version`, `revision` |
| analyses, decisions | unchanged keys, after the access check |
| register | not idempotent; a lost answer leads to `409 USERNAME_TAKEN`, and the app then logs in with the credentials it still holds in memory |

---

## 10. Android

### 10.1 Navigation

The start route (`VolleyStatApp.kt` line 132 at b2ce9b2) becomes: WELCOME until `welcomeSeen`; then, without a session, `start` while no account has ever logged in on this phone (`accountSeen` false) and LOGIN otherwise; else HOME. A pending share link overrides all three: the app opens `link` first and keeps the link in the encrypted pending store for 30 minutes (10.3), so a sign-up that the system kills on the way does not lose it. A newcomer who taps the installer's Aç button after a sideload reaches `start`, whose main action is Kod Gir, instead of a sign-up form that ends in `SIGNUP_CLOSED` (R6).

| Route | Screen | Bar |
|---|---|---|
| `start` | first start without an account: `first_start_body`, Kod Gir as the main button (with Panodaki Kodu Kullan when the clipboard yields one code), Giriş Yap as the secondary one (R6) | none |
| `login` | Giriş Yap: username, password; links Hesap Oluştur and Kod Gir; with a pending link the team card and `link_login_intro` on top; debug-only server choice | none |
| `register` | Hesap Oluştur: display name, username (suggested from the display name: Turkish letters folded to ASCII, lowercased, characters outside `[a-z0-9._]` dropped, a leading letter ensured, editable), password; the link or operator code it signs up with; with a pending link the team card. A phone on which no account has ever logged in opens this screen first when a link is pending, with Hesabım Var as the secondary action (F1). Without a pending link or code it asks for the code or the pasted message first (`register_code_first`), before name and password (R6). | none |
| `link` | landing: resolves, then routes to `join`, `request/open`, `register`, `login` or an error state (2.12 A) | none |
| `code` | Kod Gir: one field that accepts a typed code or a pasted WhatsApp message (F1, F7). A typed code ignores case, spaces and hyphens. Pasted text is read in this order (R2, fidelity V5): (1) a link-host URL with `#t=` gives the token, and the app resolves and claims by token, so no code lookup and no code throttle are involved; (2) otherwise a `c=` parameter, `[#&]c=([2-9A-HJKMNP-Z]{8})`, gives the code; (3) otherwise the case-sensitive pattern `(?<![0-9A-Za-z])[2-9A-HJKMNP-Z]{4}-?[2-9A-HJKMNP-Z]{4}(?![0-9A-Za-z])` (the code alphabet, without L), with hyphens removed and values de-duplicated, gives a code only when exactly one distinct value remains. Panodaki Kodu Kullan shows only when the clipboard yields exactly one. v3's pattern ignored case and allowed L, so on the code-only message it matched `lleyStat`, `uygulama` and `kaptanda` before the code, and the first match was the code in only 15.5 % of link messages (`verify_v3/paste_probe.py`). With the new order, 20 000 messages of each share text, the request text and a bare code always gave exactly the token or the code (`verify_v3_fix/extract_probe.py`). | none |
| `join/{teamId}` | host of the join flow: claim, waiting screen, pop-up dialog, outcomes (10.2) | none |
| `join/{teamId}/jersey` | Forma Numaran | none |
| `teams` (tab) | Takımlar: my teams with role chip, seat line and the new-member badge; Takım Kur; Kod Gir | tab |
| `team/{id}` | team: logo, name, tag, schedule, members; captain: invite card with live counts, Son katılanlar, member removal, Çıkarılanlar, Kaptanlığı Devret; Takımdan Ayrıl; team statistics card | none |
| `team/{id}/settings` | captain: name, capacity, schedule, logo | none |
| `teams/new` | Takım Kur: captain code (when the server says `teams: OPERATOR`), name, capacity, schedule, logo, my jersey; ends on the invite card with Paylaş | none |
| `roster/{id}` | today's `TeamRosterScreen` of a local roster, opened only from the personal match flow | none |
| `request/new` | Maç İsteği: my team (when captain of several), date, start time, venue, kind, then `MatchRulesCard`; İsteği Oluştur ends on Paylaş and, from New Match, continues to the recorder | none |
| `request/open` | an opened request: requesting team with tag, date, time, venue, rule; Hangi takımınla?; Kabul Et and Reddet | none |
| `tm/{id}` | team match: status, both sides with video, review and result states; captain actions (Rakibi Onayla, Rakibim Değil, Video Ekle, Sonucu Onayla, Maç Kadrosu, İsteği İptal Et, Maçı Takımımdan Kaldır; on a closed request with a held recording Yalnız Takımım İçin Kaydet and Aynı Rakiple Yeni İstek, R1) | none |
| `tm/{id}/report?job=` | the report of one job by server id, read-only, with the viewer's `result_changed_*` text when the own side result differs; reuses the stateless `ReportScreen` | none |
| `tm/{id}/roster/{side}` | Maç Kadrosu: team members with tick and jersey; Kadroyu Onayla; for a member "Bu Maçta Ben Yoktum" | none |
| `me/stats` | Kişisel İstatistik | none |

Existing tabs:
- **Home** lists pending requests, accepted requests whose opponent the captain has not confirmed, recordings waiting for acceptance, team matches waiting for the captain's result confirmation, and the personal flow as today.
- **New Match** asks a captain "Takım maçı mı, kişisel maç mı?". A team match continues with "Hangi maç?": the own team's accepted records and pending requests without a video of the own side, Yeni İstek, and (default yes, 5.7) Rakip uygulamada değil. Then today's `new/video`, `new/record` and `new/check` screens run unchanged, with the record's names and rule (F5). A member sees `new_match_member` and the personal flow.
- **Profile** gains display name, Şifreyi Değiştir, Kişisel İstatistik, Çıkış Yap and Hesabı Sil. The local "Adın" field goes.

### 10.2 The join flow on the phone

`JoinController`, a plain class the one `AppViewModel` owns (ADR-0107 kept: no second ViewModel, no DI), runs the client side of section 2:

| Client state | Enters on | Shows |
|---|---|---|
| `Resolving` | link or code opened | `link_loading` |
| `NeedsAccount(team)` | resolve answered and no session | `register` or `login` with the team card (10.1) |
| `WaitingForFocus` | session present; the join screen is composed but its window has no focus (a system sheet, the autofill save sheet, a cold start) | `link_loading` |
| `Claiming` | the window has focus after the first frame (`LocalWindowInfo.current.isWindowFocused`) | `link_loading`; resolve runs in parallel for the small logo |
| `Resuming(attemptId)` | a stored resume record exists and the intent is not a fresh tap | `link_loading`, then `GET /v1/join-attempts/{id}` |
| `Deciding(D)` | HOLDING | pop-up |
| `Sending(decision)` | tap; the resume record already holds the decision | `join_sending` |
| `TimeUp` | the phone's zero | `join_time_up` |
| `Waiting(seats, ahead)` | WAITING | waiting screen |
| `Acknowledging` | OFFERED from a poll or a claim | `queue_your_turn`, then `POST …/ack` |
| `Reconnecting(kind)` | a failed poll; kind NO_NETWORK or NO_ANSWER | `queue_no_network` or `queue_reconnecting`, without numbers |
| `Outcome(kind)` | any closed state, 404 or a refusal | 2.12 C and D |
| `Unknown` | a decision without an answer after its repeats and a failed `GET` | `join_unknown` |

- **The decision point (F4).** On the register and login screens the submit tap calls `LocalAutofillManager.current?.commit()`, so the password-save sheet appears while the request is in flight, before the pop-up. The claim goes out only in `WaitingForFocus → Claiming`, when the join screen has drawn its first frame and its window has focus. A logged-in member who taps the link still needs one round trip.
- **Screen on (C6, F2).** While WAITING, OFFERED or HOLDING, the join screen sets `view.keepScreenOn` in a `DisposableEffect`, as `Record.kt` line 346 does.
- **Polling.** One poll at a time, only while the join screen is on screen and the app is started (`repeatOnLifecycle(STARTED)`), with the `view` of the last answer. It sends `wait=15` with a read timeout of `wait` + 3 s, and `wait=2` while its `ahead` is below `seats.deciding` (conc V4). After an answer with `pollLimited: true` it waits `Retry-After` before the next poll. While WAITING or OFFERED a failed poll is retried after 0.5 s and then every 1 s; a `NetworkCallback.onAvailable` sends the next poll at once. Elsewhere the retry steps are 0.5, 1, 2, then 4 s.
- **Network state (F6).** `ConnectivityManager` with `NET_CAPABILITY_VALIDATED` tells "no network" from "no answer". The manifest gains `ACCESS_NETWORK_STATE`, a normal permission without a prompt.
- **Countdown (C3).** D as in 2.6; never lowered. A test feeds a HOLDING poll answer with `waitedMs` 15 000 and checks that D did not move down.
- **Decisions.** Before sending a decision the app writes it into the resume record. A decision is repeated with the same body on an `IOException` until the phone's zero plus 3 s; then the app asks `GET`. A `429` with `details.scope: RATE` is repeated after `Retry-After`. The resume record lets the app tell "this phone sent ACCEPT" after a process death (2.12 C).
- **Acknowledgement (C5).** OFFERED leads to `POST …/ack` at once, repeated on an `IOException` until the offer's `remainingMs` ends. A `409 ATTEMPT_CLOSED` (SKIPPED) shows `queue_skipped`. The claim never acknowledges.
- **Resume, not re-claim (C5).** The first claim answer replaces the pending link with a resume record `{userId, teamId, attemptId, link, sentDecision, createdAt}` in the encrypted store. It keeps the link (token or code), so Yeniden Sıraya Gir can send a fresh claim after a process death; the pending store already held the same secret, so P8 is unchanged (fidelity V6). After a process death, a restart, or a return from recents, the app resumes with `GET /v1/join-attempts/{id}` and shows the true state; after a closed outcome it shows that outcome and sends no claim. Only a fresh tap on a link (an intent that arrives without saved state and without `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`, or through `onNewIntent`) or Yeniden Sıraya Gir creates a new attempt. The resume record goes when the person closes the outcome, or after 24 h; only the account in `userId` resumes it.
- **Session end (R5).** A `401 AUTH_REQUIRED` keeps `pending.bin`. The app opens `login` with the team card and `link_login_intro`, and after a login by the same account it sends the claim, or resumes with `GET`, without a new tap. A login by another account discards the resume record.
- The pop-up survives rotation, because its state lives in the ViewModel.

### 10.3 What lives where on the phone (P8)

| File | Content | Cleared when |
|---|---|---|
| `filesDir/device.json` | `serverTarget`, `welcomeSeen`, `legacyHandled`, `accountSeen` (any account ever logged in here) | never (no personal data, no secret) |
| `filesDir/session.bin` | `{token, userId, username, displayName, expiresAt}`, AES-256-GCM with a key in AndroidKeyStore behind a `SessionCipher` interface (tests use a plain cipher) | logout, `AUTH_REQUIRED`, account deletion |
| `filesDir/device.bin` | device tokens of past logins, keyed by the SHA-256 of the username, encrypted through `SessionCipher` (3.2) | account deletion, or a `401` on a login that sent the token |
| `filesDir/pending.bin` | the pending link (30 min) and the join resume record with its link (24 h), encrypted through `SessionCipher` | when the join or request flow ends, at expiry, logout and account deletion; kept through `AUTH_REQUIRED` (R5) |
| `filesDir/accounts/<userId>/invites.bin` | URLs and codes of the share links this phone created, encrypted through `SessionCipher` | renewal, closing, expiry, logout, account deletion |
| `filesDir/accounts/<userId>/volleystat.json` | `LocalStore` version 3: this account's MatchRecords (personal matches and team recordings made on this phone, with `teamMatchId` and the upload hold), local rosters, the draft | account deletion |
| `filesDir/accounts/<userId>/recordings/` | this account's camera recordings | account deletion |
| `filesDir/accounts/<userId>/cache.json` | the server's view: teams with members' display names, team matches, invite states without secrets, statistics, small logos by version, each with `fetchedAtMs` | logout, `AUTH_REQUIRED`, account deletion |

- The app reads only the logged-in account's folder. Account B on the same phone never sees A's matches, recordings, teammates' names or invites (v2, kept).
- **Legacy data** (v2, kept): on the first login, if `filesDir/volleystat.json` or `filesDir/recordings/` holds anything, the app lists the match titles and asks once whether to move them into this account.
- Backup stays disabled (`allowBackup=false`, `data_extraction.xml`). The password is never stored.

**Team matches come from the server.**
- `GET /v1/matches?scope=team` fills `cache.json` on start, on resume, on pull and after every action. Nothing polls in the background except the join long-poll and the captain's live view while those screens are open. No push notifications.
- A team match is a `ServerMatch` keyed by its server id. The Matches tab merges ServerMatches with local MatchRecords by server id. A row whose local record exists (this phone added a video) opens today's review chain keyed by `localId`; any other row opens `tm/{id}`.
- **Recording for a team match (F5).** The draft carries `teamMatchId`. `startUpload()` creates the MatchRecord with `session.serverMatchId = teamMatchId`, `teamMatch = true`, the server's names and rule. For a team match `UploadEngine` skips `putMatch` and checks the record with `GET /v1/matches/{id}` before `createUpload`: until the record is ACCEPTED or SOLO and the own side has checked the opponent (or the other side left), the record shows `video_waiting_accept` while PENDING, `opponent_check_needed` while the own check is missing, and `request_held_options` once the request is DECLINED, EXPIRED or CANCELLED (R1), and the engine stops without an error; the app tries again on start, resume, pull and after every action. A request created offline is sent first with its client id.
- **What stays local only:** personal matches and their review chain, local rosters with player names (never sent), drafts, recordings, upload positions, the last analysis summary per local record.

### 10.4 Screens keyed by the server

`tm/{id}` and `tm/{id}/report?job=` need only server ids. The report comes from `GET /v1/matches/{id}/report?jobId=`, where the server checks that the job belongs to the record and applies the content rule (6.1, S4); the states, video ids and job ids from the Match payload. The other captain confirms from `tm/{id}/report`. Full review stays on the phone that added the video. Reviewing the other side's video from a phone that did not add it, and clip playback for the other side, are left out (section 15).

### 10.5 HTTP and playback

- `ApiClient(baseUrl, correctionTimeoutMs, token)`. `request()` adds `Authorization: Bearer` whenever a token is set; every call passes through it. It does not follow redirects (`instanceFollowRedirects = false`, `ApiClient.kt` line 323), which stays.
- The long-poll uses `request()` with a read timeout of `wait` + 3 s and sends `view` and `wait` (2.6).
- `ClipPlayer` builds both players with `DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))`. The URL stays `videoContentUrl(videoId)`, so no token ever sits in a URL.
- `ApiClient.deleteMatch` stays for personal matches. Team records use `leaveMatch`, which accepts 200 and 204.
- Logo encoding and decoding follow 4.2. On API 26-29 the app encodes with `CompressFormat.WEBP` at quality 80 (lossy below 100), on API 30 and later with `WEBP_LOSSY`; a test on an API 26 emulator checks that the server accepts the result.

### 10.6 Deep links

- `MainActivity` gains an intent filter for `https://<link host>/j/` and `/m/` with `android:autoVerify="true"`. The host comes from a Gradle property at build time (B6a), so the address is not committed to the public repository.
- A debug build adds the host `volleystat.test`, so tests open links with `adb shell am start -a android.intent.action.VIEW -d "https://volleystat.test/j/#t=<token>&c=<code>"`.
- The app takes the token and code from the fragment, never from a query or a path.
- A fresh tap and a resume are told apart as in 10.2.

### 10.7 Error mapping

| Code | App |
|---|---|
| `AUTH_REQUIRED` | clears the session and the cache, keeps the pending store (R5), navigates to `login` with `popUpTo(0)` (with the team card when a link or an open attempt is pending), shows `session_ended`. After a login by the same account the join continues without a new tap. A running upload stops and resumes from the server's offset after login (ADR-0111). |
| `NOT_A_MEMBER` | "erişim yok" state; the local record stays and Telefondan Kaldır removes it locally. `removeMatch` never treats it as deleted. |
| `CAPTAIN_ONLY`, `REVIEWER_ONLY` | read-only state with the reason |
| `MATCH_NOT_CONFIRMED`, `OPPONENT_NOT_CHECKED` | the recording waits (`video_waiting_accept` or `opponent_check_needed`) |
| `SIDE_HAS_VIDEO` | `video_one_per_side` |
| `QUOTA_EXCEEDED`, `STORAGE_FULL` | `upload_quota_personal`, `upload_one_unfinished`, `upload_quota_team`, `upload_quota_server`, `upload_storage_full`; the recording stays on the phone |
| `SERVER_BUSY` | waits `Retry-After` and repeats after each `Retry-After` while the screen that sent the request is open (R10) |
| `TOO_MANY_ATTEMPTS` | the wait from `Retry-After`, with the text for `details.scope` (3.6) |
| `REQUEST_CLOSED`, `ROSTER_STALE`, `RESULT_STALE` | refresh and show the current state |
| join and link codes | 2.12 |
| a non-JSON answer or a timeout | `net_offline` or `net_no_answer` by `NET_CAPABILITY_VALIDATED` (F6) |

`loadReport` and `loadRallies` today turn every failure into UNREACHABLE; they check `AUTH_REQUIRED` and `NOT_A_MEMBER` first.

### 10.8 Turkish copy outside the join flow

Rows marked "v2" are unchanged; the others are new or rewritten in v3.

| Exact state | Key | Text |
|---|---|---|
| login screen (v2) | `login_intro` | Sunucu takımlarını ve maçlarını hesabına bağlı saklar. Kullanıcı adınla ve şifrenle giriş yaparsın. |
| `401 LOGIN_FAILED` (v2) | `login_failed` | Sunucu bu kullanıcı adını ve şifreyi eşleştiremedi. Kullanıcı adını ve şifreni kontrol edip yeniden deneyebilirsin. |
| `429` on login, `details.scope: USER` | `login_wait` | Sunucu bu kullanıcı adı için çok sayıda yanlış deneme gördü ve girişi %1$s süreyle durdurdu. Şifreni unuttuysan Voleybol Ve Veleybol şifreni sıfırlayabilir. |
| `429` on login, `details.scope: DEVICE` (S2) | `login_wait_device` | Sunucu bu telefondan bu kullanıcı adı için çok sayıda yanlış deneme aldı ve bu telefondan girişi %1$s süreyle durdurdu. Şifreni unuttuysan Voleybol Ve Veleybol şifreni sıfırlayabilir. |
| `429` on login, `details.scope: SOURCE` (fidelity V10) | `login_wait_source` | Sunucu bu internet adresinden çok sayıda yanlış giriş denemesi aldı ve bu adresten girişi %1$s süreyle durdurdu. |
| `AUTH_REQUIRED` (R5, fidelity V10) | `session_ended` | Oturumun kapandı. Sen yeniden giriş yapınca uygulama yarım kalan katılma isteğini ve video yüklemelerini sürdürür. |
| register screen (F6) | `register_data` | Sunucu hesabın için kullanıcı adını, şifrenin özetini ve görünen adını saklar. Uygulama e-posta adresini ve telefon numaranı sormaz. |
| register, username field (fidelity V10: the operator sees it through the CLI) | `register_username_hint` | Takım arkadaşların ve rakip takım kullanıcı adını görmez. Kullanıcı adı küçük harf, rakam, nokta ve alt çizgi taşıyabilir. |
| register, display name field (v2) | `register_display_hint` | Takım arkadaşların bu adı görür. Rakip takım bu adı görmez. |
| `403 SIGNUP_CLOSED` | `register_gate` | Sunucu şimdilik yalnızca takım davetiyle ya da kayıt koduyla hesap açar. Kaptandan davet bağlantısı isteyebilirsin. |
| `422 PASSWORD_REJECTED`, reason COMMON | `password_common` | Bu şifre çok yaygın şifreler listesinde var. Sunucu bu şifreyi kabul etmez. Başka bir şifre seçebilirsin. |
| Takım Kur, captain code field | `team_code_body` | Sunucu şimdilik yalnızca kaptan koduyla takım kurar. Kaptan kodunu Voleybol Ve Veleybol verir. |
| `403 CAPTAIN_CODE_INVALID` | `team_code_invalid` | Sunucu bu kaptan kodunu kabul etmedi. Voleybol Ve Veleybol'dan yeni bir kod isteyebilirsin. |
| request screen (fidelity V9) | `request_body` | Uygulama bir istek bağlantısı hazırlar. Sen bağlantıyı rakip takımın kaptanına gönderirsin. Rakip kaptan isteği kabul etmeden de maçı kaydedebilirsin. Rakip kaptan isteği kabul edince ve sen rakibi onaylayınca uygulama videoyu sunucuya gönderir. |
| Paylaş on a request; %4$s = `code_title` | `request_share_text` | %1$s takımı seninle %2$s günü maç yapmak istiyor. İsteği görmek için bu bağlantıyı aç: %3$s ⏎ Bağlantı uygulamayı açmazsa VolleyStat uygulamasında %4$s ekranına bu kodu yaz: %5$s |
| request opened by a captain | `request_open_body` | %1$s takımı seninle maç yapmak istiyor. |
| request opened by a captain | `request_pick_team` | Hangi takımınla oynayacaksın? |
| request opened by someone who captains no team | `request_need_captain` | Bu isteği bir takımın kaptanı kabul eder. Sunucu senin kaptanı olduğun bir takım bulamadı. |
| `422 SAME_PERSON_BOTH_SIDES`, `details.who: ACCEPTER` | `request_same_person` | Sen %1$s takımının da üyesisin. Bir maçın iki tarafında da yer alan kişi isteği kabul edemez. |
| `422 SAME_PERSON_BOTH_SIDES`, `details.who: REQUESTER` (fidelity V9) | `request_same_person_requester` | %1$s takımının kaptanı senin takımının da üyesi. Bu yüzden sen bu isteği bu takımla kabul edemezsin. |
| `422 SAME_TEAM_NAME` | `request_same_name` | İki takımın adı aynı. Kaptanlardan biri takımın adını değiştirirse sen bu isteği kabul edebilirsin. |
| PENDING, HOME captain | `request_waiting` | Rakip kaptan isteği henüz yanıtlamadı. Sunucu yanıtsız isteği yedi gün sonra kapatır. |
| ACCEPTED, HOME has not checked the opponent; %3$s = the button label | `request_accepted` | %1$s #%2$s takımının kaptanı isteği kabul etti. Bu takım senin rakibinse %3$s düğmesine dokun. Uygulama senin videonu ancak sen onaylayınca gönderir. |
| button | `opponent_confirm` | Rakibi Onayla |
| the upload waits for the opponent check | `opponent_check_needed` | Uygulama videoyu sen rakibi onaylayınca gönderir. |
| DECLINED | `request_declined` | %1$s takımının kaptanı isteği reddetti. |
| EXPIRED | `request_expired` | Rakip kaptan isteği yedi gün içinde yanıtlamadı. Sunucu isteği kapattı. |
| DECLINED, EXPIRED or CANCELLED, and this phone holds a recording of the record (R1); buttons `solo_from_request` and `request_again` | `request_held_options` | Bu maçın videosu telefonunda bekliyor. Videoyu yalnız kendi takımının maçı olarak kaydedebilirsin ya da aynı rakibe yeni bir istek gönderebilirsin. |
| buttons | `solo_from_request` / `request_again` | Yalnız Takımım İçin Kaydet / Aynı Rakiple Yeni İstek |
| ACCEPTED, HOME has not checked the opponent; button (R1) | `opponent_not_mine` | Rakibim Değil |
| Rakibim Değil tapped | `opponent_not_mine_body` | Sunucu takımını bu maçtan çıkarır. Uygulama telefonundaki videoyu yalnız senin takımının maçı olarak yeni bir kayda bağlar. Rakip takım bu maçtaki kendi videosunu korur. |
| a request link answered `410 LINK_CLOSED` (USED, DECLINED, CANCELLED, EXPIRED) | `request_link_closed` | Bu maç isteği artık açık değil. İsteyen kaptandan yeni bir istek isteyebilirsin. |
| a recording waits while the record is PENDING (F5, R1, fidelity V9) | `video_waiting_accept` | Rakip kaptan isteği kabul edince ve sen rakibi onaylayınca uygulama videoyu sunucuya gönderir. |
| `409 SIDE_HAS_VIDEO` on a team record | `video_one_per_side` | Takımının bu maçta bir videosu var. İkinci kaydı eklemek için aynı rakiple yeni bir istek oluşturabilirsin. |
| `409 SIDE_HAS_VIDEO` on a SOLO record (fidelity V9) | `video_one_per_side_solo` | Takımının bu maçta bir videosu var. İkinci kayıt için yeni bir tek taraflı maç oluşturabilirsin. |
| `409 QUOTA_EXCEEDED`, PERSONAL | `upload_quota_personal` | Kişisel maç videoların sunucuda %1$s yer kaplıyor. Sunucu hesap başına en çok %2$s saklar. Yeni video için önce eski bir kişisel maçın videosunu silebilirsin. |
| `409 QUOTA_EXCEEDED`, PERSONAL, `details.reason: UNFINISHED_UPLOAD` (S7) | `upload_one_unfinished` | Bu hesabın yarım kalmış bir kişisel video yüklemesi var. Sunucu bu hesaptan aynı anda tek bir kişisel yükleme kabul eder. Yarım kalan yükleme bitince ya da sunucu onu bir gün sonra silince uygulama bu videoyu gönderir. |
| `409 QUOTA_EXCEEDED`, SERVER (S7) | `upload_quota_server` | Sunucu kişisel maç videolarına en çok %1$s yer ayırır. Kişisel videolar bu yeri doldurdu. Video telefonunda kalır. Uygulama sonraki açılışta yeniden dener. |
| `409 QUOTA_EXCEEDED`, TEAM (R10) | `upload_quota_team` | Takımının videoları sunucuda %1$s yer kaplıyor. Sunucu takım başına en çok %2$s saklar. Eski bir videoyu silersen uygulama yeni videoyu gönderebilir. Takımın istatistiği silinen videolarla değişmez. |
| `507 STORAGE_FULL` (%1$s = `details.floorBytes`, the free-disk floor; R10, fidelity V10; reviewed with the `turkce-insansi-metin` rules) | `upload_storage_full` | Bu video sunucunun diskindeki boş yeri %1$s sınırının altına indirirdi. Sunucu bu yüzden videoyu almadı. Video telefonunda kalır. Uygulama sonraki açılışta yeniden dener. |
| result confirm, own side | `result_confirm_body` | Sonucu onaylarsan sunucu bu maçı takımının istatistiğine katar. Rakip takımın istatistiği rakip kaptanın onayına bağlıdır. |
| own side result exists, other side not confirmed | `result_waiting_other` | Rakip kaptan bu sonucu kendi takımı için henüz onaylamadı. Bu durum senin takımının istatistiğini değiştirmez. |
| report, viewer is the other side's captain, the reviewer changed the result after this captain confirmed (F6) | `result_changed_other_captain` | %1$s takımının kaptanı sonucu değiştirdi. Yeni sonucu onaylarsan sunucu takımının istatistiğini günceller. Onaylamazsan takımının istatistiği önceki onaylı sonucu gösterir. |
| report, viewer is the reviewer, team record | `result_changed_reviewer` | Sen sonucu değiştirdin. Rakip kaptan yeni sonucu onaylarsa sunucu onun takımının istatistiğini de günceller. |
| report, viewer is the reviewer, SOLO record (fidelity V9) | `result_changed_reviewer_solo` | Sen sonucu değiştirdin. Sonucu yeniden onaylarsan sunucu takımının istatistiğini günceller. |
| report, viewer is a member, own side result differs from the job | `result_changed_member` | Takımının istatistiği kaptanının onayladığı sonucu gösterir. İnceleyen kaptan sonucu sonradan değiştirdi. Senin kaptanın yeni sonucu henüz onaylamadı. |
| roster screen (v2) | `roster_body` | Bu maçta oynayan üyeleri ve forma numaralarını seç. Sunucu bir sayıyı bir üyeye yalnızca sen kadroyu onaylayınca yazar. |
| roster at 12 (v2) | `roster_too_many` | Bir maç kadrosunda en çok 12 oyuncu olur. |
| leave confirm | `leave_match_title` | Bu maçı takımından kaldırmak istiyor musun? |
| leave confirm, team record | `leave_match_body` | Sunucu takımının videosunu, kadrosunu ve bu maçın takım istatistiğini siler. Takımının üyeleri bu maçı artık göremez. Rakip takım kendi videosunu ve kendi onaylı sonucunu korur. |
| added to `leave_match_body` when the record holds a result offer for the other side from this side's list confirmation (R7) | `leave_match_offer` | Rakip kaptan senin onayladığın sonucu kendi takımı için yine de onaylayabilir. |
| leave confirm, SOLO record (fidelity V9) | `leave_match_body_solo` | Sunucu bu maçı, videosunu ve takımının bu maçtaki istatistiğini siler. |
| statistics screen | `stats_rule_team` | Sunucu bir maçı takım istatistiğine yalnızca kendi kaptanın sonucu onaylayınca katar. |
| statistics screen (v2) | `stats_rule_person` | Sunucu bir sayıyı sana yalnızca kaptanın kadroyu onayladığı maçlarda yazar. İnceleyen kaptan o sayıda senin forma numaranı seçmiş olmalı. |
| statistics, records without video (v2) | `stats_without_video` | Videosu olmayan %1$d maç istatistiğe girmez. |
| statistics (v2) | `stats_demo` | Uygulama demo analizlerini istatistiğe katmaz. |
| New Match, member (v2) | `new_match_member` | Takım maçının videosunu kaptan ekler. Kişisel maç takım istatistiğine girmez. |
| New Match, opponent outside the app (5.7) | `solo_body` | Rakip uygulamayı kullanmıyorsa maçı yalnız kendi takımın için kaydedebilirsin. Sunucu bu maçı yalnızca senin takımının istatistiğine katar. |
| delete account | `delete_account_title` | Hesabını silmek istiyor musun? |
| delete account (fidelity V10) | `delete_account_body` | Sunucu kullanıcı adını, görünen adını, şifre özetini, oturumlarını ve takım üyeliklerini siler. Sunucu kişisel maçlarını da siler. Takımında senden başka üye yoksa sunucu takımı, takımın maç videolarını ve istatistiğini de siler. Başka üyesi olan takımlarda eklediğin videolar takımda kalır. Rapor o maçlarda senin sayılarını yalnızca forma numarasıyla gösterir. Uygulama bu telefondaki maç kayıtlarını ve kamerayla çektiğin videoları da siler. |

Privacy rows, rewritten so they state the real behaviour (P5, P6, P2):

| Key | Text |
|---|---|
| `privacy_server` | Uygulama videoyu, maç bilgilerini, takım bilgilerini ve hesap bilgilerini %1$s adresindeki sunucuya gönderir. (A build for a server behind a tunnel that ends TLS adds `privacy_tunnel`.) |
| `privacy_tunnel` | Sunucuya giden bağlantı %1$s hizmetinden geçer. %1$s bu bağlantıdaki her şeyi, videolar ve şifreler dahil, açık olarak görebilir. |
| `privacy_accounts` | Sunucu hesabın için kullanıcı adını, şifrenin özetini ve görünen adını saklar. Sunucu e-posta adresi ve telefon numarası saklamaz. |
| `privacy_visibility` | Bir takım maçının videolarını ve raporunu iki takımın üyeleri görür. Takıma maç gününden sonra katılan üye o maçı yalnızca kadrodaysa görür. Rakip takım videoda yüzünü ve forma numaranı görür. Rapor her forma numarasının sayılarını gösterir. |
| `privacy_teammates` | Takım arkadaşların görünen adını, forma numaranı ve maç raporlarında senin numarana yazılan sayıları görür. Kişisel istatistik ekranındaki toplamları yalnızca sen görürsün. |
| `privacy_opponent` | Rakip takım senin görünen adını ve kullanıcı adını görmez. |
| `privacy_join` | Davet bağlantısını açan ve giriş yapmamış kişi takımın adını ve logosunu görür. Giriş yapan kişi ayrıca maç gününü, saatini ve boş yer sayısını görür. |
| `privacy_request` | Maç isteği bağlantısını açan kişi isteyen takımın adını ve logosunu görür. Tarihi, saati, yeri ve maç kuralını yalnızca bir takımın kaptanı görür. |
| `roster_privacy` | Telefondaki kadroya yazdığın oyuncu adları yalnızca bu telefonda durur. Uygulama bu adları sunucuya göndermez. |
| `privacy_retention` | Sunucu hesabını, takımlarını ve maçlarını sen silene kadar saklar. Sunucu biten katılma isteklerini bir gün sonra siler. Sunucu kapanan maç isteklerini otuz gün sonra siler. (With B10 backups it adds: Sunucu yedeklerdeki kopyaları yedi gün sonra siler.) |
| `privacy_delete` | Sen hesabını silince sunucu hesabını ve kişisel maçlarını siler. Bir takım maçını sunucu iki takım da maçı kaldırınca siler. Videodan çıkarılmak istersen Voleybol Ve Veleybol'a yazabilirsin. (With B10 backups it adds: Silinen veriler yedeklerde yedi gün daha durur.) |
| `privacy_faces` | Uygulama ve sunucu yüz tanımaz. Rapor bir sayıyı oyuncuya yalnızca inceleyen kaptan forma numarasını seçince yazar. |

### 10.9 Screens and accessibility (v2, kept)

- Stateless composables with fixed data in `ScreenshotTest` at 411 dp, 320 dp (`kucuk_`), dark (`_koyu`) and font scale 1.5 (`buyuk_yazi_`). Every screen has loading, empty, error and full states.
- Only `Theme.kt` tokens and the shared components (FlowScaffold, VsCard, ListRow, StatusChip, Notice, PrimaryButton, SecondaryButton, SelectCard, EmptyState). Touch targets at least 48 dp; the pop-up's buttons 56 dp.
- Status always pairs an icon with a word.
- Password fields use `KeyboardType.Password`, a show/hide toggle with its own content description, and autofill semantics with the commit of 10.2.
- Codes use tabular figures and a copy button that announces through `Modifier.announced()`.

---

## 11. Privacy and KVKK hooks

### 11.1 Who sees what

| Data | The person | Teammate | Own captain | Other team in a match | Link holder without a session | Link holder with a session | Operator |
|---|---|---|---|---|---|---|---|
| username | yes | no | no | no | no | no | CLI |
| display name | yes | yes | yes | **no** | no | no | CLI |
| jersey in the team | yes | yes | yes, edits | no | no | no | |
| the person's points in each match report | yes (by jersey, name joined on the phone) | yes, if the teammate may read that record (6.1) | yes | yes, by jersey only | no | no | |
| the person in a video | yes | yes, if the teammate may read that record | yes | **yes** | no | no | |
| person totals (Kişisel İstatistik) | **yes** | no | no | no | no | no | |
| team totals and lines | members | members | yes | no | no | no | |
| team name, tag, small logo | yes | yes | yes | name and tag | yes | yes | |
| schedule and seat counts | yes | yes | yes | no | no | yes | |
| match request date, time, venue, rule | HOME members | HOME members | yes | after acceptance | no | captains only | |
| who waits or decides | no | no | counts only | no | no | no | |
| removed and blocked people | no | no | yes | no | no | no | |
| password, token, code, device and session hashes | never returned | | | | | | the database file |

### 11.2 Data inventory

The ADR carries this table, and the KVKK review starts from it.

| Data | Where | Purpose | Kept | Who sees it |
|---|---|---|---|---|
| username, display name, password hash, how the account opened (`signup_via`) | `users` | login, names in the team, the personal storage quota | until account deletion | 11.1 |
| device token hashes | `login_devices` | a login from a known phone skips the username lock (3.2) | 180 days after the last use, a password change or account deletion | nobody |
| session token hash | `sessions` | login | 180 days at most | nobody |
| operator code hashes | `operator_codes` | sign-up and team creation gate | until use or 7 days | nobody |
| team name, tag, logos, schedule, capacity | `teams` | team, invite pop-up | until team deletion | 4.5 |
| membership, role, jersey, joins-seen mark | `memberships` | team | until leave, removal or deletion | members |
| blocks | `team_blocks` | keeping a removed person out | until the captain lifts it or deletion | the captain |
| invite and request links (token hash, code HMAC) | `share_links` | joining, match requests | 7 days live, then 30 days as a tombstone with the code HMAC (R3) | nobody; the captain's phone keeps the clear values |
| the link key | the operator's configuration folder, outside the storage folder (`VOLLEYSTAT_LINK_KEY_FILE`, S9) | code HMAC | until the operator deletes it | nobody |
| join attempts, with session hash prefixes | `join_attempts` | seat holds and the queue | 24 h after closing | the person; the captain sees counts |
| match records, sides, rosters, side results, result offers | `matches`, `match_sides`, `match_rosters`, `match_results`, `match_result_offers` | team matches and statistics | until both sides leave; an offer until its side confirms or leaves | members of both teams under 6.1; rosters with names to the own side only |
| videos and analyses | media folder, `videos`, `analysis_jobs` and their tables | the existing product | until deleted or the uploading side leaves | members of both teams under 6.1 |
| server logs | the operator's terminal | errors only; the uvicorn access log is off | the operator's choice; B10 sets a period | operator |
| proxy or tunnel logs (B6a) | the operator's reverse proxy, or the tunnel provider | transport | the proxy runs with its access log off; a provider's own logs are outside the operator's control and are named in the ADR. With Tailscale the operator decides whether tailscaled runs with `--no-logs-no-support` and records the choice here (S10) | operator, provider |
| the public server's host name (B6a) | public Certificate Transparency logs and every shared link | TLS certificate, links | forever | anyone; the checklist therefore picks a neutral name (12.3, S10) |
| traffic through a TLS-ending tunnel (B6a, if chosen) | the provider's edge | transport | the provider's terms | the provider sees everything in clear: passwords, tokens, videos (P6) |
| backups (B10) | a folder on the same computer | recovery | 7 days | operator |
| throttle keys | process memory | brute-force limits | 1 h after the last failure, lost on restart | nobody |
| phone files | 10.3 | the app | 10.3 | the account on that phone |

### 11.3 Waiting for the KVKK review

Until the review: the app is not opened to other teams. The default team-creation gate (`VOLLEYSTAT_TEAMS=operator`) makes that a server rule: the operator prints captain codes only for the owner's own teams, which ADR-0119 lists by name, and the server binds each code to its team name and to the account it registered (0.4, fidelity V1, S8); no display name, username, user id or roster link of a team reaches another team; no team's statistics reach another team; no directory or search exists. `test_privacy_payloads.py` asserts that an AWAY member's roster, report, match, link and attempt payloads hold no HOME display name, username or user id, and that an anonymous LinkView holds no `playedOn`, `startTime`, `venue` or schedule.

Open for the review: the aydınlatma metni; a separate sharing consent for showing a person's match data and video to the other team; minors and parental consent; erasure for a person in a video without an account (operator CLI until then); retention periods for videos, side results, backups and tombstones; hosting abroad and a TLS-ending tunnel as a processor and a cross-border transfer (P6).

---

## 12. Hosting, link host and distribution

### 12.1 What works where

| Works today on one computer, free | Needs an internet-reachable HTTPS server |
|---|---|
| accounts, teams, invites by code, the queue, requests, rosters, side results and statistics, with several accounts on one server | two captains on phones in different places |
| two emulators at `10.0.2.2:8000`, a USB phone through `adb reverse` | 100 people from a WhatsApp group |
| one phone switching accounts | links that open the app (App Links, `assetlinks.json`, fallback pages) |
| the CI e2e and device jobs, the shaped-network test of B6b | the real-phone session of B6b and any release build (its server URL is empty and fails closed today) |

Plain HTTP on a shared Wi-Fi is not offered: passwords and tokens would cross the network in clear, and the debug network config allows cleartext only to `10.0.2.2` and `127.0.0.1`.

The server is the owner's computer. For anyone to join or upload, it must be on and awake, and the tunnel or proxy must run (F8). Free cloud hosts often sleep idle services or have no persistent disk; this design needs a disk for SQLite and the media folder.

### 12.2 The exposure minimum (slice B6a)

`--host` (default stays `127.0.0.1`); `--trusted-proxy` with `--client-ip-header` (3.6); the source keys of 3.6, with an IPv6 source keyed by its /64; `VOLLEYSTAT_PUBLIC_URL`; `/.well-known/assetlinks.json` from `VOLLEYSTAT_ANDROID_CERT_SHA256` when the API host is the link host; the `/j/` and `/m/` fallback pages and `link.js`; a refusal to start on a non-loopback host without `--trusted-proxy` and without explicit `VOLLEYSTAT_SIGNUP` and `VOLLEYSTAT_TEAMS`, and a refusal to start with `VOLLEYSTAT_PUBLIC_URL` set but no `--trusted-proxy`, because every tunnel connects from loopback (R4); the open-file limit. On Android: `ServerTarget.REMOTE` with `BuildConfig.API_URL_REMOTE` and the link host from a Gradle property, HTTPS only. An operator checklist in the ADR: the proxy's access log off; where TLS ends; the client-address header of the chosen option, confirmed in B6b by two phones on different networks getting different keys (S2, R4); the link key file outside the storage folder and every copy (S9); a neutral host name chosen before HTTPS is turned on, because the name becomes public (12.3, S10); the computer set not to sleep during a use window.

### 12.3 Where TLS ends (P6, owner decision 16.1 item 1)

| Option | Where TLS ends | What it needs and what it costs |
|---|---|---|
| a reverse proxy on the owner's computer with a free certificate (for example Caddy, Apache 2.0) | on the owner's computer | a free dynamic-DNS name pointing to the computer (the owner checks that its terms allow this use) and port 443 open on the home router. A home connection behind carrier-grade NAT or without a public address cannot use it. No fee. |
| Tailscale Funnel | on the owner's computer: Tailscale's documentation says its relays forward the encrypted traffic without decrypting it, and the Tailscale client on the device ends TLS (https://tailscale.com/kb/1223/funnel) | stable host `<machine>.<tailnet>.ts.net`; ports 443, 8443 and 10000 only. Tailscale describes its free Personal plan as free for non-commercial use, and ADR-0117 keeps resources with a non-commercial condition out of this closed commercial product, so this option means a paid plan. Not planned unless Tailscale's terms allow this use for free (fidelity V4). |
| Cloudflare Tunnel | at Cloudflare's edge | The tunnel is free. A stable host needs a domain whose DNS Cloudflare serves: a bought domain (a yearly fee), or the website domain's DNS moved to Cloudflare, which changes the owner's website setup and needs the owner's explicit permission like 12.4. A quick tunnel's random host changes on every start and cannot be built into the app, whose server address is the build-time constant `BuildConfig.API_URL_REMOTE`. Cloudflare then sees every password, token and video in clear, abroad. If chosen, the ADR, the data inventory and `privacy_tunnel` name the provider (P6). |
| a free cloud VM | on the VM | often needs a payment card for identity, has a small disk, and puts the data abroad |

The team prefers TLS that ends on the owner's computer. The only option with no fee and no website change is the reverse proxy with a free dynamic-DNS name, where the home connection allows it (fidelity V4). Free tiers can change their terms, and the team proposes no paid plan. The default, until the owner chooses: no public server (local only).

**The host name becomes public (S10).** A TLS certificate for any of these hosts appears in public Certificate Transparency logs, and every invite sent to a WhatsApp group carries the host. The checklist therefore picks a neutral host name (for example `volleystat`) before HTTPS is turned on. On Tailscale the machine name defaults to the computer's name, which on a Mac often holds the owner's personal name, so the operator renames the machine and checks the tailnet's DNS name first. tailscaled also uploads its own logs to Tailscale unless it runs with `--no-logs-no-support`; the operator decides and 11.2 records the choice.

### 12.4 The link host (F1, owner decision 16.1 item 2)

- **The owner's website domain (recommended once the owner permits it).** Four static files on the website: `/j/index.html`, `/m/index.html`, one external script that reads the fragment and builds the intent URL (the website's CSP allows only same-origin scripts, so no inline script), and `/.well-known/assetlinks.json` with the package name and the release certificate's SHA-256. Links then survive any change of the API's host or tunnel, and the fallback page works even while the owner's computer is off. This project changes no file of the website. The change needs the owner's explicit permission and would be made by a website session under the website's own rules, inside a planned website release and never as a separate one.
- **The API host (default once hosting exists).** The server serves the same files (B6a). No website change. Links break whenever the API host changes, and the page is down whenever the computer is.
- **Before hosting (default today).** No link; the app shares the code with the paste-friendly message.

### 12.5 Distribution and the newcomer without the app (F1, owner decision 16.1 item 8)

- **APK by hand (default, free).** The owner gives the APK to the captains of the owner's own teams (0.4, fidelity V1), and a captain may share the APK file in the team's WhatsApp group. The fallback page and the code-only share text tell a newcomer to ask the captain for the file, allow the install, and tap the WhatsApp link again: there is no deferred deep link outside Play, so the re-tap carries the link into the app. A newcomer who taps the installer's Aç button instead reaches the first-start screen, whose main action is Kod Gir with the copied code (10.1, R6).
- **A public GitHub Release.** The repository is public, so a release asset is downloadable by anyone; that distributes the closed app beyond the owner's teams. It needs the owner's explicit choice.
- **Play Store.** Keeps the link through the install and removes the unknown-sources steps, but needs a one-time Play Console fee. The owner uses free tools only, so it is not planned.
- **Risk:** Google announced developer verification for apps installed outside Play, starting in some countries in 2026 and later worldwide. Before 2027 the owner checks whether the APK route then needs a registration.

### 12.6 Backups and logs (P6, B10, owner decision 16.1 item 10)

Default in B10: a nightly copy with `sqlite3.Connection.backup` plus the media folder into a folder on the same computer, kept 7 days and then deleted; the link key file lives outside the storage folder and is never copied (S9). `privacy_retention` and `privacy_delete` then say that deleted data stays in the copies for at most 7 days. The other choice is no copies before the KVKK review, which loses everything if the disk fails. The data inventory lists the copies and the proxy logs.

Real use between two teams waits for both owner decisions: hosting and the KVKK review.

---

## 13. Critique findings (v2 critiques)

The v1 findings (S1-S10, F1-F10 of `critiques_v1.json`) stay handled as in v2 section 13; v3 changes none of those fixes except where a v2 finding below says so (v1 S6 and F2 in 6.4 and 6.2, v1 F4 in 6.1).

| Finding | Fix in v3 | Section |
|---|---|---|
| **C1** (high) the burst figure left out the app's polling; every claim woke every poll; polls read and wrote | Adopted as measured: writers publish a team snapshot after COMMIT; the long-poll answers from it in the event loop with no thread hop and no database read; the cursor is the caller's view tag, so a claim behind someone wakes nobody; heartbeats live in memory and the clock flushes them in one UPDATE per tick; the long-poll never runs `settle` and never writes. Added: the in-memory session cache keeps the poll free of database reads (the critic's probe skipped the session check). Test: the 14.3 burst runs with every phone polling, from a raw-socket generator with arrivals spread under 50 ms. Measured by the critic: 0.66-0.70 s instead of 6.6-7.1 s, 145 poll answers instead of about 1 900-2 000, 0.6 s CPU instead of 11-12 s. | 2.5, 2.7, 3.2, 14.3 |
| **C2** (high) the grace and the offer deadline were judged after the lock; 3 and 7 of 10 in-time ACCEPTs were lost | Adopted as measured: an outermost ASGI middleware stamps arrival; decision and ack routes are `async def`, register their attempt in an in-flight set, and run their transaction in the pool; ACCEPT is judged by arrival < `expires_at + GRACE`, ack by arrival < `offer_deadline`; `settle` and the clock skip in-flight ids; the late-ACCEPT text now states only that the server received the decision after the time. Test: a second process holds the write lock 2.5 s, an ACCEPT arrives at `expires_at + 1.0`, the answer is JOINED and nobody was offered meanwhile. Revised in 13.1 (conc V1, conc V3, S1): the entry is taken in the stamping middleware, protection is bounded, and a late request closes its own attempt. | 2.4, 2.9, 2.12 C, 14.3 |
| **C3** (high) the countdown rule on a long-poll answer plus an early TIMEOUT closed a live hold at step 3 of the owner's example | Adopted with one refinement. `waitedMs` on every answer; the phone's deadline is the maximum of `sentAt + waitedMs + remainingMs` over all HOLDING answers and never moves down. The pop-up's poll no longer wakes on queue changes (its view tag holds only state and `expiresAt`). An early TIMEOUT never shortens the visible window: it marks the hold, and `settle` closes it at `expiresAt` instead of `expiresAt + 2 s`, which keeps the critic's protection and still saves the grace in the common case where the TIMEOUT arrives just before `expiresAt` (the critic's "ignore" would fall back to the full grace then). A second session of the same person keeps the full grace (`other_session`). Tests: a HOLDING answer that waited 15 s does not lower D; a TIMEOUT 1 s early leaves the hold open and JOINED follows an ACCEPT 1 s later. | 2.4 (T10b), 2.6, 2.7, 10.2 |
| **C4** (medium) queue order followed the busy handler, not arrival | Adopted as measured: an in-process FIFO ticket lock (`threading.Condition` with tickets) in `db.connector()` in front of `BEGIN IMMEDIATE` for every writer: routes, clock, heartbeat flush, sweep, analysis worker. Busy timeout kept for other processes. Not re-entrant, never on the event loop thread, both asserted in tests. 2.8 now says order means arrival at the server, and that jitter under about 100 ms can swap two people. Test: sort the answers by the stored arrival stamp and require `queue_no` to increase for every pair more than 5 ms apart. Measured by the critic: 2-10 inversions within 2.2 ms instead of about 2 100-2 400. | 2.8, 14.3 |
| **C5** (medium) the claim acknowledged offers and resumed after process death, so a late ack silently re-queued and a restart re-claimed after REJECTED | Adopted: `POST /v1/join-attempts/{id}/ack`, judged by arrival; on a closed attempt it answers `409 ATTEMPT_CLOSED` with the state and creates nothing. The claim never acknowledges. The app replaces the pending link with an encrypted resume record after the first claim answer and resumes with `GET`; only a fresh link tap (no saved state, no `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`, or `onNewIntent`) or Yeniden Sıraya Gir creates a new attempt. Tests: an ack 0.1 s late gets 409 SKIPPED and no new row; a restart after REJECTED shows REJECTED and sends no claim. | 2.4 (T6), 9.2, 10.2, 10.6 |
| **C6** (medium) DROPPED and SKIPPED fired for screen auto-off, a server pause and the restart window, and their texts stated a false cause | Adopted: the join screen keeps the screen on while WAITING, OFFERED or HOLDING; the clock detects a pause (tick gap or wall-clock drift above 2 s) and runs the start-up recovery before any `settle`; the recovery offer window is 10 s; a hold whose grace ended during a pause stays open for one grace after it; `queue_dropped`, `queue_skipped` and `queue_keep_open` now state only what the server or app knows. Test: the fake clock freezes 60 s with waiters and an offer open, then ticks; nobody is DROPPED or SKIPPED. Revised in 13.1 (conc V2, R8): every write transaction runs recovery first, `grace_until` carries the extra grace, and a silence of every phone counts as a pause. | 2.11, 2.12 D, 10.2 |
| **C7** (low) logo bytes in claim answers ate the 10-second hold during a burst | Adopted: claim, ack, decision and poll answers carry only `logoVersion` (about 0.5-1 KB); the 128 px logo (≤ 8 KiB) comes from resolve, which a logged-in phone sends in parallel with the claim; the pop-up shows initials until it arrives. Test: with maximum-size logos every join answer stays under 2 KB. | 2.1, 4.2, 9.3 |
| **P1** (high) a request link let a brand-new account create a team, accept, and receive the other team's video; the tag was forgeable | Adopted: (1) `VOLLEYSTAT_TEAMS=operator` by default: a one-time captain code from the operator CLI is needed to create a team (owner decision, default on); (2) request links no longer open sign-up (`422 WRONG_LINK_KIND`); (3) the tag is drawn at random, stored and unique, so a client-chosen id cannot reproduce it; (4) the requesting captain confirms the opponent's name and tag before their side's first upload; the recorder still never waits (F5); (5) a video belongs to its side and leaves with it; `match_results` has no foreign key to jobs or videos. Decline now records which team declined. Tests: a fresh account with only a request link cannot sign up, create a team or accept; a client-chosen team id does not set the tag; HOME's upload is refused before the opponent check. | 0.4, 3.3, 4.1, 4.4, 5.2, 5.3, 6.4, 8.2 |
| **P2** (high) a forwarded invite gave instant access to every past team video; removal did not stick; the invite stayed live after the team filled | Adopted: (1) the invite closes on its own when members reach capacity (reason FULL); (2) `team_blocks` written by a captain's removal; T1 refuses `403 JOIN_BLOCKED` until the captain lifts the block; (3) a member reads a team record's video, analyses, report and roster only if it was played on or after the day they joined (UTC+3) or they are on its roster; aggregates stay visible; (4) a new-member badge on the Teams tab. Tests: a removed user cannot reclaim with the old link; a member who joined after a record's date gets 403 on its content and 200 once rostered. | 2.2, 4.3, 4.5, 6.1 |
| **P3** (high) unlimited sign-ups per link, no storage quota, queue squatting, unbounded long-polls | Adopted: (1) a per-invite sign-up limit, set to 200 (the queue limit) instead of the critic's capacity − members + 5, which would refuse newcomers of the owner's own example; (3) quotas per account (8 GiB personal) and per team (40 GiB), a 20 GiB free-disk floor with `507 STORAGE_FULL` (owner numbers); (4) the sweep aborts uploads idle 24 h and deletes their bytes; (5, part) at most 3 open attempts per person; (6) one live poll per attempt and session (per session, so two phones of one person do not push each other out), 4 per account, 400 per server, and the open-file limit raised at start-up. Not adopted: (2) a server-wide cap on successful sign-ups, because P4 asks that no server-wide counter refuse a request with a live token, and the per-invite limit plus the captain-code gate already bound the total; (5, part) a 5-minute re-claim cooldown, because a re-claim always joins the end of the FIFO queue, so a bot that lets holds run out never passes a real waiter, while the cooldown would block the owner's "try again". Tests: the 201st sign-up through one invite gets 403; an upload over quota gets 409 and below the floor 507. | 2.3, 2.7, 3.3, 6.5 |
| **P4** (medium) server-wide failure caps acted as a lockout switch | Adopted: per-source keys refuse (after B6a names a trusted proxy); a request with a live token or a session is never refused by a server-wide counter; no server-wide login cap (the hash queue protects the CPU); `USERNAME_TAKEN` is not a failure; 8-character codes by default; the 10 000 most common passwords refused (`PASSWORD_REJECTED`, COMMON); the limiter never evicts a locked key. A per-username lock at 50 failures per hour from any sources remains against spread guessing; usernames are visible only to their owners. Test: 61 failures from source X do not block source Y. | 3.1, 3.6, 2.2 |
| **P5** (medium) anonymous resolve revealed date, time, venue, schedule and seats | Adopted: anonymous resolve returns kind, team name, tag and the small logo only; schedule and seats need a session; request details go only to a caller who captains a team; `privacy_join` corrected and `privacy_request` added. Test: an anonymous LinkView holds no `playedOn`, `startTime`, `venue` or schedule. | 5.2, 9.3, 10.8, 11.3 |
| **P6** (medium) B10 privacy claims would turn false: a tunnel decrypts, proxies log, backups keep deleted data | Adopted: TLS ending on the owner's computer is preferred (options compared); a TLS-ending tunnel is named in the ADR, the inventory and `privacy_tunnel`; the proxy's access log off is a checklist item; backups are kept 7 days and the texts say so; the inventory lists proxy logs, tunnel traffic and backups; v2's sentence that no tunnel ever holds the token is withdrawn. | 2.2, 10.8, 11.2, 12.3, 12.6 |
| **P7** (medium) logos with lossless paths reached strangers' phones without decoding | Adopted: only one lossy `VP8 ` chunk with a valid frame header, a RIFF size equal to the body and no trailing byte; VP8L, VP8X and ALPH refused; the app draws on an opaque background; bounded decoding with initials on failure. Tests: VP8L, ALPH, VP8X, EXIF, trailing bytes and a wrong RIFF size are refused. | 4.2, 10.5 |
| **P8** (low) link tokens and codes in clear on the server and the phone | Adopted: SHA-256 of the token; HMAC-SHA256 of the code under a link key outside the database and every backup (and, since 13.1 S9, outside the storage folder); token, code and URL only in creation answers; the captain's phone keeps them in an encrypted per-account file; the pending link and resume record move to an encrypted store and are deleted when the flow ends. A captain on another phone renews instead of re-sharing. | 2.2, 8.2, 9.2, 10.3 |
| **F1** (high) a newcomer without the app had no path; too many taps for a newcomer with the app; code-only joining was slow | Adopted: the link host is an owner decision (website domain with explicit permission, recommended, or the API host by default once hosting exists); the fallback page shows the code from the fragment with a copy button, an intent with `S.browser_fallback_url`, and install steps with a re-tap; the share text has a line for people without the app; register-first with a username suggested from the display name; Kod Gir extracts the code from a pasted message and offers the clipboard code. Distribution stays APK by hand by default; the Play Store fee is not planned. | 2.2, 2.12 A and G, 10.1, 12.4, 12.5 |
| **F2** (high) real waiters lost their place on screen timeout or one failed poll, and the texts named a false cause | Adopted: `keepScreenOn` while WAITING, OFFERED or HOLDING; retries at 0.5 s then every 1 s while WAITING or OFFERED, and an immediate poll when the network returns; `OFFER_ACK` raised to 8 s; texts rewritten. Tests: a screen-off lifecycle event in `JoinControllerTest`; an e2e case where one poll fails just before the offer and the waiter still reaches HOLDING. | 2.6, 2.12 D, 10.2 |
| **F3** (high) each team's statistics needed the other captain; an early leave blocked the other side; 6.1 and 6.4 contradicted each other on video deletion | Adopted: one side result per side, `match_results(match_id, side, …)` with no foreign key to jobs; a team reads only its own; the other side's state is shown without blocking; a leave keeps the remaining side's result; video deletion never touches a side result, so the contradiction is gone. Added beyond the critic: one live video per side, so a team that films reviews its own video and never depends on the other captain. One-sided team matches against opponents not on the app are an owner decision with default yes. The trade-off (two teams' numbers may differ) is stated. | 5.7, 6.1, 6.2, 6.4, 7.2, 7.4 |
| **F4** (high) the 10 seconds could start under the password-save sheet, during a cold start or while a large logo loaded | Adopted: the decision point is the join screen with window focus after its first frame; the submit tap commits autofill so its sheet appears before the pop-up; the claim waits in `WaitingForFocus`; logos are out of the claim. Tests: no claim while unfocused; at 300 ms RTT the pop-up shows 10 and the phone's remaining time at open is at least 9.5 s. | 2.1, 4.2, 10.2, 14.1 B6b |
| **F5** (high) the recorder was locked until acceptance, against "the video part stays as it is" | Adopted: the recorder always opens; a team recording attaches on the phone to an accepted record, a pending request or a new request (created offline with a client id); `UploadEngine` uploads once the record is ACCEPTED and the own side has checked the opponent; "Aynı Rakiple Yeni İstek" opens the recorder at once; "Takım Maçına Bağla" moves a personal match whose upload has not completed; `video_waiting_accept` replaces `video_locked`. It breaks nothing: the server rule "upload only after acceptance" stays. | 5.5, 10.1, 10.3 |
| **F6** (medium) some texts were false in some states: closed invite, server off, changed result, stored data | Adopted: LINK_EXPIRED, LINK_RENEWED, LINK_CLOSED and FULL each have their own text; "no network" and "no answer" are split by `NET_CAPABILITY_VALIDATED`; `result_changed` exists per viewer (other captain, reviewer, member); `register_data` no longer says "yalnızca". Every join text now sits next to the exact state that shows it. | 2.12, 10.2, 10.8 |
| **F7** (medium) the code path could be locked for everyone by 30 bad lookups; pasting copied the whole message | Adopted: 8-character codes by default (owner decision); the anonymous code circuit breaker is 1 000 failures per 10 minutes; per-account and, after B6a, per-source keys do the refusing; paste extraction in Kod Gir. | 2.2, 3.6, 10.1 |
| **F8** (medium) the owner's own scenario was testable only in B10, after B7-B9; free hosting realities unstated; stale base | Adopted: B6a (exposure minimum) and B6b (field test) come before B7; B6b runs an automated shaped-network test always and a real-phone session in an owner window; the base is b2ce9b2; one owner decision table with a default and a cost for each item; no default needs a paid subscription (13.1 fidelity V4 states the hosting costs); hosting realities (computer awake, persistent disk, where TLS ends) are stated. | 12, 14.1, 16.1 |

### 13.1 Findings of the v3 checkers (17 November)

Four checkers verified v3 (concurrency, security, product, fidelity). Their probes are in `verify_v3/`; the per-point outcome and note are in `verify_v3.json`. Every point held, and this revision fixes each one. Two checkers used the same ids for different points, so the lens names each row.

| Point | What v3 got wrong | Fix in this revision | Section |
|---|---|---|---|
| **conc V1** (high) | The in-flight entry came only inside the route, after `BodyLimit` read the body; a slow body lost in-time ACCEPTs (6 of 6 measured); a set entry removed by one request unprotected another; T14 ignored ACCEPTs in flight; entries had no time bound | The stamping middleware takes the entry before the inner app and keeps a list of arrivals per attempt; protection only for entries that arrived before the deadline and never past deadline + 5 s; `408 BODY_TIMEOUT` after 5 s for ack and decision bodies; T14 keeps a hold whose decision arrived before the closing request; T17 sets `lateAccept` by arrival | 2.4, 2.5, 2.6, 2.9, 14.3 |
| **conc V2** (high) | Only the clock ran pause recovery, so a queued or buffered writer settled first and wiped the queue; the promised extra grace had no field | A database-free beat sets `recovery_due`; every write transaction runs recovery first when it is due or the beat is stale; `grace_until` on `join_attempts` drives T8 and T10a; recovery moves it | 2.4, 2.11, 2.13 7g, 8.2, 14.3 |
| **conc V3** (medium) | A late ack or ACCEPT on an attempt still OFFERED or HOLDING matched no transition | T6b and T8c close the attempt by its own late request and offer the seat in the same transaction; the route judges its own attempt before `settle` | 2.4, 2.9, 2.10 |
| **conc V4** (medium) | One poll answer lost without a reset dropped a waiter (30 s read timeout against a 30 s heartbeat) and skipped the head | `POLL_WAIT` 15 s with a read timeout of `wait` + 3 s; a waiter that may be offered next polls with `wait=2`; B6b swallows one answer | 2.6, 2.7, 10.2, 10.5, 14.1 B6b |
| **conc V5** (low) | A poll refused by a cap recorded no heartbeat, so the waiter was DROPPED with a false text | The heartbeat comes before any cap; over a cap the poll answers at once with the current view and `Retry-After` | 2.7, 9.2, 14.3 |
| **conc V6** (medium) | A ticket released only after COMMIT or ROLLBACK stayed taken when `BEGIN IMMEDIATE` failed, and every later writer hung | The ticket is taken before the connection opens and released in the outermost `finally`; at most 30 s of waiting, then abandoned | 2.8, 9.4, 14.1 B5 |
| **conc V7** (low) | Snapshots of a claim behind others kept the version, so a strict notifier dropped them | Every write transaction that builds a snapshot moves `queue_version`; the notifier replaces only on a strictly higher version | 2.4, 2.7, 8.2, 14.3 |
| **S1** (high) | A holder with two sessions could keep a hold open forever with a TIMEOUT flood; a decision after the grace on a hold still HOLDING was undefined; `NOT_DECIDING` was undefined | A late TIMEOUT closes the hold (T10a); entries after a deadline protect nothing and protection ends 5 s after it; 5 requests a second per attempt; `NOT_DECIDING` defined | 2.4, 2.9, 3.6 |
| **S2** (high) | Per-source keys counted validation failures, refused requests with a live token, ignored carrier-grade NAT and IPv6 rotation, mapped every tunnel user to "local", missed code misses on register, and let anyone lock a named user out | Gate failures only; no source key refuses a live token or session; limits of 100 per 10 min; IPv6 by /64; a named client-address header per hosting option; every code miss counts; device tokens exempt a known phone from the username lock; a register 429 text | 3.2, 3.3, 3.6, 2.12 A, 12.2 |
| **S3** (medium) | A removed person could rejoin with a new account through the live invite, and the removal text said the person was blocked | `member_remove_body` states the limit; the removal dialog renews the invite by default in the same transaction | 2.12 F, 4.3, 16.1 item 14 |
| **S4** (medium) | `?jobId=` on the report was undefined, so any job id could be read through any readable record | The job must belong to the path's record (`404 ANALYSIS_NOT_FOUND`); content ids are left out without `canReadContent` | 6.1, 9.2, 9.3, 9.4, 10.4 |
| **S5** (medium) | One link holder's accounts could fill the server's poll cap and get real waiters refused and dropped | Heartbeat before caps; closed attempts never wait; per-team share capacity + 200; over a cap the poll answers at once | 2.7, 14.3 |
| **S6** (medium) | `link.js` had no input rule, so a crafted fragment could redirect through the trusted host or inject markup | Strict checks of `t` and `c`, `textContent` only, an encoded fallback built from checked values, `form-action`, `base-uri` and `frame-ancestors` in the CSP; hostile-fragment tests | 2.2, 9.2, 14.1 B6a |
| **S7** (medium) | 200 invite accounts at 8 GiB each could fill the disk and stop every team's uploads | A 100 GiB server-wide personal pool; invite accounts get 2 GiB and one unfinished upload; `storage --top` | 3.3, 6.5, 8.2, 10.8, 16.1 item 4 |
| **S8** (low) | A CAPTAIN code was not bound to the account that registered with it | `used_by_user_id`; only that account creates the team | 3.3, 4.1, 8.2 |
| **S9** (low) | `link.key` sat inside the storage folder, so a copied folder held both the HMACs and the key | The key file lives in the configuration folder; the server refuses a path inside the storage folder | 2.2, 11.2, 12.2, 12.6 |
| **S10** (low) | With Funnel the computer's name reaches public CT logs and every link; tailscaled uploads logs | A neutral host name before HTTPS; the `--no-logs-no-support` choice recorded | 11.2, 12.2, 12.3 |
| **R1** (high) | A held recording had nowhere to go after a decline, an expiry, an AWAY cancel or a wrong acceptance, and `video_waiting_accept` stayed false | `POST …/solo` turns a closed or pending request into a SOLO record; AWAY's cancel is a leave; Rakibim Değil; siblings of an ACCEPTED record start ACCEPTED; 7.4 rows | 5.3, 5.4, 5.5, 5.7, 6.1, 7.4, 10.8, 16.2 |
| **R2** (high) | The paste pattern ignored case and allowed L, so it matched words before the code | Token from the URL first, then `c=`, then a case-sensitive pattern without L with boundaries and one distinct value; probe 100 % | 10.1, 2.12 A, 14.1 B6 |
| **R3** (high) | A closed link's code became unknown, so a right code got `code_wrong` and a false lock text | `closed_code_hmac` on the tombstone; a code hit answers 410 with the reason and counts toward no key; new codes avoid tombstone HMACs; the lock text names unknown codes | 2.2, 2.12 A, 2.13 7a′, 3.6, 8.2, 11.2 |
| **R4** (medium) | Every tunnel connects from loopback, so one shared "local" key could lock out everyone | "local" never gets a source key; `VOLLEYSTAT_PUBLIC_URL` requires `--trusted-proxy`; B6b checks two phones | 3.6, 12.2 |
| **R5** (medium) | A 401 erased the pending link and the resume record, against `session_ended` | `pending.bin` survives a 401; after a login by the same account the join continues without a new tap | 10.2, 10.3, 10.7, 10.8 |
| **R6** (medium) | Uygulamada Aç built an intent with host `j`; the installer's Aç led to a dead-end sign-up | `intent://<link host>/j/…` with an encoded fallback; a first-start screen with Kod Gir first; register asks for the code first | 2.2, 2.12 A, 10.1, 12.5 |
| **R7** (medium) | A team that did not film lost its numbers when the filming captain left or deleted before it confirmed | Result offers stored at each list confirmation, confirmable after the job, the video or the side is gone | 6.2, 6.4, 7.2, 7.4, 8.2 |
| **R8** (low) | A tunnel or home connection outage dropped and skipped everyone | Silence detection with a probe; no T7, T10a or T12 during a silence; recovery when requests return | 2.6, 2.11, 2.13 7h |
| **R9** (low) | The B6b pass floor of 9.5 s failed about half of the correct runs | The floor is judged per exchange against its measured round trip | 14.1 B6b |
| **R10** (low) | `queue_skipped`, `server_busy`, `upload_storage_full`, `upload_quota_team`, `link_loading` and `join_gone` were false or spoke past the reader | Rewritten texts; the app repeats after each `Retry-After` while the screen is open | 2.12, 10.7, 10.8 |
| **fidelity V1** (high) | v3 planned other operator-approved teams before the KVKK review, against ADR-0117 | Captain codes only for the owner's own teams listed in ADR-0119, bound to their names; 5.7, 7.4, 12.5 and 16.2 rewritten | 0.4, 3.3, 4.1, 5.7, 11.3, 12.5, 16.1 item 3 |
| **fidelity V2** (high) | Same as R3, plus the missing 2.13 row for P14 with the code | As R3; row 7a′ | 2.2, 2.12 A, 2.13 |
| **fidelity V3** (high) | Same as conc V2 | As conc V2 | 2.11 |
| **fidelity V4** (medium) | "None needs a paid subscription" was not true for a stable host | Each hosting option states what it needs and costs; Funnel means a paid plan under ADR-0117 | 12.3, 16.1 item 1, 16.2 |
| **fidelity V5** (medium) | Same as R2 | As R2 | 10.1 |
| **fidelity V6** (medium) | The resume record held no link, so Yeniden Sıraya Gir had nothing to send after a process death | The resume record keeps the link; without one the button hides and `join_reopen_link` shows | 2.12 C, 10.2 |
| **fidelity V7** (medium) | T8 closed the invite as FULL before `settle`, whose T14 line then closed the waiters with reason FULL | T14 never applies to FULL; T13 closes them as TEAM_FULL | 2.2, 2.4, 2.5, 2.13 7a |
| **fidelity V8** (medium) | `queue_skipped`, `join_unknown`, `link_loading`, `code_body`, the `/m/` fallback text and `server_busy` were false in some states | `offerWindowS`, `join_unknown_timeout`, `code_checking`, `codeLength`, a `/m/` text, repeats while the screen is open | 2.4, 2.12, 9.1 |
| **fidelity V9** (medium) | Request texts promised an upload at acceptance, used the passive, misnamed the person in check 6 and spoke of an opponent on SOLO records | Rewritten texts; `details.who`; SOLO variants | 5.2, 5.5, 10.8 |
| **fidelity V10** (medium) | `login_wait`, `upload_storage_full`, `delete_account_body`, `team_capacity_in_use`, `register_username_hint` and `session_ended` were false in some states | Texts per throttle scope and per state; `session_ended` matches R5 | 2.12 F, 3.1, 10.8 |

---

## 14. Slicing plan and tests

### 14.1 Slices

Prerequisite: the multi-match branch (b2ce9b2) merges first, and every slice rebases on that merge. Server and app versions follow the repository's rule (the app needs the server version that holds what it calls); the minor number is settled at each merge.

| Slice | Content | Shippable because | Tests |
|---|---|---|---|
| **B0** Contract and ADR-0119 | API_V1 sections 10-12 and the error codes, API.md 0.8, ADR-0119: accounts, the sign-up and team-creation gates, the join queue (arrival order, the single-process constraint), side results, the data inventory, where TLS ends. The invariant "Sunucu oyuncu adı tutmaz" becomes "Sunucu yalnızca kişinin kendisi için verdiği görünen adı tutar. Telefon kadrosundaki adlar telefonda kalır." | docs only | review |
| **B1** Server accounts | `users`, `sessions`, `operator_codes`; auth and `/v1/me*` routes; the hash executor; the common-password list; the session cache; `access_log=False`; `forwarded_allow_ips=""`; admin CLI `signup-code`, `captain-code --team-name` (with the ADR-0119 list and `--after-kvkk`), `reset-password`, `storage --top`; `login_devices` and device tokens; `signup_via`; health `accounts`. Existing routes still open. | additive; the old app still works | `test_auth.py` (format, NFKC, COMMON, dummy hash, register order and identical answers, expiry, sliding renewal, password change, a CAPTAIN code opens one registration and stays valid for one team of its name and for the account it registered only, a device token skips a locked username key), `test_hash_pool.py` (503 and a fast route during a flood), `test_session_cache.py` (logout evicts at once; a CLI deletion stops polls within 60 s), deletion byte search |
| **B2** App login | `SessionStore`, the encrypted pending store, per-account folders, the legacy question, register-first, username suggestion, autofill commit, token on every call, ExoPlayer header, 401 handling, login and register screens, Profile rows, rewritten privacy rows | the server accepts and ignores the header on old routes | `AppViewModelTest` (header after login only; 401 clears session and cache and keeps the pending store; claim, 401, login by the same account: the claim goes out once and the pop-up opens, and another account does not resume the old attempt (R5); account isolation A then B), `SessionStoreTest`, `PendingStoreTest`, `UsernameSuggestionTest` ("Çağrı Öztürk" → `cagri.ozturk`), `LocalStoreTest` v2 → v3, screenshots `14_giris`, `15_hesap_olustur` (with and without the team card), `AccountE2eTest` |
| **B3** Enforcement and storage limits | `current_user` everywhere, PERSONAL and LEGACY, `access.py`, the route walk, the decision replay order, `legacy --assign`; quotas, the disk floor, `touched_at` and the idle-upload sweep; app maps `NOT_A_MEMBER`, `QUOTA_EXCEEDED`, `STORAGE_FULL` | today's flow works behind login | `test_access.py` (every existing operation: anonymous 401, stranger 403 never 404, owner OK; a member who joined after `played_on` gets 403 or 404 through `/v1/matches/{R}/report?jobId=J` and through `/v1/matches/{own personal match}/report?jobId=J`, S4), `test_migration_accounts.py`, `test_quotas.py` (declared size over quota 409; an invite account at 2 GiB and one unfinished upload; 30 invite accounts fill the personal pool and a team upload still succeeds, S7; a patched `disk_usage` gives 507; the sweep aborts an idle upload and deletes its bytes), `UploadEngineTest` (an ABORTED upload restarts from zero), all existing tests through the storage-scoped fixture |
| **B4** Teams | team tables and routes, the captain-code gate, the random tag, two-size lossy logo, members, jerseys, removal and blocks, unblock, the new-member badge, transfer, delete; app Teams tab, team screen, settings, Takım Kur, Çıkarılanlar | teams exist without joining | `test_teams.py` (one captain; jersey uniqueness under two threads; transfer conflict; `CAPTAIN_CODE_REQUIRED` and consumption; the tag ignores the client id and stays unique under a forced RNG collision; logo refusals: VP8L, ALPH, VP8X, EXIF, trailing bytes, wrong RIFF size; removal blocks and unblock; removal with Bağlantıyı da Yenile off lets a new account of the removed person join, and on answers 410 RENEWED, S3), team deletion byte search, screenshots `16_takimlar` (badge), `17_takim`, `18_takim_ayarlari`, `18b_cikarilanlar` |
| **B5** Join server | `share_links` with hashed tokens, HMAC codes, `link.key` and tombstones; resolve with its three levels; `join_attempts`; claim, ack, decision, leave; the long-poll from published snapshots with view tags; arrival stamps with the in-flight entries in the same middleware, the body time limit, the rate keys and the TIMEOUT rule; `settle`; the clock, the beat, pause recovery in the write path, the silence check and the heartbeat flush; the notifier; the FIFO writer lock in `db.connector()`; start-up recovery; sign-up by invite with its limit; the invite closing at full; the captain's live view; poll caps; the open-file limit; the code throttle keys | joining works end to end by code | `test_join_state_machine.py` (T1-T17 with T6b and T8c, with a fake clock; grace edges at arrival `grace_until` − 0.01 and + 0.01 s; TIMEOUT before and after `expiresAt` with `other_session` 0 and 1, and after `grace_until`; T17's two flags; the protection tests of 2.9; a 60 s freeze recovers without DROPPED or SKIPPED; an ack 0.1 s late gets 409, the head is offered in the same transaction, and no row is added; the last seat taken with two waiters open closes them as TEAM_FULL, fidelity V7), `test_join_concurrency.py` (14.3), `test_join_restart.py`, `test_fifo_lock.py` (ticket order; a nested acquire raises; never on the loop thread; a `BEGIN IMMEDIATE` that fails with `database is locked` releases its ticket and the next writer proceeds; an abandoned ticket is skipped, conc V6), `test_links.py` (only hashes stored; tombstone reasons; anonymous resolve fields; secrets only in creation answers; a renewed invite's old code answers 410 RENEWED eleven times and the account can still use the new code, R3; a new code never reuses a tombstone's HMAC; a missing link key leaves tokens working; the server refuses to start with the key inside the storage folder, and the key is not under the storage folder, S9), `test_join_payloads.py` (no logo bytes, under 2 KB, `waitedMs` present), account and team deletion during a queue |
| **B6** Join app | link landing, the first-start screen, Kod Gir with the paste extraction of 10.1, register-first with the team card, the pop-up, the waiting screen, outcomes, the jersey prompt, the invite card and Paylaş, keep-screen-on, focus gating, the retry policy, network classification, the resume record, fresh tap versus resume, debug deep links | the owner's join flow works on emulators | `JoinControllerTest` (MockWebServer: every client state of 10.2; D never above the server's under random `waitedMs` and `remainingMs`; a 15 s HOLDING poll answer does not lower D; no claim while unfocused; a screen-off lifecycle stops and restarts polling; retries at 0.5 then 1 s; a network callback polls at once; decision repeats; process death after REJECTED leads to `GET` and no claim; a recents relaunch resumes; an ack after one failed poll lands inside the window; every key of 2.12 is reached from its state; polls send `wait=2` while ahead < deciding; Yeniden Sıraya Gir after a process death sends a claim with the stored link, fidelity V6), `CodeExtractionTest` (both share texts and the request text with 10 000 random tokens and codes, and a list of Turkish team names, always yield exactly the token or the code, R2), a fresh install with the code in the clipboard reaches the pop-up without the WhatsApp re-tap (R6), screenshots `19_davet`, `19b_davet_kapali`, `20_kod_gir` (empty, paste offer), `21_katil_karar` (10 s, 3 s, logo, initials, no schedule, sending, time up), `22_sira` (next, ahead 2, reconnecting, no network, your turn), `23_katilim_sonucu` (every outcome), `24_forma`, `JoinE2eTest` and `AppJoinFlowE2eTest` against the real backend |
| **B6a** Exposure minimum | 12.2: `--host`, `--trusted-proxy`, the source keys, the start-up refusal, `VOLLEYSTAT_PUBLIC_URL`, `assetlinks.json`, `/j/`, `/m/` and `link.js` with their CSP, `ServerTarget.REMOTE`, the link host Gradle property, `privacy_server` and `privacy_tunnel` arguments, the operator checklist | nothing changes locally; a test window becomes possible | throttle tests (61 failures from X do not block Y; the circuit breaker never refuses a token or session request; a loopback proxy without a client address shares no key, R4; two IPv6 addresses in one /64 share a key), proxy header test per hosting option, start-up refusals (also `VOLLEYSTAT_PUBLIC_URL` without `--trusted-proxy`), `assetlinks.json` content, the fallback page (no inline script; the CSP header with `form-action`, `base-uri` and `frame-ancestors`; a Node test in CI parses the intent URL as `Intent.parseUri` does, with the data before the last `#` and `intent:` replaced by the scheme, and asserts that the data matches the manifest filter and keeps the fragment, R6; hostile fragments such as HTML, a second `#Intent;`, `javascript:` and overlong values create no element and leave the intent's fixed prefix and package unchanged, S6) |
| **B6b** Field test (F8) | (1) automated, always: `test_join_network.py` runs the 14.3 burst through a Python TCP delay proxy in the test helpers (150 ms each way; 2 % of reads stall 400 ms, standing in for TCP retransmissions; a mode that swallows one long-poll answer without closing the connection, run for a mid-queue waiter and for the head at the moment of the offer, conc V4; a mode that blocks all traffic for 45 s, R8), and `JoinE2eTest` runs through the same proxy on the emulator. (2) real phones, in an owner window (16.1 item 6): 30 minutes with 5-10 phones from a WhatsApp group on the owner's computer behind the chosen tunnel, with B6a on and captain codes printed. Cases: a newcomer without the app (APK by hand, re-tap), a newcomer with the app (register-first), a logged-in member (link, then Katıl), code paste from the message, one person on two phones, a waiting screen left alone for 3 minutes, a Wi-Fi to mobile data switch, the laptop lid closed for one minute, the tunnel stopped for one minute, the captain renewing mid-queue, the app installed with its link not verified (Uygulamada Aç opens the pop-up, R6), and two phones on different mobile networks getting different source keys (S2, R4). Record per case the screens (phone screenshots), the server's counts (no names, no addresses) and any text that did not match the state. | evidence before the request slices | pass: for every holder, the remaining time at the pop-up's opening is at least 10 000 ms minus that exchange's measured round trip minus 50 ms, and the server's `expires_at` minus the phone's D is at most the measured uplink time plus 50 ms; exchanges without a stall also keep the 9.5 s floor; the pop-up always opens showing 10 or 9 (R9: a fixed 9.5 s floor failed about a third to a half of correct runs, because a 400 ms stall leaves about 9.3 s); no waiter with a live poll is SKIPPED or DROPPED, also with a swallowed answer and during the 45 s block; no ACCEPT that arrived in time is lost; in the real session, no false text, the pop-up opens showing 10 or 9, and no lost place with an open screen. Findings return to B5 and B6 before B7. |
| **B7** Match requests | request links, accept and decline with every check, the opponent check, states and expiry, SOLO records (default yes), `GET /v1/matches`, the per-side upload gate with quotas, reviewer-only routes per video, `TEAM_MATCH`, cancel and leave with the side's video deletion; app request screens, the New Match team flow with the recorder always open, held uploads, offline request creation, Aynı Rakiple Yeni İstek, Takım Maçına Bağla, `tm/{id}`, Rakibi Onayla | requests and team videos work | `test_match_requests.py` (a request link cannot sign up; request details only to captains; the accept checks; decline stores the team's name and tag; HOME's upload refused before the opponent check; a declined PENDING request turns into SOLO, the held upload starts and A's statistics count the match, R1; Rakibim Değil; AWAY's cancel on an ACCEPTED record is AWAY's leave; a sibling of an ACCEPTED record starts ACCEPTED, and a fourth answers `409 SIBLING_LIMIT`), `test_shared_match.py` (a member of B cannot upload or analyse; one video per side; the reviewer per video; leave deletes the leaving side's videos and keeps the other side's), leave byte search, screenshots `25_mac_istegi`, `26_istek_ac`, `27_takim_maci` (waiting for acceptance, opponent check, both videos), `27b_kayit_bekliyor`, `MatchRequestE2eTest`, device test `RecorderAlwaysOpenTest` (record against a PENDING request, accept afterwards, the upload starts) |
| **B8** Side results and rosters | result confirm per side, `match_results` without job foreign key, rosters with edit, confirm and leave, the member read rule on content; app `tm/{id}/report?job=`, Sonucu Onayla, Maç Kadrosu, `result_changed_*` per viewer | results can be confirmed | `test_results.py` (A's edits, re-analysis, rule change, video deletion, leave and team deletion leave B's side result byte-identical; B never confirming leaves A COUNTED; a stale revision is refused; a side result survives the deletion of its job; A confirms the list and leaves, B confirms the offer afterwards, and B is COUNTED, R7), `test_rosters.py`, `test_privacy_payloads.py` (no HOME names in AWAY payloads; a member who joined after `played_on` gets 403 on content and 200 once rostered; anonymous LinkView fields), screenshots `28_mac_kadrosu`, `29_sonuc_onayi` (three viewer variants) |
| **B9** Statistics | `stats.py`, the two routes; app statistics screens | read-only additions | `test_stats.py` (unknown never 0 as a property test over random rally lists; demo and unconfirmed never counted; only the own side result counts; `otherSide` never changes a number; `ROSTER_WAITING`; parts; line sums equal totals; SOLO counts for the own team only), screenshots `30_takim_istatistigi`, `31_kisisel_istatistik` |
| **B10** Permanent hosting | nightly copies kept 7 days without `link.key`; the backup sentences in the privacy texts; recovery codes; health `mode` and the FastAPI description stop saying "local-dev" only behind TLS | changes nothing locally | backup rotation deletes copies older than 7 days; `link.key` never in a copy; recovery code flow |

Each slice runs the full backend suite, `:app:testDebugUnitTest`, the screenshot job, the e2e and device jobs, and an independent review before its PR. Each slice that deletes anything extends the byte-search test. Every new Turkish string passes `turkce-insansi-metin` before its PR.

### 14.2 Fixtures and CI

- **Storage-scoped auth fixture.** The `client` fixture creates one user and one session per storage folder and sets `client.headers["Authorization"]`. A test that restarts the server on the same storage gets the same token, because sessions live in the database. `helpers.serve` does the same. Extra fixtures: `anonymous`, `stranger`, `two_teams` (two captains, a member each, an ACCEPTED record with both opponent checks), `fake_clock`, `delay_proxy`.
- **CI.** The e2e and device jobs start the backend with `VOLLEYSTAT_SIGNUP=open` and `VOLLEYSTAT_TEAMS=open`; the tests of the gates set `link` and `operator` explicitly. E2E tests register `e2e_<random>` users and delete them afterwards. `require_tests_ran.py` rises from 11 to 16 (e2e) and from 2 to 4 (device); the device tests log in on the emulator, record against a PENDING request, and reach the recorder of an ACCEPTED record.

### 14.3 The concurrency test (B5)

`backend/tests/test_join_concurrency.py` runs against the real server, not `TestClient`, with the app's polling on:
1. Seed a storage folder with a team (capacity 11, the captain in), its invite, and 101 users with sessions written straight into SQLite (no 101 scrypt hashes).
2. Start `python -m app --port <free port> --analyzer none` in a subprocess on that storage.
3. A raw-socket generator (asyncio streams, two keep-alive connections per phone, or one process per 25 phones) sends 100 `POST /v1/join-attempts` at once. The test asserts from the stored `arrived_at` values that the arrivals spread less than 50 ms; otherwise the run is invalid and repeats.
4. Every phone that gets WAITING or HOLDING starts its long-poll at once with its `view` and re-polls the moment an answer arrives, during the burst, as the app does.
5. Assert: every claim answered within 3 s in CI (the owner's computer is held to p95 under 1 s); exactly 10 HOLDING and 90 WAITING; sorted by `arrived_at`, `queue_no` increases for every pair more than 5 ms apart, and the holders are the 10 earliest arrivals within that tolerance; each waiter's `ahead` equals its rank; members + offered + holding ≤ capacity in the database; fewer than 400 poll answers during the burst (the critic measured 145 with these fixes and about 2 000 without); after the burst every WAITING attempt is in the published snapshot, and the burst's polls made no database read (conc V7).
6. The same person claims twice at once: one attempt.
7. With 90 long-polls waiting, `GET /v1/health` answers within 100 ms.
8. Nine holders accept and one rejects: the head waiter's poll returns OFFERED within 0.5 s of the reject's answer; its ack returns HOLDING with `remainingMs` above 9 900.
9. The new holder does nothing and sends no TIMEOUT: the next waiter is offered between 2.0 and 2.5 s after `expiresAt`. A holder that sends TIMEOUT 0.3 s before `expiresAt`: the next waiter is offered within 0.5 s after `expiresAt`, and the hold was still HOLDING until `expiresAt`.
10. A second process holds the SQLite write lock for 2.5 s; an ACCEPT arrives at `expiresAt + 1.0`: JOINED, and nobody was offered meanwhile (C2). An ACCEPT whose headers arrive 0.1 s before `grace_until` and whose body follows 0.3 s later: JOINED, and nobody was offered (conc V1).
11. An ack arrives 0.1 s after `offer_deadline`: `409 ATTEMPT_CLOSED` (SKIPPED) by T6b, the head of the queue is offered in the same transaction, and no row is added (C5, conc V3).
12. A HOLDING poll that waited 15 s answers with `waitedMs` about 15 000 and a `remainingMs` that keeps `sentAt + waitedMs + remainingMs` within 50 ms of the true deadline (C3).
13. With maximum-size logos, every claim, ack, decision and poll answer is under 2 KB (C7).
14. Slow-marked: the SIGSTOP cases (a) to (d) of 2.11: waiters polling with an offer open; a writer queued behind a 0.6 s write at the stop; a claim sent during the stop; an ACCEPT sent during the stop. Nobody DROPPED or SKIPPED, no queue wiped, the offer has at least 10 s, and the ACCEPT is JOINED (C6, conc V2).
15. Kill the server with a hold and an offer open, restart it, and assert 2.11.
16. Final state: members = capacity, the invite closed with reason FULL, every remaining waiter CLOSED with TEAM_FULL.
17. Poll caps set to 10 with 30 waiters polling for 40 s: nobody DROPPED (conc V5). 400 polls from link-created accounts on closed attempts DROP or SKIP no waiter of another team (S5).
18. A holder with two sessions sends TIMEOUT every 20 ms for 60 s: the next waiter is offered within 2.5 s after `grace_until` (S1).

The Android side runs `JoinConcurrencyE2eTest` in the e2e job: 100 `ApiClient` claims from a 100-thread pool against the CI backend, each followed by its long-poll loop, with the same partition assertions, so the app's HTTP stack and parsing are tested under the same burst. B6b runs the same through the delay proxy.

---

## 15. Deliberately left out

| Left out | Why |
|---|---|
| push notifications (FCM) | device tokens go to Google, a KVKK question; the app polls only while the join screen or the captain's live view is open |
| Google sign-in, e-mail, phone | Google later with a free OAuth client |
| recovery codes | B10, when strangers can reach the server for good |
| captain approval of joins | speed; the captain removes and blocks instead |
| several live invites per team, per-invite capacity, a one-off date per invite | not asked |
| a team or user directory, search, a previous-opponent picker | privacy |
| export endpoint, date filters, logout-all, session cap | not asked |
| reviewing the other side's video from a phone that did not add it; clip playback for the other side | a later slice that re-keys the review chain by server ids; a side that films reviews its own video instead (6.2) |
| per-part rosters | one roster per video (6.3) |
| arbitration of result disputes | each side counts only its own captain's result, so a dispute changes only the disputing side's line |
| a server-wide cap on successful sign-ups (P3 item 2) | conflicts with P4 (no server-wide refusal of requests with a live token); the per-invite limit and the captain-code gate bound the total |
| a 5-minute re-claim cooldown (P3 item 5, part) | a re-claim joins the end of the FIFO queue and never passes a real waiter; the cooldown would block "Yeniden Sıraya Gir" |
| a deferred deep link after a sideload | not possible outside Play; the person taps the WhatsApp link again |
| website files without the owner's permission | the website is the owner's; 12.4 |
| rankings, ratios without counts, "best player" | design.md line 61 |
| offline queueing of account actions | the app never shows an action as done before the server confirms; only a match request is created offline, by client id |
| roles beyond captain and member | not asked |
| Play Store distribution | a one-time fee; APK by hand is free |
| a web app or iOS | not asked |
| minors' consent, the aydınlatma metni | the KVKK review |
| more than one server process | the snapshot, notifier, session cache, in-flight entries and FIFO lock assume one process; the ADR records it |
| the time the server wrote a poll's answer as a heartbeat (conc V4's first option) | `queue_dropped` says the server heard nothing from the app for 30 s; counting the server's own answer would make that false, so the revision lowers `POLL_WAIT` instead |
| a second live video per side of one record | one reviewer per side keeps the audit simple (8.4); a second recording of an accepted match goes to a sibling record (5.5) |

---

## 16. Owner decisions and risks

### 16.1 Owner decisions

Every item has a default that works without the owner's action, and no default needs a paid subscription. Item 1 states what each hosting option needs and costs; the only public option without a fee is the reverse proxy with a free dynamic-DNS name, where the home connection allows it (fidelity V4).

| # | Decision | Default without the owner | What the default costs |
|---|---|---|---|
| 1 | **Hosting and tunnel** (12.3). A reverse proxy on the owner's computer: no fee, needs a free dynamic-DNS name whose terms allow this use and a home connection with a public address and an open port 443. Tailscale Funnel: TLS ends on the computer, but the free plan is non-commercial, so for this closed commercial product it means a paid plan (not planned). Cloudflare Tunnel: free tunnel, but a stable host needs a bought domain or the website domain's DNS on Cloudflare (an owner-permission change like item 2), and TLS ends at Cloudflare abroad. A free VM: often a payment card and data abroad. Whichever host: a neutral host name, because it becomes public; with Tailscale, whether tailscaled runs with `--no-logs-no-support` (S10). | no public server; everything on one computer | no use between teams in different places, no real-phone field test, no link that opens the app; codes only, on emulators and USB phones |
| 2 | **Link host** (12.4): the owner's website domain (four static files; needs the owner's explicit permission for a website change inside a planned release) or the API host | the API host once hosting exists; codes only before | links in old WhatsApp messages break whenever the API host changes; the fallback page is down whenever the owner's computer is |
| 3 | **Team creation gated by a captain code before the KVKK review, and the list of the owner's own teams** (0.4, 4.1, fidelity V1) | on (`VOLLEYSTAT_TEAMS=operator`); the operator prints codes only for the team names that ADR-0119 lists as the owner's own, and the CLI refuses other names without `--after-kvkk` | the operator runs `python -m app.admin captain-code --team-name <name>` once per own team; before the review no other team joins the server, accepts a request or plays a match on the app, so matches against other teams count only as one-sided records |
| 4 | **Storage quota numbers** (6.5, S7) | 8 GiB personal video per account opened with an operator code; 2 GiB and one unfinished personal upload per account opened through an invite; 100 GiB of personal video on the whole server; 40 GiB per team; 20 GiB free-disk floor; abandoned uploads swept after 24 h | a team holds about 10 maximum-size videos before it must delete old ones (statistics stay); a member who joined through an invite holds about two maximum-size personal videos; a phone that pauses an upload for more than a day restarts it from zero; uploads stop when the disk nears 20 GiB free |
| 5 | **Code length**: 6 or 8 characters (2.2) | 8 (`K7QF-2M9X`) | two more characters to type when pasting is not used |
| 6 | **Field test with real phones** (B6b) | the automated shaped-network run only; B7-B9 continue; no team uses the app outside a field-test window until the real session passes | B7-B9 rest on emulator evidence; what only real phones show (WhatsApp's browser, a maker's screen-off rules, network switches) may rework B6 screens later |
| 7 | **One-sided team matches** against opponents not on the app (5.7) | yes | those matches carry one captain's review only; one more route and screen. A declined, expired or cancelled request also turns into a one-sided record through it (R1); without it such a recording counts only through a request the other captain accepts (16.2) |
| 8 | **Distribution** (12.5): APK by hand, a public GitHub Release, or Play Store | APK by hand, shared by the captains of the owner's own teams | every newcomer allows an unknown-source install and taps the link again after installing, or uses Kod Gir on the first-start screen |
| 9 | **Decision window for screen-reader users** (2.14) | 10 s for everyone | TalkBack users get the same 10 s |
| 10 | **Backups** (12.6, B10) | nightly copy on the same computer kept 7 days, without the link key | no protection against losing the disk; deleted data stays in copies up to 7 days, and the texts say so |
| 11 | **Numbers** | capacity 14 (range 2-50); invite and request validity 7 days; 20 teams and 10 captaincies per person; queue 200; sign-ups per invite 200; 3 open attempts per person; heartbeat 30 s; poll wait 15 s with a read timeout of wait + 3 s (wait 2 s for a waiter who may be offered next); offer acknowledgement 8 s; grace 2 s; in-flight protection at most 5 s after a deadline; 5 acks or decisions a second per attempt; roster 12; polls 4 per account, capacity + 200 per team and 400 per server; source limits 100 failures per 10 min; 3 sibling records per accepted record | a ghost at the head of the queue costs the next person 8 s; a leaked invite can open up to 200 accounts before the captain renews it; a waiter far back polls every 15 s |
| 12 | **Renewing the link closes the queue** | yes | the waiting people must open the new link |
| 13 | **Team schedule** | weekday and time | a one-off match day cannot appear in the pop-up |
| 14 | **A removal renews the live invite** (4.3, S3) | the switch Bağlantıyı da Yenile starts on | the waiting and deciding people's attempts close, and the captain shares the new link again where the removed person cannot see it |

Settled without a decision: the recorder stays open (F5, the owner's rule that the video part stays as it is); each side counts only its own captain's result (F3, required by "a team's own numbers must not depend on the other captain's goodwill"), and a side that did not film can confirm the result that the filming side's reviewer offered, even after that side left (R7).

### 16.2 Risks

| Risk | Detail |
|---|---|
| One process | The snapshot, notifier, session cache, in-flight entries and FIFO lock assume one server process. A second process stays correct for writes but its polls answer only after 15 s. The ADR records the constraint. |
| Other processes | The admin CLI writes from another process, outside the FIFO lock; SQLite's busy handler still serializes it. CLI use during a join burst can add up to the busy-handler wait. |
| The owner's computer | It must stay on and awake, with the tunnel running, for anyone to join or upload. A pause or a silence is recovered (2.11), but nobody joins during it. |
| Silence detection | Two phones that go quiet at once on an otherwise quiet server look like an outage, so their places wait until the next request arrives and 30 s more. A hold or offer whose deadline falls within the first 6 s of a real outage still closes (2.11). |
| Carrier-grade NAT | Many phones share one IPv4 address, so the source limits are 100 failures per 10 min; a burst of failures from one carrier address can still slow everyone behind it (3.6). |
| Free-tier terms | Tailscale describes its free Personal plan as non-commercial, which ADR-0117's rule excludes for this product; a stable Cloudflare host needs a domain; a free dynamic-DNS name needs terms that allow this use; Cloudflare ends TLS abroad; terms can change. The owner checks the current terms when choosing (12.3). |
| WhatsApp's browser and App Links | Without verified App Links the link opens a browser page and needs one more tap or the code. Android 12+ verifies only with `assetlinks.json` on a stable host. |
| Installs outside Play | Google announced developer verification for apps installed outside Play. The owner checks before 2027 whether the APK route then needs a registration. |
| Network jitter | The queue follows arrival at the server; two taps within about 100 ms can swap. |
| Leaked request link | Before the KVKK review every team on the server is one of the owner's own teams. After it, the requesting side's upload waits for its opponent check, and Rakibim Değil moves its recording to a one-sided record (R1). Tags help only if captains know each other's tags. |
| One-sided fallback | Without one-sided records (item 7 answered no), a requesting team's recording counts only through a request the other captain accepts; a declined or expired request leaves it uncounted (R1). |
| Differing numbers | Each team shows what its own captain confirmed, so A's points against may differ from B's points for. |
| Wrong roster | The roster is the captain's declaration. A person can leave it; the statistics screen states the rule. |
| Lost credentials | No e-mail: the operator resets until recovery codes arrive in B10. |
| Ghost waiters | A waiter whose app stopped holds a place for up to 30 s, and a ghost at the head costs the next person 8 s. |
| New members and old matches | A member who joined after a match sees its content only if the captain puts them on its roster. |
| Demo-only setups | Demo jobs never count, so a demo server shows UNKNOWN statistics forever. |
| Open-file limits | The server raises its soft limit at start-up; a hard limit below 1 024 lowers the poll cap and is logged. |

---

## Appendix: measurements and probes

v1's measurements still hold: Python 3.13.18, OpenSSL 3.5.9, fastapi 0.163.1, uvicorn 0.54.0 on 4 vCPU; scrypt n=2^15 r=8 p=3 at 165 ms and the `maxmem` requirement; the auth-before-validation order; uvicorn's proxy defaults; the critics' `xff_probe.py` and `pool_probe.py`.

v2's probe (`probe_v2/seat_server.py`, `driver.py`) measured the claim transaction and the owner's example with an httpx client. The concurrency critic showed that this client spread its "simultaneous" claims over 0.80-0.84 s and that the probe opened its long-polls only after the burst, so v2's 0.31-1.28 s timed mostly the client. v3 does not rely on those numbers.

The concurrency critic's probe (`critique_v2c/`): `crit_server.py` (the v2 model plus arrival stamps, lock-wait timing, `waitedMs` and the switchable fixes FIFO, SNAP, JUDGE=arrival and TIMEOUT_GUARD), `driver_raw.py` and `driver_raw_conn.py` (raw sockets, two keep-alive connections per phone), `driver_edges.py` (countdown and late acknowledgement), `driver_burst.py` (the httpx comparison); results in `burst_results.jsonl` and `spread_results.jsonl`. Environment: 4 vCPU container idle at start, Python 3.13.18, SQLite 3.53.1, fastapi 0.163.1, uvicorn 0.54.0, anyio's default 40 threads.

| Measurement | v2 model | v3 model (FIFO + snapshot + batched heartbeats + arrival judging) |
|---|---|---|
| 100 claims arriving within 12-19 ms, every phone polling: wall time | 6.6-7.1 s | 0.66-0.70 s |
| same: claim p95 | 5.1-5.2 s | 0.63-0.67 s |
| same: poll answers during the burst | 1 868-2 013 | 145 |
| same: server CPU | 11.0-12.0 s | 0.58-0.60 s |
| same: pairs out of arrival order (of 4 950) | 2 124-2 242 | 2-10, all within 2.2 ms |
| same: first 10 arrivals without a seat | 7-9 | 0 |
| decision wait inside the server | up to 1.7 s | ≤ 7 ms |
| reject → head waiter's poll returns OFFERED | 0.64-1.26 s | 6 ms |
| 100 people over 10 s: slowest claim | 8.3-9.8 s | 10 ms |
| 100 people over 10 s: in-time ACCEPTs judged EXPIRED | 3 and 7 of 10 | 0 of 10 |
| 100 people over 10 s: server CPU | 24 s | 1.2 s |
| the C3 case: wrong early TIMEOUT closed a hold with 3.99 s left | yes | no (hold kept; ACCEPT 1 s later JOINED) |

`critique_v2/tag_grind.py` found a team id with a victim's computed 4-character tag in 0.5-1.4 s on 3 runs; v3's random stored tag removes that path.

`probe_v3/e5_checkpoint.py`, run for v3 on the same container (load average about 0.4): a `wal_checkpoint(TRUNCATE)` after a secure-delete sweep took 0.000 s with 0 readers, 0.004 s with 5 and 0.056 s with 40, and no writer overlapping it waited more than 44 ms. The minute sweep's checkpoint therefore does not need the FIFO lock.

The v3 checkers' probes (`verify_v3/`): `v3_model.py` and `drive.py` (the v3 model with switchable placements of the in-flight entry and of pause recovery; mode `split` sends headers and body apart, mode `pause` stops the process with SIGSTOP), `late_ack.py` (an ack or an ACCEPT just after its deadline, before the next tick), `fifo_release.py` (the FIFO ticket after a failed `BEGIN IMMEDIATE`), `publish_order.py` (snapshot versions under a 100-thread burst) and `paste_probe.py` (code extraction). `verify_v3_fix/extract_probe.py` checks the extraction order of 10.1 on 20 000 messages of each kind.

| Measurement (v3 checkers) | v3 | this revision |
|---|---|---|
| headers 99 ms before the grace ended, body 300 ms later: in-time ACCEPTs kept | 0 of 6 | 6 of 6 |
| SIGSTOP with a writer queued at the stop: queues wiped | 3 of 3 | 0 (9 of 9 cases correct) |
| an ACCEPT buffered during a SIGSTOP: JOINED | 0 of 3 | 3 of 3 |
| an ack 50 ms after `offer_deadline` met an attempt still OFFERED, with no transition defined | 10 of 12 | T6b closes it |
| next writer after a failed `BEGIN IMMEDIATE` | still waiting after 5 s | succeeded |
| burst snapshots dropped as equal versions | 1 999 of 2 000 | 0 |
| paste extraction: first match is the code, link message | 15.5 % | 100 % (token first) |

Sources consulted for the hosting comparison: Tailscale Funnel documentation (https://tailscale.com/kb/1223/funnel) and Tailscale's plans page (https://tailscale.com/plans/). Both are to be re-checked by the owner when choosing.
