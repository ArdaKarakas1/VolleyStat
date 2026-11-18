# VolleyStat: accounts, teams with starter and substitute seats, the join link, open matches, captain match requests and statistics (design v5)

**Status:** design proposal. Slices B1 (server accounts) and B2 (app login) are built and merged; B3 is being built; nothing from B4 on is built. v5 replaces design v4 (`design_v4.md`) as a full document, and keeps every part of v4 that the owner's message of 18 November and the owner's two answers of the same day do not change. v4 replaced v3 (`design_v3.md`, committed as `docs/design/ACCOUNTS_DESIGN_V3.md` on the branch `claude/volleystat-accounts-design`).
**Date:** 16 November 2026. **Revised:** 17 November 2026 (v3, after four checkers); 17 November 2026 (v4, after the owner's answers to decisions 1-4); 17 November 2026 (v4 revision, after three checkers of v4: 13.3, `verify_v4.json`); 18 November 2026 (v5, after the owner's message of 18 November and two answers: "v5 changes"); 18 November 2026 (v5 revision, after two checkers of v5: "Changes after the v5 checkers").
**Base:** v4 was written against `main` at 7f18839 (API 0.7.0, app 0.7.0, ADR-0118), and its file and line references point there. v5 checked the gate code against `main` at d9b27a7 (API 0.8.0 with B1 and B2 merged, ADR-0119).
**Expected numbers:** ADR-0119 holds the accounts of B1 and B2, and API 0.8.0 shipped them. The owner's decision of 18 November becomes ADR-0120 (0.4). Each later slice raises the API's minor number at its merge (14.1). Check every number again at merge time.
**Inputs:** design v3 and its critique history (`critiques_v1.json`, `critiques_v2.json`, `verify_v3.json`, and the probes in `critique_v2/`, `critique_v2c/`, `probe_v3/`, `verify_v3/` and `verify_v3_fix/`); the owner's message of 17 November (0.1); the FIVB Official Volleyball Rules 2025-2028 in Turkish (rules 4.1.1, 4.3.3, 7.3.1, 7.3.3, 19.1.1 and 19.3.1.3); the code at 7f18839; the three checkers of v4 and their probes in `verify_v4/` (13.3); the owner's message of 18 November with the answers to two questions, and the merged code of B1 and B2 at d9b27a7 (v5 changes).

## v5 changes

### What the owner decided on 18 November

Slices B1 (server accounts) and B2 (app login) are on `main`, and B3 is being built. On 18 November the session described slice B4 to the owner: a team of 6 starter seats and 2 substitute seats by default, where the captain could set 1-6 starter seats and 2-12 seats in total, and, before the KVKK review, captain codes printed only for the owner's own teams, which ADR-0119 would list by name. The owner answered, verbatim:

> Kaptan ancak yedekleri değiştirebilsin. Sahada 6 kişi olmak zorunda. 
>
> Bu uygulamayı kullanacak net bir takım yok. Uygulamaya girerken kendini takım kaptanı olarak sa belirtebilirsin. Ya da 12 kişilik maç kutmak istiyorum  ara sıra oynayacağız mantığında da kurabilirsin. Orayı biraz sen geliştir

Translation (the session reads "sa" as "da" and "kutmak" as "kurmak"): "The captain may change only the substitutes. There must be 6 people on the court. There is no specific team that will use this app. When entering the app a person can state that they are a team captain. Or they can set it up as 'I want to set up a 12-person match, we will play now and then'. Develop that part a bit yourself."

The session then asked two questions, and the owner answered them, verbatim:

| # | The session's question | The owner's answer, verbatim | Translation |
|---|---|---|---|
| Q1 | "KVKK incelemesinden önce kimler takım ya da maç kurabilsin?" The session proposed that only accounts the owner gave a code to may create. | "Yanlış anlamışsın. Uygulama içerisindeki herhangi biri yani hesabı oluşturmuş herhamgi biri maç ve takım oluşturuabilir. Takıma ya da maça kişi eklemek için takım kaptanı veya maçı oluşturan kişi link gönderimi ile takıma ya da maça kişileri davet edebilir" | "You misunderstood. Anyone in the app, that is anyone who created an account, can create a match and a team. To add people to a team or a match, the team captain or the person who created the match can invite people to the team or the match by sending a link." |
| Q2 | The reading R1 below | "Evet, böyle" | "Yes, like this." |

### The decisions and where they land

| # | Decision | From | Where in v5 |
|---|---|---|---|
| E1 | A team always has 6 starter seats. The captain sets only the number of substitute seats (0-6, default 2) and still decides who holds which type. | "Kaptan ancak yedekleri değiştirebilsin. Sahada 6 kişi olmak zorunda."; Q2 | R1; 2.3, 4.1, 4.3, 8.2, 9.2, 2.12 F, 10.8; 16.1 item 15 |
| E2 | An open match always has 12 starter seats, 6 per side. The organiser sets only the substitute seats (0-12, at most 6 per side once sides exist, default 2). | "12 kişilik maç"; "Sahada 6 kişi olmak zorunda." | R2 (the session's reading, a default); 2.3, 5.8.2, 5.8.5; items 19 and 22 |
| E3 | Every side of a match roster has 6 starter places. A place that the roster names nobody for stays empty and shows "Boş"; nobody is invented, and no statistic counts a person the roster does not name. | "Sahada 6 kişi olmak zorunda." | R3 (the session's reading, a default); 5.8.5, 6.3, 7.1, 7.3, 9.3; item 34 |
| E4 | No list of the owner's teams and no captain code bound to a team name. | "Bu uygulamayı kullanacak net bir takım yok." | R4; 0.4, 3.3, 8.2, 11.3, 14.1; item 3 removed |
| E5 | After the first sign-in on a phone the app asks how the person will use it, with four choices. | "Uygulamaya girerken kendini takım kaptanı olarak ..."; "Orayı biraz sen geliştir" | R5 (the session's reading, a default); 10.1, 10.3, 10.10, 14.1; item 35 |
| E6 | Every account creates teams and open matches. A captain or an organiser adds people only by sending a link, and both kinds of join link open sign-up for a newcomer. The protective limits stay, and new ones replace what the gate bounded. | Q1 | R6; 0.4, 3.3, 3.6, 4.1, 5.8, 6.5, 11, 12.5, 15, 16; items 23, 32 and 33 |

### The readings

The owner settled R1 (Q2) and R6 (Q1). The other readings are the session's development of "Orayı biraz sen geliştir"; each is an owner decision in 16.1 with the reading as its default: R2 in items 19 and 22, R3 in item 34, R5 in item 35 and the limits of R6 in item 33. R4 follows from the owner's message and from R6.

**R1. Six on the court, for a team (settled, Q2).** A team has exactly 6 starter seats, always, and the captain cannot change that number. The captain sets only the number of substitute seats: 0 to 6, 2 by default, so a team has 6 to 12 seats (FIVB 4.1.1 allows a team at most 12 players for a match). The captain still decides who holds a starter seat and who a substitute seat: moves and swaps keep working (2.3).

*The other reading.* "Kaptan ancak yedekleri değiştirebilsin" also allows "the captain may only swap players in and out of the substitute seats": the captain could add and remove substitutes but never touch a starter. v5 does not take it. The server fills the starter seats first in queue order (D1b), so under that reading the order of joining would fix a team's starting six for good, and a captain could change it only by removing members and inviting them again. The owner confirmed R1 on 18 November (Q2). Under both readings the players of a given match are set per match in its roster, where the captain changes roles for that match (6.3).

**R2. Six on the court, for an open match.** An open match ("12 kişilik maç, ara sıra oynayacağız") has exactly 12 starter seats, always: 6 per side. The organiser sets only the substitute seats: 0 to 12 in total, 2 by default, and at most 6 per side once the organiser has split the players into sides (5.8.5). An open match has 12 to 24 seats.

**R3. Six starter places in every match roster.** Every side of a team match, a one-sided match and an open match has exactly 6 starter places. When fewer than 6 people of a side are named as starters, the other places stay empty: the server stores no row for them, invents no person, jersey or name, and every roster screen shows such a place as "Boş". An empty place says only that the roster names nobody for it. The app cannot know whether a starter was missing or a person without an account played there, and the texts say no more than that. No statistic counts a person the roster does not name, and an empty place never makes anyone's number 0: a point the reviewer gives to a jersey outside the roster stays on that jersey and reaches nobody, and only a point marked Oyuncuyu bilmiyorum stays unattributed and leaves the named players' missing numbers in that part UNKNOWN (6.3, 7.1).

**R4. No owner team list.** Nobody's team is known in advance. v4's list of the owner's own teams in ADR-0119 and the binding of captain codes to team names go. Neither was built (corrections below).

**R5. The entry choice.** After a person's first sign-in on a phone, the app asks how they will use it, with four choices: "Takımımın kaptanıyım" (create a team, then share its invite), "Ara sıra maç kuruyoruz" (open an open match with 12 starter seats, then share its link), "Bir bağlantım ya da kodum var" (join with an invite, a match link or a code) and "Önce kendi maçımı kaydedeyim" (today's personal match flow). The choice only opens the first screen. It is skippable and reachable again from the Teams tab and from Profile, and the server stores nothing about it; the phone keeps only that the account saw the screen. A person who arrived through an invite or a match link skips the choice and lands in that flow (10.10).

**R6. Who creates (settled, Q1).** Every signed-in account may create a team and an open match. There is no lead right, no captain code and no team list: B4 removes `VOLLEYSTAT_TEAMS` with its captain-code gate, and v4's members rule for open matches (`OPEN_MATCH_MEMBERS_ONLY`, v4's item 23) goes. The captain of a team and the organiser of an open match add people only by sending the team's invite link or the match's link. Both kinds of link open sign-up for a newcomer: an open match link no longer waits for the KVKK review (`VOLLEYSTAT_OPEN_MATCH_SIGNUP` defaults to `link`). Sign-up without any link still needs an operator code, as B1 built it; the owner did not decide whether that should change, so it is an open owner point for the hosting slice B10 (16.1 item 32). The owner's decision changes ADR-0117 for creating teams and matches, and the KVKK review stays deferred (0.4, ADR-0120). Every quota and limit of v4 that protects the server stays. Four limits relied on the gate, and v5 proposes these numbers for them (3.3, 4.1, 6.5; 16.1 item 33):
- captain of at most 3 teams (v4: 10) and at most 3 team creations a day (v4: none, because one captain code made one team);
- at most 400 sign-ups in 7 days through all the join links one account created, counted per sign-up in a record that renewing a link or deleting a team or an open match does not touch (v4: none; renewing a link restarted its count of 200);
- team video counted per uploader, 40 GiB with an operator code and 16 GiB through a link, inside a server-wide team pool of 200 GiB (v4: 40 GiB per team only);
- no team video while the team has fewer than 2 members (v4: none; a one-person team could hold 40 GiB of private video).

**R7. Everything else of v4 stays** unless R1-R6 force a change (the table below). Slice B1 is merged; the corrections below say what it really holds and which slice changes it.

### What changed from v4

| Section | v4 | v5 |
|---|---|---|
| Summary (0.2) | seventeen lines; a captain-code gate and a members rule | eighteen lines; anyone with an account creates; the entry choice; fixed starter counts |
| 0.4 | why sign-up and team creation keep a gate | who may sign up and who may create; what removing the gate means; the text of ADR-0120 |
| Terms (1) | 1-6 or 2-12 starter seats | fixed 6 and 12; new terms: starter place, entry choice |
| Seats (2.3) | 1-6 starters and 2-12 in total per team; 2-12 starters per open match; `PUT …/seats {starterSeats, substituteSeats, moves}` | 6 starters always and 0-6 substitutes per team; 12 starters always and 0-12 substitutes per open match; `PUT …/seats {substituteSeats, moves}`; a CHECK in `seat_pools` |
| State machine (2.4) | the members rule in T1 and T8; T8d | no members rule and no T8d; nothing else changes |
| Concurrency (2.10) | count changes of both types; the members case | substitute count changes only; the members case removed |
| Texts (2.12, 10.8) | captain-code and members-rule texts; seat texts for changeable starter counts | those texts removed; new seat texts; `first_start_body` for the signed-out state; the creator limit; empty starter places; team limits and team storage; `privacy_links` |
| Walks (2.13) | captain code, members rule, `PUT …/seats {6, 3}` | any account creates; an empty starter place in walk 2 |
| Accounts (3.3, 3.6) | CAPTAIN codes with a team name; sign-ups per link | SIGNUP codes only; sign-ups per link and per creator; a team-creation rate key |
| Teams (4.1, 4.3) | captain code; captain of 10 | any account; captain of 3; 3 creations a day; the substitute count only |
| Matches (5.1-5.7) | requests only between the owner's teams before the review | requests between any teams; the opponent check matters from the start |
| Open matches (5.8) | members rule; the link opens no account before the review; 2-12 starters | any account; the link opens sign-up; 12 starters always; at most 6 substitutes per side |
| Rosters (6.3) | at most 6 starters and 12 entries per side | 6 starter places with empty places shown as "Boş"; at most 6 substitutes and liberos together |
| Storage (6.5) | team video per team only | also per uploader, a server-wide team pool and the 2-member rule |
| Statistics (7) | | an empty starter place adds no line and no 0; lines carry `emptyStarterPlaces` |
| Data model (8) | CAPTAIN codes; `seat_pools` without bounds; no link creator | SIGNUP codes only, B1's three reserved columns unused; a CHECK on the starter counts; `share_links.created_by`; `link_signups` |
| API (9) | captain code, members rule, `starterSeats`, `TOO_MANY` | removed; `substituteSeats`; `TOO_MANY_SUBSTITUTES`; `TEAM_TOO_SMALL`; `SIGNUP_LINK_LIMIT` with a scope; `emptyStarterPlaces` |
| Android (10) | Takım Kur with a captain code; New Match by `openMatches.allowed` | the entry choice (10.10) and its route; no code field; empty places in the roster editor |
| Privacy (11) | the app is not opened to other teams before the review | what opening it means; the data of people the owner does not know |
| Hosting (12.2, 12.5) | `VOLLEYSTAT_TEAMS` at start; the APK to the owner's teams | `VOLLEYSTAT_OPEN_MATCH_SIGNUP` at start; the APK to the first captains and organisers |
| Findings (13) | | a note on the fixes that rested on the gate |
| Slices (14) | captain code in B1 and B4; members rule in B5 and B6c | B4 removes the gate and the setting; B5 the creator limit; B6c the entry choice; B7 the team storage rules; B8 the starter places |
| Left out (15), decisions and risks (16) | items 3, 15, 19, 22 and 23 open | item 3 removed; items 15 and 23 settled; items 19 and 22 partly settled; items 32-35 new; new risks |

### Corrections found while writing v5

- **B1 does not hold the captain code.** The brief for v5 said that B1 already has `captain-code --team-name` with the list and `--after-kvkk`. The merged code at d9b27a7 holds none of them: `backend/app/admin.py` has the commands `signup-code` and `reset-password` only, ADR-0119 lists no team, and v4's B1 row (14.1) planned the command, which B1 did not build. `storage --top` is not in B1 either; it belongs with the quotas of B3. What B1 does hold for the gate: `accounts.settings()` reads `VOLLEYSTAT_TEAMS` (`operator` by default, or `open`) and refuses to start with any other value; `GET /v1/health` reports it as `accounts.teams`; `operator_codes` has the columns `signup_used`, `used_by_user_id` and `team_name`, reserved for the teams slice and never written; `issue_operator_code(db_path, kind)` accepts any kind; registration accepts only a SIGNUP code, and a test asserts that a CAPTAIN code opens no registration (`test_a_captain_code_does_not_open_a_registration_before_the_teams_slice`). No app code reads `accounts.teams`.
- **Which slice changes B1's code, and how (B4).** B4 removes `VOLLEYSTAT_TEAMS` from `accounts.settings()` and `teams` from health `accounts`. A server whose environment still sets the variable starts and logs one line saying that it no longer reads it, because a refusal would break a start script for nothing. The three reserved columns stay in `SCHEMA` unused, so storage folders made by B1 need no rebuild. Registration does not change, so every SIGNUP code printed before B4 keeps working until its 7 days end. `issue_operator_code` then raises `ValueError` for any kind other than SIGNUP. B4 replaces `test_a_captain_code_does_not_open_a_registration_before_the_teams_slice` (`test_auth.py` line 1005, which calls `issue_operator_code(..., "CAPTAIN")`) with a test of that refusal, changes the health equality test (line 233, `{"signup": "LINK", "teams": "OPERATOR"}`) and turns the `VOLLEYSTAT_TEAMS` start test (line 1126) into the logging test of 14.1. B4 also drops `teams` from the health sentence of `docs/API.md` (line 18); API_V1 section 10 names only `accounts.signup` and needs no change for it (C20 of the v5 checkers).
- v4's 0.2 item 12, 5.3, 7.4 and 16.2 said that before the KVKK review requests run only between the owner's own teams. That rested on the gate and is withdrawn.

### Changes after the v5 checkers

Two checkers read v5 on 18 November, one on seats (S1-S9) and one on the entry and the opened creation (C1-C21). Every finding was checked against v5 and the code at d9b27a7. All were fixed; for C2 the session took the checker's second option and rejected the first, and for C21 the documentation option.

| # | What v5 now says |
|---|---|
| S1 | A seat move writes only `memberships.seat` or `open_match_players.seat`, never a roster row (2.3, 5.8.6, 6.3). While an open match's roster was never saved, the seats route re-proposes the side of each moved player, so no proposal side holds more than 6 of a type (5.8.5). Every confirmation, also of version 0, runs all roster rules (6.3, 9.2). Tests in B6c and B8. |
| S2 | Every server-roster SCORER card offers Kadroda Olmayan Numara with a 1-99 field (`review_other_jersey`), so a person without an account who scored no longer turns the named players' zeros into UNKNOWN (6.3, 7.1, 10.8, item 34; B8 screenshot, B9 test). It is offered on every such card, not only with an empty starter place, because the app cannot know who came on as a substitute. |
| S3 | The freeing cases of 2.3 name a removal and an account deletion instead of a move. |
| S4 | `test_join_engine_kinds.py` runs walk 2 on a 12 + 2 pool (14.1 B5). |
| S5 | Item 20 of 14.3 has its own 6 + 2 seed. |
| S6 | The seats route runs pause recovery and `settle` before its checks; a lowering mid-queue is always refused (2.3, 2.4, 2.10). |
| S7 | ADR-0120's seat sentence states the captain's moves (0.4). |
| S8 | `seats_raise_or_swap_hint` and `seats_swap_hint` follow a substitute refusal of a call that moved a starter; `open_seats_body` counts substitutes and liberos together (2.12 F). `SEATS_IN_USE` carries `details.holders`. |
| S9 | The `seat_pools` CHECK fixes only the starter counts and keeps the substitute count at 0 or more; the code holds the upper bound, so item 22's 14 needs no rebuild (2.3, 8.2, item 22). |
| C1 | The creator's 400 is counted in `link_signups`, which deleting a team or an open match does not touch; `share_links.created_by` is cleared when a link closes, and `privacy_retention` names the 7 days (3.3, 3.7, 8.2, 8.7, 10.8, 11.2). |
| C2 | Per-creator storage budgets are rejected: every link account can create its own invite, so a budget per creator stops nobody and divides a real group's space (15). v5 says plainly that one person can fill a pool alone (0.4, 6.5, 16.2, item 33, ADR-0120 with B7), restates the 2-member rule as no abuse bound, and B7 gives the operator `signups`, `close-links` and `delete-match` beside `storage --top` and `reset-password`. |
| C3 | The code-first step and Kod Gir read a printed operator code as one; the start screen gains Kayıt Kodu İste, which opens the mail app; the code-first texts name the kayıt kodu; 10.10 and 12.5 say that a person without a link needs a code from the owner (2.12 A, 10.1). |
| C4 | ADR-0120 is split: B4 adds its part, and B5, B6c and B7 append their sentences; the numbers are marked as changeable; "maçı yöneten kişi" replaces "maçı açan kişi" (0.4, 14.1). |
| C5 | The B1 note says what merged health holds; B6c adds `openMatchSignup`, and the app reads a missing field as OFF (9.1, 2.12 A, 10.8, 14.1). |
| C6 | `upload_quota_team_uploader` says who can delete a team video. |
| C7 | Only R1 and R6 are marked settled; R2, R3 and R5 are defaults in items 19, 22, 34 and 35 (v5 changes, 16.1). |
| C8 | 10.10 orders the entry choice after the legacy question and the password suggestion, decides from local records first, handles a failed load, marks accounts that existed before B6c, and gives the true reason for the signed-out state. |
| C9 | After a link sign-in the screen is marked seen only when the flow ends in a join or an answered request (10.10). |
| C10 | B4 adds the line under ADR-0119 too, and both lines name joining and sign-up by link (0.4). |
| C11-C16 | `first_start_body`, `team_new_seats_body`, `transfer_captain_limit`, `upload_team_too_small`, `entry_offline` and `stats_empty_places` (with a new team form) are rewritten. |
| C17 | `signupLimitReached` is `LINK`, `CREATOR` or null, with `signupLimitUntil` and `invite_signup_limit_creator`; registration checks CREATOR first (2.12 F, 3.3, 9.2). |
| C18 | `privacy_links_team` comes with the invite card in B6, and `privacy_links` with organisers in B6c; B4 shows neither (10.8, 14.1). |
| C19 | 11.2 says whose data the inventory now holds. |
| C20 | B4 makes `issue_operator_code` raise `ValueError` for any kind but SIGNUP and changes the three B1 tests and `docs/API.md` line 18 (corrections above, 14.1). |
| C21 | 3.6, 4.1, item 33 and ADR-0120 say that the two daily creation limits start again when the server restarts, while the limits that bound what stays on the server are read from the database. |

Found while fixing: `delete-match`, which 6.4 names, was in no slice; B7 now builds it with the other operator commands.

### What v5 keeps

Everything else of v4: the join engine with its timing, arrival judging, in-flight protection, pause and silence recovery, FIFO lock and long-poll (2.4-2.11, without T8d); the link secrets, tombstones and code rules (2.2); login, sessions, throttling and account deletion (3, with one key and one limit added); logos, tags and team visibility (4.2, 4.4, 4.5); match requests, the opponent check, SOLO records and the recorder that never waits (5.1-5.7); the open match's sides, video, confirmation, transfer, cancellation and deletion rules (5.8); shared control and side results (6.1, 6.2, 6.4); the statistics fields (7.3, plus one field that changes no number); hosting, the link host and backups (12.1-12.4, 12.6); every finding fix of 13 except those that rested on the gate (the note at the top of 13); the measurements of the appendix. The owner's decisions of 17 November stand, with decision 1's starter counts now fixed.

---

## v4 changes

History: v4's record of its changes, kept as written. v5 changes the readings D1a, D1c, D1e, D1h and D1i, the team size row and the two gate rows below; the notes in brackets point to the v5 text.

### What the owner decided on 17 November

The owner's message of 17 November, verbatim:

> Birleştir
> 1. Akşam takım  olarak düşünmüşüm. Bu yöntem kalsın güzel. 1 takım 8 kişi olsun. 6 as 2 yedek. Maç kadrosu da bu durumda 16 oluyor. 12 as 4 yedek. Bu yöntem takım kaptaları takım kurar ve takım kaptanları diğer takım kaptanlarına maç isteği atar. Bunun haricinde normal kullanıcılar maç açabilir. Bu da 12 as 2 yedek li kadrolar açar. As ve yedek sayısı ikisinde de değişebilir.
> 2. Önerin gibi
> 3.önerin gibi
> 4. Önerin gibi
>
> Bunlara ek süreçte kaçırdığımız bir kısım olursa düzeltiriz

Translation: "Merge. 1. I had thought of it as a team; keep this method, it is good. A team is 8 people: 6 starters and 2 substitutes. The match roster is then 16: 12 starters and 4 substitutes. In this method team captains create teams, and team captains send match requests to other team captains. Apart from that, normal users can open a match. That opens rosters of 12 starters and 2 substitutes. The number of starters and substitutes can change in both. 2. As you propose. 3. As you propose. 4. As you propose. If we missed something along the way we will fix it."

The four numbered points answer the four questions the session put to the owner:

| # | Question | The owner's answer | Where in v4 |
|---|---|---|---|
| 1 | Is a team the right unit, and how big is it? | keep teams; a team is 6 starters (as) and 2 substitutes (yedek); a team match roster is 16 (12 + 4); captains send match requests to captains; besides that, any user can open a match with 12 starters and 2 substitutes; both counts can change | the readings below; 2.3, 4, 5.8, 6.3 |
| 2 | Login | as proposed: username, password and display name; Google later | 3.1 (unchanged) |
| 3 | The join link | as proposed: an invite link plus an 8-character code | 2.2 (unchanged); v3's 16.1 item 5 is now settled at 8 |
| 4 | The decision window | as proposed: 10 seconds, plus a 2-second grace that the phone never shows, judged by arrival | 2.6, 2.9 (unchanged) |

The first word, "Birleştir", asked to merge the multi-match work; `main` now holds it (7f18839). The owner answered no other item of v3's 16.1 (hosting, link host, the captain-code gate, quotas, the field test, one-sided matches, distribution, the screen-reader window, backups, numbers, renewal, schedule, removal). v4 keeps v3's default for each of them (16.1).

### The readings of decision 1

The session read decision 1 as follows. Each reading is an owner decision in 16.1 with this reading as its default, so the owner can correct any of them without stopping the build. The labels D1a-D1m are used in 16.1.

| # | Reading | Default | Sections |
|---|---|---|---|
| D1a | A team is a set of typed seats. A starter seat (as) is a seat on the team, not a promise about a given match. The captain holds one of the seats. | 6 starter seats and 2 substitute seats, capacity 8; the captain on a starter seat, so 5 starter and 2 substitute seats are open when the invite goes out; the captain may set 1-6 starter seats and 2-12 seats in total, and the captain's own seat must fit the counts (v5, R1: always 6 starter seats and 0-6 substitute seats) | 2.3, 4.1; 16.1 item 15 |
| D1b | Which seat a joiner gets | starter seats fill first in queue order, then substitute seats; when both are full the team is full. A hold or offer keeps the type it got; the server never moves a member | 2.3, 2.4; item 16 |
| D1c | Changing the counts and moving members | the captain (or the organiser) changes both counts (v5: the substitute count only, R1, R2) and moves members between starter and substitute; a count never goes below the holders of that type plus the people deciding on that type; an open hold or offer is never revoked or re-typed | 2.3, 4.1, 4.3; item 17 |
| D1d | The team match roster | each side's roster starts as that team's members on the match day (joined on or before `played_on`) with their seat types, so with 6 + 2 per team the match roster is 16 (12 + 4); a member of both teams is in neither default; the server writes the default as the side's saved roster at the record's first upload, at the end of the match day, at a confirmation or at a self-removal, whichever comes first, so a later member never enters it; each captain edits their own side per match | 6.3; item 18 |
| D1e | Open matches ("açık maç") | any logged-in account opens one with a date, a start time, a venue and a rule (before the KVKK review only a current member of a team or an account opened with an operator code, D1i; v5, R6: any account); typed seats in total, 12 starters and 2 substitutes, changeable by the organiser (v5, R2: always 12 starters, 0-12 substitutes); the organiser takes a starter seat ("Ben de oynuyorum", on by default); people join through a link and a code with exactly the same claim, queue and 10-second pop-up as a team; the pop-up shows the organiser's display name instead of a team logo, the match day and time, and the seat type | 5.8; item 19 |
| D1f | Sides of an open match | the organiser splits the players into side A and side B before or after the match; the server proposes a side for each player when they join (the side with fewer players of their seat type, A on a tie, which alternates A, B, A, B in join order) and never moves it when others leave; players see their side once the organiser saves the split; the review opens only after a saved split | 5.8.5; item 20 |
| D1g | Statistics of an open match | no team statistics; a person's statistics count only after the organiser confirms the roster with jerseys and the result; the server keeps the confirmed roster as a snapshot, which counts until the organiser confirms a new one; a person who did not play taps "Bu Maçta Oynamadım" and leaves the roster and the snapshot, so nobody is credited for a match they did not attend | 5.8.6, 5.8.7, 6.3, 7; item 21 |
| D1h | FIVB bounds (rules 4.1.1, 7.3.1, 7.3.3 and 19.1.1) | per team and per match side at most 6 starters and at most 12 players (v5, R1-R3: exactly 6 starter seats or places); the 14 players of FIVB, World and official senior competitions are not used; an open match has at most 12 starters, and each of its sides at most 6 starters and 12 players; a match roster may name at most 2 liberos per side, who count toward the 12 and not toward the 6 starters | 2.3, 5.8.5, 6.3; items 22 and 30 |
| D1i | Open matches and the KVKK rule | before the KVKK review an open match link does not open sign-up, and only a current member of one of the owner's teams or an account opened with an operator code can open an open match or take a seat in one (`403 OPEN_MATCH_MEMBERS_ONLY`); an account that registered through an invite and never joined, or a removed ex-member without a team, cannot. After the review the operator lifts both rules (`VOLLEYSTAT_OPEN_MATCH_SIGNUP=link`, `VOLLEYSTAT_TEAMS=open`) (v5, R6: both rules gone; the link opens sign-up by default) | 0.4, 3.3, 5.8.2, 5.8.3, 11.3; item 23 |
| D1j | Who sees what in an open match | a logged-in link holder sees the organiser's display name, the date, time, venue, rule and seat counts; players see each other's display names, seat types, sides and jerseys, across sides; players and the organiser see the video and the report; nobody sees a username | 5.8.8, 11.1; item 24 |
| D1k | Open match limits | 3 open matches organised and 10 joined that are not cancelled and whose day has not passed, per account; 10 creations a day; one live video per open match, and no upload while it has fewer than 2 players; open-match video counts on the account that uploaded it, 8 GiB for an account opened with an operator code and 4 GiB for one opened through a link, inside a server-wide open-match pool of 60 GiB beside a personal pool of 40 GiB; a second recording of the same game goes to a sibling record (at most 3) | 5.8.6, 5.8.9, 6.5; items 4, 25 and 28 |
| D1l | Joining an open match late | its link lives 7 days, until the end of the match day or until the record's first upload, whichever comes first; no new link after the match day, after the first upload or after a cancellation; the organiser cannot move the date once the match day began or an upload exists. Until then a person who did not play can still join; the organiser can close the link or remove that person | 5.8.3, 5.8.4; item 26 |
| D1m | The players' numbers and the organiser | the organiser cancels only while no video and no side result exist, and deletes while no side result exists or while nobody else holds a seat or a line of the confirmed roster. After a confirmed result the organiser can delete the video, save a new roster or remove a player, and the confirmed roster and result keep counting; the organiser changes what counts only by confirming a new roster or result. A player's own Bu Maçta Oynamadım removes that player's line at once | 5.8.4, 5.8.10, 6.3; item 27 |
| D1n | The organiser role moving | a transfer is an offer that the chosen player accepts; the server refuses it while the recipient organises 3 open matches, while an upload of the record is unfinished, or while a live video's rally list is not confirmed. When the organiser deletes their account, an open match with other players stays without an organiser: its link closes, and its roster, sides and result stay as they are | 3.7, 5.8.4; item 29 |
| D1o | A second recording of one open match | the organiser creates a sibling record that copies the date, the time, the venue, the rule, the players with their seat types and the saved split, with no link and no join; at most 3 siblings, none counting toward the open match limits. An open match with a date in the past and no link, for a game organised outside the app, is not offered | 5.8.2, 5.8.6; item 28 |

### What changed from v3

| Topic | v3 | v4 | Sections |
|---|---|---|---|
| Team size | capacity 14 (2-50), seats without a type | typed seats: 6 starters and 2 substitutes, capacity 8, the captain on a starter seat; 1-6 starters and 2-12 seats in total (FIVB 4.1.1, 7.3.1) (v5, R1: always 6 starters, 0-6 substitutes) | 1, 2.3, 4.1 |
| Unit of the join engine | the team | the seat pool: one per team and one per open match. Claim, queue, hold, ack, decision, `settle`, snapshots, the FIFO lock and arrival judging run on pools and do not otherwise change. A small adapter holds the only differences between the kinds: where a holder row lives, the holder limit, the pop-up's top part and the texts' nouns | 2.1, 2.4, 2.5, 8.2 |
| Seats in holds and offers | any seat | a hold or offer reserves a seat of one type, fixed at reservation; JOINED makes a holder of that type; the seat invariant holds per type | 2.3, 2.4, 2.5 |
| Changing seats | `PUT …/settings` with a capacity | `PUT …/seats` with both counts and the members' moves between the types, checked on the final state; the same route shape for a team and an open match | 2.3, 4.1, 5.8.4 |
| Pop-up and waiting screen | "Takımda 1 yer kaldı" | the pop-up names the seat type ("Sunucu sana bir yedek yeri ayırdı."); the waiting screen names the seats left per type ("Takımda 1 yedek yeri kaldı."); the queue rule says that the next person gets the type of the seat that frees | 2.12 |
| Open matches | none | new section 5.8: any account opens one; 12 + 2 typed seats; the same join flow; the organiser splits sides; one video by the organiser; person statistics only | 5.8 |
| Sign-up gate | team invites and operator codes | unchanged; an open match link opens sign-up only after the KVKK review (`VOLLEYSTAT_OPEN_MATCH_SIGNUP`, default `off`) (v5, R6: default `link`) | 0.4, 3.3 |
| Team match roster | the app suggested the members; at most 12 entries per side | the server offers each side's team members with their seat types as the default roster, so a team match roster is 16 (12 + 4); per side at most 6 starters and 12 entries; a jersey may wait until the confirmation | 6.3 |
| Removing oneself from a roster | lapsed the side's confirmation | keeps the confirmation for everyone else, because removing oneself only removes a credit | 6.3 |
| Texts | team words only | every join text has a team form and an open-match form; new texts for seats, the organiser and the sides | 2.12, 10.8 |
| Blocks | `team_blocks` | `pool_blocks`; the organiser can remove and block a player of an open match | 4.3, 5.8.4, 8.2 |
| Names in the API | `TEAM_FULL`, `TEAM_LIMIT`, `CAPACITY_IN_USE`, `CAPACITY_BELOW_MEMBERS` | `SEATS_FULL` (error) and `FULL`, `LIMIT` (attempt reasons), `SEATS_IN_USE`, `SEATS_BELOW_HOLDERS`, `SEATS_INVALID`, because one engine serves both kinds | 2.4, 9.5 |
| Person statistics | `teamCountedMatches`, `teamWon`, `teamLost` | `sideCountedMatches`, `sideWon`, `sideLost` (an open match has sides, not teams); `asStarter`, `asSubstitute` and `asLibero`; lines for open matches | 7.3 |
| Storage | personal and team quotas | an open-match scope per uploader and a server-wide open-match pool beside the personal pool (after the v4 checkers, V4-Q1) | 6.5 |
| Slices | B0-B10 | B4 adds seat pools and typed seats; B5 builds the engine on pools, with the open-match kind tested from the start; the new B6c (open matches: create, join, sides) needs no hosting; B8 adds the open-match video, roster and result; B9 counts open matches for persons only | 14.1 |
| Owner decisions | 14 open items | decisions 1-4 recorded as settled; v3's item 5 settled at 8 characters; items 15-27 hold the readings of decision 1; every other v3 default kept | 16.1 |

### Corrections of v3 found while writing v4

- v3's 16.1 item 4 said that a member who joined through an invite holds "about two maximum-size personal videos" in 2 GiB. `MAX_VIDEO_BYTES` is 4 GiB (`backend/app/main.py` line 30), so 2 GiB holds half of one maximum-size video. v4 states the cost as it is.
- v3's `server_older` text said that the app cannot open "the invite" on an older server. The same check covers every link kind, so v4 says "bağlantıyı".
- v3's `link_unknown_body` sent the person to the captain, but an unknown link has no known kind. v4 sends the person to whoever sent the link.

### Changes after the v4 checkers

Three checkers (fidelity, security, volleyball) read v4 on 17 November. Every one of their 27 points held; 13.3 lists each point and its fix, and `verify_v4.json` holds the outcomes. The larger changes:

| Topic | First v4 text | Now | Sections |
|---|---|---|---|
| Team match default roster | the team's current members, computed until the captain saves | the members on the match day only, without a member of both teams; written as stored rows at the record's first upload, at the end of the match day, at a confirmation or at a self-removal; only stored rows give access and statistics; the other team never sees a computed default | 6.1, 6.3 |
| Confirmed rosters | an edit lapsed the confirmation | the server keeps the confirmed roster as a snapshot that counts until the next confirmation; a self-removal removes the person from it at once | 6.3, 7.2, 8.2 |
| Open match sides | the proposal was recomputed from the current players | each player's proposed side is stored when they join; the review opens only after a saved split; the SCORER card offers the server roster's jerseys | 5.8.5, 5.8.6, 10.3 |
| Open matches before the KVKK review | any account | only a current member of a team or an account opened with an operator code (`OPEN_MATCH_MEMBERS_ONLY`) (v5, R6: any account again) | 0.4, 2.4, 5.8.2, 5.8.3, 11.3 |
| Joining late | the link lived to the end of the match day; the date could move | the link also closes at the first upload (reason PLAYED); one guard on every route that creates a link; the date is fixed once the match day began or an upload exists | 2.2, 5.8.3, 5.8.4 |
| The organiser | could cancel after a result; was appointed by a transfer or by account deletion | cancel and delete refused after a result while others hold a seat or a counted line; a transfer is an offer the player accepts; account deletion leaves the match without an organiser | 3.7, 5.8.4, 5.8.10 |
| Open match storage | 8 GiB per organiser, moving with a transfer | counted on the uploader (8 GiB with an operator code, 4 GiB through a link), never moved; separate server pools for personal and open-match video; no upload with fewer than 2 players | 6.5 |
| A second recording of an open match | a new open match | a sibling record with the same players and split | 5.8.2, 5.8.6 |
| Liberos | not in the roster; rule 7.3.3 quoted without its exception | a per-match LIBERO role, at most 2 per side (rule 19.1.1), outside the 6 starters | 2.3, 6.3, 7.3 |
| Texts | "Maçı açan kişi" | "Maçı yöneten kişi", because the role moves; texts for the new states; the ace column is "S. ası", because "as" now means a starter | 2.12, 10.8 |

### What v4 keeps unchanged

Accounts and sign-in (3); the state machine's timing, the arrival rule, the in-flight protection, pause and silence recovery and the FIFO writer lock (2.4-2.11, with "pool" for "team"); the live channel and its measured numbers (2.7); the link secrets and tombstones (2.2); throttling (3.6, one key added); the logo rules (4.2); the tag (4.4); match requests, the opponent check, SOLO records and recording that never waits (5.1-5.7); shared control and side results (6.1, 6.2, 6.4); the statistics principles (7.1); hosting, the link host, distribution and backups (12); every finding fix of 13 and 13.1; the measurements of the appendix.

---

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

17 November, third message (verbatim and translated under "v4 changes" at the top): keep teams; a team is 6 starters and 2 substitutes, so a team match roster is 16 (12 + 4); captains create teams and send match requests to captains; besides that any user can open a match with 12 starters and 2 substitutes; both counts can change. The login, the 8-character code and the 10-second window with a hidden 2-second grace stay as proposed.

18 November, fourth message and two answers (verbatim and translated under "v5 changes" at the top): the captain changes only the substitutes; six people must be on the court; no specific team will use the app; on entry a person can say that they captain a team, or set up a 12-person match played now and then; anyone who has an account creates teams and matches, and captains and organisers add people by sending a link.

Earlier answers that bind this design: a video may hold several matches (done, API 0.7); the owner deferred the KVKK review (ADR-0117), and on 18 November let every account create teams and open matches before it (0.4); the feedback address is voleybolveveleybol@gmail.com; the licence is closed commercial; there is no NVIDIA GPU; the owner uses free tools only.

### 0.2 The design in eighteen lines

1. **Login.** Username, password and display name (owner decision 2 of 17 November). stdlib scrypt, an opaque bearer token stored as SHA-256, no e-mail, no phone, no new dependency. A bundled list refuses the 10 000 most common passwords (v1 and v2, plus P4). Google sign-in comes later.
2. **Who may sign up and who may create.** A person signs up with a live join link (a team invite or an open match link) or with a one-time operator code (B1). Every signed-in account creates teams and open matches (the owner's decision of 18 November, R6); no captain code and no list of teams exist. A captain or an organiser adds people only by sending the team's invite or the match's link. The limits that protect the server stay, and four new ones take the place of what the gate bounded: captain of at most 3 teams and 3 team creations a day, at most 400 sign-ups in 7 days through the join links one account created, team video per uploader inside a server-wide team pool, and no team video before a team has 2 members (3.3, 4.1, 6.5). The KVKK review is still deferred; the decision changes ADR-0117's rule that the app is not opened to other teams before it (0.4, ADR-0120).
3. **Join links.** A team has at most one live invite, and an open match has at most one live link. Each is an unguessable https link and an 8-character code (`K7QF-2M9X`, owner decision 3). The server stores only a hash of the token and a keyed hash of the code, under a key kept outside the storage folder (S9). An invite lives 7 days; an open match link lives 7 days, until the end of the match day or until the record's first upload, whichever comes first. The server closes a join link on its own when its pool is full (P2, P8, F7).
4. **Typed seats.** A team has exactly 6 starter seats (as) and 0-6 substitute seats (yedek), 2 by default, the captain on one of the seats; an open match has exactly 12 starter seats, 6 per side, and 0-12 substitute seats, 2 by default. The captain or the organiser sets only the substitute count and decides who holds which type (the owner, 18 November: "Sahada 6 kişi olmak zorunda."; R1, R2; FIVB 7.3.1 and 4.1.1).
5. **Seat holds.** A person who reaches the decision point while a seat is free and nobody waits gets that seat for 10 seconds (HOLDING): a starter seat while one is free, else a substitute seat. The type never changes after that. Every seat change is one SQLite write transaction of one pool, so two people never get the same seat (D1b).
6. **FIFO queue in arrival order.** With no free seat but someone deciding, the person waits in a queue. An in-process FIFO ticket lock in front of every SQLite writer makes the queue follow the order in which requests reach the server (C4). When a seat frees, the server offers it, with its type, to the head of the queue. The waiting phone acknowledges through its own route within 8 seconds, and the 10-second pop-up starts then (C5, F2).
7. **One function for time.** `settle(pool)` does every time-based transition. Write routes and a 250 ms clock call it inside a write transaction, after the pause recovery that every write transaction runs first when it is due. A long-poll never calls it and never writes (C1, conc V2).
8. **Decisions count when they arrive.** The server stamps every request on arrival, before any queue or lock. An ACCEPT counts when its headers arrive before the deadline plus the 2-second grace, however long its body or the lock then take, for at most 5 s more. A late ACCEPT or ack closes its own attempt at once (C2, conc V1, conc V3, S1; owner decision 4).
9. **Live channel.** An async long-poll answers from an in-memory pool snapshot that every writer publishes after its commit. It wakes only when the caller's own screen would change, and heartbeats are written in one batch per clock tick. Measured: 100 phones claiming while every phone polls finished in 0.66-0.70 s instead of 6.6-7.1 s (C1).
10. **Never false.** The phone's countdown comes from the server's `remainingMs` and `waitedMs` and the phone's elapsed time only, so it never outlasts the server's (C3). Every text states only what the server or the app knows (C6, F6); the seat type in a text comes only from the server's `seat` field. The app keeps the screen on while a person waits or decides (C6, F2).
11. **Open matches.** Any signed-in person opens a match with a date, a start time, a venue and a rule, and shares its link, which also opens sign-up for a newcomer. People join through exactly the same claim, queue and pop-up as a team; the pop-up shows the organiser's display name, the day, the time and the seat type. The organiser splits the players into side A and side B before or after the match, uploads the one video, confirms the roster with jerseys and confirms the result. An open match counts only for persons (D1e-D1g, R2, R6).
12. **Match requests by link.** A captain creates the request and sends a single-use request link to the other captain, who accepts with one of their teams. Before the requesting side's first upload, its captain confirms the opponent's name and tag once (P1); since any account may lead a team (R6), this check guards against a leaked request link from the first day. A request that the other captain declines or never answers turns into a one-sided record, so the recording still counts for the own team (R1).
13. **Recording never waits.** The recorder stays open as today. A team recording waits on the phone and uploads once the request is accepted (F5); an open match recording uploads at once.
14. **Each side owns its numbers.** Each side may add its own video and reviews it. Each side's captain confirms a result for their own side, and the server keeps one snapshot per side. A team's statistics read only its own side's snapshot, so no captain can change, erase or hold back the other team's numbers (F3). A side that did not film confirms the result that the filming side's reviewer offered, even after that side left (R7).
15. **Rosters.** Each side's roster starts as its team's members on the match day with their seat types, so with 6 + 2 per team a team match roster is 16: 12 starters and 4 substitutes (D1d). The server stores that default as the side's roster at the first upload or at the end of the match day, so later members never enter it. Each side has 6 starter places; a place the roster names nobody for shows "Boş", and the server invents nobody for it (R3). The captain edits and confirms their own side with jerseys, with at most 6 substitutes and liberos together and at most 2 liberos per side. In an open match the organiser writes and confirms both sides. A person's statistics come only from the confirmed roster, which the server keeps as a snapshot until the next confirmation. Unknown is never 0, and an empty starter place never makes anyone's number 0.
16. **Hosting and the field test.** Everything works on one computer today. Real use between teams needs an internet-reachable HTTPS server, which is an owner decision. A field-test slice runs before the request and statistics slices (F8).
17. **Defaults without the owner.** Every point that needs the owner has a default that works without the owner's action and a stated cost (16.1).
18. **The entry choice.** After a person's first sign-in on a phone the app asks how they will use it: Takımımın kaptanıyım, Ara sıra maç kuruyoruz, Bir bağlantım ya da kodum var, or Önce kendi maçımı kaydedeyim. The choice only opens the first screen; it can be skipped and opened again from the Teams tab and Profile, and the server stores nothing about it. A person who arrived through a link skips it and lands in that flow (R5, 10.10).

### 0.3 What changed from v2

v3's record of its changes from v2, kept as history. v4's changes are under "v4 changes" at the top of the document.

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

### 0.4 Who may sign up and who may create (v5)

The owner's flow says a brand-new person gets a sign-up flow. That person arrives through a join link, so a live join link opens sign-up: a team invite, and from v5 on an open match link as well (`VOLLEYSTAT_OPEN_MATCH_SIGNUP=link`, the new default; `off` stays as an operator switch). `VOLLEYSTAT_SIGNUP=link` (the default, built in B1) refuses a registration that carries neither a live join link nor a one-time operator code; `open` is for tests and CI only. A match-request link never opens sign-up, because the captain who accepts a request must already lead a team (P1). Sign-up without any link or code stays closed; whether to open it is an open owner point for the hosting slice (16.1 item 32).

**Creating teams and open matches (owner decision of 18 November, R6).** Every signed-in account may create a team (`PUT /v1/teams/{id}`) and an open match (`PUT /v1/open-matches/{id}`). No captain code, no list of teams and no members rule exist. The captain of a team and the organiser of an open match add people only by sending the team's invite or the match's link; no route adds a person to a team or an open match in any other way (15).

What v4 had and v5 removes:
- `VOLLEYSTAT_TEAMS=operator` and the captain code (`403 CAPTAIN_CODE_REQUIRED`, `403 CAPTAIN_CODE_INVALID`). B1 reads the setting and reports it in health; B4 removes both (v5 changes, corrections; 14.1).
- The list of the owner's own teams in ADR-0119 and the binding of a captain code to a team name (`captain-code --team-name`, `--after-kvkk`). Neither was built.
- The members rule for open matches (`403 OPEN_MATCH_MEMBERS_ONLY`, the transition T8d, `openMatches.allowed` in `GET /v1/me`, v4's item 23).

**What this means for ADR-0117 and the KVKK review.** ADR-0117 says "Uygulama başka takımlara KVKK incelemesinden önce açılmaz." The owner's decision of 18 November changes that rule for creating teams and matches, and the KVKK review stays deferred. In practice:
- people the owner does not know can hold accounts once a captain or an organiser invites them, and those accounts can create their own teams and open matches and invite further people;
- the server holds their usernames, display names, password hashes, team, match and roster data, and videos that show their faces, without an aydınlatma metni and without a sharing consent (11.3);
- the operator holds that data without knowing who these people are, and the server does not check it (`privacy_links`, 10.8).

The limits that protect the server stay. The gate used to bound how many teams and invites existed, so v5 adds four limits in its place: captain of at most 3 teams and 3 team creations a day (4.1, 3.6), at most 400 sign-ups in 7 days through the join links of one account, counted in a record that deleting a team or an open match does not touch (3.3), team video per uploader inside a server-wide team pool, and no team video before a team has 2 members (6.5). 16.1 item 33 holds the numbers, which are defaults the owner can change.

**What the limits do not stop (C2 of the v5 checkers).** The server-wide video pools (6.5) keep one kind of upload from taking the space of the others and keep the free-disk floor for everyone, but one person can fill a pool alone. With a team of their own and its invite, a person opens the link accounts a pool needs within minutes: about 20 fill the personal pool, 15 the open-match pool and 13, each with a second member from the same invite, the team pool. Every account's uploads of that kind then stop until the operator cleans up with the operator commands of 6.5. A per-creator storage budget would not prevent it, because every link account can create its own team and invite and so would bring a fresh budget. The two daily creation limits live in the in-memory limiter and start again when the server restarts (3.6, ADR-0119); the captain limit, the organiser limit, the creator's sign-up limit and the storage quotas are read from the database and hold across restarts.

**ADR-0120 (proposed text).** B4 adds to `docs/ADR.md` the parts that B4 builds: the context, the decision that every account creates, the fixed 6 starter seats with the captain's moves, the limits of B4 and the KVKK sentence. Each later slice appends its own sentences when it ships, as ADR-0119 asks of every slice (C4 of the v5 checkers). The numbers are defaults that the owner can change (16.1 item 33), and the ADR says so. B4 also adds one line under ADR-0117's KVKK item and one under ADR-0119's sentence "Ekip uygulamayı başka takımlara bu incelemeden önce açmaz (ADR-0117).", because ADR-0119 repeats ADR-0117's rule (C10 of the v5 checkers): "Bu cümle takım ve maç kurmak, bunlara katılmak ve bağlantıyla hesap açmak için ADR-0120 ile değişti."

The text that B4 adds:

> ## ADR-0120: Ürün sahibinin 18 Kasım kararı: hesabı olan herkes takım ve maç kurar
>
> **Tarih:** 18 Kasım 2026 · **Durum:** Kabul edildi
>
> **Bağlam:** Ürün sahibi KVKK incelemesini sonraya bıraktı (ADR-0117). ADR-0117 uygulamanın başka takımlara bu incelemeden önce açılmayacağını yazdı. Hesap tasarımının dördüncü sürümü bu kuralı bir sunucu kuralına çevirdi: Bir kişi ancak operatörün kaptan koduyla takım kurabilecekti ve operatör bu kodu yalnız ürün sahibinin takımları için çıkaracaktı. Ürün sahibi 18 Kasım 2026'da bu yolu değiştirdi.
>
> Kararlar:
>
> - Ürün sahibinin kararıyla hesabı olan herkes takım ve maç kurar. Sunucu bu sürümden başlayarak her hesabın takım kurmasına izin verir. Sunucu kaptan kodu istemez ve bir takım listesi tutmaz.
> - Bir takımda her zaman 6 as yeri olur. Kaptan as yeri sayısını değiştiremez. Kaptan yedek yeri sayısını 0 ile 6 arasında seçer ve üyeleri as ve yedek yerleri arasında taşır. Bu yüzden katılma sırası takımın as altılısını belirlemez.
> - Bir hesap en çok 3 takımın kaptanı olur ve günde en çok 3 takım kurar. Ürün sahibi bu sayıları değiştirebilir. Sunucu günlük sayacı bellekte tutar ve yeniden başlayınca sıfırlar.
> - Bağlantısı olmayan kişi B1'deki gibi ancak operatörün kayıt koduyla hesap açar. Ürün sahibi bu kuralı barındırma dilimi için ayrıca karara bağlar.
> - ADR-0117'nin "Uygulama başka takımlara KVKK incelemesinden önce açılmaz." cümlesi takım ve maç kurmak, bunlara katılmak ve bağlantıyla hesap açmak için artık geçerli değildir. KVKK incelemesi yine sonraya kaldı.
>
> Sonuçlar:
>
> - Sunucuyu koruyan sınırlar kalır. Kaptan kodunun sınırladığı yerlere yeni sınırlar gelir. Her dilim kendi sınırını bu kayda ekler.

The sentences that later slices append when they ship:

| Slice | Kararlar | Sonuçlar |
|---|---|---|
| B5 (sign-up by invite, the creator's limit) | Takımın kaptanı bir kişiyi takıma yalnız davet bağlantısı göndererek ekler. Yeni gelen kişi takım davetiyle hesap da açar. Sunucu bir hesabın oluşturduğu bağlantılarla yedi günde en çok 400 hesap açar. Ürün sahibi bu sayıyı değiştirebilir. | Ürün sahibinin tanımadığı kişiler bir kaptan onları davet edince hesap açar. Bu kişiler kendi takımlarını kurabilir ve başka kişileri davet edebilir. Sunucu bu kişilerin kullanıcı adını, görünen adını, şifre özetini ve takım bilgilerini aydınlatma metni ve paylaşım onayı olmadan saklar. KVKK incelemesi bu durumu ele alır. |
| B6c (open matches) | Hesabı olan herkes açık maç açar. Maçı yöneten kişi bir kişiyi maça yalnız maç bağlantısı göndererek ekler. Yeni gelen kişi açık maç bağlantısıyla da hesap açar. Bir açık maçta her zaman 12 as yeri olur. Maçı yöneten kişi yedek yeri sayısını 0 ile 12 arasında seçer ve oyuncuları as ve yedek yerleri arasında taşır. | Maçı yöneten kişinin davet ettiği ve ürün sahibinin tanımadığı kişiler de hesap açar. Sunucu bu kişilerin maç bilgilerini ve yüzlerinin göründüğü videoları da aynı biçimde saklar. |
| B7 (team storage) | Sunucu ancak en az 2 üyesi olan bir takımın videosunu alır. Bir hesabın yüklediği takım videoları en çok 40 GiB yer tutar. Bağlantıyla açılan bir hesapta bu sınır 16 GiB olur. Bütün takım videoları birlikte en çok 200 GiB yer tutar. Ürün sahibi bu sayıları değiştirebilir. | Tek bir kişi kendi davetiyle açtığı hesaplarla bir video türünün bütün yerini doldurabilir. O zaman bütün hesapların o türdeki yüklemeleri operatör temizleyene kadar durur. |

---

## 1. Terms

| Term | Meaning |
|---|---|
| **Share link** | A link that a captain or an organiser shares: a team invite, an open match link or a match request. It has an unguessable token and an 8-character code. |
| **Join link** | A share link that leads into a seat pool: a team's invite (`TEAM_INVITE`) or an open match's link (`OPEN_MATCH`). A pool has at most one live join link. |
| **Invite** | The live join link of a team. |
| **Tombstone** | A closed share link's row. It keeps the token hash, the code's HMAC (`closed_code_hmac`), the pool or team and the reason for 30 days so the server can say why a link or a code no longer works (R3). |
| **Open match** (açık maç) | A match record that any logged-in person opens with a date, a start time, a venue and a rule, and that people join through its link. It belongs to no team (5.8). |
| **Organiser** | The person who opened an open match, or the player who accepted the role from them (5.8.4). The organiser manages the open match's seats, link, sides, roster, video and result. An open match whose organiser deleted their account has no organiser (3.7). |
| **Player** | A person who holds a seat of an open match. |
| **Seat pool** | The unit of the join engine: one per team and one per open match (`seat_pools`). It holds the seat counts of both types, the queue counter and the snapshot version. |
| **Seat type** | STARTER (as) or SUBSTITUTE (yedek). |
| **Starter seats, substitute seats** | A pool's number of seats of each type. A team always has 6 starter seats and 0-6 substitute seats, 2 by default; an open match always has 12 starter seats and 0-12 substitute seats, 2 by default. The captain or the organiser changes only the substitute count (2.3, R1, R2). A starter seat is a seat on the team, not a promise to start a given match; a match's roles live in its roster (6.3). |
| **Holder** | A person who holds a seat of a pool: a team member (the captain included) or a player of an open match. A holder holds exactly one seat of one type. |
| **Capacity** | starter seats + substitute seats. |
| **Seats left** (of a type) | seats of that type − holders of that type. |
| **Free seats** (of a type) | seats of that type − holders of that type − OFFERED and HOLDING attempts that reserve that type. Never negative. `free` without a type is the sum of both. |
| **Decision point** | The moment a logged-in person's phone asks the server for a seat. The phone sends that request only when the join screen is on screen and its window has focus (F4). |
| **Join attempt** | One person's try to get a seat of one pool, from the decision point to an outcome. At most one is open per person and pool, and at most three per person. |
| **Hold** | An attempt in state HOLDING: one seat of one type is reserved for that person until `expiresAt`. |
| **Grace deadline** | `grace_until` of a hold: `expires_at` + 2 s. Only pause recovery moves it later (2.11). |
| **Offer** | An attempt in state OFFERED: a seat of one type is reserved for the head of the queue until the phone acknowledges. |
| **Queue** | The WAITING attempts of a pool ordered by `queue_no`. A waiting attempt reserves no seat and has no type yet. |
| **Arrival** | The server's wall-clock time when a request's headers reached the ASGI app. The outermost middleware stamps it before the body is read, before the thread pool and before any lock (2.9). |
| **In flight** | A decision or acknowledgement whose arrival is stamped and whose request has not finished. The stamping middleware registers it (2.9). |
| **Pause** | A stretch in which the server process did not run (a stop or a sleep), or in which no request reached it while phones should have polled (a tunnel or home connection outage, called a silence). Recovery follows it (2.11). |
| **Pool snapshot** | The in-memory view of one pool's join state that a writer builds inside its transaction and publishes after its commit. |
| **View tag** | A short string that changes exactly when what one caller's join screen shows changes. The long-poll cursor. |
| **Match record** | One row of `matches`. A team match record has two sides; a one-sided team match has one; an open match has two sides without teams; a personal match has an owner. |
| **Side** | HOME (the team that requested) or AWAY (the team that accepted) of a team match record. In an open match HOME is side A and AWAY is side B; the organiser may name them. |
| **Side split** | The organiser's assignment of an open match's players to side A and side B (5.8.5). |
| **Match roster** | Who played for a side of a record, with a role (starter, substitute or libero) and a jersey. A team side's roster starts as the team's members on the match day (6.3). |
| **Starter place** | One of the 6 starter places of a match side's roster. A STARTER entry fills one; a place without one stays empty and shows "Boş". The server stores nothing for an empty place and never invents a person for it (6.3, R3). |
| **Confirmed roster** | The snapshot of a side's roster that its captain, or the organiser, confirmed last. Statistics read only this (6.3). |
| **Sibling** | A second record of the same game: for a team match a record created with `siblingOf` (5.5); for an open match a record that copies the players and the split (5.8.6). |
| **Reviewer** | The captain of the side that uploaded a video; for an open match, the organiser. Each video has one reviewer. |
| **Side result** | The `match_results` snapshot that one side's captain confirmed. That side's team statistics read only this. In an open match the organiser confirms one result, which the server stores for both sides. |
| **Played match** | A part of the report with `kind: "MATCH"` (ADR-0118). |
| **Operator** | The person who runs the server and its CLI. Today that is the owner. |
| **Entry choice** | The screen that asks a person, after the first sign-in on a phone, how they will use the app. It only opens the first screen, and the server stores nothing about it (10.10, R5). |

---

## 2. The join flow

### 2.1 Overview

```
 Captain or organiser                 Server                                  Joiner's phone
 ────────────────────                 ──────                                  ──────────────
 Takım Kur (any account) ─────────▶ team + seat pool (6 as + 2 yedek) + invite (token, code, 7 days)
 or Açık Maç (any account) ───────▶ open match + seat pool (12 as + 2 yedek) + link (7 days or the match day)
 Paylaş (WhatsApp) ──────────────────────────────────────────────────────────▶ opens link or pastes the message
                                                                              │ no session?
                                      POST /v1/links/resolve (anonymous) ◀────┤ team: name, tag, small logo; open match: kind only
                                      POST /v1/auth/register|login    ◀───────┤ autofill commit on the submit tap
                                      POST /v1/links/resolve (session) ◀──────┤ in parallel: schedule or match day, small logo
                                      POST /v1/join-attempts           ◀──────┘ decision point: join screen focused
                                       ├─ free seat, empty queue ──▶ HOLDING (STARTER while a starter seat is free, else SUBSTITUTE)
                                       │                             ──▶ pop-up with the seat type, countdown from 10
                                       ├─ no free seat, someone deciding ──▶ WAITING ──▶ waiting screen with the seats left per type
                                       ├─ already a holder ──▶ 409 ALREADY_MEMBER
                                       └─ link closed ──▶ 410 LINK_CLOSED (reason: FULL, EXPIRED, RENEWED, CLOSED, CANCELLED, PLAYED, ORGANISER_GONE)
 waiting phone: GET /v1/join-attempts/{id}?view=<tag> (long-poll from the snapshot; its arrival is the heartbeat)
 seat frees ──▶ settle: head WAITING → OFFERED with the free seat's type ──▶ poll answers ──▶ POST …/ack ──▶ HOLDING ──▶ pop-up
 pop-up: Katıl → POST …/decision ACCEPT (judged by arrival) → JOINED as as or yedek → "Takıma Katıldın" or "Maça Katıldın" → jersey prompt
         Reddet → REJECTED;   local zero → TIMEOUT (closes at expiresAt) → EXPIRED;   both pass the seat on
```

A logged-in member whose app has verified App Links: link tap, then Katıl. The app sends the claim and a resolve in parallel. The claim answer (about 1 KB) opens the pop-up; the resolve answer brings the 128 px logo of a team, and the pop-up shows the team's initials until it arrives. An open match has no logo, so its pop-up opens complete. That is one round trip from tap to pop-up.

**One engine for both kinds of join link (owner decision 1).** A team invite and an open match link lead into a seat pool (`seat_pools`, 8.2). Every rule of this section works on the pool: the claim, the queue, the offer and its acknowledgement, the hold and the decision, `settle`, the clock, the pool snapshot and the long-poll, the FIFO writer lock, the arrival stamps and the in-flight protection, pause and silence recovery. The server module `app/seats.py` holds them once. Five things depend on the pool's kind, and a small `PoolKind` adapter with one implementation per kind holds them:

| Kind-dependent part | TEAM | OPEN_MATCH |
|---|---|---|
| where a holder row lives (T8, leave, removal) | `memberships` with role MEMBER | `open_match_players` |
| holder limit (T1, T8b) | 20 teams per person (`409 TEAM_LIMIT_REACHED`) | 10 open matches that are not cancelled and whose day has not passed (`409 OPEN_MATCH_LIMIT`) |
| the fixed starter count and the substitute bounds (2.3; v5 replaces v4's members rule here) | 6 starter seats; 0-6 substitute seats | 12 starter seats; 0-12 substitute seats |
| the pop-up's top part (`target` in every answer) | team name, tag, logo version, weekday and time | organiser's display name, match name, date, start time, venue |
| who manages the pool | the captain | the organiser |

Holders of both kinds are counted through one view, `pool_holders` (8.2), so `settle` and the snapshot never ask which kind a pool is. The app's join flow (10.2) is one controller as well; only its texts pick a team form or an open-match form (2.12).

### 2.2 The share link and the code

**Token.** `secrets.token_urlsafe(16)`: 22 characters, 128 bits. The server stores SHA-256 of the token (P8).

**Code.** 8 characters from `23456789ABCDEFGHJKMNPQRSTUVWXYZ` (31 characters, no 0/O/1/I/L), shown as `K7QF-2M9X` (owner decision 3 of 17 November, which settles 16.1 item 5; F7, P4). `GET /v1/health` reports the length as `links.codeLength`, so no text hard-codes it. Typed input ignores case, spaces and hyphens; pasted text follows the stricter extraction rule of 10.1. The server stores HMAC-SHA256 of the normalized code under a 32-byte link key (P8). The key file lives outside the storage folder (S9): by default in the operator's configuration folder (`~/Library/Application Support/VolleyStat/link.key` on macOS, `$XDG_CONFIG_HOME/volleystat/link.key` on Linux) or at `VOLLEYSTAT_LINK_KEY_FILE`, with mode 0600, created at first start and never in the database. The server refuses to start when the key path lies inside the storage folder, and no admin command that packs or copies the storage folder takes the key. A copied storage folder, a debugging snapshot or a backup therefore hands out no code. The server draws a new code until its HMAC is unused both as a live `code_hmac` and as a tombstone's `closed_code_hmac`, inside the write transaction that stores it. A closing link moves its HMAC from `code_hmac` (unique among live links) to `closed_code_hmac` for the tombstone's 30 days (R3). Losing the key makes every live code unknown; links keep working, and a captain renews.

**Guessing.** 31^8 ≈ 8.5 × 10^11 codes. A code miss on any route that accepts a code (resolve, register, claim, accept, decline) counts: anonymous misses pass a server-wide circuit breaker of 1 000 per 10 minutes, which typos never reach; logged-in misses have 10 per hour per account; after B6a every source address (an IPv6 address by its /64) has its own key (3.6, S2). A code that hits a tombstone is not a miss. Even at the circuit breaker's full rate (144 000 guesses a day), a week of guessing against 100 live codes succeeds with a probability of about 1.2 × 10^-4. The token path is never affected by code limits.

**URL.** `https://<link host>/j/#t=<token>&c=<code>` for a join link (a team invite or an open match link; the server tells them apart by the token) and `https://<link host>/m/#t=<token>&c=<code>` for a match request.
- Browsers never send the fragment, so no access log and no WhatsApp preview fetch holds the token or the code.
- The code sits next to the token because the share message already carries both. The fallback page reads it from the fragment and shows it without asking any server (F1), and the server still stores only the HMAC (P8).
- The app reads the token from `intent.data.fragment` and sends it in request bodies. A proxy or tunnel that ends TLS sees those bodies. Section 12 therefore names where TLS ends, and v2's sentence "no proxy or tunnel ever holds the token" is withdrawn (P6).

**What makes the link open the app.** Android App Links need `https://<link host>/.well-known/assetlinks.json` with the package name and the release signing certificate's SHA-256. The link host is an owner decision (16.1 item 2):
- **The owner's website domain** survives any change of the API's host or tunnel. It needs four static files on the website and the owner's explicit permission (12.4). This project changes no file of the website.
- **The API server's own host** (the default once hosting exists, B6a). The server serves `assetlinks.json` and the fallback pages. Links break whenever that host changes.
- **Before hosting** the server has no public address: `GET /v1/health` reports `links.publicUrl: false`, the join link has no `url`, and the app shares the code with the paste-friendly message of 2.12.

**The fallback page** at `/j/` and `/m/` (F1) is static HTML with one external script, because a strict CSP allows no inline script. Its CSP is `default-src 'none'; script-src 'self'; style-src 'self'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'`. It shows no team data. Anyone can write the fragment, so the script checks it before it uses it (S6):
- It accepts `t` only when it matches `^[A-Za-z0-9_-]{22}$`, and `c` only when, after removing `-` and uppercasing, it is 8 characters of the code alphabet. Anything else shows the no-code state without an Uygulamada Aç button.
- It writes text only with `textContent`, creates no element from the fragment, and builds every URL from the checked values only.

With checked values the script:
- shows the code large with a Kodu Kopyala button;
- builds `intent://<link host>/j/#t=<t>&c=<c>#Intent;scheme=https;package=com.voleybolveveleybol.volleystat;S.browser_fallback_url=<f>;end` for Uygulamada Aç (`/m/` for a request), where `<f>` is `encodeURIComponent(location.origin + location.pathname + "#t=" + t + "&c=" + c)`. Android's `Intent.parseUri` reads the intent from the last `#` and takes the data from the text before it, with `intent:` replaced by the scheme. The data is therefore `https://<link host>/j/#t=…&c=…`, which the app's intent filter (10.6) matches with the fragment kept. v3's `intent://j/…` gave the host `j`, which no filter matches, so the button only reloaded the page; and an unencoded fallback URL with its own `#` would have moved the last `#` (R6). A phone without the app returns to this page, with the code, instead of a missing Play Store entry;
- shows the install steps of the chosen distribution (default: ask the captain for the APK file, allow the install, then tap the WhatsApp link again).

The texts are in 2.12.

**Expiry and control.**
- An invite lives 7 days. An open match link lives 7 days or until the end of the match day (23:59:59 in UTC+3, the fixed offset that 6.1 uses), whichever comes first, and the record's first upload closes it earlier (reason PLAYED, 5.8.3). One guard stands in front of every route that creates an open match link (`POST …/link` and a removal with `renewLink=true`): it refuses when the open match is cancelled (`409 MATCH_CANCELLED`), when its day has passed, an upload row of the record exists or the record is a sibling (`409 OPEN_MATCH_PLAYED`); a match without an organiser has nobody who may call these routes (V4-S2). The clock closes an expired join link even when nobody waits (2.5).
- `POST /v1/teams/{id}/invite` ("Bağlantıyı Yenile") and `POST /v1/open-matches/{id}/link` close the live join link with reason RENEWED and create a new one. `DELETE …/invite` ("Daveti Kapat") and `DELETE …/link` ("Bağlantıyı Kapat") close it with reason CLOSED. Expiry closes it with reason EXPIRED. Cancelling an open match closes its link with reason CANCELLED, its first upload with reason PLAYED, and the organiser's account deletion with reason ORGANISER_GONE (3.7). Each closes every open attempt of the pool with the matching reason (T14: LINK_RENEWED, LINK_CLOSED, LINK_EXPIRED, MATCH_CANCELLED, MATCH_PLAYED, ORGANISER_GONE); a hold whose decision arrived before the renewing, closing or cancelling request stays open until that decision's transaction runs (2.9).
- **A join link closes on its own when its pool is full** (P2). Any transaction that ends with holders = capacity closes the live join link with reason FULL. At that moment nobody is offered or holding (seat invariant), so `settle` closes every waiting attempt with reason FULL (T13). T14 does not apply to a FULL link, so a waiter's reason always comes from T13 (fidelity V7). The captain's or the organiser's card then offers "Yeni Bağlantı Oluştur", and an old link answers `410 LINK_CLOSED` with reason FULL. A FULL link stays closed when a seat frees later; the texts therefore speak of the moment it filled, and the captain's or the organiser's card picks its text from the seats left now (2.12 F, F4).
- Creating a team creates its first invite; opening an open match creates its first link.
- A closed link stays as a tombstone for 30 days: token hash, the code's HMAC in `closed_code_hmac`, pool or team, kind, reason and `closed_at`. Resolve, claim, register, accept and decline, by token or by code, then answer `410 LINK_CLOSED` with `details.reason`, `details.kind`, `details.teamName` for a team invite and `details.matchDayPassed` for an open match link, so every text can say what really happened (F6). For an open match link with `matchDayPassed: true` the app shows `link_expired_played` for every reason except CANCELLED, PLAYED and ORGANISER_GONE, because the server creates no new link then (F4). A code lookup tries the live `code_hmac` first and then the newest tombstone with that `closed_code_hmac`. A tombstone hit counts toward no throttle key, so a person who types the right code of a closed join link gets the text of its reason (`link_team_full`, `link_renewed`, `link_expired`, `link_closed` or their open-match forms), and never `code_wrong` (R3, fidelity V2). After 30 days the row goes and the token or the code answers `404 LINK_NOT_FOUND`.

**Showing the token and the code (P8).** The server returns the token, the code and the URL only in the answers of team creation, open match creation, `POST …/invite`, `POST …/link` and `PUT /v1/match-requests/{id}`. `GET …/invite` and `GET …/link` return only state, expiry and seats. The phone that created a link keeps the URL and the code encrypted (10.3). A captain or an organiser on another phone sees `invite_not_on_this_phone` in its team or open-match form and can renew.

### 2.3 Seats

A pool has S starter seats (as) and B substitute seats (yedek). For one pool at any instant, inside any transaction, and for each seat type t (STARTER and SUBSTITUTE):

```
holders_t + offered_t + holding_t ≤ seats_t           (the seat invariant, per type)
free_t = seats_t − holders_t − offered_t − holding_t
free   = free_STARTER + free_SUBSTITUTE
```

- A seat of a type is reserved from the moment an attempt becomes OFFERED or HOLDING with that type (`join_attempts.seat`). A WAITING attempt reserves nothing and has no type yet.
- **Which type the next person gets (owner decision 1, D1b).** A person who reaches the decision point with free > 0 and an empty queue gets a STARTER seat when free_STARTER > 0, else a SUBSTITUTE seat (T1). When `settle` offers free seats to the queue, it walks the WAITING attempts by `queue_no` and gives each one a STARTER seat while free_STARTER > 0, then SUBSTITUTE seats (T5). Starter seats therefore fill first in queue order, then substitute seats, and when both are full the pool is full.
- **The type is fixed at reservation.** An offer, the hold that follows and the membership that follows keep the type they got. When a starter seat frees while someone decides on a substitute seat (a starter member leaves or the captain removes one, a starter's account is deleted, a starter holder rejects), `settle` offers the freed starter seat to the head of the queue, and the person deciding keeps the substitute seat that their pop-up shows. The server never changes a member's or a player's type either; the captain or the organiser moves holders (below). Every pop-up and every outcome text therefore stays true from the moment it shows. Upgrading an open reservation instead is an owner decision (16.1 item 16).
- After `settle` returns, free > 0 and a non-empty queue never both hold. With a queue, free_STARTER = free_SUBSTITUTE = 0, so on the waiting screen the seats left of each type equal the people deciding on that type, and the screen can name them: "Takımda 1 yedek yeri kaldı. Bu yer şu an bir kişinin kararını bekliyor." (2.12 D).
- The queue exists only while someone can still get a seat. When free = 0 and nobody is offered or holding, every WAITING attempt closes with reason FULL, and if holders = capacity the join link closes with reason FULL (2.2).
- A queue holds at most 200 attempts (`409 QUEUE_FULL`). A person holds at most 3 open attempts across all pools (`409 ATTEMPT_LIMIT`, P3).
- Every holder row enters through T8 or through creation. Creation checks the creator's seat against the final counts like T8 would: a team created with `captainSeat: SUBSTITUTE` and no substitute seat answers `422 SEATS_INVALID` with `details.reason: CAPTAIN_SEAT`, and an open match created with "Ben de oynuyorum" checks the organiser's JOINED limit (5.8.2). `memberships.seat` and `open_match_players.seat` have no default and a CHECK on the two types, so no insert can skip the type (V4-C2; `verify_v4/captain_seat_probe.py` showed a substitute captain in a pool of 0 substitute seats failing `settle`'s assertion on every later write).

**Bounds from the FIVB rules (D1h).** Rule 7.3.1 keeps six players of a team in play ("Her takımın oyunda daima altı oyuncusu olmalıdır."), rule 7.3.3 makes the players outside a set's starting line-up its substitutes, liberos excepted ("Bir setin başlangıç dizilişinde yer almayan oyuncular, o set için yedek oyunculardır (Liberolar hariç)."), rule 19.1.1 lets a team name at most two liberos from its list, and rule 4.1.1 allows a team at most 12 players for a match (14 on the score sheet only in FIVB, World and official senior competitions). The owner added on 18 November: "Kaptan ancak yedekleri değiştirebilsin. Sahada 6 kişi olmak zorunda." v5 applies them like this; a libero is a role of a match roster (6.3), not a team seat:

| Pool | Starter seats | Substitute seats | Total | Default |
|---|---|---|---|---|
| team | always 6 | 0-6 | 6-12 | 6 + 2 = 8 |
| open match | always 12; after the split 6 per side (5.8.5) | 0-12; after the split at most 6 per side (5.8.5) | 12-24 | 12 + 2 = 14 |

A team's seats are bounded like one match side, so a team's default match roster (6.3) always fits rule 4.1.1. A larger group makes a second team or plays open matches. The value 14 of official competitions is not used (16.1 item 22). A substitute count outside the table answers `422 SEATS_INVALID` with `details.reason: SUBSTITUTES_RANGE`; no request body carries a starter count. `seat_pools` stores both counts under a CHECK that fixes the starter count of each kind and keeps the substitute count at 0 or more (8.2), so no write can store another starter count. The upper bound of the substitute count (6 or 12) lives in the engine function of the seats route and in the creation routes, not in the schema, so a later owner decision on it (16.1 item 22) needs no rebuild of `seat_pools` (S9 of the v5 checkers).

**Why the starter count is fixed (v5, R1, R2).** Rule 7.3.1 keeps six players of a team in play, and the owner asked for six on the court and for a captain who changes only the substitutes. A team's 6 starter seats are its usual starting six, and an open match's 12 are the sixes of its two sides. The captain and the organiser set only how many substitutes may join, and they still move holders between the two types, so the order of joining never fixes the starting six (the other reading of R1, under "v5 changes"). A team with fewer than 6 starter holders simply has free starter seats, which the join engine fills first (D1b).

**Changing the substitute count and moving holders (D1c, R1, R2).** The captain of a team (`PUT /v1/teams/{id}/seats`) and the organiser of an open match (`PUT /v1/open-matches/{id}/seats`) send the same body, `{substituteSeats, moves?: [{userId, seat}]}`. A body that carries `starterSeats` answers `422 VALIDATION_ERROR` (`extra="forbid"`), because the starter count is fixed. Both routes call one engine function. In one write transaction it first runs pause recovery when it is due and `settle`, as a claim does (2.4), so a hold whose `grace_until` passed before the clock's tick no longer counts as deciding; then it applies the moves and the substitute count and checks the final state (S6 of the v5 checkers):
- For each type t, after the moves: holders_t ≤ seats_t, else `409 SEATS_BELOW_HOLDERS` (`details.seat`, `details.holders`, `details.seats`); and holders_t + offered_t + holding_t ≤ seats_t, else `409 SEATS_IN_USE` (`details.seat`, `details.holders`, `details.deciding`, `details.seats`). A count therefore never goes below the holders of its type plus the people deciding on its type, and an open offer or hold is never revoked or re-typed. For the starter type only moves can fail these checks, because its count never changes.
- A move changes only a holder's type: it writes `memberships.seat`, or `open_match_players.seat` with, while the roster was never saved, the moved player's `proposed_side`, and never a `match_rosters` or `match_roster_confirmed` row. It therefore changes only what the server computes from the seats: a team side's `TEAM_DEFAULT` while that side has no stored rows (6.3), and an open match's PROPOSAL while its roster was never saved, where each moved player gets a new proposed side (5.8.5; S1 of the v5 checkers). Swapping a starter and a substitute is one call with two moves; a single move into a full type answers `409 SEATS_BELOW_HOLDERS` and changes nothing.
- `settle` then runs again in the same transaction. A raised substitute count offers its new free seats to the queue at once, with the type rule above (T5). A move never offers a seat: it leaves the free total unchanged, and with a queue both free counts are 0 (2.10). A lowered substitute count changes no attempt: with a queue both free counts are 0 after the first `settle`, so every lowering fails the checks above, and without a queue nobody waits (2.10). holders = capacity closes the join link with reason FULL. A closed join link stays closed until the captain or the organiser creates a new one.
- Screens: a waiter whose seats-left numbers change is woken, because its view tag holds them (2.7). A holder and a decider see no change, because their type does not change. The moved holders see their new type the next time their team or open match screen loads.

### 2.4 The server state machine

```
                  claim, free seat, empty queue; type: STARTER while a starter seat is free, else SUBSTITUTE
   (decision point) ─────────────────────────────▶ HOLDING(type) ──ACCEPT arrived < grace_until──────▶ JOINED as type
        │                                            │  ├──REJECT or DELETE─────────────────────▶ REJECTED
        │ claim, no free seat,                       │  ├──grace_until passed, no protected decision,
        │ someone deciding                           │  │  or its own ACCEPT arrived late─────────▶ EXPIRED (NO_DECISION)
        ▼                                            ▲  └──TIMEOUT reported, single session,
     WAITING ──settle: seat free, head of queue──▶ OFFERED(type)      at expiresAt───────────▶ EXPIRED (TIME_UP)
        │       (type by 2.3)             ack arrived < offerDeadline ─┘
        │                                            └──offerDeadline passed (clock, or its own late ack)──▶ SKIPPED
        ├──no heartbeat for 30 s, outside a silence──▶ DROPPED
        ├──DELETE───────────────────────────────────▶ LEFT   (OFFERED + DELETE ▶ LEFT too)
        └──free 0 and nobody deciding──────────────▶ CLOSED (FULL)
   any open state: join link renewed, closed or expired, the open match cancelled, its first upload, or its organiser's account deleted
        ──▶ CLOSED (LINK_RENEWED, LINK_CLOSED, LINK_EXPIRED, MATCH_CANCELLED, MATCH_PLAYED, ORGANISER_GONE)
```

Every transition, its trigger, its guard and its effect. "Arrival" is the request's arrival stamp (2.9); "now" is read after the write lock is taken and after pause recovery (2.11). "Protected" and "silence" are defined in 2.9 and 2.11. "Holder limit" is the limit of the pool's kind: 20 teams for a team pool, 10 open matches that are not cancelled and whose day has not passed for an open match pool (2.1). v4's "members rule" and its transition T8d are gone in v5: anyone with an account and the live link may take a seat (0.4, R6).

| # | From → to | Trigger | Guard (checked in the same write transaction) | Effect |
|---|---|---|---|---|
| T1 | none → HOLDING | `POST /v1/join-attempts` | the pool's join link live; not a holder of the pool (`409 ALREADY_MEMBER`); not blocked in the pool (`403 JOIN_BLOCKED`); no open attempt for this pool; fewer than 3 open attempts; free > 0 and queue empty (after settle); below the holder limit (`409 TEAM_LIMIT_REACHED` or `409 OPEN_MATCH_LIMIT`) | `seat` = STARTER if free_STARTER > 0 else SUBSTITUTE; `queue_no = ++queue_seq`, `arrived_at`, `opened_session`, `hold_started_at = now`, `expires_at = now + 10`, `grace_until = expires_at + 2`, `due_at = grace_until` |
| T2 | none → WAITING | same | free = 0 and offered + holding > 0; queue shorter than 200 | `seat` NULL; `queue_no = ++queue_seq`, `arrived_at`, `opened_session`, `seen_at = now`, `due_at = now + 30` |
| T3 | none → (refused) | same | free = 0 and nobody offered or holding | `409 SEATS_FULL`, no row. Defensive only: holders = capacity has already closed the join link, so the claim answers `410 LINK_CLOSED` (FULL) first. |
| T4 | WAITING → WAITING | long-poll arrival or claim | | heartbeat kept in memory, also for a poll that a cap answers at once (2.7); the clock writes `seen_at` and `due_at = seen_at + 30` for all of them in one UPDATE per tick (C1) |
| T5 | WAITING → OFFERED | `settle` | free > 0 and the attempt is among the `free` lowest `queue_no` | `seat` = STARTER for the first free_STARTER of them in queue order and SUBSTITUTE for the rest (2.3); `offered_at = now`, `offer_deadline = now + 8`, `due_at = offer_deadline` |
| T6 | OFFERED → HOLDING | its own `POST /v1/join-attempts/{id}/ack` by the same person | arrival < `offer_deadline` | `seat` unchanged; `hold_started_at = now`, `expires_at = now + 10`, `grace_until = expires_at + 2`, `due_at = grace_until` |
| T6b | OFFERED → SKIPPED | its own `POST …/ack` | arrival ≥ `offer_deadline` | closed; `settle` offers the seat to the next person in the same transaction; answer `409 ATTEMPT_CLOSED` (SKIPPED) with `details.offerWindowS` (conc V3) |
| T7 | OFFERED → SKIPPED | `settle` | now ≥ `offer_deadline`; not protected; not in a silence | closed |
| T8 | HOLDING → JOINED | its own `decision ACCEPT` | arrival < `grace_until`; below the holder limit (else T8b) | insert the holder with the attempt's `seat` through the kind adapter (a team: membership MEMBER; an open match: player), `joined_at = now`, `decided_session`, closed; if holders = capacity, close the join link (FULL) |
| T8b | HOLDING → CLOSED (LIMIT) | its own `decision ACCEPT` | arrival < `grace_until`; the person reached the holder limit meanwhile | closed, `409 TEAM_LIMIT_REACHED` or `409 OPEN_MATCH_LIMIT` |
| T8c | HOLDING → EXPIRED (NO_DECISION) | its own `decision ACCEPT` | arrival ≥ `grace_until` | closed, `late_accept_at = arrival`; `settle` offers the seat in the same transaction; answer `409 ATTEMPT_CLOSED` with `details.lateAccept: true` (conc V3) |
| T9 | HOLDING → REJECTED | `decision REJECT` or `DELETE` | | `decided_session`, closed |
| T10a | HOLDING → EXPIRED (NO_DECISION) | `settle`; or its own TIMEOUT from any session that arrived at or after `grace_until` (S1) | now ≥ `grace_until`; in `settle` also not protected and not in a silence | closed |
| T10b | HOLDING → EXPIRED (TIME_UP) | `decision TIMEOUT`, then `settle` | `other_session = 0` | The TIMEOUT sets `timeout_reported = 1`. If it arrived at or after `expires_at`, the hold closes at once. Otherwise `due_at = expires_at`, and `settle` closes the hold at `expires_at` unless it is protected. With `other_session = 1` a TIMEOUT that arrived before `grace_until` only records itself, and T10a applies (C3); one that arrived at or after `grace_until` closes the hold by T10a (S1). |
| T11 | WAITING or OFFERED → LEFT | `DELETE` | not protected | closed |
| T12 | WAITING → DROPPED | `settle` | now ≥ `seen_at + 30`; not in a silence | closed |
| T13 | WAITING → CLOSED (FULL) | `settle` | free = 0 and offered + holding = 0 | closed |
| T14 | any open → CLOSED (LINK_RENEWED, LINK_CLOSED, LINK_EXPIRED, MATCH_CANCELLED, MATCH_PLAYED, ORGANISER_GONE) | renewal, closing or expiry of the join link, cancellation of the open match, its first upload, or its organiser's account deletion; never a FULL link, whose waiters T13 closes (fidelity V7) | a hold protected by a decision that arrived before the closing request's own arrival (for expiry, before the link's `expires_at`) stays open; its own decision transaction or the next `settle` closes it (2.9, conc V1) | closed |
| T15 | any open → (row gone) | team, open match or account deleted | | `ON DELETE CASCADE` through the pool; the deleting transaction runs `settle` for every affected pool and publishes their snapshots |
| T16 | closed → (row gone) | minute sweep | `closed_at + 24 h` passed | deleted, then `empty_write_ahead_log()` |
| T17 | closed → closed | `decision ACCEPT` on a closed attempt | | `late_accept_at = arrival` is recorded. For an EXPIRED attempt the answer carries `details.lateAccept: true` when the arrival is at or after the deadline that closed it (`expires_at` for TIME_UP, `grace_until` for NO_DECISION), and `details.acceptArrivedInTime: true` otherwise; the second happens only when an in-time ACCEPT waited longer than its 5 s protection (2.9). Every other closed state answers with both false. `409 ATTEMPT_CLOSED` with `details.state` and `details.reason` (conc V1). |

A seat-count change or a move (2.3) is no transition: it changes no attempt, and the `settle` that follows it runs T5 and the FULL rule.

**Order inside a write route (conc V3).** A claim runs pause recovery when it is due (2.11), then `settle`, then T1, T2 or T3. An ack or a decision runs pause recovery when it is due, then judges its own attempt by its arrival (T6, T6b, T8, T8b, T8c, T9, T10a, T10b, T17), and only then runs `settle`, which skips only other protected attempts (2.9). The seats route (2.3) runs pause recovery when it is due and `settle` before its checks, like a claim, then makes its change and runs `settle` again. Every other write route makes its change and runs `settle`.

Every closing transition sets `closed_at = now` and `due_at = NULL`, runs `settle` in the same transaction (so a freed seat is offered, with its type, before the commit), and builds the pool snapshot that the route publishes after the commit (2.7). Every write transaction that changes a pool's join state moves `seat_pools.queue_version` by one (2.7, conc V7). A repeat of the transition that already happened answers 200 with the current state (an ACCEPT after JOINED, a DELETE after LEFT, an ack after HOLDING). An ack on a WAITING attempt answers `409 NOT_OFFERED`. A decision on a WAITING or OFFERED attempt answers `409 NOT_DECIDING` with `details.state` (S1). Any other request on a closed attempt answers `409 ATTEMPT_CLOSED` with `details.state` and `details.reason`; a SKIPPED attempt's answer also carries `details.offerWindowS`, the whole seconds from `offered_at` to `offer_deadline` (fidelity V8).

**Same person, same pool.** The partial unique index `join_attempts_one_open` allows one open attempt per person and pool. A claim from any device returns that attempt and never acknowledges an offer (C5). A claim from a session other than `opened_session` sets `other_session = 1`, which keeps the full grace for every device of that person (T10b). Only a fresh link tap or "Yeniden Sıraya Gir" creates a new attempt after a closed one; the app resumes an existing attempt with `GET /v1/join-attempts/{id}` (10.2). One person may wait in a team's queue and in an open match's queue at the same time, within the 3 open attempts.

**Why the early TIMEOUT moves the close to `expiresAt` instead of being ignored (C3, refined).** The phone's deadline lies before the server's by about the one-way delay of the request that set it (2.6), and the TIMEOUT then needs the same delay to arrive. It therefore reaches the server close to `expiresAt`, before it about half the time. The critic's measured fix ignores a TIMEOUT that arrives before `expiresAt`, so in those cases the next person waits the full 2-second grace. Marking the hold and closing it at `expiresAt` keeps the protection the critic measured (a wrong early TIMEOUT never shortens the visible 10 seconds) and still saves the grace. The grace exists for a tap that the network delays; after the only session reported its zero, no tap can follow. A second session (`other_session = 1`) keeps the grace, so a tap on the other phone is never lost.

### 2.5 `settle`: the only place time moves state

```python
def settle(db, pool_id, now, own_attempt=None):
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
    if the live join link is past its expires_at: close it with reason EXPIRED (a tombstone, 2.2)
    if the join link is missing, closed as RENEWED, CLOSED, EXPIRED, CANCELLED, PLAYED or ORGANISER_GONE,
    or past its expires_at:
        close every open attempt with the matching reason (LINK_RENEWED, LINK_CLOSED, LINK_EXPIRED,
        MATCH_CANCELLED, MATCH_PLAYED, ORGANISER_GONE), except a hold protected(closing arrival)  (T14)
    holders = count by seat from pool_holders;  reserved = count OFFERED and HOLDING by seat
    for t in (STARTER, SUBSTITUTE):
        free[t] = seats[t] - holders[t] - reserved[t];  assert free[t] >= 0
    for a in WAITING by queue_no while free[STARTER] + free[SUBSTITUTE] > 0:                     (T5)
        t = STARTER if free[STARTER] > 0 else SUBSTITUTE
        offer a with seat t;  free[t] -= 1
    if no free seat is left and nothing is OFFERED or HOLDING:
        close every WAITING attempt -> CLOSED FULL                                               (T13)
    if sum(holders) == seats[STARTER] + seats[SUBSTITUTE] and the join link is live:
        close it with reason FULL                                                                (2.2)
```

`settle` reads holders only from the `pool_holders` view (8.2), so it is the same function for a team and for an open match. The closing arrival of a renewed or closed join link is the arrival of the request that closed it, which the server stores as the link's `closed_at`; for a cancelled open match it is the arrival of the cancel request; for a link closed by the first upload, the arrival of that `initiate_upload`; for a link closed by the organiser's account deletion, the arrival of the deletion request; for an expired link it is `expires_at`. A FULL link closes no attempt through T14: by the seat invariant nobody is offered or holding then, and T13 closes the waiters with reason FULL. In v3 the T14 line ran first and closed them with the invite's reason FULL, which `join_attempts.reason` did not know and no text covered (fidelity V7).

Who calls it:
- **Every write route** that changes a pool's join state, in its own write transaction and in the order of 2.4: claim, ack, decision, leave of a queue, seat counts and moves, member or player leave and removal, join link changes, open match settings and cancellation, an open match's first `initiate_upload`, team, open match and account deletion.
- **The clock**, an asyncio task started in the app's lifespan next to the analysis worker. Every 250 ms it (1) flushes the batched heartbeats in one transaction, (2) runs one indexed query `SELECT DISTINCT pool_id FROM join_attempts WHERE due_at <= ?`, and (3) runs `settle` for each pool it finds, each in its own write transaction in the thread pool, then publishes the snapshot and wakes that pool's long-polls. A protected attempt keeps its `due_at`, so the clock looks at it again on the next tick. Like every writer, each of these transactions runs pause recovery first when it is due (2.11). The expiry of a join link needs no attempt row: the clock also runs `settle` for every pool whose live join link's `expires_at` passed (index `share_links_live_expiry`, 8.2), and that `settle` turns the link into a tombstone, so an open match link closes at the end of the match day even when nobody waits. v3 left expired invites to the next `settle` of the team.
- **Start-up**, once, after recovery (2.11).

**Never:** a long-poll, a resolve, the captain's or the organiser's live view or any other read. Reads answer from the snapshot or from a read transaction and never write (C1).

Why there is no race:
- The FIFO lock (2.8) and `BEGIN IMMEDIATE` serialize every writer of the process, and `now` is read after both. Two `settle` calls for one pool run one after the other, and the second sees the first one's result.
- Every transition is conditional on the state it leaves, and no transition reopens a closed attempt or changes the type of an open one.
- The clock holds no deadline in memory. It reads `due_at` and the live links' `expires_at` from the database on every tick, so a restart or a missed wake-up loses at most 250 ms.
- The in-flight entries (2.9) only ever delay a closing transition, by at most `PROTECT_MAX` (5 s) after the deadline they protect; they never open or move a seat.

Tests run the clock by hand, as they run the analysis worker today: `create_app(..., start_worker=False, clock=fake_clock)` and `app.state.queue.tick()`.

### 2.6 Timing and the phone's countdown

| Constant | Value | Why |
|---|---|---|
| `HOLD` | 10 s | The owner's window (approved again on 17 November). It starts when the pop-up can open: at T1 for a person who reaches the decision point with a free seat, at T6 for a person who comes from the queue. |
| `GRACE` | 2 s | Approved by the owner on 17 November. A tap made in time but delayed by the network still lands. Judged by arrival (2.9) against `grace_until`, which is `expires_at` + 2 s and which only pause recovery moves later (2.11). The phone never shows it. |
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
- WAITING: state, `ahead`, seats left of each type (which equal the people deciding on each type, 2.3), join link state.
- OFFERED: state, `offer_deadline` and seat type.
- HOLDING: state, `expires_at` and seat type. Queue changes and seat-count changes do not touch it, so the pop-up's poll sleeps through other people joining the queue and through the captain's or the organiser's changes.
- closed: state and reason.
A claim behind someone changes nothing on that person's screen, because the waiting screen does not show the total, so it answers nobody.

**The pool snapshot (C1).** Every write transaction that changes a pool's join state builds a snapshot before COMMIT: the pool's kind and id, `version` (= `queue_version`), the seats, holders, seats left and deciding of each type, the waiting count, the join link state, the target data that the pop-up shows (a team's name, tag, logo version and schedule, or an open match's name, organiser display name, date, start time and venue), the ordered WAITING ids, and for each open attempt and each attempt closed in the last 10 minutes its user id, state, reason, seat type, `queue_no`, `expires_at`, `grace_until`, `offered_at`, `offer_deadline`, `decided_session` and `late_accept_at`. After COMMIT the route hands it to the notifier with `loop.call_soon_threadsafe`.

Every write transaction that builds a snapshot moves `queue_version` by one, whatever it changed: a claim behind others, an ack, a decision, a leave, a seat-count change or move, a settings or holder change, a join link change, and `settle` when it changed something. No two published snapshots of a pool therefore share a version. The notifier replaces a pool's snapshot only with a strictly higher version, and a snapshot that a poll's read transaction built never replaces one with an equal or higher version. Without the bump per claim, 1 999 of 2 000 burst snapshots were dropped as equal versions, and the waiters missing from the kept snapshot fell back to database reads on every poll (conc V7, `verify_v3/publish_order.py`); no publication arrived out of commit order. The notifier then sets the pool's `asyncio.Event`, which wakes that pool's long-polls.

**How a poll runs.** The route is `async def` and stays in the event loop:
1. Authenticate from the in-memory session cache (3.2). On a cache miss it reads the session once in the thread pool.
2. Find the attempt in the pool's snapshot and check that it belongs to the caller (`403 NOT_YOUR_ATTEMPT`). An attempt missing from the snapshot (after a restart, or closed more than 10 minutes ago) costs one read transaction in the thread pool; its snapshot is published only when no snapshot of that pool exists yet.
3. Record the heartbeat in memory: `last_seen[attempt] = arrival`. This comes before any cap check, so every poll that authenticates is a heartbeat (conc V5, S5).
4. A poll on a closed attempt answers at once and never waits. A poll over a cap (below) also answers at once, with the current view, `"pollLimited": true` and `Retry-After: 1`; the app waits that second and polls again. A refused poll therefore still shows true numbers and still delivers an offer.
5. Compute the view from the snapshot and its tag. If the tag differs from `view`, answer. Otherwise take the pool's event, check once more, and wait on it with the remaining time. On wake, repeat from step 5.
6. Every answer carries `waitedMs` (C3) and is about 0.5-1 KB. No answer of claim, ack, decision or poll carries logo bytes (C7).
The poll holds no thread and no database connection while it waits, never runs `settle` and never writes. The clock flushes all heartbeats in one `executemany` per tick.

**Limits (P3).**
- One live long-poll per attempt and session. A newer poll from the same session answers the older one at once with the current view. Two sessions of the same person may each keep one, so two phones never push each other out.
- At most 4 waiting long-polls per account, capacity + 200 per pool, and 400 per server. Over any of them a poll answers at once as in step 4, never with `429` or `503`. One link holder's accounts can then neither take other pools' share nor cost a real waiter its place, and `queue_dropped` stays true (conc V5, S5).
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

**The captain's and the organiser's live view.** `GET /v1/teams/{id}/live?view=&wait=15` and `GET /v1/open-matches/{id}/live?view=&wait=15` are the same long-poll for the captain's team screen and the organiser's open match screen, answered from the same pool snapshot. They return counts only (holders and deciding of each type, waiting, join link state, and for a team the new joins), never who is waiting or deciding.

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
5. **Protection.** `settle`, the clock and T14 skip an attempt only while it has an entry whose arrival is before that attempt's deadline: `grace_until` for a hold, `offer_deadline` for an offer, `expires_at` for a reported single-session TIMEOUT, and for T14 the closing request's own arrival (or the join link's expiry). The skip never lasts past that deadline + `PROTECT_MAX`. A request that arrives after the deadline protects nothing, and `settle` never skips the route's own attempt, which step 4 already judged.
6. **Rate (S1).** Ack, decision and DELETE on one attempt are limited to 5 a second per attempt, and claims to 5 a second per account. An in-memory counter checks this before the entry is added and answers `429 TOO_MANY_ATTEMPTS` with `details.scope: RATE` and `Retry-After: 1`. A flood of TIMEOUTs from a second session can then neither keep a hold open (steps 4 and 5, T10a) nor fill the FIFO lock.
7. **Restart.** A restart empties the table. That is harmless: the phone repeats its decision with the same body until its zero plus 3 s.

Measured with only the arrival rule: 0 of 10 in-time ACCEPTs lost under the same load; with the FIFO lock and the snapshot the decisions waited 7 ms or less.

Tests (B5):
- A second process holds the SQLite write lock for 2.5 s, and an ACCEPT arrives at `expires_at + 1.0`: JOINED, and the clock offered the seat to nobody meanwhile.
- Headers before `grace_until` and the body 300 ms after it: JOINED, and nobody is offered.
- Two overlapping requests on one attempt, where the first finishes early, leave the second protected.
- A renew 50 ms after an in-time ACCEPT arrived: JOINED; the other open attempts close as LINK_RENEWED.
- An open match cancel 50 ms after an in-time ACCEPT arrived: JOINED; the other open attempts close as MATCH_CANCELLED.
- A holder with two sessions sends TIMEOUT every 20 ms for 60 s: the next waiter is offered within 2.5 s after `grace_until`.
- With the clock stopped, an ack at `offer_deadline + 0.05` answers `409 ATTEMPT_CLOSED` (SKIPPED), and the head of the queue is offered in the same transaction; an ACCEPT at `grace_until + 0.05` answers 409 with `lateAccept: true`.
- An ACCEPT whose transaction waits longer than `PROTECT_MAX` after `grace_until` answers 409 with `acceptArrivedInTime: true`.

### 2.10 Concurrency cases

The cases hold for a team and for an open match alike; "captain" stands for the organiser in an open match, and the text keys name the team form (the open-match form is in the same row of 2.12).

| Case | What happens | What each screen shows |
|---|---|---|
| 100 claims in the same second | The FIFO lock serializes the write transactions in arrival order. Each runs `settle`, then T1 or T2. `queue_no` follows arrival; the earliest arrivals get the starter seats while any are free, the next ones the substitute seats. Waiting phones start polling; a claim behind them wakes nobody. | holders get the pop-up with their seat type, the rest get their true position and the seats left per type |
| A team invite and an open match link burst at the same time on one server | Both pools' writers pass through the same FIFO lock; each pool's `queue_no`, seats and snapshot are its own. | each person sees their own pool only |
| One person on two phones | One open attempt; the second phone's claim returns it and sets `other_session`. Both phones show the same state, seat type and deadline. An ACCEPT from either joins; the other phone's poll then returns JOINED with `decidedHere: false`. Two decisions at once: the FIFO order decides, the second answers with the final state. A TIMEOUT from one phone never shortens the grace for the other. | each phone says which session decided |
| The captain lowers the substitute count mid-queue (the starter count never changes, 2.3) | Always refused. The route runs `settle` before its checks, and with a queue both free counts are then 0 (2.3), so any lower count falls below the substitute holders (`409 SEATS_BELOW_HOLDERS`) or below substitute holders + offered + holding (`409 SEATS_IN_USE`). Nothing changes. Without a queue a lowering that passes the checks changes no attempt, and holders = capacity closes the join link (FULL). | captain: `seats_below_*` or `seats_in_use_*`; no waiter's screen changes |
| The captain raises the substitute count mid-queue | `settle` offers the new substitute seats to the heads of the queue at once. | each head's waiting screen turns into the pop-up with that type; the others' seat lines change |
| The captain moves holders between starter and substitute mid-queue | Checked on the final state; no open offer or hold changes type. The free total stays the same, and with a queue both free counts are 0, so a move that passes the check offers nothing. | the moved holders see their new type; no waiter's screen changes |
| A starter seat frees while someone decides on a substitute seat (a starter leaves, a starter holder rejects) | `settle` offers the freed starter seat to the head of the queue; the person deciding keeps the substitute seat (2.3). | head: `queue_your_turn`, then the pop-up with `join_seat_starter`; the decider's pop-up is not woken |
| The captain renews or closes the link mid-queue | Every open attempt closes with LINK_RENEWED or LINK_CLOSED, holds included. A later ACCEPT gets `409 ATTEMPT_CLOSED`. | `join_link_renewed`, `join_link_closed`, `queue_link_renewed`, `queue_link_closed` |
| The organiser cancels the open match mid-queue | The link closes with reason CANCELLED; every open attempt closes with MATCH_CANCELLED, except a hold protected by a decision that arrived before the cancel (T14). | `join_cancelled_open`, `queue_cancelled_open`; joined players: `open_cancelled` |
| The join link expires mid-queue | The clock's `settle` closes the link and every open attempt with LINK_EXPIRED (2.5). For an open match this happens at the end of the match day at the latest. | `join_link_expired`, `queue_link_expired` |
| The organiser moves the open match's date | Allowed only before the match day began and while the record has no upload (5.8.4). The snapshot carries the new date, and the live link's expiry follows the new day. A holder's countdown does not change. | the next answer of every waiter and the next pop-up show the new date |
| The open match's first upload starts mid-queue | `initiate_upload` closes the link with reason PLAYED, and T14 closes every open attempt with MATCH_PLAYED, except a hold protected by a decision that arrived before the upload request (V4-S2). | `join_played_open`, `queue_played_open`; newcomers: `link_played_open` |
| The organiser deletes their account mid-queue | The link closes with reason ORGANISER_GONE and T14 closes every open attempt with ORGANISER_GONE, protected holds aside; the open match stays for its players without an organiser (3.7, V4-C1). | `join_organiser_gone_open`, `queue_organiser_gone_open`; players: `open_no_organiser` |
| The last seat is taken | T8 fills the pool; the join link closes (FULL); `settle`'s T13 closes the waiters with reason FULL (T14 does not apply to FULL). | joiner: `join_joined_*`; waiters: `queue_team_filled`; newcomers: `link_team_full` |
| A holder leaves, or the captain removes one, while a queue exists | The holder row's delete frees a seat of the holder's type, and `settle` in the same transaction offers it. A removal also writes a block (4.3). | the head gets the pop-up with that seat type |
| A removed person opens the old link | T1 refuses: `403 JOIN_BLOCKED`. | `join_blocked` |
| A holder's account is deleted | The attempt and the holder row go by cascade; the deleting transaction runs `settle` in every affected pool and publishes. | the head gets the pop-up |
| The team or the open match is deleted | The pool, its attempts and its link tombstones go by cascade. Polls and decisions get `404 ATTEMPT_NOT_FOUND`. | `join_gone` |
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
| A person at the holder limit accepts (20 teams, or 10 open matches that are not cancelled and whose day has not passed) | T8b. | `join_team_limit` or `join_open_limit` |
| Phone and server clocks differ | No effect: the countdown uses only elapsed time. | |
| The server process pauses or the computer sleeps | The beat sees the gap; the first write transaction after the wake, whichever writer it is, runs recovery before any judgment or `settle` (2.11, conc V2). | `queue_reconnecting` while the server is silent |
| The tunnel or the home connection drops while the process keeps running | Every phone goes quiet at once; the server treats it as a silence, closes nobody for a missing message, and runs recovery when requests return (2.11, R8). | `queue_reconnecting`; nobody DROPPED or SKIPPED |

### 2.11 Restart and pause recovery (C6)

The attempts live in SQLite with absolute times, so a restart loses nothing. Recovery runs at start-up before the server serves, and after every pause. Two kinds of pause exist:
- **The process stopped** (SIGSTOP, a sleeping computer, a wall-clock jump). A database-free asyncio beat task stamps `beat_wall` and `beat_mono` every 250 ms. When it sees its own gap above `PAUSE_GAP` (2 s) by `time.monotonic()`, or a change above 2 s in `time.time() − time.monotonic()`, it sets `recovery_due` under a small lock.
- **A silence: the process ran, but nothing reached it (R8).** With the server on the owner's computer behind a free tunnel, the likelier outage is the tunnel or the home connection, while the process keeps ticking. The middleware notes the arrival of every request. When no request of any kind arrived for 3 s (`SILENCE`) while at least two phones hold open attempts, the server answers every waiting long-poll at once with its current view. Each of those phones re-polls the moment its answer arrives, so a healthy server sees a request within a round trip. If again no request of any kind arrives within 3 s, the server is in a silence from that moment until the next arrival. While it lasts, `settle` runs no T7, T10a or T12, so nobody is closed for a message that could not arrive. The first arrival after it sets `recovery_due`. One phone that goes quiet while other phones still reach the server is no silence, so a single dead phone is still dropped after 30 s. A hold or offer whose deadline falls within the first 6 s of an outage still closes, because the server cannot yet tell the outage from a quiet minute.

**Where recovery runs (conc V2).** Every write transaction, right after it reads `now` under the FIFO lock and before any judgment or `settle`, runs recovery when `recovery_due` is set or `now − beat_wall > PAUSE_GAP`. It then clears the flag, ends a silence and sets `beat_wall = now`. The first writer after a wake therefore runs it, whichever writer that is: a thread that was already waiting for its FIFO ticket when the process stopped, a request the kernel buffered during the stop, or the clock. In v3 only the clock ran recovery, after it noticed the gap, so another writer's `settle` could run first. Measured with `verify_v3/v3_model.py` and `drive.py pause` (heartbeat scaled to 3 s, `PAUSE_GAP` to 1 s, SIGSTOP for 5 s): a writer queued at the stop wiped 3 of 3 queues (the waiters DROPPED, the offer SKIPPED, then recovery on an empty queue), a claim buffered in the kernel wiped 1 of 3, and an ACCEPT tapped with about 1 s left and buffered during the stop was lost 3 of 3 times. With recovery in the write path and `grace_until`, 9 of 9 runs were correct.

Recovery, in that one write transaction, for every pool with an open attempt:
1. Every WAITING attempt gets `seen_at = now`. The waiting phones could not reach the server, and nobody leaves the queue because the server was stopped or unreachable.
2. Every OFFERED attempt gets `offer_deadline = max(offer_deadline, now + 10)`, and `offered_at = now` when the deadline moved. No phone could acknowledge an offer the server never delivered.
3. HOLDING attempts keep their `expires_at`; the phone has shown that countdown, and extending it would make the countdown false. Their `grace_until` becomes `max(grace_until, now + GRACE)` and `due_at` follows it. A tap that the operating system had already received is stamped at the wake, before the new `grace_until`, and counts. In v3 recovery kept only `expires_at`, and T8 and T10a read only `expires_at + 2`, so the promised extra grace could not be built (conc V2, fidelity V3). Nothing on the phone changes: its countdown already ended.
4. The in-memory heartbeats are cleared, `settle` runs for each pool in the same transaction, and every such pool's snapshot is published after the commit. Seat types are not touched: a recovered offer or hold keeps its type.

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
6. A text states only what someone knows. The server knows arrivals, its own states and reasons, which session decided, whether an ACCEPT arrived late, and the seat type of every reservation. The app knows what it tried to send, whether the phone has a validated network (`NET_CAPABILITY_VALIDATED`), and whether an answer came. No text guesses a cause ("olabilir" guesses are gone, C6, F6).
7. Every sentence names its actor: "Sunucu", "Uygulama", "Kaptan", "Maçı yöneten kişi", "Telefonun", or "sen" through the verb. The predicate comes last. No dash joins clauses. No passive voice. A text in the app that names a button takes the label through a format argument.
8. Every new string goes through the `turkce-insansi-metin` review before it ships. The texts below already follow its rules.
9. **Two forms (v4).** Every join text exists in a team form and an open-match form. The app picks the form by `target.kind` of the answer, by `details.kind` of a refusal, or by the kind stored in the resume record (10.2). The tables give the team form in the third column and the open-match form in the fourth; "same" means one text serves both.
10. **Seat words (v4).** The seat type in a text comes only from the server's `seat` field, never from counts. `%s` arguments named "seat word" take `seat_word_starter` = "as" or `seat_word_substitute` = "yedek"; both read correctly before "olarak" without a suffix.

**A. Link landing, registration with a link, code entry**

| Exact state in which it shows | Key | Team form | Open-match form |
|---|---|---|---|
| a link was opened; resolve or claim has not answered; or the session exists and the join screen has no window focus yet | `link_loading` | Uygulama bağlantıyı açıyor. | same |
| a code was typed or pasted in Kod Gir; resolve has not answered | `code_checking` | Uygulama kodu kontrol ediyor. | same |
| resolve, claim or register answered `404 LINK_NOT_FOUND` (the kind is unknown) | `link_unknown_title` / `link_unknown_body` | Bağlantı Geçersiz / Sunucu bu bağlantıyı tanımıyor. Bağlantıyı gönderen kişiden yeni bir bağlantı isteyebilirsin. | same |
| `410 LINK_CLOSED`, reason EXPIRED | `link_expired` / `link_expired_open` | Bu davetin süresi doldu. Kaptandan yeni bir bağlantı isteyebilirsin. | Bu maç bağlantısının süresi doldu. Maçı yöneten kişiden yeni bir bağlantı isteyebilirsin. |
| `410 LINK_CLOSED`, open match, `details.matchDayPassed: true`, any reason except CANCELLED, PLAYED and ORGANISER_GONE (F4: after the match day the server creates no new link, so no text sends the person to ask for one) | `link_expired_played` | | Bu maçın günü geçti. Sunucu bu maça yeni oyuncu almıyor. |
| `410 LINK_CLOSED`, reason PLAYED, open match (the first upload closed the link, V4-S2) | `link_played_open` | | Sunucu bu maçın videosunu aldı ve bağlantıyı kapattı. Sunucu bu maça yeni oyuncu almıyor. |
| `410 LINK_CLOSED`, reason ORGANISER_GONE, open match (3.7, V4-C1) | `link_organiser_gone_open` | | Maçı yöneten kişi hesabını sildi ve sunucu bu bağlantıyı kapattı. Sunucu bu maça yeni oyuncu almıyor. |
| `410 LINK_CLOSED`, reason RENEWED | `link_renewed` / `link_renewed_open` | Kaptan yeni bir davet bağlantısı oluşturdu. Senin açtığın bağlantı artık geçersiz. Kaptandan yeni bağlantıyı isteyebilirsin. | Maçı yöneten kişi yeni bir bağlantı oluşturdu. Senin açtığın bağlantı artık geçersiz. Maçı yöneten kişiden yeni bağlantıyı isteyebilirsin. |
| `410 LINK_CLOSED`, reason CLOSED | `link_closed` / `link_closed_open` | Kaptan bu daveti kapattı. Kaptandan yeni bir bağlantı isteyebilirsin. | Maçı yöneten kişi bu bağlantıyı kapattı. Maçı yöneten kişiden yeni bir bağlantı isteyebilirsin. |
| `410 LINK_CLOSED`, reason CANCELLED, open match | `link_cancelled_open` | | Maçı yöneten kişi bu maçı iptal etti. |
| `410 LINK_CLOSED`, reason FULL, or `409 SEATS_FULL` (%1$s = `details.teamName`) | `link_team_full` / `link_full_open` | %1$s takımı doldu ve sunucu daveti kapattı. Kaptan yer açıp yeni bir bağlantı gönderirse takıma katılabilirsin. | Bu maç doldu ve sunucu bağlantıyı kapattı. Maçı yöneten kişi yeni bir bağlantı gönderirse ve maçta boş yer varsa maça katılabilirsin. |
| a request failed and the phone has no validated network | `net_offline` | Telefonun internete bağlı değil. İnternete bağlanınca yeniden deneyebilirsin. | same |
| the network is validated but no JSON answer came: a timeout, a refused connection, or a non-JSON answer from a proxy or tunnel | `net_no_answer` | Uygulama sunucudan yanıt alamadı. Biraz sonra yeniden deneyebilirsin. | same |
| `503 SERVER_BUSY`; the app waits `Retry-After` and repeats after each `Retry-After` while the screen that sent the request is open (R10) | `server_busy` | Sunucu şu an çok sayıda istek işliyor. Uygulama birkaç saniye sonra yeniden dener. | same |
| `GET /v1/health` lacks `accounts` (v4: "bağlantıyı" instead of "daveti", because the check covers every link kind) | `server_older` | Sunucunun sürümü bu uygulamadan eski. Uygulama bu sunucuda bağlantıyı açamaz. | same |
| WELCOME passed; no account has ever logged in on this phone (`accountSeen` false); no link is pending, for example after the installer's Aç button (R6). This is the signed-out state of the entry choice (10.10); %1$s = the `Kod Gir` label. v5's checkers changed the first sentence, because a newcomer who got the APK without any link reaches this screen too (C11), and added `Kayıt Kodu İste` (C3) | `first_start_body` + primary button `Kod Gir` (with `Panodaki Kodu Kullan` when the clipboard yields one code) + secondary buttons `Giriş Yap`, `Hesap Oluştur` and `Kayıt Kodu İste` (v5), with `signup_code_request_body` and `feedback_address` below the last one | Sana WhatsApp'ta bir takım ya da maç bağlantısı geldiyse bağlantıya dokunabilirsin. Kodu kopyaladıysan %1$s düğmesine dokunabilirsin. Kendi takımını kurmak ya da maç açmak için önce bir hesabın olmalı. Bağlantın yoksa Voleybol Ve Veleybol'dan bir kayıt kodu alıp hesap oluşturabilirsin. | (no kind yet) |
| register screen opened without a pending link or code (R6); health `accounts.openMatchSignup` is OFF or missing (missing before B6c, C5 of the v5 checkers). The field also takes an operator code (10.1, C3) | `register_code_first` | Hesap oluşturmak için önce bir kod yaz. Kaptanın gönderdiği kodu ya da Voleybol Ve Veleybol'un verdiği kayıt kodunu yazabilirsin. WhatsApp mesajını da buraya yapıştırabilirsin. | (no kind yet) |
| same, health `accounts.openMatchSignup` is LINK (the v5 default from B6c on, R6) | `register_code_first_any` | Hesap oluşturmak için önce bir kod yaz. Kaptanın ya da maçı yöneten kişinin gönderdiği kodu ya da Voleybol Ve Veleybol'un verdiği kayıt kodunu yazabilirsin. WhatsApp mesajını da buraya yapıştırabilirsin. | (no kind yet) |
| first-start screen, below `Kayıt Kodu İste`, which opens the mail app with the feedback address of 0.1 and the subject `signup_code_mail_subject` "VolleyStat kayıt kodu", like Profil's Geri Bildirim Gönder; `feedback_no_mail_app` when the phone has no mail app (v5, C3) | `signup_code_request_body` | Kayıt kodu istemek için Voleybol Ve Veleybol'a yazabilirsin. Uygulama e-posta uygulamanı aşağıdaki adresle açar. | (no kind yet) |
| Kod Gir with a session, the text yields an operator code by the rule of 10.1 (v5, C3) | `code_is_signup_code` | Uygulama 16 karakterlik bir kodu kayıt kodu olarak okur ve kayıt kodunu yalnız yeni hesap açarken kullanır. | same |
| no session; resolve answered; no account has ever logged in on this phone (register first, F1); for an open match only while the LinkView says `signup: true` | `link_register_intro` / `link_register_intro_open` + secondary button `Hesabım Var` | %1$s takımına katılmak için hesap oluştur. | Bu açık maça katılmak için hesap oluştur. |
| no session; resolve answered an OPEN_MATCH link with `signup: false`, which happens only while the operator set `VOLLEYSTAT_OPEN_MATCH_SIGNUP=off` (v5's default is `link`, R6); or register answered `403 SIGNUP_CLOSED` with `details.reason: OPEN_MATCH_LINK`; button `Giriş Yap` | `open_need_account` | | Bu maça katılmak için bir hesabın olmalı. Sunucu şimdilik açık maç bağlantısıyla hesap açmıyor. Hesabın varsa giriş yapabilirsin. Hesabın yoksa maçı yöneten kişiye yazabilirsin. Maçı yöneten kişi Voleybol Ve Veleybol'dan senin için bir kayıt kodu isteyebilir. |
| no session; resolve answered; an account has logged in on this phone before | `link_login_intro` / `link_login_intro_open` | %1$s takımına katılmak için giriş yap ya da hesap oluştur. | Bu açık maça katılmak için giriş yap. (With `signup: true`: `link_login_intro_open_signup`, "Bu açık maça katılmak için giriş yap ya da hesap oluştur.") |
| register form; the username field is pre-filled from the display name | `register_username_suggested` | Uygulama kullanıcı adını görünen adından önerdi. İstersen kullanıcı adını değiştirebilirsin. | same |
| register answered `409 USERNAME_TAKEN`; the app appended digits to its suggestion | `username_taken` | Bu kullanıcı adını başka biri kullanıyor. Uygulama yeni bir ad önerdi. İstersen başka bir ad seçebilirsin. | same |
| register answered `403 SIGNUP_LINK_LIMIT` with `details.scope: LINK` (this link opened 200 accounts) | `register_link_limit` / `register_link_limit_open` | Sunucu bu bağlantıyla yeni hesap açmayı durdurdu. Hesabın varsa giriş yapabilirsin. Hesabın yoksa kaptandan yeni bir bağlantı isteyebilirsin. | Sunucu bu bağlantıyla yeni hesap açmayı durdurdu. Hesabın varsa giriş yapabilirsin. Hesabın yoksa maçı yöneten kişiden yeni bir bağlantı isteyebilirsin. |
| register answered `403 SIGNUP_LINK_LIMIT` with `details.scope: CREATOR` (the join links of this link's creator opened 400 accounts in the last 7 days; v5, 3.3) | `register_creator_limit` | Sunucu bu bağlantıyı oluşturan kişinin bağlantılarıyla son yedi günde izin verdiği kadar hesap açtı ve bu bağlantıyla yeni hesap açmıyor. Hesabın varsa giriş yapabilirsin. Hesabın yoksa Voleybol Ve Veleybol'dan bir kayıt kodu isteyebilirsin. | same |
| register answered `429 TOO_MANY_ATTEMPTS` with `details.scope: REGISTER_SOURCE` (%1$s = the wait) (S2) | `register_wait_source` | Sunucu bu internet adresinden kısa sürede kabul etmediği çok sayıda kayıt isteği aldı ve bu adresten kaydı %1$s süreyle durdurdu. | same |
| claim answered `409 ALREADY_MEMBER` | `join_already_member` + button `Takıma Git` / `join_already_open` + button `Maça Git` | Zaten bu takımdasın. | Zaten bu maçtasın. |
| claim answered `403 JOIN_BLOCKED` | `join_blocked` / `join_blocked_open` | Kaptan seni bu takımdan çıkardı. Kaptan engeli kaldırırsa takıma yeniden katılabilirsin. | Maçı yöneten kişi seni bu maçtan çıkardı. Maçı yöneten kişi engeli kaldırırsa maça yeniden katılabilirsin. |
| claim or ACCEPT answered `409 TEAM_LIMIT_REACHED` (%1$d = `details.limit`) | `join_team_limit` | En çok %1$d takımda olabilirsin. Yeni bir takıma katılmak için önce bir takımdan ayrılmalısın. | |
| claim or ACCEPT answered `409 OPEN_MATCH_LIMIT` with `details.scope: JOINED` (%1$d = `details.limit`) | `join_open_limit` | | İptal olmamış ve günü geçmemiş en çok %1$d açık maçta yerin olabilir. Yeni bir maça katılmak için önce bir maçtan ayrılmalısın. |
| claim answered `409 QUEUE_FULL` (%1$d = `details.waiting`) | `join_queue_full` / `join_queue_full_open` | Bu takımın sırasında %1$d kişi var. Sunucu bu sıraya şu an kimseyi almıyor. Biraz sonra bağlantıyı yeniden açabilirsin. | Bu maçın sırasında %1$d kişi var. Sunucu bu sıraya şu an kimseyi almıyor. Biraz sonra bağlantıyı yeniden açabilirsin. |
| claim answered `409 ATTEMPT_LIMIT` (%1$d = `details.limit`); v4 counts queues of both kinds | `join_attempt_limit` | Şu an %1$d ayrı sıradasın. Yeni bir sıraya girmek için önce bir sıradan çıkmalısın. | same |
| a match request link opened in Kod Gir or through a join route | `link_is_request` | Bu bağlantı bir maç isteği. Uygulama isteği açıyor. | same |
| Kod Gir opened; %1$d = `links.codeLength` from health (fidelity V8) | `code_title` / `code_body` | Kod Gir / Kaptanın ya da maçı yöneten kişinin gönderdiği %1$d karakterlik kodu yaz ya da WhatsApp mesajını buraya yapıştır. | same |
| Kod Gir has focus and the clipboard text yields exactly one code by the extraction rule of 10.1 | `code_paste_offer` + button `Panodaki Kodu Kullan` | Panoda %1$s kodu var. | same |
| pasted text yields no code by the rule of 10.1 | `code_not_found` | Uygulama yapıştırdığın metinde bir kod bulamadı. | same |
| pasted text yields more than one distinct code by the rule of 10.1 | `code_many` | Uygulama yapıştırdığın metinde birden çok kod buldu. Sana gelen kodu yazabilirsin. | same |
| resolve by code answered `404 LINK_NOT_FOUND`: no live link and no tombstone of the last 30 days holds this code. A tombstone answers `410 LINK_CLOSED` and shows the link texts above (R3). | `code_wrong` | Sunucu bu kodu tanımıyor. Kodu kontrol edip yeniden deneyebilirsin. | same |
| `429 TOO_MANY_ATTEMPTS` with `details.scope: ACCOUNT_CODE` (%1$s = the wait, for example "12 dakika") | `code_wait_account` | Sunucu bu hesaptan tanımadığı çok sayıda kod aldı ve bu hesabın kod girişini %1$s süreyle durdurdu. | same |
| `429 TOO_MANY_ATTEMPTS` with `details.scope: CODE_GLOBAL` or `CODE_SOURCE` | `code_wait` | Sunucu kısa sürede tanımadığı çok sayıda kod aldı ve kod girişini %1$s süreyle durdurdu. | same |
| added after `code_wait_account` or `code_wait` only while health says `links.publicUrl: true`; without a link host no link exists to fall back on (R3) | `code_wait_link` | Bağlantı bu beklemeden etkilenmez. | same |

(v4 changed `code_many`'s second sentence from "Kaptanın gönderdiği kodu" to "Sana gelen kodu", because the code may come from an organiser, and `code_wait_link` from "Davet bağlantısı" to "Bağlantı".)

**B. Decision pop-up (HOLDING).** A full-screen dialog over the waiting screen or the landing screen. Back and an outside tap do nothing while it counts. The screen stays on.

| Exact state | Element and key | Team form | Open-match form |
|---|---|---|---|
| HOLDING | logo | the 128 px logo once resolve has brought it; until then, or when decoding fails, a circle with the initials of the team name | a volleyball icon from the app's own resources; never a picture of a person |
| HOLDING | title | the team name | the open match's name, or `open_title_default` "Açık Maç" when it has none |
| HOLDING, open match | `open_join_organiser` | | Maçı yöneten · %1$s (the organiser's display name) |
| HOLDING | `join_day` / `join_time` | Maç günü / Salı (the weekday name) · Saat / 20.00, when `team.schedule` is present; both rows are left out when it is null | Maç günü / 22 Kasım Pazar (date and weekday) · Saat / 14.00; always present |
| HOLDING, open match | `open_join_venue` | | Saha / Moda Spor Salonu (the venue text) |
| HOLDING, `seat` STARTER | `join_seat_starter` | Sunucu sana bir as yeri ayırdı. | same |
| HOLDING, `seat` SUBSTITUTE | `join_seat_substitute` | Sunucu sana bir yedek yeri ayırdı. | same |
| HOLDING | `join_question` | Katılmak istiyor musun? | same |
| HOLDING | countdown | the number `ceil((D − now) / 1000)`; content description `join_countdown_a11y`: %1$d saniye kaldı | same |
| HOLDING | left button `join_reject` | Reddet | same |
| HOLDING | right button `join_accept` | Katıl | same |
| a button was tapped; the decision has not been answered | `join_sending` | Uygulama kararını sunucuya iletiyor. | same |
| the phone's own zero passed without a tap; the countdown and both buttons are gone; TIMEOUT was sent; no outcome yet | `join_time_up` | Karar süren doldu. Uygulama sunucunun yanıtını bekliyor. | same |

The seat line sits between the details and the owner's question, so the question stays the owner's text and the person knows which seat the question is about. The seat line is true for the whole countdown, because a reservation never changes type (2.3).

**C. Outcomes.** "This phone sent ACCEPT" comes from the resume record that the app writes before it sends a decision (10.2). `decidedHere`, `lateAccept` and `seat` come from the server. %2$s (team) and %1$s (open match) are the seat word of rule 10.

| Exact state | Key | Team form | Open-match form |
|---|---|---|---|
| JOINED, `decidedHere: true` | `join_joined_title` / `join_joined_body`; `join_joined_title_open` / `join_joined_body_open` | Takıma Katıldın / Sunucu seni %1$s takımına %2$s olarak kaydetti. | Maça Katıldın / Sunucu seni bu maça %1$s olarak kaydetti. |
| JOINED, `decidedHere: false` | `join_joined_elsewhere` / `join_joined_elsewhere_open` | Sen bu daveti hesabının başka bir oturumunda kabul ettin. Sunucu seni %1$s takımına %2$s olarak kaydetti. | Sen bu maça katılmayı hesabının başka bir oturumunda kabul ettin. Sunucu seni bu maça %1$s olarak kaydetti. |
| REJECTED, `decidedHere: true` | `join_rejected` / `join_rejected_open` | Daveti reddettin. Takıma katılmadın. | Daveti reddettin. Maça katılmadın. |
| REJECTED, `decidedHere: false` | `join_rejected_elsewhere` / `…_open` | Sen bu daveti hesabının başka bir oturumunda reddettin. Takıma katılmadın. | Sen bu daveti hesabının başka bir oturumunda reddettin. Maça katılmadın. |
| EXPIRED (NO_DECISION or TIME_UP); this phone sent no ACCEPT | `join_expired` / `…_open` | Karar süren doldu. Takıma katılmadın. | Karar süren doldu. Maça katılmadın. |
| EXPIRED; this phone sent ACCEPT; `lateAccept: true` (the ACCEPT arrived at or after the deadline that closed the hold, T8c or T17) | `join_late` / `…_open` | Sunucu kararını süre bittikten sonra aldı. Takıma katılmadın. | Sunucu kararını süre bittikten sonra aldı. Maça katılmadın. |
| EXPIRED; this phone sent ACCEPT; `lateAccept: false` and `acceptArrivedInTime: false` (no ACCEPT reached the server) | `join_not_received` / `…_open` | Uygulama kararını göndermeyi denedi ama sunucu karar süresi içinde kararını almadı. Takıma katılmadın. | Uygulama kararını göndermeyi denedi ama sunucu karar süresi içinde kararını almadı. Maça katılmadın. |
| EXPIRED; this phone sent ACCEPT; `acceptArrivedInTime: true` (the ACCEPT arrived in time but waited longer than its 5 s protection, 2.9) | `join_closed_in_time` / `…_open` | Sunucu kararını süre içinde aldı ama katılma isteğini kararını işlemeden önce kapattı. Takıma katılmadın. | Sunucu kararını süre içinde aldı ama katılma isteğini kararını işlemeden önce kapattı. Maça katılmadın. |
| CLOSED, LINK_RENEWED | `join_link_renewed` / `…_open` | Kaptan yeni bir davet bağlantısı oluşturdu ve sunucu bu daveti kapattı. Takıma katılmadın. Kaptandan yeni bağlantıyı isteyebilirsin. | Maçı yöneten kişi yeni bir bağlantı oluşturdu ve sunucu bu bağlantıyı kapattı. Maça katılmadın. Maçı yöneten kişiden yeni bağlantıyı isteyebilirsin. |
| CLOSED, LINK_CLOSED | `join_link_closed` / `…_open` | Kaptan bu daveti kapattı. Takıma katılmadın. | Maçı yöneten kişi bu bağlantıyı kapattı. Maça katılmadın. |
| CLOSED, LINK_EXPIRED | `join_link_expired` / `…_open` | Davetin süresi doldu. Takıma katılmadın. | Bağlantının süresi doldu. Maça katılmadın. |
| CLOSED, MATCH_CANCELLED | `join_cancelled_open` | | Maçı yöneten kişi maçı iptal etti. Maça katılmadın. |
| CLOSED, MATCH_PLAYED | `join_played_open` | | Sunucu bu maçın videosunu aldı ve bağlantıyı kapattı. Maça katılmadın. |
| CLOSED, ORGANISER_GONE | `join_organiser_gone_open` | | Maçı yöneten kişi hesabını sildi ve sunucu bağlantıyı kapattı. Maça katılmadın. |
| CLOSED, LIMIT | `join_team_limit` / `join_open_limit` | (table A) | (table A) |
| `404 ATTEMPT_NOT_FOUND` (the attempt is older than 24 h, or the pool or the account was deleted); the form comes from the resume record's kind; for an open match whose stored match day has passed by `serverTime` in UTC+3 the app shows `join_gone_played_open` instead: "Sunucu bu katılma isteğini bulamadı. Bu maçın günü geçti ve sunucu bu maça yeni oyuncu almıyor." (F4) | `join_gone` / `join_gone_open` | Sunucu bu katılma isteğini bulamadı. Kaptandan yeni bir bağlantı isteyebilirsin. | Sunucu bu katılma isteğini bulamadı. Maçı yöneten kişiden yeni bir bağlantı isteyebilirsin. |
| an ACCEPT or REJECT the person tapped got no answer after its repeats until the phone's zero plus 3 s, and the following `GET` got none either | `join_unknown` + button `Sonucu Kontrol Et` | Uygulama sunucudan yanıt alamadı. Uygulama kararının sonucunu henüz bilmiyor. | same |
| the TIMEOUT the app sent at its own zero, without a tap, got no answer after its repeats, and the following `GET` got none either (fidelity V8) | `join_unknown_timeout` + button `Sonucu Kontrol Et` | Uygulama sunucudan yanıt alamadı ve katılma isteğinin sonucunu henüz bilmiyor. | same |

Buttons: `Yeniden Sıraya Gir` appears on EXPIRED, REJECTED, `join_late`, `join_not_received`, `join_closed_in_time`, SKIPPED and DROPPED when the answer says `link.live: true` and the resume record holds the link (10.2). `link.live` is true while the pool's live join link is the one this attempt came through, that is, a link created before the attempt's `arrived_at` that is still open. The button sends a fresh claim with the stored link, which joins the end of the queue or gets a free seat. When the resume record holds no link, the button is hidden, and `join_reopen_link` shows (fidelity V6; %1$s = the `Kod Gir` label): team form "Takıma yeniden katılmak için kaptanın gönderdiği bağlantıyı yeniden açabilir ya da kodu %1$s ekranına yazabilirsin."; open-match form `join_reopen_link_open` "Maça yeniden katılmak için maçı yöneten kişinin gönderdiği bağlantıyı yeniden açabilir ya da kodu %1$s ekranına yazabilirsin." For an open match whose match day has passed (the resume record keeps `playedOn`; the app compares it with the answer's `serverTime` in UTC+3), the button is hidden and `link_expired_played` shows instead of `join_reopen_link_open` (F4). `Kapat` appears everywhere. JOINED shows `Takıma Git` or `Maça Git` after the jersey prompt.

**D. Waiting screen (WAITING and OFFERED).** The screen stays on. With a queue the seats left of each type equal the people deciding on that type (2.3), so the first line names the seats by type and the second line counts the people deciding.

| Exact state | Key | Team form | Open-match form |
|---|---|---|---|
| WAITING | `queue_title` | Sıradasın | same |
| WAITING, numbers from the latest answer; starter seats left > 0, substitute seats left = 0 | `queue_left_starter` / `…_open` | Takımda %1$d as yeri kaldı. | Maçta %1$d as yeri kaldı. |
| WAITING; starter seats left = 0, substitute seats left > 0 | `queue_left_substitute` / `…_open` | Takımda %1$d yedek yeri kaldı. | Maçta %1$d yedek yeri kaldı. |
| WAITING; both > 0 | `queue_left_both` / `…_open` | Takımda %1$d as yeri ve %2$d yedek yeri kaldı. | Maçta %1$d as yeri ve %2$d yedek yeri kaldı. |
| WAITING; second line; plural on the people deciding, which equals the seats left | `queue_deciding` | one: Bu yer şu an bir kişinin kararını bekliyor. · other: Bu yerler şu an %1$d kişinin kararını bekliyor. | same |
| WAITING, `ahead` = 0 | `queue_next` | Sıradaki sensin. | same |
| WAITING, `ahead` > 0 | `queue_ahead` | Önünde %1$d kişi var. | same |
| WAITING | `queue_rule` | Karar veren kişi reddederse ya da karar süresi biterse sunucu o kişinin yerini sıradaki kişiye ayırır. Sunucu as yerini sıradaki kişiye as yeri olarak, yedek yerini yedek yeri olarak ayırır. | same |
| WAITING | `queue_keep_open` | Sıranı korumak için bu ekranı açık tut. Uygulama bu ekranda telefonunun ekranını açık tutar. Ekrandan çıkarsan sıranı kaybedebilirsin. | same |
| WAITING | button `queue_leave` | Sıradan Çık | same |
| WAITING, back pressed | `queue_leave_title` + buttons `Sıradan Çık` / `Sırada Kal` | Sıradan çıkmak istiyor musun? | same |
| a poll failed and the phone has no validated network; numbers hidden | `queue_no_network` | Telefonun internete bağlı değil. Uygulama bağlantı gelince yeniden dener. | same |
| a poll failed and the network is validated; numbers hidden | `queue_reconnecting` | Uygulama sunucudan yanıt alamadı. Uygulama her saniye yeniden deniyor. | same |
| OFFERED from a poll; the ack has not been answered | `queue_your_turn` | Sıra sende. Uygulama karar ekranını açıyor. | same |
| DROPPED | `queue_dropped` | Sunucu 30 saniye boyunca uygulamandan haber almadı ve seni sıradan çıkardı. | same |
| SKIPPED (from a poll, the ack's `409`, or `GET` on resume); %1$d = `details.offerWindowS`, 8 normally and at least 10 after a pause (fidelity V8, R10) | `queue_skipped` | Sıra sana geldi. Sunucu %1$d saniye içinde uygulamandan yanıt almadı ve seni sıradan çıkardı. | same |
| CLOSED, FULL | `queue_team_filled` / `queue_full_open` | Takım doldu ve sunucu seni sıradan çıkardı. Kaptan yer açıp yeni bir bağlantı gönderirse takıma katılabilirsin. | Bu maç doldu ve sunucu seni sıradan çıkardı. Maçı yöneten kişi yeni bir bağlantı gönderirse ve maçta boş yer varsa maça katılabilirsin. |
| CLOSED, LINK_RENEWED | `queue_link_renewed` / `…_open` | Kaptan yeni bir davet bağlantısı oluşturdu ve sunucu eski bağlantının sırasını kapattı. Kaptandan yeni bağlantıyı isteyebilirsin. | Maçı yöneten kişi yeni bir bağlantı oluşturdu ve sunucu eski bağlantının sırasını kapattı. Maçı yöneten kişiden yeni bağlantıyı isteyebilirsin. |
| CLOSED, LINK_CLOSED | `queue_link_closed` / `…_open` | Kaptan bu daveti kapattı ve sunucu seni sıradan çıkardı. | Maçı yöneten kişi bu bağlantıyı kapattı ve sunucu seni sıradan çıkardı. |
| CLOSED, LINK_EXPIRED | `queue_link_expired` / `…_open` | Davetin süresi doldu ve sunucu seni sıradan çıkardı. | Bağlantının süresi doldu ve sunucu seni sıradan çıkardı. |
| CLOSED, MATCH_CANCELLED | `queue_cancelled_open` | | Maçı yöneten kişi maçı iptal etti ve sunucu seni sıradan çıkardı. |
| CLOSED, MATCH_PLAYED | `queue_played_open` | | Sunucu bu maçın videosunu aldı ve seni sıradan çıkardı. |
| CLOSED, ORGANISER_GONE | `queue_organiser_gone_open` | | Maçı yöneten kişi hesabını sildi ve sunucu seni sıradan çıkardı. |
| LEFT, `decidedHere: true` | | the screen closes without a text | same |
| LEFT, `decidedHere: false` | `queue_left_elsewhere` | Sen hesabının başka bir oturumunda sıradan çıktın. | same |

Why `queue_rule` holds in every state: with a queue both free counts are 0, so the only seat a rejection or a timeout frees is the decider's own, and `settle` offers it with its type to the head. A seat that a count change or a leave frees also goes to the head, but `queue_rule` speaks only of the decider's seat and stays true.

**E. After joining: jersey**

| Exact state | Key | Team form | Open-match form |
|---|---|---|---|
| right after JOINED | `jersey_title` | Forma Numaran | same |
| same | `jersey_body` / `jersey_body_open` | Takım arkadaşların forma numaranı görür. Kaptan maç kadrosunu bu numarayla hazırlar. | Maçı yöneten kişi kadroyu yazarken bu numarayı görür. Sunucu bir sayıyı sana ancak maçı yöneten kişi kadroyu numaranla onaylayınca yazar. |
| `409 JERSEY_TAKEN` (team only; an open match checks jerseys per side in the roster) | `jersey_taken` | Takımda %1$d numaralı formayı başka bir üye taşıyor. Başka bir numara seçebilirsin. | |
| buttons | | Kaydet / Şimdi Değil | same |

**F. Captain and organiser: link card, seats, team screen.** Rows without an open-match form belong to teams only; 10.8 holds the open match screen's own texts.

| Exact state | Key | Team form | Open-match form |
|---|---|---|---|
| join link live | `invite_title` / `open_link_title` | Davet Bağlantısı | Maç Bağlantısı |
| join link live; %1$s = `join_accept` | `invite_body` / `open_link_body` | Bağlantıyı ya da kodu takıma katılacak kişilere gönder. Bağlantıyı açıp %1$s düğmesine dokunan kişi senin onayın olmadan takıma girer. | Bağlantıyı ya da kodu maça katılacak kişilere gönder. Bağlantıyı açıp %1$s düğmesine dokunan kişi senin onayın olmadan maça girer. |
| join link live, below the body | `invite_seat_order` | Sunucu önce as yerlerini, sonra yedek yerlerini doldurur. Bütün yerler dolunca sunucu bağlantıyı kapatır. | same |
| join link live, this phone holds its URL and code | `invite_code_label`, `invite_expires`, buttons | Kod · Bağlantı %1$s tarihine kadar geçerli. · Paylaş / Kodu Kopyala / Bağlantıyı Yenile / Daveti Kapat | same, with `Bağlantıyı Kapat` instead of `Daveti Kapat` |
| join link live, this phone holds no copy (P8) | `invite_not_on_this_phone` | Bu telefonda davet bağlantısının bir kopyası yok. Bu telefondan paylaşmak için bağlantıyı yenileyebilirsin. Yeni bağlantı eski bağlantıyı geçersiz kılar. | Bu telefonda maç bağlantısının bir kopyası yok. Bu telefondan paylaşmak için bağlantıyı yenileyebilirsin. Yeni bağlantı eski bağlantıyı geçersiz kılar. |
| Bağlantıyı Yenile tapped | `invite_renew_body` | Yeni bağlantı eski bağlantıyı geçersiz kılar. Sunucu sırada bekleyenlerin ve karar verenlerin katılma isteğini kapatır. | same |
| Daveti Kapat or Bağlantıyı Kapat tapped | `invite_close_title` / `open_link_close_title` | Daveti kapatmak istiyor musun? | Bağlantıyı kapatmak istiyor musun? |
| join link live; deciding or waiting above 0 (from the live view) | `invite_live` | Şu an %1$d yer karar bekliyor ve %2$d kişi sırada bekliyor. | same |
| join link live; the invite or link answer says `signupLimitReached: LINK` (this link opened 200 accounts, 3.3; v5) | `invite_signup_limit` | Sunucu bu bağlantıyla artık yeni hesap açmıyor. Hesabı olan kişiler bu bağlantıyla katılabilir. | same |
| join link live; the answer says `signupLimitReached: CREATOR` (the links of this link's creator opened 400 accounts in the last 7 days; %1$s = `signupLimitUntil` as date and time; v5, C17 of the v5 checkers). Bağlantıyı Yenile does not change it while the same account renews, and the text says no more than that the creator's links open no account until that time | `invite_signup_limit_creator` | Sunucu bu bağlantıyı oluşturan hesabın bağlantılarıyla son yedi günde izin verdiği kadar hesap açtı. %1$s tarihine kadar sunucu bu hesabın bağlantılarıyla yeni hesap açmaz. Hesabı olan kişiler bu bağlantıyla katılabilir. | same |
| The captain's or the organiser's card picks the first row that holds, in this order: cancelled (`open_cancelled`, 10.8); no organiser (`open_no_organiser`); the match day passed (`open_closed_played`); an upload of the record exists (`open_closed_video`); then the link's reason (F4) | | | |
| join link closed, reason FULL, no seat left now; button `Yeni Bağlantı Oluştur` | `invite_closed_full` / `open_closed_full` | Takım doldu ve sunucu daveti kapattı. Yer açarsan yeni bir bağlantı oluşturabilirsin. | Maç doldu ve sunucu bağlantıyı kapattı. Yer açarsan yeni bir bağlantı oluşturabilirsin. |
| join link closed, reason FULL, seats left now (a holder left or a count rose after the link closed; %1$d = seats left); button `Yeni Bağlantı Oluştur` (F4) | `invite_closed_full_free` / `open_closed_full_free` | Takımda %1$d boş yer var. Yeni bir bağlantı oluşturabilirsin. | Maçta %1$d boş yer var. Yeni bir bağlantı oluşturabilirsin. |
| join link closed, reason EXPIRED (an open match before the end of its day) | `invite_closed_expired` / `open_closed_expired` | Davetin süresi doldu. Yeni bir bağlantı oluşturabilirsin. | Bağlantının süresi doldu. Yeni bir bağlantı oluşturabilirsin. |
| open match link closed after the end of the match day, whatever its reason | `open_closed_played` | | Maçın günü geçti. Sunucu bu maça yeni oyuncu almıyor. |
| open match, an upload of the record exists (reason PLAYED, or a later reason), the day not passed; no button | `open_closed_video` | | Sunucu bu maçın videosunu aldı ve bağlantıyı kapattı. Sunucu bu maça yeni oyuncu almıyor. |
| join link closed, reason CLOSED | `invite_closed` / `open_closed` | Davet kapalı. Yeni bir bağlantı oluşturabilirsin. | Bağlantı kapalı. Yeni bir bağlantı oluşturabilirsin. |
| health `links.publicUrl: false` | `invite_no_public_address` | Sunucunun internet adresi yok. Uygulama bu yüzden yalnızca kodu paylaşır. | same |
| Paylaş with a link host; %3$s = `code_title` | `invite_share_text` | %1$s takımına katılmak için bu bağlantıyı aç: %2$s ⏎ Bağlantı uygulamayı açmazsa VolleyStat uygulamasında %3$s ekranına bu kodu yaz: %4$s ⏎ Uygulama telefonunda yoksa bağlantının açtığı sayfa kurulum adımlarını gösterir. | `open_share_text` (%1$s = date and weekday, %2$s = time, %3$s = URL, %4$s = `code_title`, %5$s = code): %1$s günü saat %2$s için açık bir voleybol maçı var. Maça katılan herkes diğer oyuncuların görünen adını ve maçın videosunu görür. Katılmak için bu bağlantıyı aç: %3$s ⏎ Bağlantı uygulamayı açmazsa VolleyStat uygulamasında %4$s ekranına bu kodu yaz: %5$s ⏎ Uygulama telefonunda yoksa bağlantının açtığı sayfa kurulum adımlarını gösterir. (v5 adds the last line, because the link now opens sign-up) |
| Paylaş without a link host; %2$s = `code_title` | `invite_share_text_code` | %1$s takımına katılmak için VolleyStat uygulamasında %2$s ekranına bu kodu yaz: %3$s ⏎ Uygulama telefonunda yoksa kaptandan uygulama dosyasını isteyebilirsin. | `open_share_text_code`: %1$s günü saat %2$s için açık bir voleybol maçı var. Maça katılan herkes diğer oyuncuların görünen adını ve maçın videosunu görür. Katılmak için VolleyStat uygulamasında %3$s ekranına bu kodu yaz: %4$s ⏎ Uygulama telefonunda yoksa maçı yöneten kişiden uygulama dosyasını isteyebilirsin. (v5 adds the last line) |
| `team/{id}/seats` and `open/{id}/seats`, the counts (v5: the substitute count only, R1, R2) | `seats_title`, `team_seats_body` / `open_seats_body` | As ve Yedek Yerleri · Bir takımda her zaman 6 as yeri olur. Yedek yeri sayısını 0 ile 6 arasında seçebilirsin. Bu sayılar senin yerini de içerir. Bütün yerler dolunca sunucu daveti kapatır. | As ve Yedek Yerleri · Bir açık maçta her zaman 12 as yeri olur. Sen oyuncuları taraflara ayırınca her tarafta 6 as yeri olur. Yedek yeri sayısını 0 ile 12 arasında seçebilirsin. Bir tarafta yedekler ve liberolar birlikte en çok 6 kişi olur. Bütün yerler dolunca sunucu bağlantıyı kapatır. |
| same screen, the moves; %1$s = the `Kaydet` label | `lineup_body` | Bir oyuncuyu as ya da yedek yapabilirsin. %1$s düğmesine dokununca sunucu yeni dağılımı yer sayılarıyla birlikte kontrol eder. | same |
| `422 SEATS_INVALID`, `details.reason: SUBSTITUTES_RANGE` (v5) | `seats_invalid` / `seats_invalid_open` | Bir takımda yedek yeri sayısı 0 ile 6 arasında olur. | Bir açık maçta yedek yeri sayısı 0 ile 12 arasında olur. |
| `422 SEATS_INVALID`, `details.reason: CAPTAIN_SEAT` (v5; the app disables Yedek under Senin yerin while the count is 0, so this answer is defensive) | `seats_invalid_captain` | Yedek yeri yokken sen yedek olamazsın. Yedek yeri sayısını artırabilir ya da as yerini seçebilirsin. | |
| `409 SEATS_BELOW_HOLDERS`, `details.seat` STARTER (%1$d = holders after the change, %2$d = seats); v5: only moves cause it | `seats_below_starter` / `…_open` | Bu değişiklikle takımda %1$d as oyuncu olur ama yalnız %2$d as yeri var. Bir yedeği as yapmak için bir as oyuncuyu da yedek yapıp birlikte kaydedebilirsin. | Bu değişiklikle maçta %1$d as oyuncu olur ama yalnız %2$d as yeri var. Bir yedeği as yapmak için bir as oyuncuyu da yedek yapıp birlikte kaydedebilirsin. |
| `409 SEATS_BELOW_HOLDERS`, SUBSTITUTE | `seats_below_substitute` / `…_open` | Bu değişiklikle takımda %1$d yedek oyuncu olur ama yalnız %2$d yedek yeri var. Yedek yeri sayısı yedek oyuncu sayısından az olamaz. | Bu değişiklikle maçta %1$d yedek oyuncu olur ama yalnız %2$d yedek yeri var. Yedek yeri sayısı yedek oyuncu sayısından az olamaz. |
| `409 SEATS_IN_USE`, STARTER (%1$d = `details.deciding`); v5: only moves cause it | `seats_in_use_starter` | Şu an %1$d as yeri karar bekliyor. Sunucu karar bekleyen as yerlerini de dolu sayar. Bu yüzden bu değişiklik için yeterli as yeri yok. | same |
| `409 SEATS_IN_USE`, SUBSTITUTE | `seats_in_use_substitute` | Şu an %1$d yedek yeri karar bekliyor. Yedek yeri sayısı yedek oyuncuların ve karar bekleyen yedek yerlerinin toplamından az olamaz. | same |
| added below `seats_below_substitute` and `seats_in_use_substitute`, both forms, when the refused call moved a starter to a substitute seat, which the app knows because it sent the call, and the count the change needs fits the bound: `details.holders` for SEATS_BELOW_HOLDERS, `details.holders` + `details.deciding` for SEATS_IN_USE, at most 6 for a team and 12 for an open match. Since the starter count is fixed, a starter moved into a full substitute type is a usual cause of these refusals (v5, S8 of the v5 checkers) | `seats_raise_or_swap_hint` | Bir as oyuncuyu yedek yapmak için yedek yeri sayısını artırabilir ya da bir yedeği as yapıp birlikte kaydedebilirsin. | same |
| same, the needed count above the bound | `seats_swap_hint` | Bir as oyuncuyu yedek yapmak için bir yedeği as yapıp birlikte kaydedebilirsin. | same |
| team screen, captain; a starter seat is free, the team has substitutes and nobody waits | `team_starter_free_hint` | Takımda %1$d boş as yeri ve %2$d yedek oyuncu var. İstersen bir yedek oyuncuyu as yapabilirsin. | (open match: `open_starter_free_hint`, same with "Maçta") |
| team screen, members since the captain's last look | `team_recent_joins`; badge content description `team_new_joins_a11y` | Son katılanlar · %1$d yeni üye | |
| team screen, member list headers | `team_starters`, `team_substitutes` | Aslar · Yedekler | same |
| captain taps remove on a member (S3) | `member_remove_body` / `open_remove_body` | Bir üyeyi çıkarınca sunucu bu hesabın takıma yeniden katılmasını engeller. Çıkardığın kişi davet bağlantısıyla yeni bir hesap açarsa takıma yeniden katılabilir. Bunu önlemek için bağlantıyı da yenileyebilirsin. Engeli Çıkarılanlar listesinden kaldırabilirsin. | Bir oyuncuyu çıkarınca sunucu bu hesabın maça yeniden katılmasını engeller ve oyuncuyu kadrodan da çıkarır. Çıkardığın kişi bağlantıyı başka bir hesapla açarsa maça yeniden katılabilir. Bunu önlemek için bağlantıyı da yenileyebilirsin. Engeli Çıkarılanlar listesinden kaldırabilirsin. |
| organiser removes a player who has a line in the confirmed roster (V4-S3, F1); added below `open_remove_body` | `open_remove_counted` | | Bu oyuncu onaylı kadroda. Sen kadroyu yeniden onaylayana kadar sunucu oyuncunun bu maçtaki sayılarını korur. |
| same dialog, join link live and, for an open match, the link guard of 2.2 passing; a switch, on by default (4.3) | `member_remove_renew` + `invite_renew_body` below it | Bağlantıyı da Yenile | same |
| link card after a removal that renewed the link | `invite_after_removal` | Yeni bağlantıyı çıkardığın kişinin göremeyeceği bir yerde paylaşabilirsin. | same |
| blocked list | `blocked_title`, button | Çıkarılanlar · Engeli Kaldır | same |

(⏎ is a line break in the shared message. v4 left the install line out of the open-match texts, because an open match link opened no account before the KVKK review; since v5 it opens sign-up, so both open-match texts carry it. The new lines hold no code-shaped text, so the extraction of 10.1 is unchanged.)

**G. Fallback web page on the link host** (static HTML, one external script; no team or match data). `/j/` serves both kinds of join link, because the page cannot ask the server which kind a token is without sending the token; so the link host keeps v3's four static files.

| Exact state | Element | Text |
|---|---|---|
| `/j/` loaded | title | VolleyStat bağlantısı |
| `/j/` loaded | body | Bu bağlantı VolleyStat uygulamasında bir takım davetini ya da bir açık maçı açar. Uygulama telefonunda kuruluysa Uygulamada Aç düğmesine dokun. |
| `/m/` loaded | body | Bu bağlantı VolleyStat uygulamasında bir maç isteğini açar. Uygulama telefonunda kuruluysa Uygulamada Aç düğmesine dokun. |
| always | button | Uygulamada Aç |
| the fragment holds a code that passed the check of 2.2; the code shown large with Kodu Kopyala | code text | Uygulama bağlantıyı açmazsa VolleyStat uygulamasında Kod Gir ekranına bu kodu yaz. |
| `/j/`, the fragment holds no code that passed the check | code text | Bağlantıyı gönderen kişiden kodu isteyebilirsin. |
| `/m/`, the fragment holds no code that passed the check (fidelity V8) | code text | İsteği gönderen kaptandan istek kodunu isteyebilirsin. |
| always, default distribution (APK by hand) | install title / body | Uygulama telefonunda yoksa / Bağlantıyı gönderen kişiden VolleyStat uygulama dosyasını iste. Dosyayı açınca telefon bu kaynaktan kurulum için senden izin ister. Kurulumdan sonra WhatsApp'taki bağlantıya yeniden dokun. |

### 2.13 The owner's example, step by step

The owner's example of 16 November: 9 people accepted, 1 person is deciding on the last place, and the 11th person is told that one place is free and someone is deciding on it; when the decider rejects or runs out of time, the first person waiting gets the decision screen. With the owner's sizes of 17 November the same situation is "every seat but one taken, one person deciding on the last seat, the next person waiting". v4 walks it twice through the one engine: a team of 8 (6 + 2) and an open match of 14 (12 + 2).

Notation: M = holders as starters + substitutes ("6+1"), H = holding, O = offered (each split by type the same way where it matters), W = waiting, free = free_STARTER + free_SUBSTITUTE ("0+1"). P1, P2 … are people from the WhatsApp group, numbered by when their claims reach the server. Times are seconds after the claim of the person who takes the last seat; network round trips are 0.3 s.

**Walk 1: team "Kuzey Spor", 6 starter seats and 2 substitute seats, capacity 8.** The captain holds a starter seat, so 5 starter and 2 substitute seats are open when the link goes out.

| Step | Event | Server state after the step | Screens |
|---|---|---|---|
| 0 | The captain creates the team (any account, default seats) and shares the link. | M 1+0, H 0, O 0, W 0, free 5+2; invite live | captain: `invite_title`, `invite_body`, `invite_seat_order` |
| 1 | P1 … P5 reach the decision point at different moments, and each taps Katıl within 10 s. | each: T1 HOLDING with seat STARTER (free_STARTER > 0), then T8 JOINED as STARTER. After P5: M 6+0, free 0+2 | each: pop-up with `join_seat_starter` ("Sunucu sana bir as yeri ayırdı."), then "Sunucu seni Kuzey Spor takımına as olarak kaydetti.", then the jersey prompt |
| 2 | P6 reaches the decision point and taps Katıl. | T1 HOLDING with seat SUBSTITUTE (free_STARTER = 0), then T8 JOINED as SUBSTITUTE. M 6+1, free 0+1 | P6: pop-up with "Sunucu sana bir yedek yeri ayırdı.", then "… takımına yedek olarak kaydetti." |
| 3 | t = 0: P7's claim arrives. | T1: P7 HOLDING SUBSTITUTE, `queue_no` 7, `expires_at` 10.0, `grace_until` and `due_at` 12.0. M 6+1, H 0+1, free 0+0 | P7: pop-up showing 10 (D − now ≈ 9.7 s) with "Sunucu sana bir yedek yeri ayırdı." |
| 4 | t = 3: P8's claim arrives. **This is the owner's situation:** every seat but one is taken (7 of 8 with the captain), one person decides on the last seat, and the next person waits. | T2: P8 WAITING, `queue_no` 8, ahead 0. W 1 | P8: "Sıradasın" · "Takımda 1 yedek yeri kaldı." · "Bu yer şu an bir kişinin kararını bekliyor." · "Sıradaki sensin." · `queue_rule`. P7's pop-up poll stays asleep, because P7's view (HOLDING, 10.0, SUBSTITUTE) did not change, and P7's countdown is untouched (C3). |
| 5 | t = 4 and t = 5: P9 and P10 claim. | T2: P9 WAITING ahead 1, P10 WAITING ahead 2. W 3 | P9: the same seat lines, "Önünde 1 kişi var." P10: "Önünde 2 kişi var." P8 is not woken. |
| 6 | t = 6: P7 taps Reddet. | T9: P7 REJECTED. `settle`: free 0+1 → T5: P8 OFFERED with seat SUBSTITUTE, `offer_deadline` 14.0. H 0, O 0+1, W 2 | P7: `join_rejected` ("Daveti reddettin. Takıma katılmadın.") with Yeniden Sıraya Gir. P8's poll answers within milliseconds: `queue_your_turn`. P9: "Sıradaki sensin." P10: "Önünde 1 kişi var." The seat lines stay "Takımda 1 yedek yeri kaldı. Bu yer şu an bir kişinin kararını bekliyor." |
| 7 | t ≈ 6.3: P8's ack arrives. | T6 (6.3 < 14.0): P8 HOLDING SUBSTITUTE, `expires_at` 16.3, `grace_until` and `due_at` 18.3. O 0, H 0+1 | P8: pop-up showing 10 with "Sunucu sana bir yedek yeri ayırdı." P9 and P10 are not woken (their views did not change). |
| 8a | t = 10: P8 taps Katıl. | T8 (arrival 10.1 < 18.3): P8 JOINED as SUBSTITUTE. M 6+2 = 8 = capacity: the invite closes (FULL), and `settle` runs T13, not T14: P9 and P10 CLOSED (FULL). M 6+2, H 0, O 0, W 0 | P8: "Takıma Katıldın / Sunucu seni Kuzey Spor takımına yedek olarak kaydetti.", the jersey prompt. P9, P10: `queue_team_filled`. Captain: `invite_closed_full`. P11 opening the old link now: `link_team_full`. |
| 8a′ | After 8a, P11 has no working link and types the code from the old message in Kod Gir. | The code's HMAC is the tombstone's `closed_code_hmac`: `410 LINK_CLOSED` (FULL) with `kind` and `teamName`; no throttle key moves (R3, fidelity V2). | P11: `link_team_full`, however often P11 retries. |
| 8b | P8 lets the time run out. | P8's phone reaches zero at about 16.15 (its deadline lies one uplink delay before the server's) and sends TIMEOUT. If it arrives before 16.3: `timeout_reported`, `due_at` 16.3, and the first tick after 16.3 runs T10b (EXPIRED, TIME_UP) and T5: P9 OFFERED SUBSTITUTE with `offer_deadline` about 24.5. If it arrives after 16.3: the same at once. If no TIMEOUT arrives: T10a at 18.3. | P8: `join_time_up`, then `join_expired` with Yeniden Sıraya Gir. P9: `queue_your_turn`, then the pop-up with the substitute line after its ack. P10: "Sıradaki sensin." |
| 8c | P8 had pressed Home before step 6, so the app stopped polling and sent no ack. | t = 14.0 (first tick ≤ 14.25): T7 P8 SKIPPED, T5 P9 OFFERED SUBSTITUTE (`offer_deadline` ≈ 22.25). | P8 on return: the app reads the stored attempt id, `GET` answers SKIPPED, the app shows `queue_skipped` with Yeniden Sıraya Gir and sends no claim (C5). P9: the pop-up. |
| 8d | P8 taps Katıl when the pop-up shows 1. On a slow network the request's headers reach the server at 17.8 (1.5 s after `expires_at`), and a long analysis write holds the lock until 18.7. | Judged by arrival: 17.8 < 18.3 → T8 JOINED as SUBSTITUTE at 18.7. The middleware registered the entry at 17.8, before `grace_until`, so the ticks at 18.3-18.5 skipped the hold; the protection would have ended at 23.3 (C2, conc V1). End state as in 8a. | P8: `join_sending`, then the joined texts. |
| 8e | Same, but the request arrives at 18.8. | It protects nothing (18.8 ≥ 18.3). If the tick at 18.3-18.55 ran first: T10a (EXPIRED, NO_DECISION) and T5, then the ACCEPT runs T17. If the ACCEPT's transaction ran first: T8c closes the hold and offers P9 in the same transaction (conc V3). Either way `late_accept_at` 18.8 and `409 ATTEMPT_CLOSED` with `lateAccept: true`; P9 OFFERED SUBSTITUTE. | P8: `join_late`. P9: the pop-up. |
| 8f | P8's phone loses the network from t = 5.5 to t = 9.2. | Polls fail; the app retries at 6.0 and then every second. When the network returns at 9.2 the app polls at once and gets OFFERED at about 9.5; the ack arrives at about 9.65 < 14.0 → T6 HOLDING SUBSTITUTE, `expires_at` ≈ 19.65. | P8: `queue_no_network` without numbers, then `queue_your_turn` and the pop-up (F2). |
| 8g | The owner's computer sleeps from t = 5 to t = 65; P7 has not tapped. | At the wake the beat sees a 60 s gap and sets `recovery_due`. The first write transaction after the wake, whether a buffered request or the clock takes the first ticket, runs recovery (2.11) before any judgment or `settle`: P8, P9, P10 `seen_at` = 65; P7's `grace_until` = max(12.0, 67.0) = 67.0. At 67.0 T10a: P7 EXPIRED, T5: P8 OFFERED SUBSTITUTE (`offer_deadline` 75.0). | During the sleep: P8-P10 `queue_reconnecting` without numbers; P7 `join_time_up`, then `join_unknown_timeout`. After the wake: P7 `join_expired` after Sonucu Kontrol Et; P8 the pop-up. Nobody was DROPPED (C6, conc V2). |
| 8h | Same as 8g, but the owner's tunnel drops from t = 5 to t = 50 while the computer stays awake. | At t = 8 no request has arrived for 3 s: the server answers every waiting poll at once. At t = 11 still nothing has arrived: a silence, which starts before P7's `grace_until` (12.0). The clock runs no T7, T10a or T12. At 50 the first arrival sets `recovery_due`, and the next write transaction runs recovery: `seen_at` = 50, P7's `grace_until` = 52.0. At 52.0 T10a and T5: P8 OFFERED SUBSTITUTE (R8). | As in 8g. Nobody was DROPPED. |

Seat-type variants, each starting from the state after step 7 (t ≈ 6.3: M 6+1, P8 HOLDING SUBSTITUTE until 16.3, P9 WAITING ahead 0, P10 WAITING ahead 1):

| Step | Event | Server state after the step | Screens |
|---|---|---|---|
| 8i | t = 8: P3, a starter, taps Takımdan Ayrıl. | The membership delete frees a STARTER seat: M 5+1, free 1+0. `settle` T5: P9 OFFERED with seat STARTER (`offer_deadline` 16.0). P8 keeps SUBSTITUTE (2.3). H 0+1, O 1+0, W 1 | P9: `queue_your_turn`, then after the ack the pop-up with "Sunucu sana bir as yeri ayırdı." P10: "Takımda 1 as yeri ve 1 yedek yeri kaldı." · "Bu yerler şu an 2 kişinin kararını bekliyor." · "Sıradaki sensin." P8's pop-up poll stays asleep (its view: HOLDING, 16.3, SUBSTITUTE). Captain: `invite_live` "Şu an 2 yer karar bekliyor ve 1 kişi sırada bekliyor." If P8 and P9 both accept: M 6+2 = 8, the invite closes FULL, and P10 gets `queue_team_filled`. |
| 8j | t = 8: the captain raises the substitute seats from 2 to 3 (`PUT …/seats {substituteSeats: 3}`). | Check: substitute holders 1 + deciding 1 = 2 ≤ 3. Accepted; capacity 9; `settle` T5: P9 OFFERED SUBSTITUTE (`offer_deadline` 16.0). | P9: the pop-up with the substitute line after its ack. P10: "Takımda 2 yedek yeri kaldı." · "Bu yerler şu an 2 kişinin kararını bekliyor." · "Sıradaki sensin." |
| 8k | t = 8: the captain lowers the substitute seats from 2 to 1. | Check: substitute holders 1 + deciding 1 = 2 > 1 → `409 SEATS_IN_USE` (`seat` SUBSTITUTE, `holders` 1, `deciding` 1). Nothing changes and nobody is woken. | Captain: `seats_in_use_substitute` "Şu an 1 yedek yeri karar bekliyor. Yedek yeri sayısı yedek oyuncuların ve karar bekleyen yedek yerlerinin toplamından az olamaz." The call moved nobody, so no hint follows. |
| 8l | t = 8: the captain makes P6 a starter and P2 a substitute in one call. | Holders stay 6+1; both checks pass; `settle` offers nothing (free stays 0+0). The same call with P6's move alone would give 7 starters: `409 SEATS_BELOW_HOLDERS` (`seat` STARTER, `holders` 7, `seats` 6), and nothing changes. | P2 and P6 see their new types on the team screen. P8-P10 see no change. For the refused call the captain sees `seats_below_starter` "Bu değişiklikle takımda 7 as oyuncu olur ama yalnız 6 as yeri var. Bir yedeği as yapmak için bir as oyuncuyu da yedek yapıp birlikte kaydedebilirsin." |
| 8m | After 8a the captain raises the substitute seats to 3, then taps Yeni Bağlantı Oluştur and shares the new link; P9 opens it. | The raise frees a substitute seat, but the invite is closed (FULL), so nobody is offered. The new invite is live; P9's fresh claim runs T1: HOLDING SUBSTITUTE. | Captain: `invite_closed_full`, then the new invite card. P9: the pop-up with the substitute line. |

**Walk 2: an open match of 14 (12 starter seats and 2 substitute seats).** Deniz, a member of Kuzey Spor (any account may open one, R6), opens it for Sunday 22 November 2026 at 14.00 in "Moda Spor Salonu", with the FIVB rule, no name, and "Ben de oynuyorum" on, so Deniz holds a starter seat and 11 starter and 2 substitute seats are open. The link lives until the end of 22 November (UTC+3) or until the first upload. The players need no team (R6). Steps 9-16 were rewritten after the v4 checkers (R1, R2, F1, F4, F9, V4-S2, V4-S3).

| Step | Event | Server state after the step | Screens |
|---|---|---|---|
| 0 | Deniz opens the open match and shares the link in the group. | pool 12+2; M 1+0, free 11+2; link live | Deniz: `open_link_title`, `open_link_body`, `invite_seat_order`, Paylaş with `open_share_text` |
| 1 | P1 … P11 reach the decision point at different moments, and each taps Katıl. | each: T1 HOLDING STARTER, then T8 JOINED as STARTER (an `open_match_players` row). After P11: M 12+0, free 0+2 | each pop-up: "Açık Maç" · "Maçı yöneten · Deniz" · "Maç günü · 22 Kasım Pazar" · "Saat · 14.00" · "Saha · Moda Spor Salonu" · "Sunucu sana bir as yeri ayırdı." · "Katılmak istiyor musun?"; then "Maça Katıldın / Sunucu seni bu maça as olarak kaydetti." and `jersey_body_open` |
| 2 | P12 taps Katıl. | T1 HOLDING SUBSTITUTE, T8 JOINED as SUBSTITUTE. M 12+1, free 0+1 | P12: the substitute line, then "… bu maça yedek olarak kaydetti." |
| 3 | t = 0: P13's claim arrives. | T1: P13 HOLDING SUBSTITUTE, `expires_at` 10.0, `grace_until` 12.0. H 0+1, free 0+0 | P13: the pop-up with "Sunucu sana bir yedek yeri ayırdı." |
| 4 | t = 3: P14's claim arrives. **The owner's situation:** 13 of 14 seats taken, one person deciding on the last seat, the next person waiting. | T2: P14 WAITING, ahead 0. W 1 | P14: "Sıradasın" · "Maçta 1 yedek yeri kaldı." · "Bu yer şu an bir kişinin kararını bekliyor." · "Sıradaki sensin." · `queue_rule` |
| 5 | t = 4 and t = 5: P15 and P16 claim. | WAITING ahead 1 and 2. W 3 | P15: "Önünde 1 kişi var." P16: "Önünde 2 kişi var." |
| 6 | t = 6: P13 taps Reddet. | T9; `settle` T5: P14 OFFERED SUBSTITUTE, `offer_deadline` 14.0 | P13: `join_rejected_open` ("Daveti reddettin. Maça katılmadın."). P14: `queue_your_turn`. P15: "Sıradaki sensin." |
| 7 | t ≈ 6.3: P14's ack arrives. | T6: P14 HOLDING SUBSTITUTE, `expires_at` 16.3, `grace_until` 18.3 | P14: the pop-up with the substitute line |
| 8 | t = 10: P14 taps Katıl. | T8: P14 JOINED as SUBSTITUTE. M 12+2 = 14 = capacity: the link closes (FULL), T13 closes P15 and P16 (FULL). | P14: "Maça Katıldın / Sunucu seni bu maça yedek olarak kaydetti." P15, P16: `queue_full_open` ("Bu maç doldu ve sunucu seni sıradan çıkardı. …"). Deniz: `open_closed_full`; if a player leaves later, the card shows `open_closed_full_free` (F4). P17 with the old link: `link_full_open`. |
| 6′ | Variant: at t = 5.5, before P13 decides, Deniz raises the substitute seats from 2 to 3. | substitute holders 1 + deciding 1 ≤ 3; `settle` T5: P14 OFFERED SUBSTITUTE at once (`offer_deadline` 13.5). | P14: the pop-up after its ack, while P13 still decides. P15: "Maçta 2 yedek yeri kaldı." · "Bu yerler şu an 2 kişinin kararını bekliyor." · "Sıradaki sensin." P16: "Önünde 1 kişi var." |
| 6″ | Variant: at t = 5.5 P4, a starter, taps Maçtan Ayrıl. | The player row goes; free 1+0; `settle` T5: P14 OFFERED STARTER (`offer_deadline` 13.5); P13 keeps SUBSTITUTE. | P14: the pop-up with "Sunucu sana bir as yeri ayırdı." P15: "Maçta 1 as yeri ve 1 yedek yeri kaldı." · "Bu yerler şu an 2 kişinin kararını bekliyor." · "Sıradaki sensin." |
| 9 | Saturday: Deniz opens Taraflar. | The roster was never saved, so `GET …/roster` carries the proposal: each player's `proposed_side`, which the server stored when the player joined (5.8.5). The starters in join order (Deniz, P1 … P11) got A, B, A, B …: A = Deniz, P2, P4, P6, P8, P10; B = P1, P3, P5, P7, P9, P11. The substitutes (P12, P14) got A and B. Each side: 6 starters and 1 substitute. Had P4 left on Friday, no other player's proposed side would move (R2). | Deniz: `open_sides_proposed`. Players still see "Belli değil", because the proposal is the organiser's draft until Deniz saves it. |
| 10 | Deniz swaps P1 and P2 and taps Kaydet. The app fills each entry's jersey from the player's suggestion; P11 suggested none. | `PUT …/roster` version 1 on both sides, 13 jerseys and P11's empty; not confirmed. | Every player sees their side on the open match screen and `open_waiting_roster`. |
| 11 | Sunday 14.00: the match is played. Deniz records it (New Match, Açık maç) and the upload starts after the match: no acceptance gate, and the match has 14 players (5.8.6). Deniz reviews it on the same phone. | `initiate_upload` writes the upload row; the link was already closed (FULL), so it stays closed, and from now on no route creates a link (2.2). The split is saved, so the review opens (R2). The RALLY_WINNER card shows "A Tarafı" with Deniz, P1, P4 under it. A SCORER card of side B offers the 6 jerseys of B's saved entries, not P11's; Deniz taps the card's Kadro button, writes P11's jersey (version 2), returns and picks it (R1). Deniz confirms the list and taps Sonucu Onayla: the same frozen report becomes the side result of A and of B. Every player's line: ROSTER_WAITING. | Deniz's card: `open_closed_video`. Players: `open_waiting_roster`. |
| 12 | P9 did not come and taps Bu Maçta Oynamadım. | One transaction deletes P9's player row and roster row; the seat frees, but the link is closed and the record has an upload, so nobody is offered and no link can be created. Roster version 3 on both sides. | P9: the match leaves P9's list; P9 can no longer read its video or report. Side B: 5 starters, 1 empty starter place and 1 substitute (R3). Deniz's Kadro screen shows the empty place as "Boş" with `roster_empty_place_body`, and `roster_empty_place_hint` ("Bu tarafta 1 boş as yeri ve 1 yedek var. Maça bir yedek başladıysa onu as yapabilirsin."), because side B also has a substitute; if P14 started the match, Deniz makes P14 a starter. |
| 13 | Deniz taps Kadroyu Onayla. | Version 3 holds a jersey on every entry. The server confirms both sides at version 3 and stores the confirmed roster as a snapshot (6.3). Side B's empty starter place has no row and no line, so nobody gets a number for it; a point that Deniz gave to a jersey outside B's roster with Kadroda Olmayan Numara stays on that jersey, and only a point marked Oyuncuyu bilmiyorum stays unattributed (7.1, R3). | Every player with a line in the snapshot: COUNTED in Kişisel İstatistik with a line of kind OPEN_MATCH. No team's statistics change, also not Kuzey Spor's, whose member P1 played. |
| 13′ | Variant: in step 11 Deniz did not write P11's jersey and picked Oyuncuyu bilmiyorum for P11's points. | Kadroyu Onayla answers `422 ROSTER_INVALID` with `details.reason: JERSEY_MISSING`, `count` 1. Deniz writes the jersey and confirms. P11's points stay unattributed until Deniz changes those SCORER answers and confirms the result again. | Deniz: `roster_jersey_missing`. P11's line: COUNTED, with points PARTIAL or UNKNOWN by 7.3. |
| 14 | Variant: P9 taps Bu Maçta Oynamadım only after step 13. | Version 4; the server removes P9's line from the snapshot in the same transaction, and the snapshot keeps counting for everyone else (6.3). | Everyone else stays COUNTED; P9 has no line. |
| 15 | After step 13 Deniz tries to delete the open match, then to cancel it. | Both answer `409 MATCH_HAS_CONTENT` with `details.reason: RESULT_CONFIRMED`, because other players hold seats and lines of the confirmed roster (5.8.4, F1, F9). Deniz may still delete the video; the side results and the snapshot stay. | Deniz: `open_delete_refused` and `open_cancel_refused` |
| 16 | After step 13 Deniz removes P3 and then saves a roster without P3. | The removal deletes P3's player row and roster row and writes a block; P3's line in the snapshot stays. The save moves the version; the snapshot keeps counting, and the roster shows `changedSinceConfirmed` until Deniz confirms again (V4-S3). A new confirmation without P3 removes P3's line. | Deniz: `open_remove_counted` in the removal dialog. P3: the match leaves P3's list, and Kişisel İstatistik keeps P3's line until that confirmation. |

Every row was checked against T1-T17 (v5 removed T8d), the seat rules of 2.3, the timing table and the texts of 2.12 and 10.8.

### 2.14 Accessibility of the pop-up

- When the pop-up opens, TalkBack hears the team name or the open match's title, the seat line ("Sunucu sana bir as yeri ayırdı."), "Katılmak istiyor musun?" and "10 saniye kaldı" once, through a polite live region. It hears the countdown again at 5 seconds only, so the reading does not drown the buttons.
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

The same character rule (NFC, trim, no Cc or Cf, internal whitespace collapsed) applies to team names, match names, opponent names, open match side names and venues from 0.8 on.

The common-password list is a free list whose licence allows bundling in a closed product (for example SecLists' 10k list under MIT; the slice checks the licence file and records it in the third-party notices). It ships inside the backend package, so no network call is needed.

Google sign-in comes later, when the owner creates a free OAuth client. It will add a `user_identities` table and change nothing here.

### 3.2 Passwords, sessions and the session cache

- `hashlib.scrypt(n=2**15, r=8, p=3, dklen=32, maxmem=64 MiB)`, about 165 ms on this container, stored as `scrypt$32768$8$3$<salt>$<key>`, compared with `hmac.compare_digest`. An unknown username still costs one scrypt against a dummy hash (v1, kept).
- Token `secrets.token_urlsafe(32)`, only in `Authorization: Bearer`, stored as SHA-256. 30 days idle and 180 days absolute; the server writes `last_used_at` at most once an hour (v1, kept).
- No cookies, so no CSRF; no CORS, because the only client is the app.
- **Device token (S2).** A successful login or registration also answers with a `deviceToken` (`secrets.token_urlsafe(16)`), which the server stores as SHA-256 in `login_devices` with the user id. The phone keeps it in `device.bin`, keyed by the SHA-256 of the username, and keeps it across logout. A login that carries a device token valid for its username skips the username keys of 3.6 and counts its failures under `login:device:<hash>` instead. A stranger who knows a username can therefore lock that name out only on phones that never logged in with it, never on the owner's own phone (the OWASP device-cookie rule). A password change or an operator reset deletes the user's device tokens; a token unused for 180 days goes.
- Logout deletes the session row. A password change deletes every other session.
- **Session cache (C1).** The process keeps a map from token hash to (user id, expiry, time read), filled by every database session lookup. Only the long-poll and the captain's and the organiser's live view read it, so they never touch the database while they wait. Logout, password change, account deletion and session expiry in this process remove the entry at once. An entry older than 60 s is checked against the database again on its next use, so a session the operator CLI deletes from another process stops serving polls within 60 s. Every write route still reads the session inside its own transaction.

### 3.3 Registration order and the gates (v5: sign-up gate only)

`POST /v1/auth/register` checks, in this order, and answers the first failure:
1. throttle (3.6): only the `register:src` source key and, for a code, the code keys apply;
2. the sign-up gate: `403 SIGNUP_CLOSED` with `details.reason: NO_LINK` when the body has no link and no code, and with `details.reason: OPEN_MATCH_LINK` when its link is an open match link while the operator set `VOLLEYSTAT_OPEN_MATCH_SIGNUP=off` (v5's default is `link`, R6); `404 LINK_NOT_FOUND`, `410 LINK_CLOSED` (with the reason) or `422 WRONG_LINK_KIND` (a match-request link, P1) for a link; `403 SIGNUP_LINK_LIMIT` with `details.scope: LINK` when the join link has opened 200 sign-ups (P3), and with `details.scope: CREATOR` when the join links of that link's creator have opened 400 sign-ups in the last 7 days (v5, below), checked before LINK, because a new link of the same creator would not help; `403 SIGNUP_CODE_INVALID` for an operator code. Nothing about the username is looked up before this step.
3. field validation (`422 USERNAME_INVALID`, `422 DISPLAY_NAME_INVALID`, `422 PASSWORD_REJECTED` with `details.reason`);
4. username uniqueness (`409 USERNAME_TAKEN`).

The join link's `signups` counter moves in the transaction that inserts the user; a `409 USERNAME_TAKEN` does not move it. The user row records how the account opened (`signup_via`: INVITE, OPEN_MATCH, SIGNUP_CODE or OPEN; B1 stores SIGNUP_CODE and OPEN, and B5 adds the two link values), which sets its per-account storage quotas (6.5, S7, V4-Q1). v4's CAPTAIN_CODE value and its use for the members rule are gone (R6). A registration with a code that no live link and no tombstone holds counts as a code miss under the code keys of 3.6, so registration cannot serve as an uncounted code oracle (S2). Renewing the join link starts a new link counter; the creator's count below does not restart. The limit of 200 equals the queue limit, so the owner's WhatsApp group of 100 newcomers can all sign up, wait and get a truthful answer, while a leaked link can create at most 200 accounts before the captain renews it. P3 proposed capacity − members + 5; with the owner's team of 8 and the captain in, that refuses the 13th newcomer of the owner's WhatsApp group before the queue is even full, so the design ties the limit to the queue instead (owner number, 16.1 item 11).

**The creator's sign-up limit (v5, R6).** Under v4's gate only captains whom the operator approved could create invites, and open match links opened no account, so renewing a link to restart its 200 was a captain's remedy for a leak and nothing more. With every account able to create teams and open matches, one account could renew its links again and again and open any number of accounts. v5 therefore also counts per creator. `share_links.created_by` names the account that created a live join link (8.2). Every registration through a join link writes one row of `link_signups(creator_id, user_id, at)` in the registering transaction (8.2), and a registration is refused with `403 SIGNUP_LINK_LIMIT` (`details.scope: CREATOR`, `details.untilAt`) when the link's creator already has 400 rows from the last 7 days. The count reads `link_signups`, never `share_links`: deleting a team or an open match deletes its links and their tombstones by cascade, so v5's first count, `SUM(signups)` over the creator's links, started again from zero at every such deletion, and one account could open thousands of accounts a day by creating, inviting and deleting (C1 of the v5 checkers). The minute sweep deletes a row 7 days after its `at`. `user_id` names the new account, so that the operator can trace the accounts opened through one creator's links (`signups`, 6.5); it becomes NULL when that account is deleted, so the count stays exact, and the creator's rows go with the creator's account. `share_links.created_by` becomes NULL when the link closes, because a tombstone opens no account, so a tombstone does not keep its creator for 30 days. 400 is two full links: the owner's WhatsApp group of 100 newcomers fits twice over, and a captain whose leaked link filled its 200 still has room for 200 through a renewed link. A link whose creator deleted their account (`created_by` NULL) counts only toward its own 200. The answer carries a live token, so it moves no throttle key (3.6). The count is read in the registering transaction, so two registrations at once cannot both pass the 400th place. `details.untilAt` is the moment the oldest of the 400 rows leaves the 7-day window. The captain's or the organiser's link card shows `invite_signup_limit` or `invite_signup_limit_creator` once the link no longer opens sign-up (2.12 F). The number is an owner number (16.1 item 33).

**Operator codes.** 16 characters from the code alphabet (about 79 bits), shown as four groups of four, valid 7 days, stored as SHA-256 in `operator_codes` with a kind:
- `SIGNUP` (`python -m app.admin signup-code`) opens one registration. The registration consumes it; a `409 USERNAME_TAKEN` does not.
- `CAPTAIN`: v4 planned a captain code here (`captain-code --team-name`, bound to a team name and to the account it registered). v5 removes it (R4, R6): no command prints one, and team creation needs none. B1 already reserved the kind and three columns for it (`signup_used`, `used_by_user_id` and `team_name` in `operator_codes`) and registers only with SIGNUP codes, so every existing operator code keeps working unchanged. B4 makes `issue_operator_code` raise `ValueError` for any kind other than SIGNUP and leaves the three columns unused (v5 changes, corrections; 14.1).

Tests: with `link` mode and no link, a taken and a free username get byte-identical answers; a registration with an OPEN_MATCH link under `off` answers `403 SIGNUP_CLOSED` with `OPEN_MATCH_LINK`, creates no user and moves no throttle key, and under `link`, the default, opens an account with `signup_via` OPEN_MATCH; once the join links of one account opened 400 accounts in the last 7 days, a registration through any of them, also through a link renewed a moment ago, answers `403 SIGNUP_LINK_LIMIT` (`CREATOR`) with `untilAt`, and a link whose creator was deleted counts only its own 200; deleting the team or the open match whose links opened those 400 accounts leaves the count, so the invite of a new team still answers `SIGNUP_LINK_LIMIT` (`CREATOR`), C1 of the v5 checkers; a row whose new account was deleted still counts; a closed link's tombstone holds no `created_by`; a SIGNUP code printed before B4 still opens one registration after B4; a registration with an unknown code moves `code:anon:global` (S2).

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
| `register:src:<source>` | after B6a; never "local" | 100 gate failures in 10 min lock registration from that source for 15 min. A gate failure is `404 LINK_NOT_FOUND` by token, `422 WRONG_LINK_KIND`, `403 SIGNUP_CLOSED` without a link (`NO_LINK`) or `403 SIGNUP_CODE_INVALID`; a `403 SIGNUP_CLOSED` for a live open match link carries a live token and is none. A field-validation 422 (`PASSWORD_REJECTED`, `USERNAME_INVALID`, `DISPLAY_NAME_INVALID`), which the app cannot check in advance, `USERNAME_TAKEN`, a tombstone hit (410) and a code miss (counted under the code keys) are not. | REGISTER_SOURCE |
| `code:user:<user id>` | logged-in code misses on any route that accepts a code: resolve, claim, accept, decline | 10 misses in one hour | ACCOUNT_CODE |
| `code:src:<source>` | after B6a; never "local"; anonymous code misses on resolve and register | 100 misses in 10 min | CODE_SOURCE |
| `code:anon:global` | anonymous code misses on resolve and register | circuit breaker: 1 000 misses in 10 min stop anonymous code lookups for 10 min | CODE_GLOBAL |
| `join:attempt:<attempt id>`, `join:user:<user id>` | ack, decision and DELETE per attempt; claims per account (2.9) | 5 a second | RATE |
| `request:team:<team id>` | | 20 match requests a day | REQUESTS |
| `open:user:<user id>` | a `PUT /v1/open-matches/{id}` that creates a record | 10 open matches created a day | OPEN_MATCHES |
| `team:user:<user id>` | a `PUT /v1/teams/{id}` that creates a team (v5, R6) | 3 teams created a day; like every key here it starts again when the server restarts (C21 of the v5 checkers) | TEAMS |

Every 429 is `429 TOO_MANY_ATTEMPTS` with `Retry-After`, `details.retryAfterSeconds` and `details.scope`, so the app names the right subject: this username, this phone, this internet address, this account's code entry, code entry for everyone, this account's new open matches, or this account's new teams (fidelity V10, 10.8).

Rules:
- A code miss is a code that no live link and no tombstone of the last 30 days holds. A code that hits a tombstone answers `410 LINK_CLOSED` and counts toward no key (R3).
- No per-source key and no server-wide counter refuses a request that carries a live link token or a valid session (P4, S2). A logged-in code lookup counts only under its account key, and the per-attempt rate key applies to everyone.
- There is no server-wide login cap. The hash executor's queue (16, then `503 SERVER_BUSY`) protects the processor.
- The limiter lives in process memory (a dict and a lock, at most 10 000 keys), so nothing about addresses or usernames reaches the disk. It never evicts a locked key; it evicts the oldest unlocked key; when every slot holds a locked key it tracks no new key and logs that once a minute.
- Before B6a the server listens on 127.0.0.1 only, and only the per-username, per-device, per-account and per-attempt keys can trigger.
- The two daily creation keys (`open:user`, `team:user`) live here too, so they start again when the server restarts, which ADR-0119 accepts because a stranger cannot restart the server. They only slow churn. The limits that bound what stays on the server are read from the database and hold across restarts: captain of 3 teams (4.1), 3 open matches organised (5.8.9), the creator's 400 sign-ups (`link_signups`, 3.3) and the storage quotas (6.5) (C21 of the v5 checkers).

Tests: 61 failures from source X do not block a login from source Y; 1 000 anonymous code failures do not block a logged-in lookup or a claim with a token; a registration that ends in `USERNAME_TAKEN` or in `PASSWORD_REJECTED` moves no counter; a registration with a live token is never refused by `register:src`; a spoofed `X-Forwarded-For` changes no key before B6a; behind a loopback proxy that sends no client address, 10 failed registrations from one client block neither another client's registration nor its login (R4); two IPv6 addresses in one /64 share a key; with `VOLLEYSTAT_PUBLIC_URL` set the server refuses to start without `--trusted-proxy`; a login with a valid device token succeeds while `login:user:<username>` is locked (S2).

### 3.7 Account deletion

`POST /v1/me/delete {password}` → `204`. One write transaction:
1. Every unfinished upload the user started (`uploads.started_by`, 8.3) is aborted, its bytes deleted before the commit (V4-C1).
2. For every team the user captains with other members, captaincy moves to the earliest-joined member who is not a member of the other side of any PENDING or ACCEPTED record of that team. If no member qualifies, the call answers `409 CAPTAIN_MUST_TRANSFER` (`details.teamId`) and deletes nothing. A team with no other member is deleted (4.6). The review chain of a video lives only on the phone that added it (10.4), so the new captain could never finish a review the user started: every live video the user uploaded to a team side of which no job has a confirmed list is deleted, bytes before the commit, and `delete_account_body` says so (R9). A video with a confirmed list stays with the side, and the new captain becomes its reviewer.
3. For every open match the user organises: one without another player and without another person's line in its confirmed roster is deleted like `delete_match`, bytes before the commit. Any other loses its organiser instead of getting one appointed (V4-C1): `open_matches.organiser_user_id` becomes NULL (`ON DELETE SET NULL`, set explicitly in this transaction), a pending transfer offer goes, the live join link closes with reason ORGANISER_GONE, and T14 closes the pool's open attempts with ORGANISER_GONE. The sides, the seats, the roster, the confirmed roster and the side results stay as they are; nobody can change them, players can still leave (which removes their line, 6.3), and their video stays readable to the players. The minute sweep deletes such a record once no player and no confirmed-roster line remains. Its players see `open_no_organiser`.
4. Personal matches are deleted like `delete_match`, bytes before the commit.
5. `uploads.started_by` becomes NULL on every row that names the user; those bytes then count only in the server-wide pool (6.5). The user row goes. `share_links.created_by` becomes NULL on the user's links (`ON DELETE SET NULL`), so those links count only toward their own 200 (3.3). The user's `link_signups` rows as a creator go by cascade, and `user_id` becomes NULL on the row that recorded the user's own registration (8.2). Sessions, memberships, open match player rows, blocks, join attempts, roster rows and confirmed-roster rows go by cascade. Every pool that lost a holder or an open attempt runs `settle` and publishes its snapshot.
6. Commit, then `empty_write_ahead_log()`, then the session cache entry goes.

The byte-search test of `test_match_delete.py` runs on the username, display name and user id, also while a second connection holds the WAL open.

---

## 4. Teams

### 4.1 Create and settings (v5: any account, the substitute count only)

- `PUT /v1/teams/{teamId}` with a client-generated lowercase UUID (ADR-0113 pattern) and `{name, substituteSeats?, captainSeat?, schedule?, jersey?}`. Every signed-in account may call it (the owner's decision of 18 November, R6); no captain code exists. A team always has 6 starter seats; `substituteSeats` defaults to 2 and lies within 0-6 (R1, 2.3, `422 SEATS_INVALID` with `SUBSTITUTES_RANGE`), and a body with `starterSeats` answers `422 VALIDATION_ERROR`. The captain holds one of the seats: a starter seat unless `captainSeat` is SUBSTITUTE, so with the defaults 5 starter seats and 2 substitute seats are open when the first invite goes out. The captain's seat must fit the final counts: `captainSeat: SUBSTITUTE` with 0 substitute seats answers `422 SEATS_INVALID` with `details.reason: CAPTAIN_SEAT` (2.3, V4-C2). The server creates the team with a random tag (4.4), its seat pool, the caller's CAPTAIN membership on the chosen seat and the first invite (with `created_by` = the caller, 3.3), and answers `201` with the invite's token, code and URL. A repeat by a member answers `200` without the token or code; anyone else gets `409 TEAM_ID_TAKEN`.
- `PUT /v1/teams/{teamId}/settings {name, schedule}` (captain). The seats have their own route.
- `PUT /v1/teams/{teamId}/seats {substituteSeats, moves?: [{userId, seat}]}` (captain) changes the substitute count and moves members between starter and substitute; the server checks the final state and runs `settle` in the same transaction (2.3). The captain may move themselves. The starter count never changes (R1).
- `schedule` is `{weekday: 1..7, time: "HH:MM"}` or null. It is the team's usual match day and time, which the pop-up shows.
- Limits: a person is a member of at most 20 teams and captain of at most 3 (`409 TEAM_LIMIT_REACHED`, `details.limit`, `details.scope: MEMBER` or `CAPTAIN`), and creates at most 3 teams a day (`429 TOO_MANY_ATTEMPTS`, `details.scope: TEAMS`, 3.6). The daily count lives in the in-memory limiter of 3.6 and starts again when the server restarts; the captain limit is read from the database (C21 of the v5 checkers). A captaincy transfer to a member who already captains 3 teams answers `409 TEAM_LIMIT_REACHED` (`CAPTAIN`, `details.userId`); the automatic move at account deletion (3.7) ignores the limit, so a team never stays without a captain. v4 allowed 10 captaincies and no creation limit, because one captain code made one team and the operator printed the codes. With creation open to every account (R6), each team brings an invite that opens up to 200 accounts, a logo and its own video quota, so v5 proposes 3 and 3 (16.1 item 33). A real captain of more than 3 teams is rare; one who reaches the limit hands a captaincy on.

### 4.2 Logo (P7, C7, F4)

- The app picks the image with the photo picker (no permission), crops it square, draws it on an opaque background (so no alpha is ever needed) and encodes two lossy WebP files at quality 80: 256 px for members (about 10-25 KB, at most 36 KiB) and 128 px for link views (about 4-6 KB, at most 8 KiB).
- `PUT /v1/teams/{teamId}/logo` takes `{"large": "<base64>", "small": "<base64>"}`. Both together stay under the 64 KiB `BodyLimit`, which does not change.
- The server accepts only a simple lossy WebP for each: `RIFF` whose size field equals the decoded length minus 8, then `WEBP`, then exactly one `VP8 ` chunk whose frame header is a valid key frame of at most 512 × 512 (256 for large, 128 for small), and no byte after it. `VP8L`, `VP8X`, `ALPH`, EXIF, XMP, ICC, animation and trailing bytes answer `422 LOGO_INVALID`. The lossless decoder path, where the libwebp bugs of 2023 lived, never reaches a phone through this server, and no location or camera data is stored.
- The bytes live in `teams.logo` and `teams.logo_small` (BLOBs), so deletion follows `secure_delete` and the WAL rule with no file. `logo_version` moves on each change.
- Members read either size with `GET /v1/teams/{id}/logo?size=large|small` (session, ETag on the version).
- `POST /v1/links/resolve` embeds the small logo as base64 when the request's `knownLogoVersion` differs. Claim, ack, decision and poll answers never carry logo bytes (C7); they carry `logoVersion` only. A logged-in phone sends resolve in parallel with the claim, and the pop-up shows initials until the small logo arrives. Test: with maximum-size logos every claim, ack and poll answer stays under 2 KB.
- The app decodes logos with explicit bounds: `ImageDecoder` with `setTargetSize` on API 28 and later, `BitmapFactory` with an `inJustDecodeBounds` check and `inSampleSize` on API 26-27. A failed decode shows the initials.

### 4.3 Members, seats, jerseys, leaving, removal

- Members see the team: name, tag, schedule, the seat counts of both types, and the members with display name, jersey, role and seat type; the app lists them under Aslar and Yedekler. The captain also sees the invite state and the live counts.
- **Seats (D1a-D1c).** Every member holds one seat, STARTER or SUBSTITUTE, set when they joined by the type of the seat their hold reserved (2.3). The server never moves a member. The captain moves members, and changes the substitute count, with `PUT …/seats` (4.1); the 6 starter seats stay (R1). When a starter seat is free, the team has substitutes and nobody waits, the captain's team screen shows `team_starter_free_hint`. A team seat says who belongs to the team's usual starting six; a match's roles live in its roster, which starts from the team seats and which the captain changes per match (6.3).
- A member sets their own jersey; the captain may set anyone's. A jersey is unique among the members of one team (`409 JERSEY_TAKEN`, `details.jersey`).
- `DELETE /v1/teams/{id}/members/{userId}` leaves (self) or removes (captain). A captain with other members gets `409 CAPTAIN_MUST_TRANSFER`. The delete frees the member's seat, and `settle` in the same transaction offers it, with its type, to the head of the queue.
- **Removal blocks (P2).** A captain's removal writes `pool_blocks(pool_id, user_id)` for the team's pool in the same transaction. T1 refuses a blocked person with `403 JOIN_BLOCKED`, whatever link they hold. The captain sees the blocked people under "Çıkarılanlar" (display names, since they were members) and can lift a block with `DELETE /v1/teams/{id}/blocks/{userId}`. Leaving by oneself writes no block. Team or account deletion removes the rows by cascade. An open match uses the same table for its pool (5.8.4).
- **Removal and the live invite (S3).** A block stops one account, not the person: a removed person who still holds the live invite can register a new account with it and join at once. When the invite is live, the removal dialog therefore shows the switch Bağlantıyı da Yenile, on by default, with `member_remove_body` and `invite_renew_body`. With the switch on, `DELETE /v1/teams/{id}/members/{userId}?renewInvite=true` removes, blocks and renews in one transaction (T14 RENEWED and a new invite) and answers `200 {invite}` with the new token, code and URL. The new invite card then shows `invite_after_removal`. Test: a removed user who registers a new account with the old link is JOINED when the switch is off; with the switch on, the same user gets `410 LINK_CLOSED` (RENEWED).
- **New-join badge (P2).** `GET /v1/teams` and `GET /v1/teams/{id}` carry `newJoins` for the captain: members whose `joined_at` is later than the captain's `joins_seen_at`. The Teams tab shows the sum as a badge. Opening the team screen calls `POST /v1/teams/{id}/joins-seen {upTo}`.
- `POST /v1/teams/{id}/captain {userId}`: transfer to a member who captains fewer than 3 teams (`409 TEAM_LIMIT_REACHED`, 4.1), refused with `409 CAPTAIN_CONFLICT` (`details.matchId`) when the new captain is a member of the other side of a PENDING or ACCEPTED record of this team. It is also refused while an upload to one of the team's sides is unfinished (`409 UPLOAD_IN_PROGRESS`, `details.matchId`), because the old captain's phone would get `403 REVIEWER_ONLY` on its next chunk, and while a live video of one of the team's sides has no job yet or its newest job has no confirmed list (`409 REVIEW_IN_PROGRESS`, `details.matchId`), because the review chain lives only on the phone that added the video (10.4, R9). Both role updates happen in one transaction, so the partial unique index never sees two captains. Seats do not move with the captaincy.

### 4.4 The tag (P1)

The server draws a team's tag at random: 4 characters of the code alphabet from `secrets.choice`, stored in `teams.tag` with a UNIQUE index, redrawn inside the creating transaction until unused (31^4 ≈ 923 000 tags). A client-chosen team id no longer influences it, so the forgery that `critique_v2/tag_grind.py` showed (a matching tag in 0.5-1.4 s) is gone. Match request screens and the team screen show the tag next to the name (`Kuzey Spor #K7Q2`). Captains can tell each other their tags, so a team of the same name is recognizable.

### 4.5 Who sees what of a team

| Data | Member | Captain | Link holder without a session | Link holder with a session | Other team in a match |
|---|---|---|---|---|---|
| name, tag, small logo | yes | yes | yes (P5) | yes | name and tag |
| large logo | yes | yes | no | no | no |
| schedule and seat counts of both types | yes | yes | no (P5) | yes | no |
| member list with display names, jerseys and seat types | yes | yes | no | no | no |
| invite token and code | no | only in the creation answer and on the phone that created it (P8) | | | no |
| who is waiting or deciding | no | counts only | no | no | no |
| blocked people | no | yes | no | no | no |
| content of past team match records | only records played on or after the day the member joined, or records with the member on the roster (P2, 6.1) | all | no | no | 6.1 |
| the side's match roster | own side with names | own side with names | no | no | jersey and role of stored rows only; an unsaved default not at all (6.3, F2) |

### 4.6 Delete a team

`DELETE /v1/teams/{id}` (captain) → `204`. One transaction:
1. PENDING match records requested by the team are deleted with their links. No video can exist in them on the server.
2. In ACCEPTED records, the team's side leaves exactly as in 6.4: its videos, jobs and bytes, its roster rows and its side result go, and `match_sides.team_id` becomes NULL. A record with no side left is deleted like `delete_match`.
3. One-sided records of the team are deleted like `delete_match`.
4. The team row goes; memberships and the team's seat pool go by cascade, and with the pool its blocks, its invite, the invite's tombstones and its join attempts. The deleting transaction publishes the pool's last snapshot, so polls answer `404 ATTEMPT_NOT_FOUND`.
5. `empty_write_ahead_log()`.

The byte-search test covers the team name, tag, logo bytes, code HMACs, token hashes and the members' links.

---

## 5. Matches: team matches and open matches

Sections 5.1-5.7 cover matches between two teams, as in v3. Section 5.8 adds open matches (owner decision 1).

### 5.1 The request link

The captain of team A creates the request and sends its link to the other captain, the same way as an invite. No static match code, no search and no directory exist.

`PUT /v1/match-requests/{matchId}` (client UUID, so the phone can create it offline and send it later; 10.1):

```json
{"teamId": "6f0c…", "playedOn": "2026-11-22", "startTime": "14:00", "venue": "Okul Spor Salonu",
 "name": null, "kind": "TRAINING", "rules": {"setsToWin": 3, "setTarget": 25, "decidingSetTarget": 15}}
```

- `playedOn` and `startTime` are required, because the captains agree on a time and the statistics and the member read rule (6.1) read the date.
- The other fields follow `MatchInput`, with `extra="forbid"` and the 3.1 character rule.
- The caller must be the captain of `teamId` (`403 CAPTAIN_ONLY`). Since v5 any account may lead a team (R6), so the other team can be anyone's; the opponent check of 5.3 guards against a leaked link.
- The answer is `201 {match, link}` with the token, the 8-character code and the URL. The record is PENDING; A is HOME. The link is a share link of kind `MATCH_REQUEST`, valid 7 days, single use. It never opens sign-up (P1).
- A repeat with the same id answers `200` to members of A, without the token and code, and `403 NOT_A_MEMBER` to anyone else.

### 5.2 Opening and accepting

1. Captain B opens the link. Without a session, B logs in. A person without an account cannot use a request link to sign up; a new captain signs up through a join link or with an operator code and creates a team first (0.4).
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

Effect: status ACCEPTED, `closed_at = now`, `match_sides` rows for HOME and AWAY, AWAY's `opponent_checked_at = now` (accepting is B's check of A), `away_team` = B's current name, the link closes as a tombstone with reason USED. Each side's match roster then starts as its team's members with their seat types (6.3); nothing is copied.

`POST /v1/match-requests/decline {link, teamId}` by a captain closes the record as DECLINED and stores the declining team's name and tag in `matches.declined_by`, so A sees who declined. A code miss on accept or decline counts under the code keys (3.6, S2).

### 5.3 The requesting captain confirms the opponent (P1)

After acceptance, A's record card shows the accepting team's name, tag and small logo with `request_accepted` and a Rakibi Onayla button: `POST /v1/matches/{id}/opponent/confirm` (HOME captain) sets HOME's `opponent_checked_at`. Until then the server refuses HOME's upload with `409 OPPONENT_NOT_CHECKED`, and A's phone keeps any recording it holds (5.5). If the opponent is wrong, A taps Rakibim Değil (R1): A's side leaves the record (6.4), the app creates a SOLO record for A with a new client id (5.7), and the phone moves the held recording to it. When the other side has left the record, HOME needs no opponent check, because no other team receives HOME's video.

This adds one tap to the owner's flow for the requesting captain only. It stops the case that P1 describes: a leaked request link accepted by another team whose captain would otherwise receive A's video at once. v4 counted on the gate before the KVKK review; since v5 any account may lead a team (R6), so the check matters from the first day.

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
- An organiser picks an open match they organise, or creates one (Yeni Açık Maç). Its upload starts once the record is on the server and holds at least 2 players, because an open match waits for nobody's acceptance (5.8.6). A second recording of the same game goes to a sibling record, "Aynı Maçın İkinci Kaydı" (5.8.6, R4).

### 5.6 Personal matches (v2, kept)

Today's single-user flow stays as the personal match: `PUT /v1/matches/{id}` and `POST /v1/matches` create `status = PERSONAL` with `owner_user_id = caller`. Only the owner sees it, upload and review work as today, and it feeds no team or person statistic.

### 5.7 One-sided team matches (F3, owner decision 16.1 item 7, default yes)

Many opponents of an amateur team will not use the app, also now that every account may create a team (R6), and a one-sided match is the only way to count those matches. Without it a team's statistics would stay almost empty, which defeats the owner's request (fidelity V1).

`PUT /v1/solo-matches/{matchId} {teamId, opponentName, playedOn, startTime?, venue, name, kind, rules}` (captain of `teamId`) creates a record with `status = SOLO`, one side (HOME = the own team, `opponent_checked_at = now`) and `away_team = opponentName`. The own captain uploads, reviews and confirms; the record counts only for the own team and its rostered members. The opponent's name is free text and is never a team. Nothing else changes: the record has the same content rules, roster, side result and leave (which deletes it). A PENDING or closed request without a video becomes a SOLO record through `POST /v1/matches/{id}/solo` (5.4, R1).

### 5.8 Open matches (açık maç)

The owner, 17 November: "Bunun haricinde normal kullanıcılar maç açabilir. Bu da 12 as 2 yedek li kadrolar açar. As ve yedek sayısı ikisinde de değişebilir." An open match is a pickup game: one person opens it and shares its link, and people join it the same way as a team. It belongs to no team and counts only for persons. The owner added on 18 November that such a match is a 12-person game played now and then, with six on the court, and that anyone with an account may open one (v5 changes): an open match therefore always has 12 starter seats, 6 per side (R2), and its link opens sign-up for a newcomer (R6). The readings D1e-D1m (v4 changes), R2 and R6, and 16.1 items 19-27 hold the choices made here.

#### 5.8.1 What an open match is

- A `matches` row with `status = 'OPEN_MATCH'`, an `open_matches` row (organiser, transfer offer, cancellation), one seat pool of kind OPEN_MATCH, and two `match_sides` rows: HOME is side A and AWAY is side B, both with `team_id` NULL. `matches.home_team` and `away_team` hold the side names, "A Tarafı" and "B Tarafı" unless the organiser names them, with the rules `MatchInput` applies to team names today: 2-80 characters, the character rule of 3.1, and two names that differ after casefold (`main.py` lines 78-79 and 107). `report.py` keys its sides HOME and AWAY, and the match payload carries the names as `homeTeamName` and `awayTeamName` (`main.py` line 140), so the app shows an open match's report with the side names and no report change.
- It has an organiser, a date (`playedOn`, required), a start time (required), a venue text (required), an optional name, a `kind` (TRAINING by default) and a rule (`rules`, the FIVB rule by default), with the same fields and checks as a match request (5.1).
- Its players are the holders of its pool (`open_match_players`). Each holds one starter or substitute seat and has a proposed side (5.8.5).
- A sibling (5.8.6) is an open match with `matches.sibling_of` set to the original; it has a pool with the copied players and never a join link.

#### 5.8.2 Opening one

`PUT /v1/open-matches/{matchId}` with a client UUID, like a match request, so the phone can create it offline and send it later:

```json
{"playedOn": "2026-11-22", "startTime": "14:00", "venue": "Moda Spor Salonu", "name": null,
 "kind": "TRAINING", "rules": {"setsToWin": 3, "setTarget": 25, "decidingSetTarget": 15},
 "substituteSeats": 2, "organiserPlays": true,
 "sideNames": {"a": "A Tarafı", "b": "B Tarafı"}}
```

- Any signed-in account may open one (owner decisions of 17 and 18 November, R6), within the limits of 5.8.9. v4's members rule (`OPEN_MATCH_MEMBERS_ONLY`) is gone.
- `playedOn` is today or later in UTC+3 (`422 MATCH_DATE_PAST`). An open match with a past date and no link, for a game organised outside the app, is not offered (16.1 item 28); a sibling copies its original's date.
- An open match always has 12 starter seats (R2). `substituteSeats` defaults to 2 and lies within 0-12 (2.3, `422 SEATS_INVALID` with `SUBSTITUTES_RANGE`); a body with `starterSeats` answers `422 VALIDATION_ERROR`.
- `organiserPlays` (the switch "Ben de oynuyorum", on by default) gives the organiser a starter seat at creation, so 11 starter seats and 2 substitute seats are open when the link goes out. Creation then checks the organiser's JOINED limit like T8 (`409 OPEN_MATCH_LIMIT`, `details.scope: JOINED`; V4-C2), and the organiser's proposed side is A. With the switch off, the organiser holds no seat: they manage the match but are no player, have no roster row and get no statistics from it. Such an organiser who later wants to play claims a seat through their own link like anyone else.
- Limits (5.8.9): at most 3 open matches organised that are not cancelled and whose day has not passed (`409 OPEN_MATCH_LIMIT`, `details.scope: ORGANISED`) and 10 creations a day per account (`429 TOO_MANY_ATTEMPTS`, `details.scope: OPEN_MATCHES`). A cancelled open match frees its slot at once (F8).
- The answer is `201 {openMatch, link}` with the token, the code and the URL of the first join link; only this answer and `POST …/link` carry them (P8). A repeat with the same id answers `200` to the organiser without link secrets and `403 NOT_A_MEMBER` to anyone else.
- **Sibling (R4).** `PUT /v1/open-matches/{newId} {siblingOf}` by the organiser of the original, at any time, also after the match day, creates a second record of the same game: it copies the date, the start time, the venue, the name, the kind, the rule, the side names, the seat counts, the players with their seat types, suggested jerseys and proposed sides, and the saved split as the sibling's roster at version 1, unconfirmed. It creates no link (the link guard of 2.2 refuses one) and runs no join. The original has at most 3 siblings (`409 SIBLING_LIMIT`), and `siblingOf` must name an original, not a sibling. A sibling counts toward no open match limit. Each sibling has its own video, roster confirmation and result, and a player leaves each one separately.

#### 5.8.3 The link and joining

- The join link has kind OPEN_MATCH, the URL `https://<link host>/j/#t=<token>&c=<code>`, and the same token, code, HMAC, tombstone and throttle rules as an invite (2.2, 3.6). It lives 7 days or until the end of the match day (23:59:59 UTC+3), whichever comes first, and the clock closes it on time even when nobody waits (2.5). The record's first `initiate_upload` closes it in the same transaction with reason PLAYED (T14 with MATCH_PLAYED). No route creates a new link after the match day, once the record has an upload row (of any status, also of a deleted video), on a cancelled match, on a sibling or without an organiser (the guard of 2.2: `409 OPEN_MATCH_PLAYED`, `409 MATCH_CANCELLED`). So a person who did not play can join only until the first upload or the end of the match day, whichever comes first, and nobody joins after the video reached the server (D1l, V4-S2, F6). Before that moment the organiser can close the link (Bağlantıyı Kapat) and remove a late joiner (5.8.4).
- Joining is exactly a team's join: resolve, the decision point, claim, queue, offer, ack, the 10-second pop-up, decision, `settle`, the snapshots, the FIFO lock and arrival judging (section 2). Only the pop-up's top part differs (2.1, 2.12 B): it shows the organiser's display name instead of a team logo, the match day as a date with its weekday, the start time and the venue. The seat line is the same as a team's.
- **Sign-up (R6, 16.1 item 23).** An OPEN_MATCH link opens sign-up like an invite: under `VOLLEYSTAT_OPEN_MATCH_SIGNUP=link` (the v5 default) the anonymous LinkView says `signup: true`, and a registration through the link opens an account with `signup_via` OPEN_MATCH, at most 200 per link and 400 per creator in 7 days (3.3). The operator can still set `off`; then the LinkView says `signup: false`, the app shows `open_need_account`, and a registration that carries such a link answers `403 SIGNUP_CLOSED` with `details.reason: OPEN_MATCH_LINK`, which carries a live token and counts toward no throttle key (3.6). Anyone with an account and the live link may take a seat: v4's members rule and its transition T8d are gone (0.4). The organiser removes and blocks a person who should not play (5.8.4).
- Holder limit: a person holds seats in at most 10 open matches that are not cancelled and whose day has not passed (T1 and T8b, `409 OPEN_MATCH_LIMIT` with `details.scope: JOINED`). Siblings do not count.

#### 5.8.4 Managing it

By the organiser (`403 ORGANISER_ONLY` for anyone else):
- `PUT /v1/open-matches/{id}/settings {playedOn, startTime, venue, name, sideNames}` changes the details. A new `playedOn` is today or later; the live link's expiry moves to the end of the new day, never past the link's own 7 days. A change of `playedOn` is refused once the match day has begun (UTC+3) or once the record has an upload row or a side result (`409 OPEN_MATCH_PLAYED`, `details.reason`: `DAY_STARTED` or `HAS_UPLOAD`), so a played match never moves to a later day, never counts again toward anyone's limits, and never gets a new link (F6, V4-S2). A change of `startTime` is refused once the record has an upload row. The pool snapshot publishes the new details, so every waiter's next answer and every new pop-up shows them; a running countdown does not change.
- `PUT /v1/open-matches/{id}/seats` changes the substitute count and moves players between starter and substitute, through the same engine function as a team's (2.3); the 12 starter seats stay (R2).
- `GET …/link`, `POST …/link` (create or renew), `DELETE …/link` (close) and `GET …/live` work as a team's invite routes. `POST …/link` passes the guard of 2.2 first.
- `DELETE /v1/open-matches/{id}/players/{userId}?renewLink=` removes a player, deletes their player row and their roster row and blocks the account in this pool (`pool_blocks`), with the same "Bağlantıyı da Yenile" switch as a team (4.3, S3). With `renewLink=true` the call passes the guard of 2.2 first and, when the guard refuses, answers its 409 and changes nothing (V4-S2); the app shows the switch only while the link is live and the guard passes. A removal never touches the confirmed roster: a player removed after a confirmation keeps that line, and with it their numbers from this match, until the organiser confirms a new roster (`open_remove_counted`, 6.3, V4-S3, F1). The removed person loses the seat and access at once. `GET …/blocks` and `DELETE …/blocks/{userId}` list and lift blocks.
- **Transfer (V4-C1, R9).** `POST /v1/open-matches/{id}/organiser {userId}` offers the role to a player; `{userId: null}` withdraws the offer, and a new offer replaces it. The player sees the offer on `open/{id}` (`open_transfer_offer`) and answers with `POST …/organiser/accept` or `POST …/organiser/decline`. The accept transaction moves the role, with the reviewer role of the video, only when the offer still names the caller, the caller is still a player, the caller organises fewer than 3 open matches that are not cancelled and whose day has not passed (`409 OPEN_MATCH_LIMIT`, `details.scope: ORGANISED`), no upload of the record is unfinished (`409 UPLOAD_IN_PROGRESS`), and every live video of the record has a newest job with a confirmed list (`409 REVIEW_IN_PROGRESS`), because the review chain lives only on the phone that added the video (10.4). The offer route checks the last two as well. The offer goes when the player leaves, is removed, declines or accepts. The old organiser stays a player only if they hold a seat; otherwise the open match leaves their lists.
- `POST /v1/open-matches/{id}/cancel` while no video and no side result exists (`409 MATCH_HAS_CONTENT`, `details.reason: HAS_VIDEO` or `RESULT_CONFIRMED`; F1): sets `cancelled_at`, closes the link with reason CANCELLED, and T14 closes every open attempt with MATCH_CANCELLED. Players keep the cancelled match in their lists with `open_cancelled` until the sweep deletes it 30 days later. Deleting the video therefore never opens a way to cancel a match with a result and let the sweep erase its numbers.
- `DELETE /v1/open-matches/{id}` deletes the record like `delete_match` (bytes before the commit), with its pool, players, rosters, confirmed roster and link tombstones, while no side result exists, or, after a confirmed result, while no person other than the organiser holds a seat or a line of the confirmed roster (F9). Otherwise it answers `409 MATCH_HAS_CONTENT` with `details.reason: RESULT_CONFIRMED`. The organiser can still delete the video at any time (`DELETE /v1/videos/{id}`), and the side results stay, because they do not depend on the video (6.2). The organiser can therefore never erase other players' numbers by cancelling or deleting the match; the organiser changes them only by confirming a new roster or result (D1m, 16.1 item 27).

By a player (`403 NOT_A_PLAYER` for anyone else):
- `POST /v1/open-matches/{id}/leave`: the app labels it "Maçtan Ayrıl" until the match day starts and "Bu Maçta Oynamadım" from the match day on. One transaction deletes the player row, the roster row and the person's line of the confirmed roster, frees the seat and runs `settle`, which offers the seat to the head of the queue while the link is live. A player of an open match without an organiser can leave the same way. Leaving writes no block; a later join through a live link is a new claim. The organiser's own leave frees the organiser's seat and keeps the role: the organiser then manages the match without playing, as with "Ben de oynuyorum" off, and has no roster row.
- `PUT /v1/open-matches/{id}/players/me/jersey {jersey}` suggests a jersey (1-99 or null) that the organiser sees while writing the roster. It has no uniqueness rule; the roster has one per side.

Account deletion (3.7) deletes an organised open match without another player and leaves any other without an organiser; it never appoints one (V4-C1).

#### 5.8.5 Sides A and B (D1f)

- **The proposal is stored (R2).** When a player joins (T8), and for a playing organiser at creation, the server stores `open_match_players.proposed_side`: the side that has fewer players of the joiner's seat type, A on a tie. Without leaves that is A, B, A, B … in join order for the starters, and the same for the substitutes, starting again with A; with 12 + 2 each side gets 6 starters and 1 substitute, and a playing organiser lands on A. A leave never moves anyone else's proposed side. A seat move re-proposes only the moved players, and only while the roster has never been saved: in the transaction of the seats route (2.3) the server takes the moved players out of the counts and gives each of them, in the order of the moves, the side that has fewer players of its new seat type, A on a tie. Nobody else moves. With 12 starter seats and at most 12 substitute seats this keeps every side of the proposal at 6 or fewer of each type, so its `emptyStarterPlaces` never drops below 0 (S1 of the v5 checkers). Example: after walk 2's step 8, P4 (A, starter) leaves and Deniz makes P14 (B, substitute) a starter; P14 gets A, the side that has 5 starters at that moment, and each side has 6 starters again. While the roster has never been saved, `GET /v1/open-matches/{id}/roster` carries this **proposal** to the organiser, who sees it with `open_sides_proposed` and edits it (16.1 item 20). Players see no side (`open_side_none`) until the organiser saves the split, so nobody reads a draft as their side. In v4's first text the proposal was recomputed from the current players, so one early leave swapped every later player's side.
- `PUT /v1/open-matches/{id}/roster {entries: [{userId, side, role, jersey?}], expectedVersion}` saves the split, before or after the match. Every entry's person is a current player (`422 ROSTER_INVALID`, `NOT_A_PLAYER`); `role` starts as the player's seat type and the organiser may change it for this match to STARTER, SUBSTITUTE or LIBERO (6.3); per side at most 6 starters (`TOO_MANY_STARTERS`), at most 6 substitutes and liberos together (`TOO_MANY_SUBSTITUTES`) and at most 2 liberos (`TOO_MANY_LIBEROS`), the bounds of 2.3 and 6.3; jerseys 1-99 and unique per side when set. Each side shows 6 starter places, and a place without a starter shows "Boş" (R3, 6.3); with all 12 starter seats held, the proposal fills each side's 6 places. The app's Taraflar screen fills each entry's jersey from the player's suggestion when no other player of that side suggested the same number (R1). A player without an entry is not on a side (`open_sides_unassigned`) and gets no statistics from this match.
- **The review needs a saved split (R2).** On the organiser's phone the review of an open match (the RALLY_WINNER and SCORER cards) opens only after the split is saved, so the sides the organiser judges are the stored ones. Under each side name the RALLY_WINNER card shows the first three display names of that side.
- A confirmation of a never-saved roster (`version: 0`) writes the proposal as rows at version 1 and confirms that version in the same transaction (6.3, F2). Like every confirmation, it checks the rows it would confirm against every rule of 6.3, not only the jerseys (S1 of the v5 checkers). The proposal holds no jersey, so in practice such a confirmation answers `422 ROSTER_INVALID` with `JERSEY_MISSING` and writes nothing; the organiser saves the split with jerseys first.
- A player who joins after the split appears unassigned. The server never moves anyone between sides on its own.
- Both `match_sides` rows of an open match keep the same `roster_version`: every save moves both, and a confirmation confirms both and writes the confirmed roster of both (6.3). The statistics code of 7.2 therefore reads an open match like any other record.

#### 5.8.6 Video, roster confirmation and result (D1g)

- The organiser is the reviewer. An open match holds one live video (`409 SIDE_HAS_VIDEO`), which only the organiser uploads, reviews and deletes; `uploads.side` stays NULL for it. No acceptance gate exists: the upload may start once the record exists on the server and holds at least 2 players (`409 OPEN_MATCH_TOO_SMALL`, `details.players`; V4-Q1), so an open match is no private video store. The first `initiate_upload` closes the live join link (reason PLAYED, 5.8.3). The recorder picks the open match as the record (New Match, "Açık maç", 10.1).
- **The review's player list (R1).** The SCORER card and the Players screen of an open match read `GET /v1/open-matches/{id}/roster` when the review opens: the saved split with display names and jerseys for both sides. A side without any jersey shows `review_no_roster_open` with a button to `open/{id}/roster` (10.8).
- **A second recording (R4).** One open match holds one video. A second recording of the same game (a battery, a full card, a break) goes to a sibling (5.8.2): "Aynı Maçın İkinci Kaydı" on `open/{id}` creates it with the same players and split and opens the recorder or attaches the held recording. Each sibling is reviewed and confirmed on its own.
- `POST /v1/open-matches/{id}/roster/confirm {version}` confirms both sides at that version and stores the confirmed roster (6.3). It needs a jersey on every entry (`422 ROSTER_INVALID`, `JERSEY_MISSING`, `details.count`): as the owner asked, a person's statistics count only after the organiser confirms the roster with jerseys. A later save or a removal changes the working roster only, and a seat move changes no roster row at all (2.3); the confirmed roster keeps counting until the next confirmation (V4-S3).
- `POST /v1/matches/{id}/result/confirm {jobId, revision}` by the organiser writes the same frozen report as the side result of HOME (A) and of AWAY (B) in one transaction (6.2). No result offer exists, because one person reviews and confirms.
- A player who did not play taps "Bu Maçta Oynamadım" (5.8.4). The server removes the person's working row and confirmed line at once and keeps the confirmation for everyone else (6.3), so nobody is credited for a match they did not attend and nobody else waits for a new confirmation.

#### 5.8.7 Statistics

- An open match has no team statistics. Its sides have no team, so the team scope of 7.2 never includes it, and a team member who plays in it changes nothing on their team's numbers.
- A player's person statistics from it count when the organiser confirmed a result and a roster, and the player has a line in the confirmed roster (which always carries a jersey). The player's side won or lost by the side result of that side (7.2, 7.3).

#### 5.8.8 Who sees what (D1j)

| Data | Anonymous link holder | Logged-in link holder | Player | Organiser | A player's teammates |
|---|---|---|---|---|---|
| that the link belongs to an open match, and whether it opens sign-up | yes | yes | yes | yes | no |
| the organiser's display name | no | yes | yes | yes | no |
| date, start time, venue, rule, name, side names | no (P5) | yes | yes | yes | no |
| seat counts of both types | no | yes | yes | yes, with the deciding and waiting counts | no |
| players' display names, seat types, sides and jerseys | no | no | yes | yes | no |
| usernames | no | no | no | no | no |
| the video and the report | no | no | yes | yes | no |
| a player's person totals | no | no | their own only | no | no |

Players see each other's display names across sides, which a team match never allows (11.1). The people of an open match join one shared game through one link, the organiser's split can change until the roster is confirmed, and a player must know who comes. A joiner reads it before joining in the share message (`open_share_text`, `open_share_text_code`: "Maça katılan herkes diğer oyuncuların görünen adını ve maçın videosunu görür."), the organiser reads `privacy_open_match` under Maçı Aç, and every player finds `privacy_open_match` on `open/{id}` (R7, 16.1 item 24). A person who got only the code reads it on `open/{id}` after joining. Since v5 anyone with an account and the link can be a player, also a person whom nobody else in the game knows; the organiser removes and blocks such a person (5.8.4, R6).

#### 5.8.9 Limits and quotas (D1k)

| Limit | Default | Answer |
|---|---|---|
| open matches organised that are not cancelled and whose day has not passed (siblings not counted) | 3 per account; also checked when a player accepts a transfer | `409 OPEN_MATCH_LIMIT`, `details.scope: ORGANISED` |
| open matches created | 10 a day per account | `429 TOO_MANY_ATTEMPTS`, `details.scope: OPEN_MATCHES` |
| open matches with a seat that are not cancelled and whose day has not passed (siblings not counted) | 10 per account; also checked at creation with "Ben de oynuyorum" | `409 OPEN_MATCH_LIMIT`, `details.scope: JOINED` |
| seats | the bounds of 2.3 | `422 SEATS_INVALID` |
| queue, open attempts, sign-ups per link, polls | as for a team (2.3, 2.7, 3.3) | as for a team |
| videos | one live video per open match, and only while it holds at least 2 players; a second recording goes to a sibling, at most 3 per open match | `409 SIDE_HAS_VIDEO`, `409 OPEN_MATCH_TOO_SMALL`, `409 SIBLING_LIMIT` |
| open-match video storage | live open-match videos and unfinished open-match uploads counted on the account that started the upload, never moved by a transfer: 8 GiB for an account opened with an operator code, 4 GiB for one opened through a link; at most one unfinished open-match upload; all of them inside a server-wide open-match pool of 60 GiB (6.5, V4-Q1) | `409 QUOTA_EXCEEDED` with `details.scope` OPEN_MATCH, or SERVER with `details.pool: OPEN_MATCH` |

#### 5.8.10 States and deletion

- **Open** (`cancelled_at` NULL): the link may be live until the end of the match day or the first upload; players join, leave and get removed; the organiser splits, records, reviews and confirms.
- **Cancelled**: link closed (CANCELLED), attempts closed (MATCH_CANCELLED); players see `open_cancelled`; the minute sweep deletes the record 30 days after `cancelled_at`. Only an open match without a video and without a side result can be cancelled (F1).
- **Without an organiser** (`organiser_user_id` NULL, after the organiser's account deletion, 3.7): link closed (ORGANISER_GONE); nothing changes any more except players leaving; players see `open_no_organiser`; the minute sweep deletes the record once no player and no confirmed-roster line remains.
- **Deleted** by the organiser while no side result exists, or after a confirmed result once no person other than the organiser holds a seat or a confirmed-roster line (F9); or with the organiser's account when no other player and no other confirmed line remains (3.7). Otherwise a record with a confirmed result stays until those people have left and the organiser deletes it, or until the KVKK review sets a retention period (8.7).
- `DELETE /v1/matches/{id}` answers `409 TEAM_MATCH` with `details.status: OPEN_MATCH` for an open match; the app uses `DELETE /v1/open-matches/{id}`.

#### 5.8.11 Who may do what

| Action | Who | Refusals |
|---|---|---|
| see the open match in lists; read its details and its players | the organiser, the players | `403 NOT_A_MEMBER` |
| read content: video, quality, analyses, rallies, cards, report, roster | the organiser, the players | `403 NOT_A_MEMBER` |
| settings, seats, link, live view, removal, blocks, transfer offer, cancel, delete, sibling | the organiser; nobody when the organiser is gone | `403 ORGANISER_ONLY`, `409 MATCH_HAS_CONTENT`, `409 OPEN_MATCH_PLAYED`, `409 MATCH_CANCELLED`, `409 UPLOAD_IN_PROGRESS`, `409 REVIEW_IN_PROGRESS`, `409 SIBLING_LIMIT` |
| accept or decline a transfer offer | the player the offer names | `403 NOT_A_PLAYER`, `409 OPEN_MATCH_LIMIT`, `409 UPLOAD_IN_PROGRESS`, `409 REVIEW_IN_PROGRESS`, `409 TRANSFER_NOT_OFFERED` (no offer names the caller) |
| start an upload; chunks, complete, abort; quality, analysis and review routes; rule change; delete the video | the organiser, as reviewer | `403 REVIEWER_ONLY`, `409 SIDE_HAS_VIDEO`, `409 OPEN_MATCH_TOO_SMALL`, `409 QUOTA_EXCEEDED`, `507 STORAGE_FULL` |
| save the split and the roster; confirm the roster | the organiser | `403 ORGANISER_ONLY`, `409 ROSTER_STALE`, `422 ROSTER_INVALID` |
| confirm the result | the organiser | `403 ORGANISER_ONLY`, `409 LIST_NOT_CONFIRMED`, `409 MOCK_RESULT`, `409 RESULT_STALE` |
| suggest a jersey; leave ("Maçtan Ayrıl", "Bu Maçta Oynamadım") | the player; the organiser's leave frees only the seat and keeps the role | `403 NOT_A_PLAYER` |

---

## 6. Shared control of a team match

The rule: a team's statistics change only through its own captain's confirmation. Neither the other captain nor any member can change, erase or hold them back (F3). An open match has one manager, its organiser, and follows 5.8.11; this section names it where a shared rule touches it.

### 6.1 Who may do what

Each side of a team record may hold one live video (`409 SIDE_HAS_VIDEO`). The **reviewer** of a video is the captain of the side that uploaded it (`uploads.side`); for an open match it is the organiser (5.8.6).

| Action | Who | Refusals |
|---|---|---|
| see the record in lists (teams, date, states) | members of a side's team; PENDING: members of HOME | `403 NOT_A_MEMBER` |
| read content: videos, quality, analyses, rallies, cards, report, roster | both captains; members of either side's team whose `joined_at` date (UTC+3, Türkiye's fixed offset) is on or before `played_on`; anyone with a stored row of that record's roster or a line of its confirmed roster (P2). A computed default (`TEAM_DEFAULT`, 6.3) never grants access (V4-P1) | `403 NOT_A_MEMBER` |
| confirm the opponent | HOME captain, once, ACCEPTED (AWAY's acceptance is its check) | `403 CAPTAIN_ONLY` |
| turn a request into a SOLO record (`POST …/solo`) | HOME captain; PENDING, DECLINED, EXPIRED or CANCELLED; no video on the server (R1) | `403 CAPTAIN_ONLY`, `409 REQUEST_CLOSED`, `409 MATCH_HAS_CONTENT` |
| start an upload (`POST …/uploads`) | the captain of side S: ACCEPTED or SOLO, S checked the opponent or the other side left, S has no live video, S's team has at least 2 members (v5), quotas hold (6.5) | `409 MATCH_NOT_CONFIRMED`, `409 OPPONENT_NOT_CHECKED`, `409 SIDE_HAS_VIDEO`, `409 TEAM_TOO_SMALL`, `409 QUOTA_EXCEEDED`, `507 STORAGE_FULL` |
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
6. In an open match the organiser's confirmation writes the same frozen report for HOME (side A) and AWAY (side B) in one transaction, and no result offer exists (5.8.6).

Consequences:
- Team T's statistics read only T's side result. Only T's captain writes it, and only T's leave or T's deletion removes it. `match_results` has no foreign key to a job or a video, so no deletion of a video or a job, by anyone, reaches it (P1, F3).
- A captain who never confirms keeps only their own team's line out of their own statistics. The other team counts its own result (F3).
- A team that films its match reviews its own video and never depends on the other captain. A team that does not film depends on the filming team's review for its numbers, and still decides alone whether to count them. Once the filming team's reviewer confirmed the list, the filming captain can no longer take that result away: the offer stays when the video, the job or the filming side goes (R7).
- The two teams' numbers may differ: A's `pointsAgainst` may differ from B's `pointsFor`, because each team shows what its own captain confirmed. The statistics line shows the other side's state (confirmed, not confirmed, left) without blocking.
- SCORER answers for the other side's points come from the reviewer and count for the other side only after its captain confirms. The other captain can also keep points from persons by leaving jerseys off the roster.

### 6.3 Per-match rosters (owner decision 1, D1d)

- `match_rosters(match_id, side, user_id, role, jersey)`: who played for a side, as a starter (as) or a substitute (yedek), and with which jersey.
- **A team side starts from the team.** While a side's roster has never been saved (`roster_version = 0`), `GET /v1/matches/{id}/roster` answers that side with `source: "TEAM_DEFAULT"` and the team's members on the match day: the current members whose `joined_at` date (UTC+3) is on or before `played_on`, each with their team seat as `role` and their team jersey (null when the member has none). A person who is a member of both teams of the record is in neither default until a captain places them (F2). With the default 6 + 2 seats each side lists 6 starters and 2 substitutes, so the match roster of a team match is 16: 12 starters and 4 substitutes, as the owner said. A team whose match-day members hold fewer than 6 starter seats lists fewer starters, and the rest of its 6 starter places are empty (below). A SOLO record's one side starts the same way.
- **The default becomes stored rows (R3, V4-P1, F2).** The computed default never counts and never grants access: statistics and the read rule of 6.1 read only stored rows. The server writes a never-saved side's default as stored rows (version 1, unconfirmed), in the same transaction, at the first of these moments:
  - the record's first `initiate_upload` (both sides);
  - the end of the match day (23:59:59 UTC+3, by the minute sweep), or the creation of the side's row when that day has already ended (an acceptance or a SOLO record after the match);
  - the side's captain confirms version 0: the default becomes version 1 and the server confirms version 1, so a confirmed roster never follows later changes of the team;
  - a person takes themselves off version 0 ("Bu Maçta Ben Yoktum"): the default without that person becomes version 1.
  From then on, joins, leaves and jersey changes in the team leave the side unchanged. Example (R3): X plays on 22 November in jersey 7 and leaves the team on 25 November; Y joins on 26 November and takes jersey 7. The side froze at the end of 22 November with X, so X keeps the credit and Y never appears in it and gets no access through it.
- **The other team never sees a computed default.** For the other side, a `TEAM_DEFAULT` side reads as not saved, without entries and without counts, because the default would reveal the team's seat types and jerseys (4.5, F2). Stored rows show their roles and jerseys as before.
- **Each captain edits their own side per match.** `PUT /v1/matches/{id}/roster/{side} {entries: [{userId, role, jersey}], expectedVersion}` by that side's captain saves the side (`source: "SAVED"`); the app opens the editor on the default or on the stored rows, and the captain unticks who did not play, changes a role for this match (as, yedek or libero) and fills the jerseys. `POST …/roster/{side}/confirm {version}` confirms it. The other captain never edits it (7.4).
- **Roles (R6).** `role` is STARTER, SUBSTITUTE or LIBERO. The team default gives each member their team seat as role; LIBERO is a per-match role only, and a team's libero usually holds a substitute seat. FIVB 7.3.3 makes the players outside a set's starting line-up its substitutes, liberos excepted, and 19.1.1 allows a team at most two liberos, so a libero counts toward the 12 entries and not toward the 6 starters.
- **Six starter places (v5, R3).** The owner, 18 November: "Sahada 6 kişi olmak zorunda." Every side of every record (a team side, a SOLO side, an open match side) has exactly 6 starter places. A STARTER entry fills one place; a place without one stays empty, and every roster screen shows it as "Boş" (`roster_place_empty`), the other team's view included. The server never invents a person for an empty place: no row, no user id, no jersey and no placeholder name exist for it, and the number of empty places is derived (6 − STARTER entries) and never stored. An empty place says only that the roster names nobody for it. The app cannot know why: a starter may have been missing, or a person without an account may have played there, and `roster_empty_place_body` says exactly that much. When a side has an empty starter place and a substitute, the editor shows `roster_empty_place_hint`, because a substitute who started the match belongs on a starter place; the captain or the organiser decides, and the server forces nothing. A confirmation with empty places is allowed. A team's default roster has empty places when fewer than 6 of the match-day members hold starter seats. 7.1 says what an empty place means for the statistics.
- Rules (`422 ROSTER_INVALID`, `details.reason`): per side at most 6 starters, one per starter place (`TOO_MANY_STARTERS`, FIVB 7.3.1), at most 6 substitutes and liberos together (`TOO_MANY_SUBSTITUTES`, v5), so at most 12 entries (FIVB 4.1.1; the 14 of FIVB, World and official senior competitions is not used, 16.1 item 22), and at most 2 liberos (`TOO_MANY_LIBEROS`, FIVB 19.1.1); a jersey is 1-99 or null and unique per side when set (`JERSEY_RANGE`, `DUPLICATE_JERSEY`); a person appears once per record (`DUPLICATE_PERSON`); the person is a current member of that side's team or already holds a stored row of that side, from the freeze above or an earlier save (`NOT_A_TEAM_MEMBER`, R3); a person who is a member of both teams appears on one side only (`ON_OTHER_SIDE`). A confirmation needs a jersey on every entry (`JERSEY_MISSING`, `details.count`), because a point reaches a person only through a jersey (7.1). Every confirmation, also one of version 0 that first writes the default or the proposal as rows, checks the rows it confirms against all of these rules (S1 of the v5 checkers). v4's `TOO_MANY` (more than 12 entries) can no longer occur and is gone.
- Jerseys: FIVB 4.3.3 numbers shirts 1-20. The app keeps 1-99, as the review's SCORER cards do (`review.py` line 32), because amateur teams wear other numbers.
- **The confirmed roster is a snapshot (V4-S3).** Every change moves `match_sides.roster_version`; a confirmation names the version it confirms (`409 ROSTER_STALE`) and copies that version's rows into `match_roster_confirmed` (8.2), replacing the side's earlier snapshot. Statistics read only the snapshot. A later save or a removal touches the working rows only, and a seat move or a team change touches no stored row at all (2.3, the freeze above): the snapshot keeps counting, and the side shows `changedSinceConfirmed: true` until its captain, or the organiser, confirms again. In v4's first text an edit lapsed the confirmation, so one save after a confirmed result put every person of the side back to ROSTER_WAITING, and in an open match any account could hold back every player's numbers that way. A new confirmation still replaces the snapshot, so the captain or the organiser can correct the counted roster, and a person dropped by it loses that line.
- `POST /v1/matches/{id}/roster/leave` ("Bu Maçta Ben Yoktum") lets a person take themselves off. A self-removal deletes the person's working row and their line of the snapshot in one transaction, moves the version, and keeps the side's confirmation for everyone else: when the side was confirmed at the version before, the server sets `roster_confirmed_version` to the new version. Removing oneself can only remove a credit, so nobody else needs a new confirmation. In v3 it lapsed the whole side until the captain confirmed again. An open match's "Bu Maçta Oynamadım" follows the same rule (5.8.6).
- **Open matches** use the same tables. The organiser writes both sides with one route and confirms them together; both sides keep one version (5.8.5).
- The other side sees a stored roster's roles and jerseys, never its names or user ids, and never a computed default (9.3).
- **The review's player list (R1).** For a MatchRecord with `teamMatchId`, the review chain's SCORER card and the Players screen read `GET /v1/matches/{id}/roster` when the review opens: the own side with display names and jerseys, the other side with role and jersey. Because the first upload writes both sides as stored rows, both sides have entries by the time a review starts. A winning side without any jersey shows `review_no_roster_server` with a button to the own side's roster editor (10.8); the other side's jerseys come only from its captain. Today's app takes the card's jerseys from local rosters (`VolleyStatApp.kt` line 218, `Review.kt` lines 224-236, `Report.kt` line 403), which a team or open match recording does not have, so without this every SCORER answer would be "Oyuncuyu bilmiyorum" and every person's points UNKNOWN.
- **A jersey outside the roster (v5, S2 of the v5 checkers).** Every SCORER card that reads the server roster (a team match or an open match) also offers `review_other_jersey` ("Kadroda Olmayan Numara") with a number field of 1-99 that refuses the jerseys of that side's roster (`review_other_jersey_taken`). It sends `JERSEY:n`, which the server already accepts (`review.py` line 32), so the server does not change. A person without an account who played, in an empty starter place or as a substitute, then keeps the point on their jersey in the report instead of in `unattributed`, and the named players' zeros in that part stay MEASURED (7.1, 7.3). The answer appears on every such card, not only on a side with an empty starter place, because the app cannot know who came on as a substitute.
- **Libero warning (R6, rule 19.3.1.3).** When a SCORER answer names a jersey whose entry is a LIBERO and the rally's point type is BLOCK or ACE, the card shows `review_libero_warning`; the answer stays the reviewer's choice.
- One roster serves every played match of the video (ADR-0118). Players changing between played matches in one video is a known limit. The roles say who was listed as a starter, a substitute or a libero; the video does not show rotations, so the statistics count roles only as `asStarter`, `asSubstitute` and `asLibero` (7.3).

### 6.4 Leaving and deleting (P1, F3)

- `POST /v1/matches/{id}/leave` by S's captain, in one transaction: S's videos (`uploads.side = S`) with their jobs (cascade) and bytes (deleted before the commit), S's roster rows, S's confirmed roster and S's side result go; `match_sides.team_id = NULL`, `left_at = now`. S's members lose access at once.
- The other side keeps its own videos and its own side result, including a side result that came from S's job, because the snapshot is self-contained. It also keeps the result offer from S's last list confirmation, which its captain can still confirm (R7). The other side never inherits S's video (P1).
- AWAY's cancel on an ACCEPTED record runs this leave for AWAY (R1).
- When no side remains, the server deletes the record exactly like `delete_match`, bytes before the commit, then `empty_write_ahead_log()`, and answers `204`. While one side remains, the answer is `200` with the record. A SOLO record is deleted at once.
- `DELETE /v1/videos/{id}` by its reviewer works at any time; v2's `RESULT_COUNTED` refusal is gone, because no side result depends on a video. It also blanks the upload row: `file_name = 'deleted'` and `client_sha256 = NULL`.
- No step waits for the other captain, so nothing can get stuck.
- A person seen in a video of a team that left can ask the operator for removal (`python -m app.admin delete-match <id>`) until the KVKK review defines a process. `privacy_delete` says so.
- The byte-search test covers video deletion, leave on each side, the one-sided case, team deletion and an open match's deletion. An open match has its own leave and deletion rules (5.8.4, 5.8.10).

### 6.5 Storage quotas and abandoned uploads (P3, owner decision 16.1 item 4)

The server is the owner's own computer, and today's code accepts uploads up to 4 GiB (`MAX_VIDEO_BYTES`, `main.py` line 30) with no quota and no cleanup of abandoned uploads.

| Limit | Default | Checked in | Answer |
|---|---|---|---|
| personal video per account: live personal videos plus unfinished personal uploads at their declared size | 8 GiB for an account opened with an operator code (or in `open` mode); 2 GiB and at most one unfinished personal upload for an account opened through a join link, a team invite or an open match link (S7) | `initiate_upload` | `409 QUOTA_EXCEEDED` (`details.usedBytes`, `details.limitBytes`, `details.scope: PERSONAL`; `details.reason: UNFINISHED_UPLOAD` for a second unfinished upload) |
| open-match video per uploader: live open-match videos and unfinished open-match uploads that the account started (`uploads.started_by`, 8.3), wherever the organiser role is now (5.8.9, V4-Q1) | 8 GiB for an account opened with an operator code (or in `open` mode); 4 GiB, one maximum-size video, for an account opened through a join link; at most one unfinished open-match upload | `initiate_upload` | same with `scope: OPEN_MATCH` |
| an open match with fewer than 2 players | no upload | `initiate_upload` | `409 OPEN_MATCH_TOO_SMALL` (`details.players`) |
| personal video on the whole server: every account's personal videos plus their unfinished uploads | 40 GiB (S7, V4-Q1) | `initiate_upload` | same with `scope: SERVER`, `details.pool: PERSONAL` |
| open-match video on the whole server: every open match's live video plus unfinished open-match uploads, also those whose uploader deleted their account | 60 GiB (V4-Q1) | `initiate_upload` | same with `scope: SERVER`, `details.pool: OPEN_MATCH` |
| team video per team: live videos plus unfinished uploads of the team's sides | 40 GiB | `initiate_upload` | same with `scope: TEAM` |
| team video per uploader: live team videos and unfinished team uploads that the account started, across every team, wherever the captaincy is now (v5, R6) | 40 GiB for an account opened with an operator code (or in `open` mode); 16 GiB, four maximum-size videos, for an account opened through a join link | `initiate_upload` | same with `scope: TEAM_UPLOADER` |
| team video on the whole server: every team side's live videos plus unfinished team uploads, also those whose uploader deleted their account (v5) | 200 GiB | `initiate_upload` | same with `scope: SERVER`, `details.pool: TEAM` |
| a team side whose team has fewer than 2 members (v5) | no upload | `initiate_upload` | `409 TEAM_TOO_SMALL` (`details.members`) |
| free-disk floor: free space on the storage disk minus the declared size | 20 GiB | `initiate_upload`; `store_chunk` refuses below half the floor | `507 STORAGE_FULL` |
| abandoned uploads: an UPLOADING row whose `touched_at` is 24 h old | 24 h | minute sweep | marked ABORTED, bytes deleted before the commit |

`touched_at` moves with `initiate_upload` and every stored chunk, in the statement that already moves `next_offset`. `UploadEngine.openOrResume` already starts a new upload when the old one is ABORTED or missing, so a phone that was off for a day resumes from zero without any app change. Texts are in 10.8. A team that reaches its quota deletes old videos; its statistics stay, because side results do not depend on videos. An organiser's transfer never moves bytes: they stay on the account that started the upload until the video goes, so handing a match on and uploading again frees nothing (V4-Q1). v4's first text moved them, so one account could upload, hand the match over and upload again without limit.

One leaked invite opens up to 200 accounts. With v3's 8 GiB each that allowed about 1.6 TiB of personal video, and once the free-disk floor was reached every team's upload would answer `507 STORAGE_FULL` until the operator cleaned up. The server-wide pools of non-team video keep the team uploads their space, a member who joined through an invite has little need for personal video before the KVKK review, and `python -m app.admin storage --top` lists the largest personal, open-match and team uploaders for the operator (S7). An open match's video belongs to no team. It counts in its own server-wide pool, so abuse of personal video cannot block open matches and the other way round; the per-uploader limit follows `signup_via` like the personal one; an open match needs 2 players before it takes a video, so it is no private store; and since v5 every account may open open matches (R6), which the per-uploader limit and the pool already assumed. With 4 GiB per link account, 15 such accounts are needed to fill the open-match pool, where v4's first text needed 10 accounts to fill the whole non-team pool. Tests: 20 accounts created through one invite fill the personal pool, and an open-match upload and a team upload still succeed; 15 link-opened accounts, each organising an open match of 2 players, fill the open-match pool, and a personal and a team upload still succeed; an account that uploads 4 GiB, hands the match on and tries again gets `409 QUOTA_EXCEEDED` (`OPEN_MATCH`); an open match with 1 player answers `409 OPEN_MATCH_TOO_SMALL`.

**Teams after the gate (v5, R6).** Under v4 the team-video quota was 40 GiB per team, and only the owner's teams could exist before the review, so team video had a natural bound. With every account able to captain 3 teams and record one-sided matches alone, team video gets three more rules. A team side takes a video only while its team has at least 2 members (`409 TEAM_TOO_SMALL`). The rule keeps an honest one-person team from using team video as personal storage, where the same account holds 2 GiB of personal video. It is no abuse bound: the captain's own invite opens a second account in a minute (C2 of the v5 checkers). Team video also counts on the account that started the upload (`uploads.started_by`), never moved by a captaincy transfer, so creating more teams adds no space: 40 GiB for an account opened with an operator code and 16 GiB (four maximum-size videos) for one opened through a link. And a server-wide team pool of 200 GiB keeps the free-disk floor from being the only stop, because the floor refuses every kind of upload at once. 200 GiB holds five teams at their full 40 GiB; with the personal pool (40 GiB) and the open-match pool (60 GiB) the server keeps at most 300 GiB of video beside the 20 GiB floor, and on a smaller disk the floor stops uploads first. Under the per-team limit alone one link-opened account could have filled 120 GiB with three teams.

**One person can fill a pool alone (C2 of the v5 checkers).** With a team of their own and its invite, one person opens the link accounts a pool needs within minutes: about 20 fill the personal pool, 15 that organise open matches of 2 players fill the open-match pool, and 13 captains of 2-member teams, whose second members come from the same invite, fill the team pool. Every account's uploads of that kind then stop until the operator cleans up. The other kinds and the free-disk floor stay unaffected, which is what the pools are for. A per-creator storage budget, which the checkers proposed, would not prevent it: every link account can create its own team and invite and would bring a fresh budget, so five creators with four accounts each would fill the personal pool, while a real group would share one budget among everyone its captain invited. v5 therefore keeps the pools, says this plainly (0.4, 16.2, ADR-0120 with B7) and gives the operator these commands, which B7 builds; `storage --top` comes with B3's quotas, and B7 adds its two columns:
- `storage --top` lists the largest uploaders of each pool with how each account opened (`signup_via`) and the teams it captains;
- `signups <username>` lists, from `link_signups` (3.3, 7 days), the account whose join link opened this account and the accounts that this account's join links opened;
- `close-links <username>` closes every live join link the account created with reason CLOSED, and T14 closes their open attempts;
- `reset-password <username>` (B1) ends every session of the account; while the operator keeps the temporary password, nobody can log in to it, and after 7 days the temporary password stops working too;
- `delete-match <id>` deletes a record like `delete_match`, bytes before the commit (6.4).

The deletion of an account through the app (3.7) stays the only way an account goes; the operator locks it instead. The numbers are owner numbers (16.1 items 4 and 33). Tests: a team with 1 member answers `409 TEAM_TOO_SMALL` on a SOLO upload; a link-opened account that uploaded 16 GiB to one team gets `409 QUOTA_EXCEEDED` (`TEAM_UPLOADER`) on an upload to its second team; a captaincy transfer moves no bytes; a full team pool refuses a team upload with `SERVER`/`TEAM` while a personal and an open-match upload still succeed.

---

## 7. Statistics

### 7.1 Principles

- Only user-verified decisions count; a model proposal never counts. Demo jobs never count.
- Unknown is `{"value": null, "status": "UNKNOWN"}`, never 0. A positive lower bound is PARTIAL. MEASURED means complete. Every total uses `report._value`.
- A point reaches a person only through a SCORER decision (side and jersey) plus that side's confirmed roster (the snapshot of 6.3) holding the person with that jersey.
- **Empty starter places (v5, R3).** An empty starter place (6.3) is no person. It has no line, no jersey and no number, and it never adds a 0 to anyone's total. A point the reviewer could not give to a named jersey of the side (Oyuncuyu bilmiyorum) stays in `unattributed.<side>` of the report; while that is so, the part's person counts are incomplete, and a rostered person without a `players[]` line in that part gets UNKNOWN, never 0 (7.3). A point the reviewer gave to a jersey that no roster entry carries (`review_other_jersey`, 6.3) stays on that jersey in the report, never enters `unattributed`, and reaches a person only if a later confirmed roster gives that jersey to someone; the named players' zeros in that part stay MEASURED (S2 of the v5 checkers). Team numbers come from the side result, not from the roster, so empty places change no team number. A person who played without being named (for example without an account) gets no statistics, and nobody else gets theirs.
- A team's numbers come only from its own side results (6.2). An open match feeds no team's numbers, only persons' (5.8.7).
- No ranking, no composite score, no prediction, no percentage without its counts.
- `app/stats.py` is a pure function over side results and rosters under a thin route.

### 7.2 Scope and record states

**Team T:** ACCEPTED and SOLO records with a side whose `team_id = T`. An open match never has a side with a team, so it is never in a team's scope (5.8.7). **Person U:** every ACCEPTED, SOLO and open match record where U has a stored roster row or a line of the confirmed roster, never a computed default (V4-P1); optional `teamId` (team records of that team only) or `scope=open` (open matches only).

| Record state for team T | Condition | Counted for the team | Counted for persons |
|---|---|---|---|
| `COUNTED` | T's side result exists | yes, from T's side result | yes, where the person has a line in the own side's confirmed roster; `changedSinceConfirmed` shows when the working roster moved since |
| `COUNTED`, `changedSinceCounted: true` | the job the side result came from still exists and its revision moved | yes, the side result | same |
| `NOT_CONFIRMED` | no side result for T, and a job of the record has a confirmed list or the record holds a result offer for T | no | no |
| `IN_REVIEW` | a live video exists, no job has a confirmed list | no | no |
| `NO_VIDEO` | no live video and no side result for T | no | no |
| `ROSTER_WAITING` | person view only: the person has a stored row of the own side but no line in its confirmed roster (never confirmed, or added after the confirmation) | | no |

A person's line of an open match takes the same states, read from the side result of the person's side (the organiser's one confirmation writes both sides) and from that side's confirmed roster: `NOT_CONFIRMED` while the organiser has not confirmed a result, `ROSTER_WAITING` while the person has no line in the confirmed roster, `COUNTED` when both hold. "Counted for the team" does not apply.

Each line also carries `otherSide: CONFIRMED | NOT_CONFIRMED | LEFT | NONE` (NONE for SOLO records and open matches), which the app shows and which never changes T's numbers.

Totals are complete when every record of T with a live video is COUNTED for T. A record without a video does not make a total partial, because the totals describe filmed and confirmed matches, and the field names say so.

### 7.3 Fields (v2, kept; person fields renamed in v4)

**Team** (per part of the side result, then summed), reported twice: `inMatches` (parts of kind MATCH) and `outsideMatches` (AFTER_MATCH and UNFINISHED; a STOPPED part cannot occur in a confirmed list):

| Field | From |
|---|---|
| `countedMatches`, `won`, `lost` | MATCH parts and their `winner` (inMatches only) |
| `setsWon`, `setsLost` | the part's `setsWon` |
| `pointsFor`, `pointsAgainst` | the part's `teams.<own>.points` and `teams.<other>.points` |
| `attackPoints`, `blockPoints`, `aces`, `opponentErrors` | the part's `teams.<own>.*`; complete only when the part's field is MEASURED |

**Person**: `rosteredRecords` (records with a line of the person in a confirmed roster; a plain count, not "matches played", because the video does not show who was on court), `asStarter`, `asSubstitute` and `asLibero` (how many of those records listed the person in each role, 6.3, R6), `sideCountedMatches`, `sideWon`, `sideLost` ("your side, while you were on the roster"; v3 called them `teamCountedMatches`, `teamWon` and `teamLost`, but an open match has sides, not teams), and `points`, `attackPoints`, `blockPoints`, `aces` from the own `players[]` line of the own side result. A person's count in a part is complete when the part's `teams.<side>.attackPoints` is MEASURED and `unattributed.<side>` equals `teams.<side>.opponentErrors.value`. A rostered person without a `players[]` line in a complete part scored 0, MEASURED. In an incomplete part that 0 becomes UNKNOWN. Team records and open matches add up in one total; each line says its kind (`TEAM_MATCH`, `SOLO` or `OPEN_MATCH`), so the app can also show them apart.

**Not counted:** personal and legacy matches, demo jobs, unconfirmed results, the other side's result, opponent errors per person, serves, receptions, digs, assists, rotations, time on court, win rates, rankings, and any team number from an open match.

Every statistics answer carries `records` (accepted, withVideo, counted, notConfirmed, inReview, withoutVideo; for a person also `openMatches`) and `lines[]`, one per record with its kind, state, `otherSide`, the person's role and per-part values, so the app links each line to its report. Each line also carries the own side's `emptyStarterPlaces` from the confirmed roster (v5, R3), which the app shows next to the line (`stats_empty_places_team` on a team line, `stats_empty_places` on a person line) and which changes no number.

### 7.4 What one captain can and cannot do to the other team's numbers

| Action of captain A | Effect on team B's statistics |
|---|---|
| edits, reopens, corrections, re-analysis, rule change on A's video | none; B's side result stays; B's line may show `changedSinceCounted` if it came from A's job |
| deletes A's video, leaves the match, deletes team A, deletes the account | none on a result B already confirmed. If B has not confirmed yet, B can still confirm the result offer that A's last list confirmation stored for B (6.2, R7). Only a team whose list A never confirmed has nothing to confirm. |
| never confirms, never reviews, never uploads | none on B's own video and result; if only A filmed, B has nothing to confirm until A's list is confirmed |
| edits B's roster or B's side result | impossible: `403 CAPTAIN_ONLY` |
| declines B's request, or never answers it (EXPIRED after 7 days) | B's recording waits on B's phone; B taps Yalnız Takımım İçin Kaydet, which turns the record into a SOLO record that counts for B (5.4), or sends a new request (R1) |
| as AWAY, cancels an ACCEPTED record | the cancel is A's leave; the record stays for B, and B uploads without an opponent check (5.3, R1) |
| accepts B's request link that leaked to A | any account can lead a team since v5 (R6), so A can be anyone's team. B's own upload waits until B confirms the opponent (5.3); B taps Rakibim Değil, which leaves the record and moves B's recording to a SOLO record; A keeps only A's own video (R1) |

The rows on declining, cancelling and a leaked link rest on SOLO records (owner decision 16.1 item 7, default yes). If the owner answers no, a requesting team's recording counts only through a request that the other captain accepts (16.2).

No captain's or organiser's action on an open match reaches any team's statistics, because an open match has no team side (5.8.7).

---

## 8. Data model

### 8.1 Rules from `backend/app/db.py` (v2, kept)

- New tables go in `SCHEMA` with `CREATE TABLE IF NOT EXISTS`. Columns added to existing tables go both in `SCHEMA` and in `ADDED_COLUMNS`; a NOT NULL one needs a DEFAULT.
- No comment inside a `CREATE TABLE` contains a comma (db.py lines 15-17).
- Columns added to existing tables carry no `REFERENCES`, `UNIQUE` or index that a rebuild test would have to drop. The migration test drops the new indexes first and the new columns second. The code keeps their integrity in the same transaction.
- The append-only tables (`rally_proposals`, `review_decisions`, `rally_corrections`) get no column, index or trigger change.
- Every connection keeps `foreign_keys=ON` and `secure_delete=ON`. Every deletion ends with `empty_write_ahead_log()`.
- New in v3: every write transaction goes through the FIFO ticket lock inside `connector()` (2.8).
- New in v4: one view, `pool_holders`, created with `CREATE VIEW IF NOT EXISTS` in `SCHEMA` after the tables it reads; the migration test drops it before those tables (8.6). It adds no column to an existing table.

### 8.2 New tables

v4 changes against v3: `teams` loses `capacity`, `queue_seq` and `queue_version`, which move to the new `seat_pools`; `memberships` gains `seat`; the view `pool_holders` is new; `team_blocks` becomes `pool_blocks`; `share_links` gains `pool_id` and the kind OPEN_MATCH; `join_attempts` keys on `pool_id` and gains `seat`; `open_matches` and `open_match_players` are new; `match_rosters` gains `role`, keys on the person and lets the jersey wait until the confirmation. After the v4 checkers: `memberships.seat` loses its default and both seat columns get a CHECK (V4-C2); `open_matches.organiser_user_id` becomes nullable with `ON DELETE SET NULL` and gains the transfer offer (V4-C1); `open_match_players` gains `proposed_side` (R2); `match_rosters.role` allows LIBERO (R6); `match_roster_confirmed` is new (V4-S3). No comment inside a `CREATE TABLE` holds a comma (8.1).

v5 changes against v4: `users.signup_via` loses CAPTAIN_CODE; `operator_codes` keeps B1's three reserved columns unused; `seat_pools` gains a CHECK that fixes the starter count per kind (6 or 12) and keeps the substitute count at 0 or more, R1, R2, while the engine and the creation routes enforce its upper bound (0-6 or 0-12), so the owner's item 22 needs no table rebuild (S9 of the v5 checkers); `share_links` gains `created_by` with its index, cleared when the link closes; `link_signups` is new and holds the creator's sign-up limit, so deleting a team or an open match cannot reset it (3.3, C1 of the v5 checkers); `join_attempts` loses the reason MEMBERS_ONLY. Nothing is stored for an empty starter place (6.3, R3) or for the entry choice (8.5, R5).

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
  -- How the account opened: INVITE or OPEN_MATCH or SIGNUP_CODE or OPEN. It sets the per-account video quotas.
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

-- One-time codes the operator prints (as B1 built it). Since v5 only SIGNUP codes exist.
CREATE TABLE IF NOT EXISTS operator_codes (
  -- SHA-256 hex of the code
  code_hash TEXT PRIMARY KEY,
  -- SIGNUP opens one registration. B1 reserved CAPTAIN for v4's captain code and v5 removed it.
  kind TEXT NOT NULL,
  -- Unused since v5. B1 created it for the captain code and older storage folders keep it.
  signup_used INTEGER NOT NULL DEFAULT 0,
  -- Unused since v5 (as above)
  used_by_user_id TEXT REFERENCES users(id) ON DELETE CASCADE,
  -- Unused since v5 (as above)
  team_name TEXT,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS teams (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  -- Four characters of the code alphabet that the server draws at random
  tag TEXT NOT NULL UNIQUE,
  -- 1 for Monday to 7 for Sunday or NULL
  match_weekday INTEGER,
  -- HH:MM or NULL
  match_time TEXT,
  -- Lossy WebP of 256 px for members or NULL
  logo BLOB,
  -- Lossy WebP of 128 px for link views or NULL
  logo_small BLOB,
  logo_version INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS memberships (
  team_id TEXT NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  -- CAPTAIN or MEMBER
  role TEXT NOT NULL DEFAULT 'MEMBER',
  -- STARTER or SUBSTITUTE: the seat this member holds in the team's pool. No default: every insert names it.
  seat TEXT NOT NULL CHECK (seat IN ('STARTER', 'SUBSTITUTE')),
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

-- An open match: a match record that any account opens and that people join through its link (5.8)
CREATE TABLE IF NOT EXISTS open_matches (
  match_id TEXT PRIMARY KEY REFERENCES matches(id) ON DELETE CASCADE,
  -- NULL once the organiser deleted their account. The match then has no organiser (3.7).
  organiser_user_id TEXT REFERENCES users(id) ON DELETE SET NULL,
  -- The player the organiser offered the role to or NULL. The player accepts or declines (5.8.4).
  transfer_to_user_id TEXT REFERENCES users(id) ON DELETE SET NULL,
  transfer_offered_at TEXT,
  created_at TEXT NOT NULL,
  -- When the organiser cancelled it. The minute sweep deletes it 30 days later.
  cancelled_at TEXT
);
CREATE INDEX IF NOT EXISTS open_matches_organiser ON open_matches(organiser_user_id);

-- The players of an open match: the holders of its pool
CREATE TABLE IF NOT EXISTS open_match_players (
  match_id TEXT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  -- STARTER or SUBSTITUTE
  seat TEXT NOT NULL CHECK (seat IN ('STARTER', 'SUBSTITUTE')),
  -- The jersey the player suggests. The organiser writes the roster.
  jersey INTEGER,
  -- A or B: the side the server proposed when the player joined. It never moves when others leave.
  proposed_side TEXT NOT NULL CHECK (proposed_side IN ('A', 'B')),
  joined_at TEXT NOT NULL,
  PRIMARY KEY (match_id, user_id)
);
CREATE INDEX IF NOT EXISTS open_match_players_user ON open_match_players(user_id);

-- The unit of the join engine: one per team and one per open match (2.1)
CREATE TABLE IF NOT EXISTS seat_pools (
  id TEXT PRIMARY KEY,
  -- TEAM or OPEN_MATCH
  kind TEXT NOT NULL,
  -- The team of a TEAM pool and NULL otherwise
  team_id TEXT UNIQUE REFERENCES teams(id) ON DELETE CASCADE,
  -- The open match record of an OPEN_MATCH pool and NULL otherwise
  match_id TEXT UNIQUE REFERENCES matches(id) ON DELETE CASCADE,
  -- Seats of each type with the captain or a playing organiser counted (2.3)
  starter_seats INTEGER NOT NULL,
  substitute_seats INTEGER NOT NULL,
  -- The queue number the next claim takes. Only a write transaction moves it.
  queue_seq INTEGER NOT NULL DEFAULT 0,
  -- Moves by one in every write transaction that changes the join state of the pool. The snapshot carries it.
  queue_version INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  CHECK ((kind = 'TEAM' AND team_id IS NOT NULL AND match_id IS NULL)
      OR (kind = 'OPEN_MATCH' AND match_id IS NOT NULL AND team_id IS NULL)),
  -- v5: six on the court. A team always has 6 starter seats and an open match 12 (R1 and R2).
  -- The upper bound of the substitute seats lives in the seats engine and the creation routes (2.3).
  CHECK ((kind = 'TEAM' AND starter_seats = 6) OR (kind = 'OPEN_MATCH' AND starter_seats = 12)),
  CHECK (substitute_seats >= 0)
);

-- Every holder of every pool with its seat type. settle and the snapshot count holders only through this view.
CREATE VIEW IF NOT EXISTS pool_holders AS
  SELECT p.id AS pool_id, m.user_id AS user_id, m.seat AS seat, m.joined_at AS joined_at
    FROM seat_pools p JOIN memberships m ON m.team_id = p.team_id
  UNION ALL
  SELECT p.id, o.user_id, o.seat, o.joined_at
    FROM seat_pools p JOIN open_match_players o ON o.match_id = p.match_id;

-- People a captain or an organiser removed. They cannot claim a seat of this pool until the block goes.
CREATE TABLE IF NOT EXISTS pool_blocks (
  pool_id TEXT NOT NULL REFERENCES seat_pools(id) ON DELETE CASCADE,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  PRIMARY KEY (pool_id, user_id)
);

-- A link a captain or an organiser shares: a team invite or an open match link or a match request.
-- A closed link stays 30 days as a tombstone.
CREATE TABLE IF NOT EXISTS share_links (
  -- SHA-256 hex of the token. The token itself is never stored.
  token_hash TEXT PRIMARY KEY,
  -- HMAC-SHA256 hex of the normalized code under the link key. NULL once closed.
  code_hmac TEXT UNIQUE,
  -- The same HMAC once the link closed. Kept for the 30 days of the tombstone so a code can say why it no longer works.
  closed_code_hmac TEXT,
  -- TEAM_INVITE or OPEN_MATCH or MATCH_REQUEST
  kind TEXT NOT NULL,
  -- The pool a TEAM_INVITE or OPEN_MATCH link leads into. NULL for MATCH_REQUEST.
  pool_id TEXT REFERENCES seat_pools(id) ON DELETE CASCADE,
  -- The team that invites or that requests the match. NULL for OPEN_MATCH.
  team_id TEXT REFERENCES teams(id) ON DELETE CASCADE,
  -- The requested record of a MATCH_REQUEST or the record of an OPEN_MATCH. NULL for TEAM_INVITE.
  match_id TEXT REFERENCES matches(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  -- Server clock in seconds since 1970
  expires_at REAL NOT NULL,
  -- Registrations this link opened
  signups INTEGER NOT NULL DEFAULT 0,
  -- The account that created the link (v5). A registration through the link writes a link_signups row for it (3.3). NULL once the link closes or that account is deleted.
  created_by TEXT REFERENCES users(id) ON DELETE SET NULL,
  -- For a renewed or closed link or a cancelled open match the arrival of the request that closed it. Otherwise the time it closed.
  closed_at REAL,
  -- EXPIRED or RENEWED or CLOSED or FULL or USED or DECLINED or CANCELLED or PLAYED or ORGANISER_GONE
  closed_reason TEXT
);
CREATE INDEX IF NOT EXISTS share_links_creator ON share_links(created_by) WHERE created_by IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS share_links_one_join ON share_links(pool_id)
  WHERE pool_id IS NOT NULL AND closed_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS share_links_one_request ON share_links(match_id)
  WHERE kind = 'MATCH_REQUEST' AND closed_at IS NULL;
CREATE INDEX IF NOT EXISTS share_links_live_expiry ON share_links(expires_at)
  WHERE pool_id IS NOT NULL AND closed_at IS NULL;
CREATE INDEX IF NOT EXISTS share_links_closed ON share_links(closed_at) WHERE closed_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS share_links_closed_code ON share_links(closed_code_hmac) WHERE closed_code_hmac IS NOT NULL;

-- One row per registration through a join link (v5 and C1 of the v5 checkers). The creator's sign-up limit counts these rows (3.3).
-- Deleting a team or an open match leaves them. The minute sweep deletes a row 7 days after its time.
CREATE TABLE IF NOT EXISTS link_signups (
  -- The account that created the link. The row goes with that account.
  creator_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  -- The account the registration opened. NULL once that account is deleted. The operator command signups reads it (6.5).
  user_id TEXT REFERENCES users(id) ON DELETE SET NULL,
  -- Server clock in seconds since 1970
  at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS link_signups_creator ON link_signups(creator_id, at);
CREATE INDEX IF NOT EXISTS link_signups_user ON link_signups(user_id) WHERE user_id IS NOT NULL;

-- One person's try to get a seat of one pool through its join link (docs/API_V1.md section 10)
CREATE TABLE IF NOT EXISTS join_attempts (
  id TEXT PRIMARY KEY,
  pool_id TEXT NOT NULL REFERENCES seat_pools(id) ON DELETE CASCADE,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  -- WAITING or OFFERED or HOLDING while open. JOINED or REJECTED or EXPIRED or SKIPPED or DROPPED or LEFT or CLOSED once closed.
  state TEXT NOT NULL,
  -- NO_DECISION or TIME_UP for EXPIRED. FULL or LINK_RENEWED or LINK_CLOSED or LINK_EXPIRED or MATCH_CANCELLED
  -- or MATCH_PLAYED or ORGANISER_GONE or LIMIT for CLOSED.
  reason TEXT,
  -- STARTER or SUBSTITUTE from the moment the attempt is OFFERED or HOLDING. It never changes after that. NULL while WAITING.
  seat TEXT,
  -- Place in the queue of the pool in the order the claims reached the server
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
CREATE UNIQUE INDEX IF NOT EXISTS join_attempts_one_open ON join_attempts(pool_id, user_id)
  WHERE state IN ('WAITING', 'OFFERED', 'HOLDING');
CREATE INDEX IF NOT EXISTS join_attempts_queue ON join_attempts(pool_id, state, queue_no);
CREATE INDEX IF NOT EXISTS join_attempts_due ON join_attempts(due_at) WHERE due_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS join_attempts_user ON join_attempts(user_id, state);

-- The sides of a team match record or an open match. A side that left or whose team was deleted keeps its row
-- with team_id NULL. An open match has a HOME row for side A and an AWAY row for side B and both keep team_id NULL.
CREATE TABLE IF NOT EXISTS match_sides (
  match_id TEXT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
  -- HOME for the team that requested and AWAY for the team that accepted. Side A and side B in an open match.
  side TEXT NOT NULL,
  team_id TEXT REFERENCES teams(id) ON DELETE SET NULL,
  -- When this side's captain confirmed the opponent. Accepting counts for AWAY. NULL in an open match.
  opponent_checked_at TEXT,
  -- Moves with every change of this side's roster. Both sides of an open match move together.
  roster_version INTEGER NOT NULL DEFAULT 0,
  -- The roster_version the side's captain or the organiser confirmed or NULL
  roster_confirmed_version INTEGER,
  roster_confirmed_at TEXT,
  left_at TEXT,
  PRIMARY KEY (match_id, side)
);
CREATE INDEX IF NOT EXISTS match_sides_team ON match_sides(team_id);

-- Who played for one side of one record with role and jersey. The side's captain or the organiser writes it.
CREATE TABLE IF NOT EXISTS match_rosters (
  match_id TEXT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
  -- HOME or AWAY. In an open match HOME is side A and AWAY is side B.
  side TEXT NOT NULL,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  -- STARTER or SUBSTITUTE or LIBERO in this match
  role TEXT NOT NULL CHECK (role IN ('STARTER', 'SUBSTITUTE', 'LIBERO')),
  -- 1 to 99. NULL is allowed until the roster is confirmed.
  jersey INTEGER,
  PRIMARY KEY (match_id, user_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS match_rosters_jersey ON match_rosters(match_id, side, jersey)
  WHERE jersey IS NOT NULL;

-- The confirmed roster of one side: the rows of the version its captain or the organiser confirmed last (6.3).
-- Statistics read only this. A save leaves it. A confirmation replaces it. A self-removal deletes one line.
CREATE TABLE IF NOT EXISTS match_roster_confirmed (
  match_id TEXT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
  side TEXT NOT NULL,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role TEXT NOT NULL CHECK (role IN ('STARTER', 'SUBSTITUTE', 'LIBERO')),
  -- 1 to 99. Never NULL: a confirmation needs a jersey on every entry.
  jersey INTEGER NOT NULL,
  -- The roster_version this line was confirmed at
  version INTEGER NOT NULL,
  PRIMARY KEY (match_id, user_id)
);
CREATE INDEX IF NOT EXISTS match_roster_confirmed_user ON match_roster_confirmed(user_id);

-- One side's confirmed result: the report of one job at one revision. That side's team statistics read only this row.
-- In an open match the organiser's one confirmation writes the same report for both sides.
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
-- An open match has no offers.
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

Why a view and not a copied holder table: a team member and an open match player carry different data (role and team jersey; suggested jersey), and both tables already exist for their own screens. A separate holder table would duplicate every membership and need to stay in step with it. The view keeps one source of truth, and the engine still counts both kinds with one query (`SELECT seat, COUNT(*) FROM pool_holders WHERE pool_id = ? GROUP BY seat`), which the primary keys of `memberships` and `open_match_players` serve.

### 8.3 New columns on existing tables

In `SCHEMA` and in `ADDED_COLUMNS`, none with a foreign key:

| Table | Column | Type / default | Meaning |
|---|---|---|---|
| `matches` | `status` | `TEXT NOT NULL DEFAULT 'LEGACY'` | `LEGACY`, `PERSONAL`, `PENDING`, `ACCEPTED`, `SOLO`, `DECLINED`, `CANCELLED`, `EXPIRED`, `OPEN_MATCH`. The default marks every row stored before accounts. |
| `matches` | `owner_user_id` | `TEXT` | the owner of a PERSONAL match, NULL otherwise (an open match's organiser lives in `open_matches`) |
| `matches` | `start_time` | `TEXT` | `HH:MM` of a team match or an open match |
| `matches` | `closed_at` | `TEXT` | when a request was accepted, declined, cancelled or expired |
| `matches` | `declined_by` | `TEXT` | the declining team's name and tag (`Güney Gençlik #M4XR`) |
| `matches` | `sibling_of` | `TEXT` | the ACCEPTED record a sibling was created from; at most 3 siblings per record (5.5, R1) |
| `uploads` | `side` | `TEXT` | `HOME` or `AWAY` for a team record, NULL otherwise. Names the video's reviewer; an open match's reviewer is its organiser. |
| `uploads` | `touched_at` | `REAL` | the last `initiate_upload` or stored chunk; the sweep aborts uploads idle for 24 h |
| `uploads` | `started_by` | `TEXT` | the user id that started the upload, or NULL once that account is deleted (3.7); the per-account quotas count on it, and a transfer never changes it (6.5, V4-Q1). No foreign key (8.1); account deletion clears it in its transaction |

Indexes: `matches_owner ON matches(owner_user_id) WHERE owner_user_id IS NOT NULL`, `matches_status ON matches(status)`, `uploads_touched ON uploads(touched_at) WHERE status = 'UPLOADING'` and `uploads_started_by ON uploads(started_by) WHERE started_by IS NOT NULL`. `matches.sibling_of` also names the original of an open match sibling (5.8.2).

`matches.home_team` and `away_team` keep the team names as they were at acceptance (the typed opponent name of a SOLO record; the side names of an open match), so a renamed or deleted team still shows by its old name in the match.

v2's `analysis_jobs.home_confirmed_revision` and `away_confirmed_revision` are not added; `match_results` per side replaces them.

### 8.4 The audit without touching append-only tables (v2, kept)

Only the reviewer of a video writes decisions and corrections on its jobs, and a job's rows die with its video. The acting side of every audit row is therefore `uploads.side` of the job's video. An open match's video has no side; its acting role is the organiser at the time, and the organiser's transfer (5.8.4) is the only change of reviewer, which the ADR records. The server refuses a transfer, of an open match or of a captaincy, while a review is in progress (R9), so every audit row of a job still comes from one reviewer. A rule change records nothing per row today and keeps that.

### 8.5 Not stored

| Not stored | Why |
|---|---|
| e-mail, phone, birth date, photo of a person, real name | display names are free text the user chooses |
| IP addresses | the access log is off; the throttle keeps keys in memory only |
| link tokens, link codes, session tokens, operator codes in clear | SHA-256 or HMAC only (P8) |
| who is waiting, as seen by anyone but the server | the captain and the organiser get counts only |
| device name or id | the attempt keeps a 16-character prefix of a session hash for 24 h at most; a device token is a random value stored as a hash (3.2), not an identifier of the phone |
| user ids in append-only tables | 8.4 |
| precomputed totals | only side results; totals are computed on read |
| the entry choice (v5, R5) | it only opens the first screen; the phone keeps only that the account saw the screen (10.3, 10.10) |
| anything for an empty starter place (v5, R3) | the number of empty places is derived from the STARTER entries; no placeholder row, name or jersey exists (6.3) |

**In memory only** (lost on restart, rebuilt from the database): pool snapshots, the session cache, heartbeats not yet flushed, the in-flight entries, the throttle and rate keys, the FIFO lock, the beat stamps, the recovery flag and the silence state.

### 8.6 Migration (v2, kept)

| Data | Migration |
|---|---|
| `matches` rows from before accounts | `status = 'LEGACY'`; nobody sees them; every route answers `403 NOT_A_MEMBER`. |
| assigning them | `python -m app.admin legacy --list`, `--assign <username>` (they become PERSONAL of that user) and `--delete`. No claim endpoint exists, because a match id is not a secret. The server holds test data only today. |
| `review_decisions`, `rally_corrections` | unchanged |
| phone data | 10.3 |

Migration test: build pre-accounts storage by dropping the view `pool_holders`, then the new indexes, then the new columns and tables, run `migrate()`, assert `LEGACY`. The three existing rebuild tests keep passing.

### 8.7 Retention

| Data | Kept until |
|---|---|
| closed join attempts | 24 h after closing (minute sweep) |
| live share links | renewal, closing, use, a full pool, cancellation of the open match, or expiry (an open match link at the end of its match day at the latest) |
| share link tombstones (token hash, code HMAC, pool or team, reason) | 30 days after closing |
| PENDING requests | EXPIRED after 7 days; closed requests deleted 30 days after closing |
| cancelled open matches | 30 days after the cancellation |
| other open matches | the organiser deletes them while no result is confirmed; with a confirmed result, once nobody but the organiser holds a seat or a confirmed-roster line and the organiser deletes them; with the organiser's account when no other player and no other confirmed line remains; without an organiser, by the minute sweep once no player and no confirmed line remains (5.8.10) |
| confirmed rosters (`match_roster_confirmed`) | the next confirmation of that side, the person's own leave, the side's leave, or the record |
| open match players | they leave, the organiser removes them, or the open match goes |
| sessions | logout, password change or expiry |
| device tokens (`login_devices`) | 180 days after the last use, a password change, an operator reset or account deletion |
| result offers | that side confirms a result, that side leaves, or the record is deleted |
| operator codes | use or expiry |
| sign-ups through join links (`link_signups`, v5) | 7 days after the sign-up (minute sweep); a creator's rows go with the creator's account |
| pool blocks | the captain or the organiser lifts the block, or the pool or the account is deleted |
| logo | replaced, removed or team deleted |
| unfinished uploads | 24 h after the last chunk |
| accounts, teams, team records, videos, side results | until their owners delete them, a side leaves, or the team is deleted (the KVKK review sets periods) |
| backups (B10) | 7 days (16.1 item 10) |

The minute sweep ends with `empty_write_ahead_log()` when it deleted anything.

---

## 9. API 0.8

### 9.1 Conventions

- Root `/v1`. API_V1.md gains section 10 *Hesap, takım, açık maç ve katılma*, section 11 *Takım maçı ve açık maç*, section 12 *İstatistik* and the new error codes in section 8. API.md gains the `Authorization` header and the 0.8 paragraph. Both change with the code.
- Errors use the existing envelope through `errors.fail`; a 401 also sets `WWW-Authenticate: Bearer`.
- Every new input model has `extra="forbid"`. JSON bodies stay under the 64 KiB `BodyLimit`.
- Every route except `GET /v1/health`, `POST /v1/auth/register`, `POST /v1/auth/login`, `POST /v1/links/resolve` and the B6a static pages needs a session. A route-walk test fails when a route outside that list answers without one.
- An outermost ASGI middleware stamps every request's arrival (2.9). Every join answer (claim, ack, decision, poll, live view) carries `waitedMs` and `serverTime`.
- Times the phone computes with are integers in milliseconds (`remainingMs`, `waitedMs`, `serverTime`). Other times are ISO strings as today.
- `GET /v1/health` adds `"accounts": {"signup": "LINK" | "OPEN", "openMatchSignup": "LINK" | "OFF"}` and `"links": {"publicUrl": true | false, "codeLength": 8}`. B1 also answers `accounts.teams`; B4 removes it with `VOLLEYSTAT_TEAMS`, and no app reads it (v5 changes, corrections). `openMatchSignup` is LINK unless the operator set `off` (R6). Merged B1 answers `{signup, teams}` only; B6c adds `openMatchSignup` together with `VOLLEYSTAT_OPEN_MATCH_SIGNUP`, and the app reads a missing field as OFF, so the B6 app against a B5 server shows the team texts (`register_code_first`, `register_gate`), which are then true (C5 of the v5 checkers).
- Every 429 carries `details.scope` (3.6). `BodyLimit` answers `408 BODY_TIMEOUT` to an ack or decision body that takes longer than 5 s (2.9). An older server lacks both, and the app then shows `server_older`.
- `docs/openapi-local-dev.json` is regenerated and `test_openapi.py` lists every new route.

### 9.2 Endpoints

**Accounts (B1)**

| Method and path | Body | Success | Errors |
|---|---|---|---|
| `POST /v1/auth/register` | `{username, displayName, password, link?: {token} \| {code}, operatorCode?}` | `201 {user, session, deviceToken}` | `403 SIGNUP_CLOSED` (`details.reason`: `NO_LINK` or `OPEN_MATCH_LINK`), `404 LINK_NOT_FOUND`, `410 LINK_CLOSED`, `422 WRONG_LINK_KIND`, `403 SIGNUP_LINK_LIMIT` (`details.scope`: `LINK` or `CREATOR`; CREATOR also carries `details.untilAt`, 3.3), `403 SIGNUP_CODE_INVALID`, `409 USERNAME_TAKEN`, `422 USERNAME_INVALID`, `422 DISPLAY_NAME_INVALID`, `422 PASSWORD_REJECTED`, `429 TOO_MANY_ATTEMPTS`, `503 SERVER_BUSY` |
| `POST /v1/auth/login` | `{username, password, deviceToken?}` | `200 {user, session, deviceToken}` | `401 LOGIN_FAILED`, `429` (`details.scope` DEVICE, USER or SOURCE), `503` |
| `POST /v1/auth/logout` | `{}` | `204` | |
| `GET /v1/me` | | `200 {user, teams[]}` (as B1 answers it; B4 fills `teams`; v4's `openMatches.allowed` is gone with the members rule, R6) | `401 AUTH_REQUIRED` |
| `PUT /v1/me/display-name` | `{displayName}` | `200 {user}` | `422` |
| `PUT /v1/me/password` | `{currentPassword, newPassword}` | `200 {user}` | `403 PASSWORD_WRONG`, `422`, `429`, `503` |
| `POST /v1/me/delete` | `{password}` | `204` (3.7: unfinished uploads aborted, unreviewed team videos deleted, organised open matches left without an organiser) | `403 PASSWORD_WRONG`, `409 CAPTAIN_MUST_TRANSFER`, `429`, `503` |

**Teams (B4)**

| Method and path | Body | Success | Errors |
|---|---|---|---|
| `GET /v1/teams` | | `200 {teams[]}` with `newJoins` for captains | |
| `PUT /v1/teams/{teamId}` | `{name, substituteSeats?, captainSeat?, schedule?, jersey?}` (any signed-in account, R6) | `201` Team with the new invite's token, code and URL / `200` Team | `409 TEAM_ID_TAKEN`, `409 TEAM_LIMIT_REACHED` (`details.scope`: `MEMBER` or `CAPTAIN`), `429 TOO_MANY_ATTEMPTS` (`TEAMS`), `422 TEAM_ID_INVALID`, `422 NAME_INVALID`, `422 SEATS_INVALID` (`SUBSTITUTES_RANGE` or `CAPTAIN_SEAT`), `422 VALIDATION_ERROR` (for example a `starterSeats` field) |
| `GET /v1/teams/{teamId}` | | `200` Team | `403 NOT_A_MEMBER`, `404 TEAM_NOT_FOUND` |
| `PUT /v1/teams/{teamId}/settings` | `{name, schedule}` | `200` Team | `403 CAPTAIN_ONLY`, `422` |
| `PUT /v1/teams/{teamId}/seats` | `{substituteSeats, moves?: [{userId, seat}]}` | `200` Team (2.3; runs `settle`) | `403 CAPTAIN_ONLY`, `409 SEATS_BELOW_HOLDERS`, `409 SEATS_IN_USE`, `422 SEATS_INVALID`, `422 NOT_A_TEAM_MEMBER` |
| `PUT /v1/teams/{teamId}/logo` | `{large, small}` (base64 WebP) | `200 {logo: {version}}` | `403`, `413 PAYLOAD_TOO_LARGE`, `422 LOGO_INVALID` |
| `DELETE /v1/teams/{teamId}/logo` | | `204` | `403` |
| `GET /v1/teams/{teamId}/logo?size=large\|small` | | `200 image/webp` | `403`, `404 LOGO_NOT_FOUND` |
| `PUT /v1/teams/{teamId}/members/{userId}/jersey` | `{jersey: 1..99 \| null}` | `200` Member | `403`, `409 JERSEY_TAKEN`, `422` |
| `DELETE /v1/teams/{teamId}/members/{userId}?renewInvite=` | | `204`; with `renewInvite=true` `200 {invite}` with the new token, code and URL (a captain's removal also blocks; 4.3) | `403`, `404 MEMBER_NOT_FOUND`, `409 CAPTAIN_MUST_TRANSFER` |
| `GET /v1/teams/{teamId}/blocks` | | `200 {blocked[]}` | `403 CAPTAIN_ONLY` |
| `DELETE /v1/teams/{teamId}/blocks/{userId}` | | `204` | `403 CAPTAIN_ONLY` |
| `POST /v1/teams/{teamId}/joins-seen` | `{upTo}` | `204` | `403 CAPTAIN_ONLY` |
| `POST /v1/teams/{teamId}/captain` | `{userId}` | `200` Team | `403 CAPTAIN_ONLY`, `409 CAPTAIN_CONFLICT`, `409 TEAM_LIMIT_REACHED` (`CAPTAIN`: the member already captains 3 teams), `409 UPLOAD_IN_PROGRESS`, `409 REVIEW_IN_PROGRESS` (`details.matchId`), `422 NOT_A_TEAM_MEMBER` |
| `DELETE /v1/teams/{teamId}` | | `204` | `403 CAPTAIN_ONLY` |

**Invites and joining (B5)** — the join-attempt routes serve every pool.

| Method and path | Body | Success | Errors |
|---|---|---|---|
| `GET /v1/teams/{teamId}/invite` | | `200 {kind, state, closedReason, expiresAt, seats, signupLimitReached}` (no token, no code; `signupLimitReached` is `LINK` when this link opened 200 accounts, `CREATOR` when its creator's links opened 400 in the last 7 days (CREATOR when both hold), else null; with CREATOR the answer also carries `signupLimitUntil`, 3.3, C17 of the v5 checkers) | `403 CAPTAIN_ONLY`, `404 INVITE_NOT_FOUND` |
| `POST /v1/teams/{teamId}/invite` | `{}` | `201` Invite with token, code and URL (closes the live one as RENEWED, T14) | `403 CAPTAIN_ONLY` |
| `DELETE /v1/teams/{teamId}/invite` | | `204` (T14, reason CLOSED) | `403 CAPTAIN_ONLY` |
| `GET /v1/teams/{teamId}/live?view=&wait=` | | `200 {seats, invite, newJoins, view, waitedMs, serverTime}` | `403 CAPTAIN_ONLY` |
| `POST /v1/links/resolve` | `{token} \| {code}`, `knownLogoVersion?` | `200` LinkView (session optional; 9.3) | `404 LINK_NOT_FOUND`, `410 LINK_CLOSED`, `422`, `429` |
| `POST /v1/join-attempts` | `{link: {token} \| {code}}` | `201` new / `200` existing Attempt | `404 LINK_NOT_FOUND`, `410 LINK_CLOSED`, `403 JOIN_BLOCKED`, `409 ALREADY_MEMBER` (`details.kind`, `details.teamId` or `details.matchId`), `409 SEATS_FULL`, `409 QUEUE_FULL`, `409 ATTEMPT_LIMIT`, `409 TEAM_LIMIT_REACHED`, `409 OPEN_MATCH_LIMIT`, `422 WRONG_LINK_KIND`, `429` |
| `GET /v1/join-attempts/{id}?view=&wait=` | | `200` Attempt (long-poll; its arrival is the heartbeat). Over a cap: `200` at once with `pollLimited: true` and `Retry-After` (2.7) | `403 NOT_YOUR_ATTEMPT`, `404 ATTEMPT_NOT_FOUND` |
| `POST /v1/join-attempts/{id}/ack` | `{}` | `200` Attempt (HOLDING) | `403`, `404`, `408 BODY_TIMEOUT`, `409 NOT_OFFERED`, `409 ATTEMPT_CLOSED` (`details.state`, `details.reason`; SKIPPED by T6b with `details.offerWindowS`), `429` |
| `POST /v1/join-attempts/{id}/decision` | `{decision: "ACCEPT" \| "REJECT" \| "TIMEOUT"}` | `200` Attempt | `403`, `404`, `408 BODY_TIMEOUT`, `409 NOT_DECIDING` (`details.state`: WAITING or OFFERED), `409 ATTEMPT_CLOSED` (`details.state`, `details.reason`, `details.lateAccept`, `details.acceptArrivedInTime`), `409 TEAM_LIMIT_REACHED`, `409 OPEN_MATCH_LIMIT`, `429` |
| `DELETE /v1/join-attempts/{id}` | | `204` (T9 or T11; a closed attempt also answers 204) | `403`, `404`, `429` |

`wait` is 0 to 15 seconds, default 15. `view` absent means "answer at once". The phone's read timeout is `wait` + 3 s (2.6).

**Open matches (B6c; video, roster confirmation and result in B8)**

| Method and path | Body | Success | Errors |
|---|---|---|---|
| `PUT /v1/open-matches/{matchId}` | 5.8.2, or `{siblingOf}` for a sibling (R4) | `201 {openMatch, link}` with token, code and URL / `200` OpenMatch; a sibling has no link | `403 NOT_A_MEMBER` (a repeat by someone else), `403 ORGANISER_ONLY` (`siblingOf` names a match the caller does not organise), `409 OPEN_MATCH_LIMIT` (ORGANISED, or JOINED with "Ben de oynuyorum"), `409 SIBLING_LIMIT`, `422 MATCH_ID_INVALID`, `422 MATCH_DATE_PAST`, `422 SEATS_INVALID` (`SUBSTITUTES_RANGE`), `422 VALIDATION_ERROR`, `429` (OPEN_MATCHES) |
| `GET /v1/open-matches/{id}` | | `200` OpenMatch | `403 NOT_A_MEMBER`, `404 MATCH_NOT_FOUND` |
| `PUT /v1/open-matches/{id}/settings` | `{playedOn, startTime, venue, name, sideNames}` | `200` OpenMatch | `403 ORGANISER_ONLY`, `409 OPEN_MATCH_PLAYED` (`details.reason`: `DAY_STARTED`, `HAS_UPLOAD`; a date or time change after it is fixed, 5.8.4), `422 MATCH_DATE_PAST`, `422 VALIDATION_ERROR` |
| `PUT /v1/open-matches/{id}/seats` | `{substituteSeats, moves?}` | `200` OpenMatch (2.3) | `403 ORGANISER_ONLY`, `409 SEATS_BELOW_HOLDERS`, `409 SEATS_IN_USE`, `422 SEATS_INVALID`, `422 NOT_A_PLAYER` |
| `GET /v1/open-matches/{id}/link` | | `200 {kind, state, closedReason, expiresAt, seats, signupLimitReached}` | `403 ORGANISER_ONLY`, `404 INVITE_NOT_FOUND` |
| `POST /v1/open-matches/{id}/link` | `{}` | `201` Invite of kind OPEN_MATCH with token, code and URL (closes the live one as RENEWED) | `403 ORGANISER_ONLY`, `409 OPEN_MATCH_PLAYED` (the day passed or an upload row exists; also on a sibling), `409 MATCH_CANCELLED` (the guard of 2.2) |
| `DELETE /v1/open-matches/{id}/link` | | `204` (T14, reason CLOSED) | `403 ORGANISER_ONLY` |
| `GET /v1/open-matches/{id}/live?view=&wait=` | | `200 {seats, link, view, waitedMs, serverTime}` | `403 ORGANISER_ONLY` |
| `DELETE /v1/open-matches/{id}/players/{userId}?renewLink=` | | `204`; with `renewLink=true` `200 {link}` (removal blocks; the confirmed roster keeps the person's line; 5.8.4) | `403 ORGANISER_ONLY`, `404 MEMBER_NOT_FOUND` (the organiser's own id works as the organiser's leave), with `renewLink=true` also `409 OPEN_MATCH_PLAYED` and `409 MATCH_CANCELLED` (the guard of 2.2), and then nothing changes |
| `GET /v1/open-matches/{id}/blocks`, `DELETE …/blocks/{userId}` | | `200 {blocked[]}`, `204` | `403 ORGANISER_ONLY` |
| `POST /v1/open-matches/{id}/leave` | `{}` | `204` (the organiser keeps the role, 5.8.4) | `403 NOT_A_PLAYER` |
| `PUT /v1/open-matches/{id}/players/me/jersey` | `{jersey: 1..99 \| null}` | `200` Player | `403 NOT_A_PLAYER`, `422` |
| `POST /v1/open-matches/{id}/organiser` | `{userId}` (a player) or `{userId: null}` (withdraw) | `200` OpenMatch with `transferOffer` (an offer; the role does not move yet) | `403 ORGANISER_ONLY`, `409 UPLOAD_IN_PROGRESS`, `409 REVIEW_IN_PROGRESS`, `422 NOT_A_PLAYER` |
| `POST /v1/open-matches/{id}/organiser/accept` | `{}` | `200` OpenMatch (the caller is the organiser) | `403 NOT_A_PLAYER`, `409 TRANSFER_NOT_OFFERED`, `409 OPEN_MATCH_LIMIT` (ORGANISED), `409 UPLOAD_IN_PROGRESS`, `409 REVIEW_IN_PROGRESS` |
| `POST /v1/open-matches/{id}/organiser/decline` | `{}` | `200` OpenMatch (the offer is gone) | `403 NOT_A_PLAYER`, `409 TRANSFER_NOT_OFFERED` |
| `POST /v1/open-matches/{id}/cancel` | `{}` | `200` OpenMatch (cancelled) | `403 ORGANISER_ONLY`, `409 MATCH_HAS_CONTENT` (`details.reason`: `HAS_VIDEO`, `RESULT_CONFIRMED`) |
| `DELETE /v1/open-matches/{id}` | | `204` (no side result, or nobody but the organiser holds a seat or a confirmed line) | `403 ORGANISER_ONLY`, `409 MATCH_HAS_CONTENT` (`details.reason: RESULT_CONFIRMED`) |
| `GET /v1/open-matches/{id}/roster` | | `200` OpenRoster (the proposal while never saved) | `403 NOT_A_MEMBER` |
| `PUT /v1/open-matches/{id}/roster` | `{entries: [{userId, side, role, jersey?}], expectedVersion}` | `200` OpenRoster | `403 ORGANISER_ONLY`, `409 ROSTER_STALE`, `422 ROSTER_INVALID` (`TOO_MANY_SUBSTITUTES`, `TOO_MANY_STARTERS`, `TOO_MANY_LIBEROS`, `DUPLICATE_JERSEY`, `DUPLICATE_PERSON`, `NOT_A_PLAYER`, `JERSEY_RANGE`) |
| `POST /v1/open-matches/{id}/roster/confirm` (B8) | `{version}` | `200` OpenRoster (stores the confirmed roster; version 0 writes the proposal first, 5.8.5) | `403 ORGANISER_ONLY`, `409 ROSTER_STALE`, `422 ROSTER_INVALID` (`JERSEY_MISSING`; every reason of 6.3 for the rows it confirms, among them `TOO_MANY_STARTERS` and `TOO_MANY_SUBSTITUTES`) |

An open match's uploads, report, video deletion and result confirmation use the match routes of B7 and B8 with the rules of 5.8.11; `initiate_upload` on an open match also answers `409 OPEN_MATCH_TOO_SMALL` and closes the live link (PLAYED). `GET /v1/matches?scope=open` lists the caller's open matches; B6c brings this route with `scope=open` only (14.1, R8).

**Match requests and team records (B7)**

| Method and path | Body | Success | Errors |
|---|---|---|---|
| `PUT /v1/match-requests/{matchId}` | 5.1, or `{siblingOf}` for a sibling of an ACCEPTED record (5.5) | `201` / `200 {match, link}`; a sibling is ACCEPTED at once and has no link | `403 CAPTAIN_ONLY`, `409 SIBLING_LIMIT`, `422 MATCH_ID_INVALID`, `422 VALIDATION_ERROR`, `429` |
| `POST /v1/match-requests/accept` | `{link, teamId}` | `200` Match | 5.2 table |
| `POST /v1/match-requests/decline` | `{link, teamId}` | `200 {status: "DECLINED"}` | `403 CAPTAIN_ONLY`, `404 LINK_NOT_FOUND`, `410 LINK_CLOSED`, `409 REQUEST_CLOSED` |
| `POST /v1/matches/{id}/opponent/confirm` | `{}` | `200` Match | `403 CAPTAIN_ONLY`, `409 MATCH_NOT_CONFIRMED` |
| `PUT /v1/solo-matches/{matchId}` | 5.7 | `201` / `200` Match | `403 CAPTAIN_ONLY`, `422 MATCH_ID_INVALID`, `422 VALIDATION_ERROR` |
| `GET /v1/matches?scope=team\|open\|personal\|all&status=&limit=&cursor=` | | `200 {matches[], nextCursor}` | |
| `POST /v1/matches/{id}/cancel` | `{}` | `200` Match (AWAY's cancel on an ACCEPTED record is AWAY's leave) | `403 CAPTAIN_ONLY`, `409 REQUEST_CLOSED`, `409 MATCH_HAS_CONTENT` |
| `POST /v1/matches/{id}/solo` | `{opponentName}` | `200` Match (SOLO, 5.4) | `403 CAPTAIN_ONLY`, `409 REQUEST_CLOSED`, `409 MATCH_HAS_CONTENT`, `422 VALIDATION_ERROR` |
| `POST /v1/matches/{id}/leave` | `{}` | `200` Match, or `204` when no side remains | `403 CAPTAIN_ONLY`, `409 MATCH_NOT_CONFIRMED` |

**Side results and rosters (B8)**

| Method and path | Body | Success | Errors |
|---|---|---|---|
| `GET /v1/matches/{id}/report?jobId=` | | `200` Report of that job, or of the newest job without `jobId` | `403 NOT_A_MEMBER`, `404 ANALYSIS_NOT_FOUND` (the job does not belong to this record, S4), `404 REPORT_NOT_AVAILABLE` |
| `POST /v1/matches/{id}/result/confirm` | `{jobId, revision, fromOffer?}` | `200` Match (writes the caller's side result, from a job or from the result offer for the caller's side, 6.2; for an open match the organiser's confirmation writes both sides, 5.8.6) | `403 CAPTAIN_ONLY`, `403 ORGANISER_ONLY`, `409 LIST_NOT_CONFIRMED`, `409 MOCK_RESULT`, `409 RESULT_STALE` (`details.currentRevision`) |
| `GET /v1/matches/{id}/roster` | | `200` Roster (a never-saved team side as `TEAM_DEFAULT` to its own team, and as not saved without entries to the other team, 6.3) | `403 NOT_A_MEMBER` |
| `PUT /v1/matches/{id}/roster/{side}` | `{entries: [{userId, role, jersey}], expectedVersion}` | `200` Roster | `403 CAPTAIN_ONLY`, `409 ROSTER_STALE` (`details.currentVersion`), `422 ROSTER_INVALID` (`details.reason`: `TOO_MANY_SUBSTITUTES`, `TOO_MANY_STARTERS`, `TOO_MANY_LIBEROS`, `DUPLICATE_JERSEY`, `DUPLICATE_PERSON`, `NOT_A_TEAM_MEMBER`, `ON_OTHER_SIDE`, `JERSEY_RANGE`) |
| `POST /v1/matches/{id}/roster/{side}/confirm` | `{version}` | `200` Roster (stores the confirmed roster; version 0 writes the default as version 1 first, 6.3) | `403 CAPTAIN_ONLY`, `409 ROSTER_STALE`, `422 ROSTER_INVALID` (`JERSEY_MISSING`; every reason of 6.3 for the rows it confirms, among them `TOO_MANY_STARTERS` and `TOO_MANY_SUBSTITUTES`) |
| `POST /v1/matches/{id}/roster/leave` | `{}` | `200` Roster (removes the person's row and confirmed line, keeps the side's confirmation for the others; on version 0 writes the default without the person as version 1, 6.3) | `403 NOT_ON_ROSTER` |

**Statistics (B9)**

| Method and path | Success | Errors |
|---|---|---|
| `GET /v1/teams/{teamId}/stats` | `200` TeamStats (team records only; never an open match) | `403 NOT_A_MEMBER` |
| `GET /v1/me/stats?teamId=&scope=all\|team\|open` | `200` PersonStats | `403 NOT_A_MEMBER` (teamId) |

**Exposure (B6a, static, no session)**

| Path | Content |
|---|---|
| `GET /.well-known/assetlinks.json` | package name and `VOLLEYSTAT_ANDROID_CERT_SHA256`, when the API host is the link host |
| `GET /j/`, `GET /m/`, `GET /static/link.js` | the fallback pages of 2.2 and 2.12 G (`/j/` serves both kinds of join link) and their one external script, with `Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'` (S6) |

### 9.3 Main shapes

**Attempt** (every join call and every long-poll answer; about 0.5-1 KB, no logo bytes):

```json
{"id": "8b1c…", "state": "WAITING", "reason": null, "seat": null,
 "target": {"kind": "TEAM", "poolId": "p41…",
            "team": {"id": "6f0c…", "name": "Kuzey Spor", "tag": "K7Q2", "logoVersion": 3,
                     "schedule": {"weekday": 2, "time": "20:00"}}},
 "seats": {"starter":    {"seats": 6, "taken": 6, "left": 0, "deciding": 0},
           "substitute": {"seats": 2, "taken": 1, "left": 1, "deciding": 1}},
 "queue": {"ahead": 0},
 "offer": null,
 "decision": null,
 "outcome": null,
 "link": {"live": true},
 "view": "W.0.0-1.L",
 "waitedMs": 3,
 "serverTime": 1763320000123}
```

For an open match, `target` is:

```json
{"kind": "OPEN_MATCH", "poolId": "p77…",
 "openMatch": {"id": "c9…", "name": null, "organiserName": "Deniz",
               "playedOn": "2026-11-22", "startTime": "14:00", "venue": "Moda Spor Salonu"}}
```

- OFFERED: `"seat": "STARTER" | "SUBSTITUTE"` and `"offer": {"remainingMs": 7990}`; the phone acknowledges at once with `POST …/ack`.
- HOLDING: `"seat"`, `"queue": null`, `"decision": {"remainingMs": 9984}`.
- JOINED: `"seat"` names the seat the person now holds. Other closed states keep the `seat` the attempt had, or null.
- Closed: `state` and `reason` give the outcome; `"outcome": {"decidedHere": true | false | null, "lateAccept": false, "acceptArrivedInTime": false}` (T17), plus `"offerWindowS": 8` for SKIPPED; `seats` and `link.live` are still current, so the app knows whether to offer Yeniden Sıraya Gir. `link.live` is true while the pool's live join link is the one this attempt came through (2.12 C).
- A poll over a cap carries `"pollLimited": true` (2.7).
- `seats.<type>.left` = seats − taken; `deciding` = offered + holding of that type. With a queue, `left` equals `deciding` for each type (2.3). The joiner's view carries no waiting total.
- `view` is opaque; the phone sends it back as `?view=`.

**LinkView** (`POST /v1/links/resolve`)

Without a session, for a team invite (P5):

```json
{"kind": "TEAM_INVITE", "signup": true,
 "team": {"name": "Kuzey Spor", "tag": "K7Q2", "logo": {"version": 3, "small": "<base64 or null>"}}}
```

Without a session, for an open match link (P5; `signup` is true under the v5 default and false only while the operator set `VOLLEYSTAT_OPEN_MATCH_SIGNUP=off`, R6):

```json
{"kind": "OPEN_MATCH", "signup": true}
```

With a session, for an invite:

```json
{"kind": "TEAM_INVITE", "expiresAt": "2026-11-23T20:00:00Z",
 "target": {"kind": "TEAM", "poolId": "p41…",
            "team": {"id": "6f0c…", "name": "Kuzey Spor", "tag": "K7Q2", "logo": {"version": 3, "small": null},
                     "schedule": {"weekday": 2, "time": "20:00"}}},
 "seats": {"starter": {"seats": 6, "taken": 6, "left": 0, "deciding": 0},
           "substitute": {"seats": 2, "taken": 1, "left": 1, "deciding": 1}},
 "availability": "QUEUE",
 "me": {"member": false, "blocked": false, "attempt": null}}
```

With a session, for an open match link:

```json
{"kind": "OPEN_MATCH", "expiresAt": "2026-11-22T20:59:59Z",
 "target": {"kind": "OPEN_MATCH", "poolId": "p77…",
            "openMatch": {"id": "c9…", "name": null, "organiserName": "Deniz", "playedOn": "2026-11-22",
                          "startTime": "14:00", "venue": "Moda Spor Salonu", "kind": "TRAINING",
                          "rules": {"…": "…"}, "sideNames": {"a": "A Tarafı", "b": "B Tarafı"}}},
 "seats": {"starter": {"seats": 12, "taken": 7, "left": 5, "deciding": 1},
           "substitute": {"seats": 2, "taken": 0, "left": 2, "deciding": 0}},
 "availability": "OPEN",
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

A closed link answers `410 LINK_CLOSED` with `details: {"reason": "FULL", "kind": "TEAM_INVITE", "teamName": "Kuzey Spor"}`; for an open match link `details: {"reason": "EXPIRED", "kind": "OPEN_MATCH", "matchDayPassed": true}`.

**Invite** (captain or organiser; token, code and URL only in the creation answer): `{"kind": "TEAM_INVITE" | "OPEN_MATCH", "state": "LIVE", "closedReason": null, "expiresAt": "…", "seats": {…}, "signupLimitReached": null, "token": "…", "code": "K7QF2M9X", "url": "https://…/j/#t=…&c=K7QF2M9X" | null}`.

**Team**

```json
{"id": "6f0c…", "poolId": "p41…", "name": "Kuzey Spor", "tag": "K7Q2",
 "schedule": {"weekday": 2, "time": "20:00"}, "logo": {"version": 3},
 "myRole": "CAPTAIN", "mySeat": "STARTER", "myJersey": 7, "newJoins": 2,
 "members": [{"userId": "a1…", "displayName": "Deniz", "jersey": 7, "role": "CAPTAIN", "seat": "STARTER", "joinedAt": "…"}],
 "seats": {"starter": {"seats": 6, "taken": 5, "left": 1, "deciding": 1},
           "substitute": {"seats": 2, "taken": 2, "left": 0, "deciding": 0},
           "waiting": 0},
 "invite": {"state": "LIVE", "closedReason": null, "expiresAt": "…"}}
```

`invite`, `newJoins` and `seats.waiting` are for the captain only, else null.

**OpenMatch**

```json
{"id": "c9…", "poolId": "p77…", "status": "OPEN_MATCH", "cancelled": false,
 "name": null, "playedOn": "2026-11-22", "startTime": "14:00", "venue": "Moda Spor Salonu",
 "kind": "TRAINING", "rules": {"…": "…"}, "sideNames": {"a": "A Tarafı", "b": "B Tarafı"},
 "organiser": {"userId": "a1…", "displayName": "Deniz"}, "transferOffer": null, "siblingOf": null,
 "players": [{"userId": "a1…", "displayName": "Deniz", "seat": "STARTER", "side": "A", "role": "STARTER",
              "jersey": 7, "suggestedJersey": 7, "joinedAt": "…"}],
 "seats": {"starter": {"seats": 12, "taken": 12, "left": 0, "deciding": 0},
           "substitute": {"seats": 2, "taken": 2, "left": 0, "deciding": 0},
           "waiting": 0},
 "link": {"state": "CLOSED", "closedReason": "FULL", "expiresAt": "…", "matchDayPassed": false},
 "roster": {"version": 1, "confirmedVersion": null, "changedSinceConfirmed": false, "unassigned": 0},
 "video": {"id": "v9…", "status": "READY"},
 "analysis": {"jobId": "j7…", "state": "COMPLETE", "revision": 3, "listConfirmed": true, "mock": false},
 "result": {"jobId": "j7…", "revision": 3, "confirmedAt": "…", "changedSinceCounted": false},
 "me": {"organiser": true, "player": true, "seat": "STARTER", "side": "A", "canReadContent": true},
 "request": null}
```

- `link` and `seats.waiting` are for the organiser only, else null. `request` is `{"expiresAt", "url", "code"}` only in the creation answer.
- `side` is "A", "B" or null (not on a side yet; also null for everyone while the split was never saved, because the proposal is the organiser's draft, 5.8.5); `role` and `jersey` come from the roster; `suggestedJersey` from the player. The organiser's own view adds `proposedSide` per player.
- `organiser` is null after the organiser deleted their account (3.7). `transferOffer` is `{"userId", "displayName", "offeredAt"}` while an offer is open, shown to the organiser and to the player it names, else null. `siblingOf` names the original of a sibling (5.8.2).
- `roster.confirmedVersion` is the version of the confirmed roster or null; `changedSinceConfirmed` is true when the working roster moved since (6.3).
- A logged-in non-player never gets this shape (`403 NOT_A_MEMBER`); the LinkView is all they see.

**Match** (a team record; the old fields stay, and `status` carries the real state instead of the constant `"DRAFT"` that no client reads):

```json
{"id": "c3…", "homeTeamName": "Kuzey Spor", "awayTeamName": "Güney Gençlik",
 "name": null, "playedOn": "2026-11-22", "startTime": "14:00", "venue": "Okul Spor Salonu",
 "kind": "TRAINING", "rules": {"…": "…"}, "status": "ACCEPTED",
 "sides": {"HOME": {"teamId": "6f0c…", "name": "Kuzey Spor", "tag": "K7Q2", "left": false, "opponentChecked": true,
                    "roster": {"source": "SAVED", "version": 2, "confirmed": true, "starters": 6, "emptyStarterPlaces": 0, "substitutes": 2},
                    "video": {"id": "v7…", "status": "READY"},
                    "analysis": {"jobId": "j2…", "state": "COMPLETE", "revision": 14, "listConfirmed": true, "mock": false},
                    "result": {"jobId": "j2…", "revision": 12, "confirmedAt": "…", "changedSinceCounted": true}},
           "AWAY": {"teamId": "9d1e…", "name": "Güney Gençlik", "tag": "M4XR", "left": false, "opponentChecked": true,
                    "roster": {"source": "SAVED", "version": 1, "confirmed": false, "starters": 5, "emptyStarterPlaces": 1, "substitutes": 2},
                    "video": null, "analysis": null,
                    "result": {"jobId": "j2…", "revision": 14, "confirmedAt": "…", "changedSinceCounted": false}}},
 "me": {"side": "HOME", "captain": true, "reviewerOf": ["v7…"], "readOnly": false, "canReadContent": true},
 "request": null}
```

- `request` is `{"expiresAt", "url", "code"}` for the HOME captain while PENDING, and only in the creation answer; else null.
- A SOLO record has `status: "SOLO"`, one side and `awayTeamName` = the typed opponent name.
- A personal match has `sides: null` and `me.owner: true`.
- The other team's members never appear in any of these shapes; the roster summary gives counts only, and for the other side's `TEAM_DEFAULT` not even counts (6.3, F2). In the example HOME's upload has already written AWAY's default as stored rows (version 1); before any upload, HOME would read AWAY's roster as `{"source": "TEAM_DEFAULT", "version": 0, "confirmed": false, "starters": null, "emptyStarterPlaces": null, "substitutes": null}`. `emptyStarterPlaces` is 6 − `starters` (v5, R3); AWAY in the example has 5 starters and one empty place. With stored rows the summary also carries `liberos`, `confirmedVersion` and `changedSinceConfirmed`.
- With `me.canReadContent: false` the shape leaves out `video.id`, `analysis.jobId` and `result.jobId` (S4).
- A sibling record carries `"siblingOf": "<record id>"` (5.5).

**Roster** (a team record; the caller is on HOME):

```json
{"sides": {"HOME": {"source": "SAVED", "version": 2, "confirmed": true, "starterPlaces": 6, "emptyStarterPlaces": 5,
                    "entries": [{"jersey": 7, "role": "STARTER", "userId": "a1…", "displayName": "Deniz"}]},
           "AWAY": {"source": "SAVED", "version": 1, "confirmed": false, "starterPlaces": 6, "emptyStarterPlaces": 5,
                    "entries": [{"jersey": 4, "role": "STARTER"}, {"jersey": 12, "role": "LIBERO"}]}}}
```

The other side's entries carry role and jersey only (6.3). Each side carries `starterPlaces` (always 6) and `emptyStarterPlaces` (6 − STARTER entries; null for a `TEAM_DEFAULT` side read by the other team); the app draws the empty places as "Boş" after the starters, and no entry exists for them (v5, R3). A side that is still `TEAM_DEFAULT` reads to the other team as `{"source": "TEAM_DEFAULT", "version": 0, "confirmed": false, "entries": null}`; to its own team it lists the members on the match day. Each side also carries `confirmedVersion` and `changedSinceConfirmed`. The review chain reads this shape for its SCORER card and its Players screen (6.3, R1).

**OpenRoster** (organiser and players):

```json
{"source": "PROPOSAL", "version": 0, "confirmed": false,
 "sides": {"A": [{"userId": "a1…", "displayName": "Deniz", "role": "STARTER", "jersey": null, "suggestedJersey": 7}],
           "B": [{"userId": "b2…", "displayName": "Ece", "role": "STARTER", "jersey": null, "suggestedJersey": 10}]},
 "emptyStarterPlaces": {"A": 5, "B": 5},
 "unassigned": []}
```

`source` is `PROPOSAL` while the roster was never saved (5.8.5), else `SAVED`; the proposal comes from the stored `proposed_side` of each player, and a player sees it only after the organiser saved the split (the shape then holds the saved sides). `role` may be LIBERO. The shape also carries `confirmedVersion` and `changedSinceConfirmed`, and `emptyStarterPlaces` per side (6 − STARTER entries of that side, never below 0, because a seat move re-proposes the moved players, 5.8.5; v5, R3).

**The report does not change.** It stays jersey-level. The app joins names from the roster for its own side only, and for both sides of an open match.

**TeamStats** (team records only):

```json
{"teamId": "6f0c…",
 "records": {"accepted": 10, "withVideo": 4, "counted": 3, "notConfirmed": 1, "inReview": 0, "withoutVideo": 6},
 "inMatches": {"countedMatches": {"value": 4, "status": "PARTIAL"}, "won": {"value": 3, "status": "PARTIAL"},
               "lost": {"…": "…"}, "setsWon": {"…": "…"}, "pointsFor": {"…": "…"}, "attackPoints": {"…": "…"}},
 "outsideMatches": {"setsWon": {"…": "…"}, "pointsFor": {"…": "…"}},
 "lines": [{"matchId": "c3…", "kind": "TEAM_MATCH", "playedOn": "2026-11-22", "opponentName": "Güney Gençlik",
            "side": "HOME", "state": "COUNTED", "changedSinceCounted": false, "otherSide": "CONFIRMED",
            "parts": [{"number": 1, "kind": "MATCH", "result": "WON", "setsWon": 3, "setsLost": 1}]}]}
```

**PersonStats** has the same frame with the person fields of 7.3; each line adds `kind` (`TEAM_MATCH`, `SOLO` or `OPEN_MATCH`) and `role`, and an open match line names its sides instead of an opponent team (`"sideName": "B Tarafı", "otherSideName": "A Tarafı"`).

### 9.4 Changes to the existing operations

| Change | Detail |
|---|---|
| session on every operation except health | `401 AUTH_REQUIRED` |
| access check after the object is found | `404 <OBJECT>_NOT_FOUND` for an object that does not exist; `403 NOT_A_MEMBER` for one the caller may not see, including content outside the member read rule (6.1). The app reads 404 as "deleted" (`AppViewModel.removeMatch`), so a stranger never gets it. |
| `access.match_of(db, kind, id)` | resolves upload, video, job, card (or the decision row of a removed card) and rally to the match record and, for team records, to the side of the video; for an open match, to its organiser as the reviewer |
| `PUT /v1/matches/{id}`, `POST /v1/matches` | create PERSONAL; an existing record answers only to a reader |
| `initiate_upload` | team record: 6.1 upload row, with the 2-member rule and the team quotas of v5 (6.5); open match: the organiser, one live video, at least 2 players, no acceptance gate (5.8.11); stores `uploads.side` (NULL for an open match), `uploads.started_by` and `touched_at`; quotas and the disk floor (6.5) for every upload; the record's first upload writes every never-saved team side as stored rows (6.3) and closes a live open match link with reason PLAYED (5.8.3) |
| `store_chunk` | moves `touched_at`; refuses below half the disk floor |
| chunks, complete, abort, upload status; quality recheck; `request_analysis`; cancel | team record: the reviewer of that video only (`403 REVIEWER_ONLY`); a defensive check also in `jobs.request_analysis` |
| decisions, reopen, the five corrections, list confirm, `PUT …/rules` | team record: the reviewer of that video (rules: of either live video) |
| `decide` | the key lookup runs, then the access check on the stored row's match, then the replay |
| `DELETE /v1/videos/{id}` | team record: its reviewer at any time; every record: blank the upload row |
| `DELETE /v1/matches/{id}` | personal only; a team or SOLO record answers `409 TEAM_MATCH`, an open match the same with `details.status: OPEN_MATCH` |
| `match_payload` | real `status`, plus the fields of 9.3; an open match answers with the OpenMatch shape; no content ids without `canReadContent` (S4) |
| `GET /v1/matches/{id}/report` | optional `jobId`, resolved by `access.match_of`; `404 ANALYSIS_NOT_FOUND` unless the job belongs to `{id}`; then the content rule (6.1, S4) |
| `db.connector()` | FIFO ticket lock around every write transaction, taken before the connection opens and released in the outermost `finally` on every path; at most 30 s of waiting (2.8) |
| `BodyLimit` | `408 BODY_TIMEOUT` for ack and decision bodies slower than 5 s (2.9) |
| app factory | outermost middleware for arrival stamps and in-flight entries; the beat task; the queue clock next to the analysis worker; the notifier; the session cache |
| `__main__.py` | `access_log=False`, `forwarded_allow_ips=""`, the open-file limit (2.7); from B6a `--host`, `--trusted-proxy` and `--client-ip-header` (3.6) |

### 9.5 New error codes

| Group | Codes |
|---|---|
| Session and sign-up | `AUTH_REQUIRED`, `LOGIN_FAILED`, `PASSWORD_WRONG`, `PASSWORD_REJECTED`, `USERNAME_INVALID`, `USERNAME_TAKEN`, `DISPLAY_NAME_INVALID`, `SIGNUP_CLOSED` (`details.reason`: `NO_LINK`, `OPEN_MATCH_LINK`), `SIGNUP_LINK_LIMIT` (`details.scope`: `LINK`, `CREATOR` with `details.untilAt`), `SIGNUP_CODE_INVALID`, `TOO_MANY_ATTEMPTS`, `SERVER_BUSY`, `BODY_TIMEOUT` (408) |
| Access | `NOT_A_MEMBER`, `CAPTAIN_ONLY`, `REVIEWER_ONLY`, `ORGANISER_ONLY`, `NOT_A_PLAYER` |
| Teams | `TEAM_NOT_FOUND`, `TEAM_ID_INVALID`, `TEAM_ID_TAKEN`, `TEAM_LIMIT_REACHED` (`details.scope`: `MEMBER`, `CAPTAIN`), `NAME_INVALID`, `MEMBER_NOT_FOUND`, `NOT_A_TEAM_MEMBER`, `JERSEY_TAKEN`, `CAPTAIN_MUST_TRANSFER`, `CAPTAIN_CONFLICT`, `LOGO_INVALID`, `LOGO_NOT_FOUND`, `TEAM_TOO_SMALL` (`details.members`) |
| Seats (teams and open matches) | `SEATS_INVALID` (`details.reason`: `SUBSTITUTES_RANGE`, `CAPTAIN_SEAT`), `SEATS_BELOW_HOLDERS` (`details.seat`, `details.holders`, `details.seats`), `SEATS_IN_USE` (`details.seat`, `details.holders`, `details.deciding`, `details.seats`) |
| Links and joining | `LINK_NOT_FOUND`, `LINK_CLOSED` (410), `WRONG_LINK_KIND`, `INVITE_NOT_FOUND`, `ALREADY_MEMBER`, `JOIN_BLOCKED`, `SEATS_FULL`, `QUEUE_FULL`, `ATTEMPT_LIMIT`, `NOT_YOUR_ATTEMPT`, `ATTEMPT_NOT_FOUND`, `NOT_OFFERED`, `NOT_DECIDING`, `ATTEMPT_CLOSED` |
| Open matches | `OPEN_MATCH_LIMIT` (`details.scope`: `ORGANISED`, `JOINED`; `details.limit`), `OPEN_MATCH_PLAYED` (`details.reason` on settings: `DAY_STARTED`, `HAS_UPLOAD`), `MATCH_DATE_PAST`, `MATCH_CANCELLED`, `OPEN_MATCH_TOO_SMALL` (`details.players`), `TRANSFER_NOT_OFFERED` |
| Transfers (teams and open matches) | `UPLOAD_IN_PROGRESS`, `REVIEW_IN_PROGRESS` (`details.matchId`) |
| Team matches | `REQUEST_CLOSED`, `SIBLING_LIMIT`, `SAME_TEAM`, `SAME_TEAM_NAME`, `SAME_PERSON_BOTH_SIDES`, `MATCH_NOT_CONFIRMED`, `OPPONENT_NOT_CHECKED`, `SIDE_HAS_VIDEO`, `MATCH_HAS_CONTENT` (`details.reason` for an open match: `HAS_VIDEO`, `RESULT_CONFIRMED`), `TEAM_MATCH`, `RESULT_STALE`, `LIST_NOT_CONFIRMED`, `MOCK_RESULT` |
| Storage | `QUOTA_EXCEEDED` (`details.scope`: `PERSONAL`, `OPEN_MATCH`, `TEAM`, `TEAM_UPLOADER`, `SERVER` with `details.pool`: `PERSONAL`, `OPEN_MATCH` or `TEAM`), `STORAGE_FULL` (507) |
| Rosters | `ROSTER_STALE`, `ROSTER_INVALID` (`details.reason`: `TOO_MANY_SUBSTITUTES`, `TOO_MANY_STARTERS`, `TOO_MANY_LIBEROS`, `DUPLICATE_JERSEY`, `DUPLICATE_PERSON`, `NOT_A_TEAM_MEMBER`, `NOT_A_PLAYER`, `ON_OTHER_SIDE`, `JERSEY_RANGE`, `JERSEY_MISSING`), `NOT_ON_ROSTER` |

Removed from v2: `MATCH_HAS_VIDEO` (now per side: `SIDE_HAS_VIDEO`), `RESULT_COUNTED` (video deletion never touches a side result), `SIGNUP_LINK_INVALID` (replaced by `LINK_NOT_FOUND`, `LINK_CLOSED` and `WRONG_LINK_KIND`). Removed in v3's revision: `POLL_LIMIT` (a poll over a cap answers at once instead, 2.7). Renamed in v4, because one engine serves teams and open matches: `TEAM_FULL` → `SEATS_FULL`; `CAPACITY_BELOW_MEMBERS` → `SEATS_BELOW_HOLDERS`; `CAPACITY_IN_USE` → `SEATS_IN_USE`; the attempt reasons `TEAM_FULL` → `FULL` and `TEAM_LIMIT` → `LIMIT`. Added after the v4 checkers: the link reasons PLAYED and ORGANISER_GONE and the attempt reasons MATCH_PLAYED, ORGANISER_GONE and MEMBERS_ONLY (2.4). Removed in v5 (R1, R3, R6): `CAPTAIN_CODE_REQUIRED`, `CAPTAIN_CODE_INVALID`, `OPEN_MATCH_MEMBERS_ONLY` and the attempt reason MEMBERS_ONLY; the `SEATS_INVALID` reasons `STARTERS_RANGE` and `TOTAL_RANGE`; the roster reason `TOO_MANY`. Added in v5: `TEAM_TOO_SMALL`, `TOO_MANY_SUBSTITUTES`, the scopes of `SIGNUP_LINK_LIMIT` and `TEAM_LIMIT_REACHED`, the quota scope `TEAM_UPLOADER` and the pool `TEAM`, and the 429 scope `TEAMS`.

Status rules: only a lost session answers 401, so a wrong password on a logged-in action answers `403 PASSWORD_WRONG` and does not log the user out. `403 NOT_A_MEMBER` is never `404 MATCH_NOT_FOUND`.

### 9.6 Idempotency

| Operation | Mechanism |
|---|---|
| team create, open match create, match request, SOLO record | client UUID and PUT; a repeat answers 200 to readers, without link secrets |
| seat counts and moves | the body states the final counts and types, so a repeat of the same body changes nothing and answers 200 |
| claim | one open attempt per person and pool; a repeat returns it and never acknowledges |
| ack | arrival before the deadline; a repeat after HOLDING answers 200 |
| decision | repeating the reached outcome answers 200; anything else `409 ATTEMPT_CLOSED` |
| leave queue, accept, decline, cancel, opponent confirm, leave match, solo, open match leave, cancel, transfer offer, accept and decline | state check; the reached state answers 200 (204 where the record or the row is gone) |
| sibling record (team match or open match) | client UUID and PUT, like a match request |
| roster edit and confirm (team sides and open matches), result confirm | `expectedVersion`, `version`, `revision` |
| analyses, decisions | unchanged keys, after the access check |
| register | not idempotent; a lost answer leads to `409 USERNAME_TAKEN`, and the app then logs in with the credentials it still holds in memory |

---

## 10. Android

### 10.1 Navigation

The start route (`VolleyStatApp.kt` line 132 at 7f18839) becomes: WELCOME until `welcomeSeen`; then, without a session, `start` while no account has ever logged in on this phone (`accountSeen` false) and LOGIN otherwise; else HOME. A pending share link overrides all three: the app opens `link` first and keeps the link in the encrypted pending store for 30 minutes (10.3), so a sign-up that the system kills on the way does not lose it. A newcomer who taps the installer's Aç button after a sideload reaches `start`, whose main action is Kod Gir, instead of a sign-up form that ends in `SIGNUP_CLOSED` (R6). v5: after the first successful login or registration of an account on this phone, the app may open `entry` once before HOME, by the rules of 10.10.

| Route | Screen | Bar |
|---|---|---|
| `start` | first start without an account: `first_start_body`, Kod Gir as the main button (with Panodaki Kodu Kullan when the clipboard yields one code), Giriş Yap, Hesap Oluştur and Kayıt Kodu İste as the secondary ones (R6; v5 adds Hesap Oluştur, which asks for a code first, `register_code_first` or `register_code_first_any`, and its checkers add Kayıt Kodu İste, which opens the mail app with the feedback address, C3). This is the signed-out state of the entry choice (10.10) | none |
| `entry` | the entry choice (v5, R5): `entry_title`, `entry_body`, four `SelectCard`s, Şimdi Değil on the first opening; opened once after the first sign-in on a phone, and again from Takımlar and Profil (10.10) | none |
| `login` | Giriş Yap: username, password; links Hesap Oluştur and Kod Gir; with a pending link the team card or the open match line and `link_login_intro` or `link_login_intro_open` on top; debug-only server choice | none |
| `register` | Hesap Oluştur: display name, username (suggested from the display name: Turkish letters folded to ASCII, lowercased, characters outside `[a-z0-9._]` dropped, a leading letter ensured, editable), password; the link or operator code it signs up with; with a pending link the team card. A phone on which no account has ever logged in opens this screen first when a link that opens sign-up is pending, with Hesabım Var as the secondary action (F1). Without a pending link or code it asks for the code or the pasted message first (`register_code_first`), before name and password (R6). That field also takes an operator code, which it reads before any join code (C3 of the v5 checkers): (0) the typed text with spaces and hyphens removed and its letters upper-cased is exactly 16 characters of the code alphabet; or (0b) the text holds no link-host URL with `#t=` and exactly one printed group `(?<![0-9A-Za-z])[2-9A-HJKMNP-Z]{4}(?:-[2-9A-HJKMNP-Z]{4}){3}(?![0-9A-Za-z])`. Such a code goes to the request as `operatorCode` and is never read as join codes; v5's first text read a printed operator code `K7QF-2M9X-AB3D-EF4G` as two join codes (`code_many`) and the same code without hyphens as none. Otherwise the rules of `code` apply. The form then shows the code it signs up with, as B2's Kayıt kodu field does. An OPEN_MATCH link with `signup: false` never opens this screen; `open_need_account` shows instead (D1i). | none |
| `link` | landing: resolves, then routes to `join`, `request/open`, `register`, `login`, `open_need_account` or an error state (2.12 A) | none |
| `code` | Kod Gir: one field that accepts a typed code or a pasted WhatsApp message (F1, F7). A typed code ignores case, spaces and hyphens. Before the steps below, Kod Gir applies the operator-code check of `register` (C3 of the v5 checkers): without a session an operator code opens `register` with it, and with a session it shows `code_is_signup_code`. Pasted text is then read in this order (R2, fidelity V5): (1) a link-host URL with `#t=` gives the token, and the app resolves and claims by token, so no code lookup and no code throttle are involved; (2) otherwise a `c=` parameter, `[#&]c=([2-9A-HJKMNP-Z]{8})`, gives the code; (3) otherwise the case-sensitive pattern `(?<![0-9A-Za-z])[2-9A-HJKMNP-Z]{4}-?[2-9A-HJKMNP-Z]{4}(?![0-9A-Za-z])` (the code alphabet, without L), with hyphens removed and values de-duplicated, gives a code only when exactly one distinct value remains. Panodaki Kodu Kullan shows only when the clipboard yields exactly one. v3's first pattern ignored case and allowed L, so on the code-only message it matched `lleyStat`, `uygulama` and `kaptanda` before the code, and the first match was the code in only 15.5 % of link messages (`verify_v3/paste_probe.py`). With the new order, 20 000 messages of each share text, the request text and a bare code always gave exactly the token or the code (`verify_v3_fix/extract_probe.py`). The open match share texts (2.12 F) hold one URL and one code in the same forms, so the same order applies; `CodeExtractionTest` adds them (14.1 B6c). | none |
| `join/{poolId}` | host of the join flow for both kinds: claim, waiting screen, pop-up dialog, outcomes (10.2) | none |
| `join/{poolId}/jersey` | Forma Numaran (team jersey, or the suggested jersey of an open match) | none |
| `teams` (tab) | Takımlar: my teams with role chip, the seat line (as and yedek taken of each) and the new-member badge; Takım Kur; Kod Gir; the row Nasıl Kullanacağını Seç (`entry_again`, 10.10) | tab |
| `team/{id}` | team: logo, name, tag, schedule, members under Aslar and Yedekler; captain: invite card with live counts, Son katılanlar, `team_starter_free_hint`, member removal, Çıkarılanlar, Kaptanlığı Devret; Takımdan Ayrıl; team statistics card | none |
| `team/{id}/settings` | captain: name, schedule, logo | none |
| `team/{id}/seats` | captain: As ve Yedek Yerleri: the fixed 6 starter seats and the substitute count with its bound 0-6 (R1), the members with an as or yedek switch each, Kaydet (2.3) | none |
| `teams/new` | Takım Kur: name, the substitute seats (2 filled in, 0-6) next to the fixed 6 starter seats (`team_new_seats_body`; v5 removes the captain code field), my seat (as by default), schedule, logo, my jersey; ends on the invite card with Paylaş | none |
| `open/new` | Açık Maç: date, start time, venue, name (optional), kind, then `MatchRulesCard`, the substitute seats (2 filled in, 0-12) next to the fixed 12 starter seats, Ben de oynuyorum (on), side names (A Tarafı and B Tarafı filled in); Maçı Aç ends on the link card with Paylaş and, from New Match, continues to the recorder | none |
| `open/{id}` | the open match: name or "Açık Maç", organiser, date, time, venue, rule, my seat and side, the players under A, B and Tarafı Belli Değil; organiser: link card with live counts (its text chosen in the order of 2.12 F), Taraflar, As ve Yedek Yerleri, Kadro, Video Ekle, Aynı Maçın İkinci Kaydı, Sonucu Onayla, Çıkarılanlar, Maçı Devret (with the pending offer and Teklifi Geri Al), Maçı İptal Et, Maçı Sil; player: Forma Numaran, Maçtan Ayrıl or Bu Maçta Oynamadım, `open_waiting_roster` or `open_not_on_roster` (F5), the transfer offer card with Kabul Et and Reddet for the player it names (V4-C1), and `privacy_open_match` (R7); a cancelled open match shows `open_cancelled`, one without an organiser `open_no_organiser` | none |
| `open/{id}/seats` | organiser: the same screen as `team/{id}/seats` for the open match's pool | none |
| `open/{id}/sides` | organiser: Taraflar: the proposal (stored per player, 5.8.5) or the saved split, each player movable between A, B and Tarafı Belli Değil and between as, yedek and libero, the players' suggested jerseys filled in, Kaydet | none |
| `open/{id}/roster` | organiser: Kadro: jerseys per side (the players' suggestions filled in), 6 starter places per side with each empty one drawn as "Boş" and the texts of `tm/{id}/roster/{side}` (R3), Kadroyu Onayla | none |
| `roster/{id}` | today's `TeamRosterScreen` of a local roster, opened only from the personal match flow | none |
| `request/new` | Maç İsteği: my team (when captain of several), date, start time, venue, kind, then `MatchRulesCard`; İsteği Oluştur ends on Paylaş and, from New Match, continues to the recorder | none |
| `request/open` | an opened request: requesting team with tag, date, time, venue, rule; Hangi takımınla?; Kabul Et and Reddet | none |
| `tm/{id}` | team match: status, both sides with video, review and result states and the roster counts (6 + 2 per side by default); captain actions (Rakibi Onayla, Rakibim Değil, Video Ekle, Sonucu Onayla, Maç Kadrosu, İsteği İptal Et, Maçı Takımımdan Kaldır; on a closed request with a held recording Yalnız Takımım İçin Kaydet and Aynı Rakiple Yeni İstek, R1) | none |
| `tm/{id}/report?job=` | the report of one job by server id, read-only, with the viewer's `result_changed_*` text when the own side result differs; reuses the stateless `ReportScreen`; an open match opens the same screen | none |
| `tm/{id}/roster/{side}` | Maç Kadrosu: starts from the team's members on the match day with their seat types (`TEAM_DEFAULT`, or the stored rows once the server froze the default, 6.3); a tick, an as, yedek or libero switch and a jersey per member; 6 starter places with each empty one drawn as "Boş" (`roster_place_empty`), `roster_empty_place_body` below them while any place is empty, and `roster_empty_place_hint` when a substitute is listed too (6.3, R3); Kadroyu Onayla; `roster_changed_since` while the saved roster differs from the confirmed one; for a member "Bu Maçta Ben Yoktum" | none |
| `me/stats` | Kişisel İstatistik: totals, then lines of team matches and open matches with their kind and role | none |

Existing tabs:
- **Home** lists pending requests, accepted requests whose opponent the captain has not confirmed, recordings waiting for acceptance, team matches waiting for the captain's result confirmation, open matches whose day has not passed (organised or joined), open matches waiting for the organiser's roster or result, and the personal flow as today.
- **New Match** asks "Hangi maçı kaydedeceksin?" with Takım maçı (captains only), Açık maç and Kişisel maç. A team match continues with "Hangi maç?": the own team's accepted records and pending requests without a video of the own side, Yeni İstek, and (default yes, 5.7) Rakip uygulamada değil. An open match continues with the caller's organised open matches without a video and Yeni Açık Maç. Then today's `new/video`, `new/record` and `new/check` screens run unchanged, with the record's names and rule (F5). A member who captains no team sees Açık maç and Kişisel maç with `new_match_member`. v5 removes v4's `openMatches.allowed` condition and `new_match_personal_only`, because every account may open an open match (R6).
- **Matches** merges team matches, open matches and personal matches; an open match row opens `open/{id}`.
- **Profile** gains display name, Şifreyi Değiştir, Kişisel İstatistik, Çıkış Yap, Hesabı Sil and, since v5, the row Nasıl Kullanacağını Seç (`entry_again`, 10.10). The local "Adın" field goes.

### 10.2 The join flow on the phone

`JoinController`, a plain class the one `AppViewModel` owns (ADR-0107 kept: no second ViewModel, no DI), runs the client side of section 2 for both kinds of join link; only the texts pick a form, by `target.kind` (2.12 rule 9):

| Client state | Enters on | Shows |
|---|---|---|
| `Resolving` | link or code opened | `link_loading` |
| `NeedsAccount(team)` | resolve answered and no session | `register` or `login` with the team card (10.1) |
| `WaitingForFocus` | session present; the join screen is composed but its window has no focus (a system sheet, the autofill save sheet, a cold start) | `link_loading` |
| `Claiming` | the window has focus after the first frame (`LocalWindowInfo.current.isWindowFocused`) | `link_loading`; resolve runs in parallel for a team's small logo |
| `Resuming(attemptId)` | a stored resume record exists and the intent is not a fresh tap | `link_loading`, then `GET /v1/join-attempts/{id}` |
| `Deciding(D, seat)` | HOLDING | pop-up with the seat line |
| `Sending(decision)` | tap; the resume record already holds the decision | `join_sending` |
| `TimeUp` | the phone's zero | `join_time_up` |
| `Waiting(seats, ahead)` | WAITING | waiting screen with the seats left of each type |
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
- **Resume, not re-claim (C5).** The first claim answer replaces the pending link with a resume record `{userId, poolId, kind, attemptId, link, sentDecision, createdAt}`, plus `playedOn` for an open match (F4), in the encrypted store. It keeps the link (token or code), so Yeniden Sıraya Gir can send a fresh claim after a process death; the pending store already held the same secret, so P8 is unchanged (fidelity V6). After a process death, a restart, or a return from recents, the app resumes with `GET /v1/join-attempts/{id}` and shows the true state; after a closed outcome it shows that outcome and sends no claim. Only a fresh tap on a link (an intent that arrives without saved state and without `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`, or through `onNewIntent`) or Yeniden Sıraya Gir creates a new attempt. The resume record goes when the person closes the outcome, or after 24 h; only the account in `userId` resumes it.
- **Session end (R5).** A `401 AUTH_REQUIRED` keeps `pending.bin`. The app opens `login` with the team card or the open match line and `link_login_intro` or `link_login_intro_open`, and after a login by the same account it sends the claim, or resumes with `GET`, without a new tap. A login by another account discards the resume record.
- The pop-up survives rotation, because its state lives in the ViewModel.

### 10.3 What lives where on the phone (P8)

| File | Content | Cleared when |
|---|---|---|
| `filesDir/device.json` | `serverTarget`, `welcomeSeen`, `legacyHandled`, `accountSeen` (any account ever logged in here) | never (no personal data, no secret) |
| `filesDir/session.bin` | `{token, userId, username, displayName, expiresAt}`, AES-256-GCM with a key in AndroidKeyStore behind a `SessionCipher` interface (tests use a plain cipher) | logout, `AUTH_REQUIRED`, account deletion |
| `filesDir/device.bin` | device tokens of past logins, keyed by the SHA-256 of the username, encrypted through `SessionCipher` (3.2) | account deletion, or a `401` on a login that sent the token |
| `filesDir/pending.bin` | the pending link (30 min) and the join resume record with its link (24 h), encrypted through `SessionCipher` | when the join or request flow ends, at expiry, logout and account deletion; kept through `AUTH_REQUIRED` (R5) |
| `filesDir/accounts/<userId>/links.bin` | URLs and codes of the share links this phone created (team invites, open match links, match requests), encrypted through `SessionCipher` | renewal, closing, expiry, logout, account deletion |
| `filesDir/accounts/<userId>/volleystat.json` | `LocalStore` version 3: this account's MatchRecords (personal matches, and team and open match recordings made on this phone, with `teamMatchId` and the upload hold), local rosters, the draft; since v5 also `entrySeen` (a boolean, false when missing, so the version stays 3; the B6c app sets it in every folder that exists at its first start; 10.10) | account deletion |
| `filesDir/accounts/<userId>/recordings/` | this account's camera recordings | account deletion |
| `filesDir/accounts/<userId>/cache.json` | the server's view: teams with members' display names and seats, team matches, open matches with players' display names, link states without secrets, statistics, small logos by version, each with `fetchedAtMs` | logout, `AUTH_REQUIRED`, account deletion |

- The app reads only the logged-in account's folder. Account B on the same phone never sees A's matches, recordings, teammates' names, open matches or links (v2, kept).
- **Legacy data** (v2, kept): on the first login, if `filesDir/volleystat.json` or `filesDir/recordings/` holds anything, the app lists the match titles and asks once whether to move them into this account.
- Backup stays disabled (`allowBackup=false`, `data_extraction.xml`). The password is never stored.

**Team matches come from the server.**
- `GET /v1/matches?scope=team` and `GET /v1/matches?scope=open` fill `cache.json` on start, on resume, on pull and after every action. Nothing polls in the background except the join long-poll and the captain's or the organiser's live view while those screens are open. No push notifications.
- A team match or an open match is a `ServerMatch` keyed by its server id. The Matches tab merges ServerMatches with local MatchRecords by server id. A row whose local record exists (this phone added a video) opens today's review chain keyed by `localId`; any other row opens `tm/{id}` or `open/{id}`.
- **Recording for a team match (F5).** The draft carries `teamMatchId`. `startUpload()` creates the MatchRecord with `session.serverMatchId = teamMatchId`, `teamMatch = true`, the server's names and rule. For a team match `UploadEngine` skips `putMatch` and checks the record with `GET /v1/matches/{id}` before `createUpload`: until the record is ACCEPTED or SOLO and the own side has checked the opponent (or the other side left), the record shows `video_waiting_accept` while PENDING, `opponent_check_needed` while the own check is missing, and `request_held_options` once the request is DECLINED, EXPIRED or CANCELLED (R1), and the engine stops without an error; the app tries again on start, resume, pull and after every action. A request created offline is sent first with its client id. An open match created offline is sent first the same way; its upload then starts at once, because only its organiser uploads and no acceptance exists (5.8.6).
- **The review's player list (R1).** A MatchRecord with `teamMatchId` (a team match or an open match recorded on this phone) opens today's review chain, but its SCORER card and its Players screen take their jerseys and names from `GET /v1/matches/{id}/roster` (or the open match roster) when the review opens, not from `state.teams` (`VolleyStatApp.kt` line 156 and 218). Local rosters (`roster/{id}`) serve personal matches only. For an open match the review opens only after the split is saved (5.8.5, R2).
- **What stays local only:** personal matches and their review chain, local rosters with player names (never sent), drafts, recordings, upload positions, the last analysis summary per local record.

### 10.4 Screens keyed by the server

`tm/{id}`, `open/{id}` and `tm/{id}/report?job=` need only server ids, and the review chain of a server match reads its player list from the server roster (10.3, R1). The report comes from `GET /v1/matches/{id}/report?jobId=`, where the server checks that the job belongs to the record and applies the content rule (6.1, S4); the states, video ids and job ids from the Match payload. The other captain confirms from `tm/{id}/report`. Full review stays on the phone that added the video. Reviewing the other side's video from a phone that did not add it, and clip playback for the other side, are left out (section 15).

### 10.5 HTTP and playback

- `ApiClient(baseUrl, correctionTimeoutMs, token)`. `request()` adds `Authorization: Bearer` whenever a token is set; every call passes through it. It does not follow redirects (`instanceFollowRedirects = false`, `ApiClient.kt` line 323), which stays.
- The long-poll uses `request()` with a read timeout of `wait` + 3 s and sends `view` and `wait` (2.6).
- `ClipPlayer` builds both players with `DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))`. The URL stays `videoContentUrl(videoId)`, so no token ever sits in a URL.
- `ApiClient.deleteMatch` stays for personal matches. Team records use `leaveMatch`, which accepts 200 and 204. Open matches use `leaveOpenMatch`, `cancelOpenMatch` and `deleteOpenMatch`.
- Logo encoding and decoding follow 4.2. On API 26-29 the app encodes with `CompressFormat.WEBP` at quality 80 (lossy below 100), on API 30 and later with `WEBP_LOSSY`; a test on an API 26 emulator checks that the server accepts the result.

### 10.6 Deep links

- `MainActivity` gains an intent filter for `https://<link host>/j/` and `/m/` with `android:autoVerify="true"`. `/j/` carries both kinds of join link; the app learns the kind from resolve. The host comes from a Gradle property at build time (B6a), so the address is not committed to the public repository.
- A debug build adds the host `volleystat.test`, so tests open links with `adb shell am start -a android.intent.action.VIEW -d "https://volleystat.test/j/#t=<token>&c=<code>"`.
- The app takes the token and code from the fragment, never from a query or a path.
- A fresh tap and a resume are told apart as in 10.2.

### 10.7 Error mapping

| Code | App |
|---|---|
| `AUTH_REQUIRED` | clears the session and the cache, keeps the pending store (R5), navigates to `login` with `popUpTo(0)` (with the team card when a link or an open attempt is pending), shows `session_ended`. After a login by the same account the join continues without a new tap. A running upload stops and resumes from the server's offset after login (ADR-0111). |
| `NOT_A_MEMBER` | "erişim yok" state; the local record stays and Telefondan Kaldır removes it locally. `removeMatch` never treats it as deleted. |
| `CAPTAIN_ONLY`, `REVIEWER_ONLY`, `ORGANISER_ONLY`, `NOT_A_PLAYER` | read-only state with the reason |
| `MATCH_NOT_CONFIRMED`, `OPPONENT_NOT_CHECKED` | the recording waits (`video_waiting_accept` or `opponent_check_needed`) |
| `SIDE_HAS_VIDEO` | `video_one_per_side`, `video_one_per_side_solo` or `video_one_per_open_match` |
| `SEATS_INVALID`, `SEATS_BELOW_HOLDERS`, `SEATS_IN_USE` | `seats_invalid`, `seats_invalid_open` or `seats_invalid_captain` by `details.reason`, `seats_below_*`, `seats_in_use_*` (2.12 F), and below a SUBSTITUTE refusal of a call that moved a starter to a substitute seat `seats_raise_or_swap_hint` or `seats_swap_hint` (S8 of the v5 checkers); the seat screen keeps the input |
| `OPEN_MATCH_LIMIT`, `OPEN_MATCH_PLAYED`, `MATCH_DATE_PAST`, `MATCH_CANCELLED`, `MATCH_HAS_CONTENT` on an open match | `join_open_limit` or `open_limit_organised` by `details.scope`; `open_closed_played`, `open_closed_video` or, on settings, `open_date_locked_day` / `open_date_locked_video` by `details.reason`; `open_date_past`; `open_cancelled`; on cancel `open_cancel_has_video` or `open_cancel_refused` by `details.reason`; on delete `open_delete_refused` |
| `TEAM_LIMIT_REACHED` | `join_team_limit` (`MEMBER`, on a claim or an ACCEPT); `team_limit_captain` (`CAPTAIN`, on Takım Kur); `transfer_captain_limit` (`CAPTAIN`, on Kaptanlığı Devret) (v5) |
| `TEAM_TOO_SMALL` | `upload_team_too_small`; the recording stays on the phone and the app tries again on start, resume, pull and after every action (v5) |
| `OPEN_MATCH_TOO_SMALL` | `upload_open_too_small`; the recording stays on the phone and the app tries again on start, resume, pull and after every action |
| `UPLOAD_IN_PROGRESS`, `REVIEW_IN_PROGRESS` | `transfer_upload_in_progress` or `transfer_review_in_progress` in the team or open-match form (4.3, 5.8.4) |
| `TRANSFER_NOT_OFFERED`, `SIBLING_LIMIT` | refresh and show the current state; `SIBLING_LIMIT` also shows `sibling_limit` |
| `ROSTER_INVALID` | `roster_too_many` or `roster_jersey_missing` by `details.reason`; the editor marks the entries |
| `QUOTA_EXCEEDED`, `STORAGE_FULL` | `upload_quota_personal`, `upload_one_unfinished`, `upload_quota_open`, `upload_one_unfinished_open`, `upload_quota_team`, `upload_quota_team_uploader` (v5), `upload_quota_server` or `upload_quota_server_open` or `upload_quota_server_team` (v5) by `details.pool`, `upload_storage_full`; the recording stays on the phone |
| `SERVER_BUSY` | waits `Retry-After` and repeats after each `Retry-After` while the screen that sent the request is open (R10) |
| `TOO_MANY_ATTEMPTS` | the wait from `Retry-After`, with the text for `details.scope` (3.6) |
| `REQUEST_CLOSED`, `ROSTER_STALE`, `RESULT_STALE` | refresh and show the current state |
| join and link codes | 2.12 |
| a non-JSON answer or a timeout | `net_offline` or `net_no_answer` by `NET_CAPABILITY_VALIDATED` (F6) |

`loadReport` and `loadRallies` today turn every failure into UNREACHABLE; they check `AUTH_REQUIRED` and `NOT_A_MEMBER` first.

### 10.8 Turkish copy outside the join flow

Rows marked "v2" are unchanged since v2, rows marked "v3" since v3, and rows marked "v5" are new or rewritten in v5; the others are new or rewritten in v4. v5 removes the rows of v4's captain-code field (`team_code_body`, `team_code_invalid`) and of the members rule (`new_match_personal_only`, `open_new_members_note`, `open_new_members_only`, and `join_members_only_open` in 2.12).

| Exact state | Key | Text |
|---|---|---|
| login screen (v2) | `login_intro` | Sunucu takımlarını ve maçlarını hesabına bağlı saklar. Kullanıcı adınla ve şifrenle giriş yaparsın. |
| `401 LOGIN_FAILED` (v2) | `login_failed` | Sunucu bu kullanıcı adını ve şifreyi eşleştiremedi. Kullanıcı adını ve şifreni kontrol edip yeniden deneyebilirsin. |
| `429` on login, `details.scope: USER` (v3) | `login_wait` | Sunucu bu kullanıcı adı için çok sayıda yanlış deneme gördü ve girişi %1$s süreyle durdurdu. Şifreni unuttuysan Voleybol Ve Veleybol şifreni sıfırlayabilir. |
| `429` on login, `details.scope: DEVICE` (v3, S2) | `login_wait_device` | Sunucu bu telefondan bu kullanıcı adı için çok sayıda yanlış deneme aldı ve bu telefondan girişi %1$s süreyle durdurdu. Şifreni unuttuysan Voleybol Ve Veleybol şifreni sıfırlayabilir. |
| `429` on login, `details.scope: SOURCE` (v3, fidelity V10) | `login_wait_source` | Sunucu bu internet adresinden çok sayıda yanlış giriş denemesi aldı ve bu adresten girişi %1$s süreyle durdurdu. |
| `AUTH_REQUIRED` (v3, R5, fidelity V10) | `session_ended` | Oturumun kapandı. Sen yeniden giriş yapınca uygulama yarım kalan katılma isteğini ve video yüklemelerini sürdürür. |
| register screen (v3, F6) | `register_data` | Sunucu hesabın için kullanıcı adını, şifrenin özetini ve görünen adını saklar. Uygulama e-posta adresini ve telefon numaranı sormaz. |
| register, username field (v3) | `register_username_hint` | Takım arkadaşların, maç arkadaşların ve rakip takım kullanıcı adını görmez. Kullanıcı adı küçük harf, rakam, nokta ve alt çizgi taşıyabilir. |
| register, display name field | `register_display_hint` | Takım arkadaşların ve açık maçlarda maça katılan herkes bu adı görür. Bir takım maçında rakip takım bu adı görmez. |
| `403 SIGNUP_CLOSED`, `details.reason: NO_LINK`, health `accounts.openMatchSignup` OFF (only when the operator set `off`) or missing (before B6c, C5 of the v5 checkers) (v3) | `register_gate` | Sunucu şimdilik yalnızca takım davetiyle ya da kayıt koduyla hesap açar. Kaptandan davet bağlantısı isteyebilirsin. |
| same, health `accounts.openMatchSignup` LINK (the v5 default, R6; F10) | `register_gate_any` | Sunucu şimdilik yalnızca takım davetiyle, açık maç bağlantısıyla ya da kayıt koduyla hesap açar. Kaptandan ya da maçı yöneten kişiden bir bağlantı isteyebilirsin. |
| `422 PASSWORD_REJECTED`, reason COMMON (v3) | `password_common` | Bu şifre çok yaygın şifreler listesinde var. Sunucu bu şifreyi kabul etmez. Başka bir şifre seçebilirsin. |
| Takım Kur, seats (2.3; v5, R1) | `team_new_seats_body` | Bir takımda her zaman 6 as yeri olur. Uygulama yedek yeri sayısını 2 olarak doldurdu. Yedek yeri sayısını 0 ile 6 arasında seçebilirsin. |
| Takım Kur, `409 TEAM_LIMIT_REACHED` with `details.scope: CAPTAIN` (%1$d = `details.limit`; v5, 4.1) | `team_limit_captain` | En çok %1$d takımın kaptanı olabilirsin. Yeni bir takım kurmak için önce bir takımın kaptanlığını devredebilir ya da bir takımı silebilirsin. |
| Kaptanlığı Devret, `409 TEAM_LIMIT_REACHED` with `details.scope: CAPTAIN` (%1$s = the member's display name, %2$d = `details.limit`; v5) | `transfer_captain_limit` | %1$s şu an en az %2$d takımın kaptanı. Sunucu bir kişiye en çok %2$d takımın kaptanlığını verir. |
| Takım Kur, `429`, `details.scope: TEAMS` (%1$s = the wait; v5, 3.6) | `team_wait_create` | Sunucu bu hesaptan son bir günde çok sayıda yeni takım aldı ve yeni takım kurmayı %1$s süreyle durdurdu. |
| Takım Kur, my seat | `team_new_my_seat` | Senin yerin · As / Yedek |
| request screen (v3, fidelity V9) | `request_body` | Uygulama bir istek bağlantısı hazırlar. Sen bağlantıyı rakip takımın kaptanına gönderirsin. Rakip kaptan isteği kabul etmeden de maçı kaydedebilirsin. Rakip kaptan isteği kabul edince ve sen rakibi onaylayınca uygulama videoyu sunucuya gönderir. |
| Paylaş on a request; %4$s = `code_title` (v3) | `request_share_text` | %1$s takımı seninle %2$s günü maç yapmak istiyor. İsteği görmek için bu bağlantıyı aç: %3$s ⏎ Bağlantı uygulamayı açmazsa VolleyStat uygulamasında %4$s ekranına bu kodu yaz: %5$s |
| request opened by a captain (v3) | `request_open_body` | %1$s takımı seninle maç yapmak istiyor. |
| request opened by a captain (v3) | `request_pick_team` | Hangi takımınla oynayacaksın? |
| request opened by someone who captains no team (v3) | `request_need_captain` | Bu isteği bir takımın kaptanı kabul eder. Sunucu senin kaptanı olduğun bir takım bulamadı. |
| `422 SAME_PERSON_BOTH_SIDES`, `details.who: ACCEPTER` (v3) | `request_same_person` | Sen %1$s takımının da üyesisin. Bir maçın iki tarafında da yer alan kişi isteği kabul edemez. |
| `422 SAME_PERSON_BOTH_SIDES`, `details.who: REQUESTER` (v3, fidelity V9) | `request_same_person_requester` | %1$s takımının kaptanı senin takımının da üyesi. Bu yüzden sen bu isteği bu takımla kabul edemezsin. |
| `422 SAME_TEAM_NAME` (v3) | `request_same_name` | İki takımın adı aynı. Kaptanlardan biri takımın adını değiştirirse sen bu isteği kabul edebilirsin. |
| PENDING, HOME captain (v3) | `request_waiting` | Rakip kaptan isteği henüz yanıtlamadı. Sunucu yanıtsız isteği yedi gün sonra kapatır. |
| ACCEPTED, HOME has not checked the opponent; %3$s = the button label (v3) | `request_accepted` | %1$s #%2$s takımının kaptanı isteği kabul etti. Bu takım senin rakibinse %3$s düğmesine dokun. Uygulama senin videonu ancak sen onaylayınca gönderir. |
| button (v3) | `opponent_confirm` | Rakibi Onayla |
| the upload waits for the opponent check (v3) | `opponent_check_needed` | Uygulama videoyu sen rakibi onaylayınca gönderir. |
| DECLINED (v3) | `request_declined` | %1$s takımının kaptanı isteği reddetti. |
| EXPIRED (v3) | `request_expired` | Rakip kaptan isteği yedi gün içinde yanıtlamadı. Sunucu isteği kapattı. |
| DECLINED, EXPIRED or CANCELLED, and this phone holds a recording of the record (v3, R1); buttons `solo_from_request` and `request_again` | `request_held_options` | Bu maçın videosu telefonunda bekliyor. Videoyu yalnız kendi takımının maçı olarak kaydedebilirsin ya da aynı rakibe yeni bir istek gönderebilirsin. |
| buttons (v3) | `solo_from_request` / `request_again` | Yalnız Takımım İçin Kaydet / Aynı Rakiple Yeni İstek |
| ACCEPTED, HOME has not checked the opponent; button (v3, R1) | `opponent_not_mine` | Rakibim Değil |
| Rakibim Değil tapped (v3) | `opponent_not_mine_body` | Sunucu takımını bu maçtan çıkarır. Uygulama telefonundaki videoyu yalnız senin takımının maçı olarak yeni bir kayda bağlar. Rakip takım bu maçtaki kendi videosunu korur. |
| a request link answered `410 LINK_CLOSED` (USED, DECLINED, CANCELLED, EXPIRED) (v3) | `request_link_closed` | Bu maç isteği artık açık değil. İsteyen kaptandan yeni bir istek isteyebilirsin. |
| a recording waits while the record is PENDING (v3, F5, R1, fidelity V9) | `video_waiting_accept` | Rakip kaptan isteği kabul edince ve sen rakibi onaylayınca uygulama videoyu sunucuya gönderir. |
| `409 SIDE_HAS_VIDEO` on a team record (v3) | `video_one_per_side` | Takımının bu maçta bir videosu var. İkinci kaydı eklemek için aynı rakiple yeni bir istek oluşturabilirsin. |
| `409 SIDE_HAS_VIDEO` on a SOLO record (v3, fidelity V9) | `video_one_per_side_solo` | Takımının bu maçta bir videosu var. İkinci kayıt için yeni bir tek taraflı maç oluşturabilirsin. |
| `409 SIDE_HAS_VIDEO` on an open match; %1$s = the `open_sibling` label (R4) | `video_one_per_open_match` | Bu maçın bir videosu var. İkinci video için %1$s düğmesine dokunabilirsin. |
| `open/{id}`, organiser, button `open_sibling` "Aynı Maçın İkinci Kaydı" | `open_sibling_body` | Sunucu bu maçın gününü, saatini, sahasını, oyuncularını ve taraflarını yeni bir kayda kopyalar. Yeni kayda kimse bağlantıyla katılmaz. Yeni kaydın kadrosunu ve sonucunu ayrıca onaylarsın. |
| `409 SIBLING_LIMIT` (%1$d = `details.limit`) | `sibling_limit` | Bu maçın en çok %1$d ek kaydı olabilir. |
| `409 OPEN_MATCH_TOO_SMALL` (%1$d = `details.players`; V4-Q1) | `upload_open_too_small` | Bu açık maçta %1$d oyuncu var. Sunucu en az 2 oyuncusu olan açık maçın videosunu alır. Video telefonunda kalır. |
| `409 QUOTA_EXCEEDED`, PERSONAL (v3) | `upload_quota_personal` | Kişisel maç videoların sunucuda %1$s yer kaplıyor. Sunucu hesap başına en çok %2$s saklar. Yeni video için önce eski bir kişisel maçın videosunu silebilirsin. |
| `409 QUOTA_EXCEEDED`, PERSONAL, `details.reason: UNFINISHED_UPLOAD` (v3, S7) | `upload_one_unfinished` | Bu hesabın yarım kalmış bir kişisel video yüklemesi var. Sunucu bu hesaptan aynı anda tek bir kişisel yükleme kabul eder. Yarım kalan yükleme bitince ya da sunucu onu bir gün sonra silince uygulama bu videoyu gönderir. |
| `409 QUOTA_EXCEEDED`, OPEN_MATCH (V4-Q1, F3) | `upload_quota_open` | Açık maçlara yüklediğin videolar sunucuda %1$s yer kaplıyor. Sunucu bir hesabın açık maç videolarına en çok %2$s yer ayırır. Maçı yöneten kişi bu videolardan birini silerse sunucu yeni videoyu kabul eder. Videoyu silmek oyuncuların sayılarını değiştirmez. |
| `409 QUOTA_EXCEEDED`, OPEN_MATCH, `details.reason: UNFINISHED_UPLOAD` | `upload_one_unfinished_open` | Bu hesabın yarım kalmış bir açık maç video yüklemesi var. Sunucu bu hesaptan aynı anda tek bir açık maç yüklemesi kabul eder. Yarım kalan yükleme bitince ya da sunucu onu bir gün sonra silince uygulama bu videoyu gönderir. |
| `409 QUOTA_EXCEEDED`, SERVER, `details.pool: PERSONAL` (S7, V4-Q1) | `upload_quota_server` | Sunucu bütün hesapların kişisel maç videolarına en çok %1$s yer ayırır. Bu videolar bu yeri doldurdu. Video telefonunda kalır. Uygulama sonraki açılışta yeniden dener. |
| `409 QUOTA_EXCEEDED`, SERVER, `details.pool: OPEN_MATCH` (V4-Q1) | `upload_quota_server_open` | Sunucu bütün açık maç videolarına en çok %1$s yer ayırır. Bu videolar bu yeri doldurdu. Video telefonunda kalır. Uygulama sonraki açılışta yeniden dener. |
| `409 QUOTA_EXCEEDED`, TEAM (v3, R10) | `upload_quota_team` | Takımının videoları sunucuda %1$s yer kaplıyor. Sunucu takım başına en çok %2$s saklar. Eski bir videoyu silersen uygulama yeni videoyu gönderebilir. Takımın istatistiği silinen videolarla değişmez. |
| `409 QUOTA_EXCEEDED`, TEAM_UPLOADER (v5, 6.5) | `upload_quota_team_uploader` | Takım maçlarına yüklediğin videolar sunucuda %1$s yer kaplıyor. Sunucu bir hesabın takım videolarına en çok %2$s yer ayırır. Her videoyu o videonun takımının kaptanı silebilir. Kaptan bu videolardan birini silerse sunucu o videonun boyutunu senin payından düşer. Takımların istatistiği silinen videolarla değişmez. |
| `409 QUOTA_EXCEEDED`, SERVER, `details.pool: TEAM` (v5, 6.5) | `upload_quota_server_team` | Sunucu bütün takımların videolarına en çok %1$s yer ayırır. Bu videolar bu yeri doldurdu. Video telefonunda kalır. Uygulama sonraki açılışta yeniden dener. |
| `409 TEAM_TOO_SMALL` (%1$d = `details.members`; v5, 6.5) | `upload_team_too_small` | Takımında %1$d üye var. Sunucu en az 2 üyesi olan bir takımın videosunu alır. Video telefonunda kalır. Uygulama videoyu her açılışta yeniden göndermeyi dener. |
| `507 STORAGE_FULL` (%1$s = `details.floorBytes`, the free-disk floor; v3, R10, fidelity V10) | `upload_storage_full` | Bu video sunucunun diskindeki boş yeri %1$s sınırının altına indirirdi. Sunucu bu yüzden videoyu almadı. Video telefonunda kalır. Uygulama sonraki açılışta yeniden dener. |
| result confirm, own side (v3) | `result_confirm_body` | Sonucu onaylarsan sunucu bu maçı takımının istatistiğine katar. Rakip takımın istatistiği rakip kaptanın onayına bağlıdır. |
| own side result exists, other side not confirmed (v3) | `result_waiting_other` | Rakip kaptan bu sonucu kendi takımı için henüz onaylamadı. Bu durum senin takımının istatistiğini değiştirmez. |
| report, viewer is the other side's captain, the reviewer changed the result after this captain confirmed (v3, F6) | `result_changed_other_captain` | %1$s takımının kaptanı sonucu değiştirdi. Yeni sonucu onaylarsan sunucu takımının istatistiğini günceller. Onaylamazsan takımının istatistiği önceki onaylı sonucu gösterir. |
| report, viewer is the reviewer, team record (v3) | `result_changed_reviewer` | Sen sonucu değiştirdin. Rakip kaptan yeni sonucu onaylarsa sunucu onun takımının istatistiğini de günceller. |
| report, viewer is the reviewer, SOLO record (v3, fidelity V9) | `result_changed_reviewer_solo` | Sen sonucu değiştirdin. Sonucu yeniden onaylarsan sunucu takımının istatistiğini günceller. |
| report, viewer is the organiser of an open match | `result_changed_organiser` | Sen sonucu değiştirdin. Sonucu yeniden onaylarsan sunucu oyuncuların bu maçtaki sayılarını günceller. |
| report, viewer is a member, own side result differs from the job (v3) | `result_changed_member` | Takımının istatistiği kaptanının onayladığı sonucu gösterir. İnceleyen kaptan sonucu sonradan değiştirdi. Senin kaptanın yeni sonucu henüz onaylamadı. |
| report, viewer is a player of an open match, the result differs from the job (F3) | `result_changed_player` | Kişisel istatistiğin bu maçın onaylı sonucunu gösterir. Maçı yöneten kişi sonucu sonradan değiştirdi ve yeni sonucu henüz onaylamadı. |
| team match roster screen (6.3) | `roster_body` | Sunucu kadroyu maç günü takımında olan üyelerle başlatır. Bu maçta oynayanları, as, yedek ya da libero olduklarını ve forma numaralarını seç. Sunucu bir sayıyı bir üyeye yalnızca sen kadroyu onaylayınca yazar. |
| roster screen, the saved roster differs from the confirmed one (6.3, V4-S3) | `roster_changed_since` | Kadronun son hâli onaylı kadrodan farklı. Sen kadroyu yeniden onaylayana kadar sunucu istatistikte onaylı kadroyu kullanır. |
| roster at a bound (6.3, R6; v5) | `roster_too_many` | Bir tarafın maç kadrosunda en çok 6 as olur. Yedekler ve liberolar birlikte en çok 6 kişi olur. Bir tarafta en çok 2 libero olur. |
| roster editor, an empty starter place (6.3, R3; v5); content description `roster_place_empty_a11y` "Boş as yeri" | `roster_place_empty` | Boş |
| roster editor and roster view, a side with empty starter places (%1$d = their number; v5, R3) | `roster_empty_place_body` | Kadroda bu tarafın %1$d as yerinde kimse yok. Maçta hesabı olmayan biri oynadıysa onun yeri de boş kalır. Sunucu boş yer için kimseye sayı yazmaz. |
| roster editor, a side with an empty starter place and a substitute (%1$d = empty places, %2$d = substitutes; v5, R3) | `roster_empty_place_hint` | Bu tarafta %1$d boş as yeri ve %2$d yedek var. Maça bir yedek başladıysa onu as yapabilirsin. |
| SCORER card of a team match, the winning side's server roster has no jersey (R1); for the own side with a button `Maç Kadrosu` to `tm/{id}/roster/{side}` | `review_no_roster_server` | Bu tarafın kadrosunda forma numarası yok. Bu tarafın kaptanı numaraları yazınca oyuncu seçebilirsin. |
| same, open match; button `Kadro` | `review_no_roster_open` | Bu tarafın kadrosunda forma numarası yok. Sen numaraları yazınca oyuncu seçebilirsin. |
| SCORER card, the chosen jersey is a LIBERO and the point type is BLOCK or ACE (R6, rule 19.3.1.3) | `review_libero_warning` | Kadroda %1$d numaralı oyuncu libero. Kurallara göre libero servis atamaz ve blok yapamaz. |
| SCORER card of a team match or an open match, below the roster's jerseys; the answer opens a number field (v5, 6.3, S2 of the v5 checkers) | `review_other_jersey` / `review_other_jersey_body` | Kadroda Olmayan Numara / Kadroda olmayan bir oyuncu sayı aldıysa onun forma numarasını yazabilirsin. Onaylı kadroda bu numara olmadıkça sunucu bu sayıyı hiçbir oyuncuya yazmaz. |
| the number field holds a jersey of that side's roster (%1$d = the number; v5) | `review_other_jersey_taken` | Kadroda %1$d numaralı bir oyuncu var. Bu oyuncuyu listeden seçebilirsin. |
| the number field holds a number outside 1-99 (v5) | `review_other_jersey_range` | Forma numarası 1 ile 99 arasında olmalı. |
| `422 ROSTER_INVALID`, `JERSEY_MISSING` (%1$d = `details.count`) | `roster_jersey_missing` | Kadroyu onaylamak için her oyuncunun bir forma numarası olmalı. %1$d oyuncunun numarası eksik. |
| leave confirm (v3) | `leave_match_title` | Bu maçı takımından kaldırmak istiyor musun? |
| leave confirm, team record (v3) | `leave_match_body` | Sunucu takımının videosunu, kadrosunu ve bu maçın takım istatistiğini siler. Takımının üyeleri bu maçı artık göremez. Rakip takım kendi videosunu ve kendi onaylı sonucunu korur. |
| added to `leave_match_body` when the record holds a result offer for the other side from this side's list confirmation (v3, R7) | `leave_match_offer` | Rakip kaptan senin onayladığın sonucu kendi takımı için yine de onaylayabilir. |
| leave confirm, SOLO record (v3, fidelity V9) | `leave_match_body_solo` | Sunucu bu maçı, videosunu ve takımının bu maçtaki istatistiğini siler. |
| statistics screen (v3) | `stats_rule_team` | Sunucu bir maçı takım istatistiğine yalnızca kendi kaptanın sonucu onaylayınca katar. |
| statistics screen, team | `stats_rule_team_open` | Açık maçlar hiçbir takımın istatistiğine girmez. |
| statistics screen, person | `stats_rule_person` | Sunucu bir sayıyı sana yalnızca kaptanın ya da maçı yöneten kişinin kadroyu onayladığı maçlarda yazar. İnceleyen kişi o sayıda senin forma numaranı seçmiş olmalı. |
| statistics screen, person, below the totals (R6) | `stats_roles` | %1$d maçta as, %2$d maçta yedek, %3$d maçta libero olarak kadrodaydın. |
| statistics, records without video (v2) | `stats_without_video` | Videosu olmayan %1$d maç istatistiğe girmez. |
| statistics, a team line whose own side's confirmed roster has empty starter places (%1$d = the line's `emptyStarterPlaces`; v5, R3, 7.3; C16 of the v5 checkers names the side) | `stats_empty_places_team` | Takımının bu maçtaki onaylı kadrosunda %1$d boş as yeri var. |
| statistics, a person line, same (v5) | `stats_empty_places` | Senin tarafının bu maçtaki onaylı kadrosunda %1$d boş as yeri var. |
| statistics (v2) | `stats_demo` | Uygulama demo analizlerini istatistiğe katmaz. |
| New Match | `new_match_kind_title` | Hangi maçı kaydedeceksin? |
| New Match, a person who captains no team (v5: every account may open open matches) | `new_match_member` | Takım maçının videosunu kaptan ekler. Açık maç açabilir ya da kişisel maç kaydedebilirsin. Kişisel maç hiçbir istatistiğe girmez. |
| New Match, Açık maç | `new_match_open_body` | Açık maçın videosunu maçı yöneten kişi ekler. Açık maç yalnız kişisel istatistiğe girer. |
| New Match, opponent outside the app (v3, 5.7) | `solo_body` | Rakip uygulamayı kullanmıyorsa maçı yalnız kendi takımın için kaydedebilirsin. Sunucu bu maçı yalnızca senin takımının istatistiğine katar. |
| `open/new`, top | `open_new_title` / `open_new_body` | Açık Maç / Bağlantıyı açan ve giriş yapan kişi bu maça katılabilir. Sunucu önce as yerlerini, sonra yedek yerlerini doldurur. Katılanları maçtan önce ya da sonra A ve B tarafına sen ayırırsın. |
| `open/new`, switch | `open_plays` / `open_plays_body` | Ben de oynuyorum / Bu seçenek açıksa sunucu sana bir as yeri ayırır. |
| `open/new`, side names | `open_side_names` | A tarafının adı · B tarafının adı (filled in with `open_side_a` "A Tarafı" and `open_side_b` "B Tarafı") |
| `open/new`, privacy line under the button | `privacy_open_match` | (11.1 privacy rows) |
| `409 OPEN_MATCH_LIMIT`, `details.scope: ORGANISED` (%1$d = `details.limit`), on Maçı Aç or on accepting a transfer | `open_limit_organised` | İptal olmamış ve günü geçmemiş en çok %1$d açık maçı yönetebilirsin. Yeni bir maç açmak ya da bir maçı devralmak için önce bir maçı iptal edebilir ya da maç gününün geçmesini bekleyebilirsin. |
| `409 OPEN_MATCH_PLAYED` on settings, `details.reason: DAY_STARTED` (F6, V4-S2) | `open_date_locked_day` | Maçın günü başladı. Sunucu bu maçın tarihini artık değiştirmez. |
| same, `details.reason: HAS_UPLOAD` | `open_date_locked_video` | Sunucu bu maçın videosunu aldı. Sunucu bu maçın tarihini ve saatini artık değiştirmez. |
| `429`, `details.scope: OPEN_MATCHES` | `open_wait_create` | Sunucu bu hesaptan son bir günde çok sayıda açık maç aldı ve yeni açık maç açmayı %1$s süreyle durdurdu. |
| `422 MATCH_DATE_PAST` | `open_date_past` | Maçın tarihi bugün ya da daha sonra olmalı. |
| `409 OPEN_MATCH_PLAYED` on Yeni Bağlantı Oluştur | `open_closed_played` | (2.12 F) |
| `open/{id}`, a player's own line | `open_my_seat` | Senin yerin · As / Yedek · Tarafın · %1$s (the side name, or `open_side_none` "Belli değil") |
| `open/{id}`, players list header without a side | `open_sides_none_title` | Tarafı Belli Değil |
| `open/{id}`, organiser's split screen while the roster was never saved | `open_sides_proposed` | Sunucu oyuncuları katılma sırasına göre sırayla A ve B tarafına önerdi. Dağılımı değiştirip kaydedebilirsin. |
| `open/{id}`, organiser, players without a side (%1$d) | `open_sides_unassigned` | Tarafı belli olmayan %1$d oyuncu var. Bu oyuncular bu maçtan istatistik almaz. |
| `open/{id}/roster` | `open_roster_body` | Maçta oynayanları, tarafını ve forma numarasını seç. Sunucu bir sayıyı bir oyuncuya yalnızca sen kadroyu onaylayınca yazar. |
| `open/{id}`, a player with an entry in the saved roster, or any player while the split was never saved; no line of the player in the confirmed roster, or no confirmed result (F5) | `open_waiting_roster` | Maçı yöneten kişi kadroyu ve sonucu onaylayınca sunucu bu maçtaki sayılarını sana yazar. |
| `open/{id}`, a player without an entry in the saved roster and without a line in the confirmed roster (F5) | `open_not_on_roster` | Bu maçın kayıtlı kadrosunda adın yok. Kadroda adı olmayan oyuncu bu maçtan istatistik almaz. |
| `open/{id}`, the organiser deleted their account (3.7, V4-C1) | `open_no_organiser` | Maçı yöneten kişi hesabını sildi. Bu maçın kadrosunu ve sonucunu artık kimse değiştiremez. Maçtan ayrılabilirsin. |
| organiser, result confirm | `open_result_confirm_body` | Sonucu onaylarsan sunucu bu maçtaki sayıları kadrodaki oyunculara yazar. Açık maç hiçbir takımın istatistiğine girmez. |
| player, before the match day; button `Maçtan Ayrıl` | `open_leave_title` / `open_leave_body` | Maçtan ayrılmak istiyor musun? / Sunucu seni bu maçtan çıkarır ve yerini boşaltır. Bağlantı açıksa sunucu yerini sıradaki kişiye ayırır. |
| player, from the match day on; button `Bu Maçta Oynamadım` | `open_not_played_title` / `open_not_played_body` | Bu maçta oynamadın mı? / Sunucu seni bu maçın kadrosundan ve oyuncu listesinden çıkarır. Bu maç kişisel istatistiğine girmez. Bu maçın videosunu ve raporunu artık göremezsin. |
| organiser who holds a seat, button `Maçtan Ayrıl` or `Bu Maçta Oynamadım` | `open_leave_organiser_body` | Sunucu senin yerini boşaltır. Sen maçı yönetmeye devam edersin ama bu maçta oyuncu olmazsın. |
| organiser, button `Maçı Devret`, then the player list (V4-C1, R9) | `open_transfer_body` | Seçtiğin oyuncu teklifini kabul ederse bağlantıyı, yerleri, tarafları, kadroyu ve sonucu o yönetir ve videoyu o silebilir. Bu maçta yerin varsa sen oyuncu olarak kalırsın. |
| organiser, an offer is open (%1$s = the player's display name); button `Teklifi Geri Al` | `open_transfer_pending` | %1$s devir teklifini henüz yanıtlamadı. |
| the player the offer names (%1$s = the organiser's display name); buttons `Kabul Et` / `Reddet` | `open_transfer_offer` | %1$s bu maçı sana devretmek istiyor. Kabul edersen bağlantıyı, yerleri, tarafları, kadroyu ve sonucu sen yönetirsin. |
| `409 UPLOAD_IN_PROGRESS` on a transfer (team form / open-match form) | `transfer_upload_in_progress` / `…_open` | Takımının bir maçında video yüklemesi henüz bitmedi. Sunucu yükleme bitmeden kaptanlığı devretmez. / Bu maçın video yüklemesi henüz bitmedi. Sunucu yükleme bitmeden devri tamamlamaz. |
| `409 REVIEW_IN_PROGRESS` on a transfer (team form / open-match form) | `transfer_review_in_progress` / `…_open` | Takımının bir maçında videonun ralli listesi henüz onaylı değil. Sunucu kaptanlığı ancak liste onaylı olunca devreder. / Bu maçın videosunun ralli listesi henüz onaylı değil. Sunucu devri ancak liste onaylı olunca tamamlar. |
| same, the organiser holds no seat | `open_transfer_no_seat` | Bu maçta yerin yok. Maçı devredince sen bu maçı artık göremezsin. |
| organiser, button `Maçı İptal Et` | `open_cancel_title` / `open_cancel_body` | Maçı iptal etmek istiyor musun? / Sunucu bağlantıyı ve sıradaki herkesin katılma isteğini kapatır. Maça katılanlar maç listesinde iptali görür. |
| a player's list, cancelled open match | `open_cancelled` | Maçı yöneten kişi maçı iptal etti. |
| `409 MATCH_HAS_CONTENT` on cancel (`HAS_VIDEO`; the server answers `RESULT_CONFIRMED` first when a result exists, so this text holds) | `open_cancel_has_video` | Bu maçın bir videosu var. Sunucu videosu olan maçı iptal etmez. Önce videoyu silersen maçı iptal edebilirsin. |
| `409 MATCH_HAS_CONTENT` on cancel (`RESULT_CONFIRMED`, F1) | `open_cancel_refused` | Bu maçın onaylı bir sonucu var ve oyuncuların sayıları bu sonuca bağlı. Sunucu bu yüzden maçı iptal etmez. |
| organiser, button `Maçı Sil` | `open_delete_title` / `open_delete_body` | Maçı silmek istiyor musun? / Sunucu maçı, videosunu, oyuncu listesini ve kadrosunu siler. |
| `409 MATCH_HAS_CONTENT` on delete (`RESULT_CONFIRMED`: another person holds a seat or a confirmed line, F9) | `open_delete_refused` | Bu maçın onaylı bir sonucu var ve başka oyuncuların sayıları bu sonuca bağlı. Sunucu bu yüzden maçı silmez. Videoyu silebilirsin. Sonuç ve oyuncuların sayıları kalır. |
| organiser, link card, starter seat free and substitutes present, nobody waiting | `open_starter_free_hint` | Maçta %1$d boş as yeri ve %2$d yedek oyuncu var. İstersen bir yedek oyuncuyu as yapabilirsin. |
| delete account | `delete_account_title` | Hesabını silmek istiyor musun? |
| delete account (R9, V4-C1, F3) | `delete_account_body` | Sunucu kullanıcı adını, görünen adını, şifre özetini, oturumlarını ve takım üyeliklerini siler. Sunucu kişisel maçlarını da siler. Takımında senden başka üye yoksa sunucu takımı, takımın maç videolarını ve istatistiğini de siler. Başka üyesi olan takımlarda eklediğin videolar takımda kalır. Takım maçlarına eklediğin ve ralli listesini onaylamadığın videoları sunucu siler. Rapor o maçlarda senin sayılarını yalnızca forma numarasıyla gösterir. Yönettiğin açık maçlarda başka oyuncu yoksa sunucu bu maçları siler. Başka oyuncusu olan açık maçları sunucu yöneticisiz bırakır. Oyuncular bu maçları ve videolarını görmeye devam eder ama bu maçların kadrosunu ve sonucunu artık kimse değiştiremez. Uygulama bu telefondaki maç kayıtlarını ve kamerayla çektiğin videoları da siler. |

The open match texts that belong to the join flow (link landing, pop-up, outcomes, waiting screen, link card, seats) are in 2.12.

Privacy rows, rewritten so they state the real behaviour (P5, P6, P2, D1j):

| Key | Text |
|---|---|
| `privacy_server` | Uygulama videoyu, maç bilgilerini, takım bilgilerini ve hesap bilgilerini %1$s adresindeki sunucuya gönderir. (A build for a server behind a tunnel that ends TLS adds `privacy_tunnel`.) |
| `privacy_tunnel` | Sunucuya giden bağlantı %1$s hizmetinden geçer. %1$s bu bağlantıdaki her şeyi, videolar ve şifreler dahil, açık olarak görebilir. |
| `privacy_accounts` | Sunucu hesabın için kullanıcı adını, şifrenin özetini ve görünen adını saklar. Sunucu e-posta adresi ve telefon numarası saklamaz. |
| `privacy_visibility` | Bir takım maçının videolarını ve raporunu iki takımın üyeleri görür. Takıma maç gününden sonra katılan üye o maçı yalnızca kadrodaysa görür. Rakip takım videoda yüzünü ve forma numaranı görür. Rapor her forma numarasının sayılarını gösterir. |
| `privacy_teammates` | Takım arkadaşların görünen adını, forma numaranı, as ya da yedek olduğunu ve maç raporlarında senin numarana yazılan sayıları görür. Kişisel istatistik ekranındaki toplamları yalnızca sen görürsün. |
| `privacy_opponent` | Bir takım maçında rakip takım senin görünen adını ve kullanıcı adını görmez. |
| `privacy_open_match` | Açık maç bağlantısını açıp giriş yapan kişi maçı yöneten kişinin görünen adını, maçın gününü, saatini, sahasını ve boş yer sayısını görür. Maça katılan herkes diğer oyuncuların görünen adını, tarafını ve forma numarasını görür. Maçın videosunu ve raporunu maça katılan herkes görür. Kullanıcı adını kimse görmez. |
| `privacy_join` | Takım davetini açan ve giriş yapmamış kişi takımın adını ve logosunu görür. Giriş yapan kişi ayrıca maç gününü, saatini ve boş yer sayısını görür. Açık maç bağlantısını açan ve giriş yapmamış kişi yalnız bağlantının bir açık maça ait olduğunu görür. |
| `privacy_request` | Maç isteği bağlantısını açan kişi isteyen takımın adını ve logosunu görür. Tarihi, saati, yeri ve maç kuralını yalnızca bir takımın kaptanı görür. |
| `roster_privacy` | Telefondaki kadroya yazdığın oyuncu adları yalnızca bu telefonda durur. Uygulama bu adları sunucuya göndermez. |
| `privacy_retention` | Sunucu hesabını, takımlarını ve maçlarını sen silene kadar saklar. Sunucu biten katılma isteklerini bir gün sonra siler. Sunucu bir kişinin hangi hesabın bağlantısıyla hesap açtığını yedi gün saklar. Sunucu kapanan maç isteklerini otuz gün sonra siler. Maçı yöneten kişi bir açık maçı iptal ederse sunucu o maçı otuz gün sonra siler. (With B10 backups it adds: Sunucu yedeklerdeki kopyaları yedi gün sonra siler.) |
| `privacy_delete` | Sen hesabını silince sunucu hesabını ve kişisel maçlarını siler. Bir takım maçını sunucu iki takım da maçı kaldırınca siler. Bir açık maçtan ayrılınca sunucu seni o maçın kadrosundan çıkarır. Videodan çıkarılmak istersen Voleybol Ve Veleybol'a yazabilirsin. (With B10 backups it adds: Silinen veriler yedeklerde yedi gün daha durur.) |
| `privacy_faces` | Uygulama ve sunucu yüz tanımaz. Rapor bir sayıyı oyuncuya yalnızca inceleyen kişi forma numarasını seçince yazar. |
| `privacy_links_team` (v5, R6; from B6, when invites exist, on Takım Kur, on the invite card and on the register screen; C18 of the v5 checkers) | Kaptan davet bağlantısını istediği kişiye gönderebilir. Sunucu bağlantıyla katılan kişinin kim olduğunu denetlemez. |
| `privacy_links` (v5, R6; from B6c, when organisers exist, in place of `privacy_links_team` on the same screens and also on Açık Maç and the open match's link card) | Kaptan ya da maçı yöneten kişi bağlantıyı istediği kişiye gönderebilir. Sunucu bağlantıyla katılan kişinin kim olduğunu denetlemez. |

### 10.9 Screens and accessibility (v2, kept)

- Stateless composables with fixed data in `ScreenshotTest` at 411 dp, 320 dp (`kucuk_`), dark (`_koyu`) and font scale 1.5 (`buyuk_yazi_`). Every screen has loading, empty, error and full states.
- Only `Theme.kt` tokens and the shared components (FlowScaffold, VsCard, ListRow, StatusChip, Notice, PrimaryButton, SecondaryButton, SelectCard, EmptyState). Touch targets at least 48 dp; the pop-up's buttons 56 dp.
- Status always pairs an icon with a word.
- `TeamBadge` (`Components.kt` line 271) takes its content description as an argument: a team match passes `team_home` or `team_away` as today, an open match passes the side's name, because "Ev sahibi takım" is false for side A (R2).
- The ace column of the Players table reads `players_col_serve` "S. ası" with the content description `players_col_serve_a11y` "Servis ası"; every statistics screen labels aces "Servis ası" (`report_aces`). No label for aces is the bare word "As", because "as" now names a starter (R5; `strings.xml` line 470 today says "As").
- Password fields use `KeyboardType.Password`, a show/hide toggle with its own content description, and autofill semantics with the commit of 10.2.
- Codes use tabular figures and a copy button that announces through `Modifier.announced()`.

### 10.10 The entry choice (v5, R5)

The owner wrote on 18 November that a person entering the app can say that they captain a team, or can set up a 12-person match that a group plays now and then, and left the details to the session ("Orayı biraz sen geliştir"). No specific team uses the app, so the app does not guess a person's way in: it asks once and routes.

**When it opens.**
- Route `entry`, without a bar. The app opens it at most once per account and phone, after the first successful login or registration of that account on this phone, before Home, by the rules below (C8 and C9 of the v5 checkers).
- **Order after a sign-in.** First the legacy question of 10.3, when the phone holds legacy data; then the suggestion of a new password after an operator reset (3.4); then the entry choice. Each waits until the one before it closed.
- **Who skips it.** While `entrySeen` is false, the app decides in this order. (1) When the account's folder holds a personal MatchRecord, the account already uses the app: the app marks the screen seen and opens Home. (2) Otherwise the app shows Home's loading state while the session's first `GET /v1/me` and `GET /v1/matches?scope=open` load, which the app sends on every start anyway (10.3). When either lists a team or an open match, the app marks the screen seen and stays on Home; when both answer and list none, it opens `entry`. (3) When a load fails and the other lists neither, the app opens Home without marking the screen seen and decides again at the next start of the app.
- **After a link sign-in.** When the sign-in came with a pending join or request link, the person lands in that flow (10.2, 5.2). The app marks the screen seen when the join flow ends JOINED or the request flow ends with the request accepted or declined. When the flow ends any other way (Reddet, time up, FULL, a closed or renewed link, a block, a refusal), the app applies the rules above after the person closes the outcome, so a newcomer whose join did not work out still gets the choice.
- **Accounts from before the entry choice.** At its first start the B6c app sets `entrySeen: true` in every account folder that already exists on the phone, so an account that signed in with an older app never meets the screen as if it signed in for the first time, also not after `AUTH_REQUIRED`.
- Seen: `entrySeen: true` in the account's `volleystat.json` (10.3), written when the person picks a choice, taps Şimdi Değil or Back, or when the screen was skipped as above. Nothing goes to the server, and the server has no field for it (8.5).
- Again: the Teams tab and Profile carry the row `entry_again` ("Nasıl Kullanacağını Seç"), which opens the same screen with a back arrow and without Şimdi Değil. Picking another choice is all that "changing" means; the app stores no choice.
- Signed out the screen never opens, because every target needs an account: the app asks for an account at start since 0.8.0 (ADR-0119). The signed-out state is the first-start screen (`first_start_body`, 2.12 A): Kod Gir, Giriş Yap, Hesap Oluştur and Kayıt Kodu İste. A newcomer who arrives through a link signs up through it.
- **Without a link.** Until the owner decides item 32, a person who has no join link opens an account only with an operator code from the owner, so the first captain or organiser of every new group asks the owner for one, through Kayıt Kodu İste or in person (C3 of the v5 checkers).

**What each choice opens.** Each choice is a `SelectCard` with a title and one line below it.

| Choice | Opens | Ends on |
|---|---|---|
| Takımımın kaptanıyım | `teams/new` (Takım Kur; 6 starter seats, 2 substitute seats filled in) | the invite card with Paylaş (2.12 F) |
| Ara sıra maç kuruyoruz | `open/new` (Açık Maç; 12 starter seats, 2 substitute seats filled in) | the link card with Paylaş |
| Bir bağlantım ya da kodum var | `code` (Kod Gir, with Panodaki Kodu Kullan when the clipboard yields one code, 10.1) | the join flow (10.2) or an opened request (5.2) |
| Önce kendi maçımı kaydedeyim | New Match with Kişisel maç chosen, then today's `new/video` | today's personal flow |
| Şimdi Değil | Home | |

A refusal on the target screen shows that screen's own text, for example `team_limit_captain` on Takım Kur or `open_limit_organised` on Maçı Aç.

**States and texts.**

| Exact state | Key | Text |
|---|---|---|
| signed in; the screen opens | `entry_title` | Uygulamayı nasıl kullanacaksın? |
| same, below the title | `entry_body` | Uygulama seçimine göre ilk ekranı açar. Sunucu bu seçimi saklamaz. Bu ekranı sonra Takımlar sekmesinden ya da Profil sekmesinden yeniden açabilirsin. |
| choice 1 | `entry_captain` / `entry_captain_body` | Takımımın kaptanıyım / Takımını kurarsın ve davet bağlantısını takım arkadaşlarına gönderirsin. |
| choice 2 | `entry_open` / `entry_open_body` | Ara sıra maç kuruyoruz / 12 as yeriyle bir açık maç açarsın ve maçın bağlantısını oynayacak kişilere gönderirsin. |
| choice 3 | `entry_link` / `entry_link_body` | Bir bağlantım ya da kodum var / Kaptanın ya da maçı yöneten kişinin gönderdiği kodu yazarsın ya da WhatsApp mesajını yapıştırırsın. |
| choice 4 | `entry_personal` / `entry_personal_body` | Önce kendi maçımı kaydedeyim / Bir maçı yalnız kendin için kaydedersin. Kişisel maç hiçbir istatistiğe girmez. |
| first opening only | button `entry_skip` | Şimdi Değil |
| the phone has no validated network (`NET_CAPABILITY_VALIDATED` false, 10.2); choices 1-3 are disabled, choice 4 and Şimdi Değil stay active; a network callback enables them again | `entry_offline` | Telefonun internete bağlı değil. Takım kurmak, maçın bağlantısını paylaşmak ve kodla katılmak için uygulama sunucuya ulaşmalı. Kendi maçını şimdi de kaydedebilirsin. |
| Teams tab and Profile, the row that opens the screen again | `entry_again` | Nasıl Kullanacağını Seç |

Why these texts hold: the screen sends no request, so it states nothing about the server except that the server stores no choice, which is true by design. The offline line names the phone's network state, which the app knows, and the personal flow records without the server (the upload waits, 5.6). `entry_open_body` names the 12 starter seats, which every open match has (R2); the substitute count follows on Açık Maç.

**Accessibility.** The four cards are at least 48 dp high, each one reads its title and its line as one item, and the disabled cards in the offline state carry `entry_offline` as their state description. The screen loads nothing, so its states are the first opening, the offline state and the screen opened again.

**Built in** B6c, the slice in which the last target (`open/new`) arrives (14.1). Tests: `EntryChoiceTest` (opens once after the first login on a phone; never when `GET /v1/me` lists a team; never when the account's folder holds a personal MatchRecord; a failed first load opens Home, leaves the screen unseen and opens it at the next start when the loads list nothing; it waits for the legacy question and then for the password suggestion; an account folder that existed before the B6c update never sees it; a link sign-in whose join ends JOINED marks it seen, and one whose join ends with Reddet or FULL opens it after Kapat, C8 and C9 of the v5 checkers; Back and Şimdi Değil mark it seen; each choice opens its route; offline disables choices 1-3 and the callback enables them; the Teams tab and Profile open it again; no request carries the choice), screenshots `38_giris_secimi` (first opening, offline, opened again).

---

## 11. Privacy and KVKK hooks

### 11.1 Who sees what

| Data | The person | Teammate | Own captain | Other team in a match | Fellow player of an open match | Organiser of an open match the person joined | Link holder without a session | Link holder with a session | Operator |
|---|---|---|---|---|---|---|---|---|---|
| username | yes | no | no | no | no | no | no | no | CLI |
| display name | yes | yes | yes | **no** | **yes** | yes | no | the organiser's only | CLI |
| jersey in the team | yes | yes | yes, edits | no | no | no | no | no | |
| seat type in the team (as or yedek) | yes | yes | yes, edits | no | no | no | no | no | |
| side, seat type and jersey in an open match | yes | no | no | no | yes | yes, edits | no | no | |
| the person's points in each match report | yes (by jersey, name joined on the phone) | yes, if the teammate may read that record (6.1) | yes | yes, by jersey only | yes, with the name | yes, with the name | no | no | |
| the person in a video | yes | yes, if the teammate may read that record | yes | **yes** | **yes** | yes | no | no | |
| person totals (Kişisel İstatistik) | **yes** | no | no | no | no | no | no | no | |
| team totals and lines | members | members | yes | no | no | no | no | no | |
| team name, tag, small logo | yes | yes | yes | name and tag | no | no | yes | yes | |
| team schedule and seat counts | yes | yes | yes | no | no | no | no | yes | |
| open match date, time, venue, rule, seat counts | players | | | | yes | yes | no | yes | |
| match request date, time, venue, rule | HOME members | HOME members | yes | after acceptance | | | no | captains only | |
| who waits or decides | no | no | counts only | no | no | counts only | no | no | |
| removed and blocked people | no | no | yes | no | no | yes | no | no | |
| password, token, code, device and session hashes | never returned | | | | | | | | the database file |

A person's open matches never show on their teams, and a team's members never see that a teammate played an open match. Fellow players of an open match see display names across sides; a team match never shows names to the other team (5.8.8, D1j).

### 11.2 Data inventory

The ADR carries this table, and the KVKK review starts from it.

| Data | Where | Purpose | Kept | Who sees it |
|---|---|---|---|---|
| username, display name, password hash, how the account opened (`signup_via`) | `users` | login, names in the team, the per-account storage quotas | until account deletion | 11.1 |
| device token hashes | `login_devices` | a login from a known phone skips the username lock (3.2) | 180 days after the last use, a password change or account deletion | nobody |
| session token hash | `sessions` | login | 180 days at most | nobody |
| operator code hashes | `operator_codes` | the sign-up gate (SIGNUP codes only since v5) | until use or 7 days | nobody |
| team name, tag, logos, schedule | `teams` | team, invite pop-up | until team deletion | 4.5 |
| seat counts of each type, queue counter | `seat_pools` | the join engine | until the team or the open match goes | the captain or the organiser; counts to link holders with a session |
| membership, role, seat type, jersey, joins-seen mark | `memberships` | team | until leave, removal or deletion | members |
| blocks | `pool_blocks` | keeping a removed person out of a team or an open match | until the captain or the organiser lifts it, or deletion | the captain or the organiser |
| invite, open match and request links (token hash, code HMAC, the creating account while the link is live, since v5) | `share_links` | joining, match requests, the creator's sign-up limit (3.3) | 7 days live (an open match link until the end of its match day at the latest), then 30 days as a tombstone with the code HMAC (R3) | nobody; the phone that created the link keeps the clear values |
| sign-ups through join links: the link's creator, the new account and the time (v5) | `link_signups` | the creator's sign-up limit (3.3) and the operator's trace of the accounts opened through one creator's links (6.5) | 7 days | nobody; the operator through `signups` |
| the link key | the operator's configuration folder, outside the storage folder (`VOLLEYSTAT_LINK_KEY_FILE`, S9) | code HMAC | until the operator deletes it | nobody |
| join attempts with their seat type and session hash prefixes | `join_attempts` | seat holds and the queue | 24 h after closing | the person; the captain or the organiser sees counts |
| open matches: organiser, transfer offer and cancellation; players: seat type, suggested jersey and proposed side | `open_matches`, `open_match_players` | open matches | 8.7 | the players and the organiser (5.8.8) |
| match records, sides, rosters, confirmed rosters, side results, result offers | `matches`, `match_sides`, `match_rosters`, `match_roster_confirmed`, `match_results`, `match_result_offers` | team matches and statistics | until both sides leave; an offer until its side confirms or leaves | members of both teams under 6.1; rosters with names to the own side only; an open match to its players and organiser |
| videos and analyses, and who started each upload (`uploads.started_by`, cleared at account deletion) | media folder, `videos`, `uploads`, `analysis_jobs` and their tables | the existing product; the per-account quotas | until deleted or the uploading side leaves | members of both teams under 6.1 |
| server logs | the operator's terminal | errors only; the uvicorn access log is off | the operator's choice; B10 sets a period | operator |
| proxy or tunnel logs (B6a) | the operator's reverse proxy, or the tunnel provider | transport | the proxy runs with its access log off; a provider's own logs are outside the operator's control and are named in the ADR. With Tailscale the operator decides whether tailscaled runs with `--no-logs-no-support` and records the choice here (S10) | operator, provider |
| the public server's host name (B6a) | public Certificate Transparency logs and every shared link | TLS certificate, links | forever | anyone; the checklist therefore picks a neutral name (12.3, S10) |
| traffic through a TLS-ending tunnel (B6a, if chosen) | the provider's edge | transport | the provider's terms | the provider sees everything in clear: passwords, tokens, videos (P6) |
| backups (B10) | a folder on the same computer | recovery | 7 days | operator |
| throttle keys | process memory | brute-force limits | 1 h after the last failure, lost on restart | nobody |
| phone files | 10.3 | the app | 10.3 | the account on that phone |

Since v5 these rows include accounts of people the owner does not know: anyone who got a join link from any account, or an operator code, and the teams, open matches, rosters and videos they create (0.4, 11.3, ADR-0120; C19 of the v5 checkers).

### 11.3 Waiting for the KVKK review (v5)

v4 promised here that the app would not be opened to other teams before the review, and turned that into the captain-code gate and the members rule. The owner's decision of 18 November (0.4, R6) withdraws that promise for creating teams and matches, and the review stays deferred. What holds now:
- **Who can be on the server.** Anyone who gets a join link (a team invite or an open match link) from any account can open an account, and so can anyone with an operator code. Every account can create teams and open matches and invite further people. People the owner does not know can therefore hold accounts, create teams and invite others, and the server holds their usernames, display names, password hashes, team, match and roster data, and videos that show their faces. The server does not check who a person is, and the app says so where people create teams, open matches and accounts (`privacy_links_team` from B6, `privacy_links` from B6c; C18 of the v5 checkers). Sign-up without any link or code stays closed (0.4, 16.1 item 32).
- **What still holds between teams.** No display name, username, user id or roster link of a team reaches another team; no team's statistics reach another team; a team match shows the other team roles and jerseys only (6.3, 11.1); no directory or search exists, so nobody finds a team or an open match without its link.
- **Inside an open match** the players see each other's display names, sides and jerseys and the video (5.8.8); the open-match privacy text says so before anyone joins, in the share message and under Maçı Aç.
- **What the review must still bring:** the list below. Since v5 it concerns people the owner does not know, not only the owner's own teams. ADR-0120 records this (0.4), and 16.2 lists it as a risk.

`test_privacy_payloads.py` asserts that an AWAY member's roster, report, match, link and attempt payloads hold no HOME display name, username or user id; that an anonymous LinkView holds no `playedOn`, `startTime`, `venue` or schedule, and for an open match nothing but `kind` and `signup`; that a roster payload holds no row, name or jersey for an empty starter place (R3); that a logged-in non-player's LinkView of an open match holds the organiser's display name and no player's; that a registration with an OPEN_MATCH link under the operator switch `off` answers `403 SIGNUP_CLOSED` and creates no user, and under the default `link` opens an account; that an account opened through an invite and one opened through an open match link can each create a team and an open match (R6); that the other team's roster payload of a `TEAM_DEFAULT` side holds no entry and no count (F2); and that no team payload carries any open match of its members.

Open for the review: the aydınlatma metni; a separate sharing consent for showing a person's match data and video to the other team, and for showing display names and video among the players of an open match; minors and parental consent; erasure for a person in a video without an account (operator CLI until then); retention periods for videos, side results, open matches with a confirmed result, backups and tombstones; accounts, teams and open matches of people the owner does not know (R6), and sign-up without a link (16.1 item 32); hosting abroad and a TLS-ending tunnel as a processor and a cross-border transfer (P6).

---

## 12. Hosting, link host and distribution

### 12.1 What works where

| Works today on one computer, free | Needs an internet-reachable HTTPS server |
|---|---|
| accounts, teams, invites and open matches by code, the queue, requests, rosters, side results and statistics, with several accounts on one server | two captains on phones in different places |
| two emulators at `10.0.2.2:8000`, a USB phone through `adb reverse` | 100 people from a WhatsApp group |
| one phone switching accounts | links that open the app (App Links, `assetlinks.json`, fallback pages) |
| the CI e2e and device jobs, the shaped-network test of B6b | the real-phone session of B6b and any release build (its server URL is empty and fails closed today) |

Plain HTTP on a shared Wi-Fi is not offered: passwords and tokens would cross the network in clear, and the debug network config allows cleartext only to `10.0.2.2` and `127.0.0.1`.

The server is the owner's computer. For anyone to join or upload, it must be on and awake, and the tunnel or proxy must run (F8). Free cloud hosts often sleep idle services or have no persistent disk; this design needs a disk for SQLite and the media folder.

### 12.2 The exposure minimum (slice B6a)

`--host` (default stays `127.0.0.1`); `--trusted-proxy` with `--client-ip-header` (3.6); the source keys of 3.6, with an IPv6 source keyed by its /64; `VOLLEYSTAT_PUBLIC_URL`; `/.well-known/assetlinks.json` from `VOLLEYSTAT_ANDROID_CERT_SHA256` when the API host is the link host; the `/j/` (both kinds of join link) and `/m/` fallback pages and `link.js`; a refusal to start on a non-loopback host without `--trusted-proxy` and without explicit `VOLLEYSTAT_SIGNUP` and `VOLLEYSTAT_OPEN_MATCH_SIGNUP` (v5: `VOLLEYSTAT_TEAMS` is gone), and a refusal to start with `VOLLEYSTAT_PUBLIC_URL` set but no `--trusted-proxy`, because every tunnel connects from loopback (R4); the open-file limit. On Android: `ServerTarget.REMOTE` with `BuildConfig.API_URL_REMOTE` and the link host from a Gradle property, HTTPS only. An operator checklist in the ADR: the proxy's access log off; where TLS ends; the client-address header of the chosen option, confirmed in B6b by two phones on different networks getting different keys (S2, R4); the link key file outside the storage folder and every copy (S9); a neutral host name chosen before HTTPS is turned on, because the name becomes public (12.3, S10); the computer set not to sleep during a use window.

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

- **The owner's website domain (recommended once the owner permits it).** Four static files on the website (open matches use `/j/` as well, so v4 adds none): `/j/index.html`, `/m/index.html`, one external script that reads the fragment and builds the intent URL (the website's CSP allows only same-origin scripts, so no inline script), and `/.well-known/assetlinks.json` with the package name and the release certificate's SHA-256. Links then survive any change of the API's host or tunnel, and the fallback page works even while the owner's computer is off. This project changes no file of the website. The change needs the owner's explicit permission and would be made by a website session under the website's own rules, inside a planned website release and never as a separate one.
- **The API host (default once hosting exists).** The server serves the same files (B6a). No website change. Links break whenever the API host changes, and the page is down whenever the computer is.
- **Before hosting (default today).** No link; the app shares the code with the paste-friendly message.

### 12.5 Distribution and the newcomer without the app (F1, owner decision 16.1 item 8)

- **APK by hand (default, free).** The owner gives the APK to the first captains and organisers, and any captain or organiser may share the APK file in their WhatsApp group. Since v5 these people need not be the owner's own teams (R6). Until the owner decides item 32, each of them needs either a join link from someone or an operator code from the owner, which the first-start screen's Kayıt Kodu İste asks for by e-mail (10.10, C3 of the v5 checkers). The fallback page and the code-only share text tell a newcomer to ask the captain for the file, allow the install, and tap the WhatsApp link again: there is no deferred deep link outside Play, so the re-tap carries the link into the app. A newcomer who taps the installer's Aç button instead reaches the first-start screen, whose main action is Kod Gir with the copied code (10.1, R6).
- **A public GitHub Release.** The repository is public, so a release asset is downloadable by anyone; that distributes the closed app to anyone, also to people whom no captain or organiser invited. It needs the owner's explicit choice.
- **Play Store.** Keeps the link through the install and removes the unknown-sources steps, but needs a one-time Play Console fee. The owner uses free tools only, so it is not planned.
- **Risk:** Google announced developer verification for apps installed outside Play, starting in some countries in 2026 and later worldwide. Before 2027 the owner checks whether the APK route then needs a registration.

### 12.6 Backups and logs (P6, B10, owner decision 16.1 item 10)

Default in B10: a nightly copy with `sqlite3.Connection.backup` plus the media folder into a folder on the same computer, kept 7 days and then deleted; the link key file lives outside the storage folder and is never copied (S9). `privacy_retention` and `privacy_delete` then say that deleted data stays in the copies for at most 7 days. The other choice is no copies before the KVKK review, which loses everything if the disk fails. The data inventory lists the copies and the proxy logs.

Real use between two teams waits for both owner decisions: hosting and the KVKK review.

---

## 13. Critique findings (v2 critiques)

**v5 note.** The owner's decision of 18 November (R6) withdraws the fixes below that rested on the captain-code gate and the members rule: P1's item (1) (`VOLLEYSTAT_TEAMS=operator`), S8 (a CAPTAIN code bound to its account), fidelity V1 (codes only for the owner's teams, bound to their names), the condition of F10's `register_gate_any` and V4-S1 (the members rule). The threats they answered are handled as follows: a leaked request link by the opponent check (5.3) and SOLO records (5.7); unlimited sign-ups by the per-link limit and the per-creator limit (3.3); team storage by the per-uploader quota, the team pool and the 2-member rule (6.5); strangers in the owner's teams by the fact that nobody joins a team without its captain's link, and by removal with blocks (4.3). P1's other items (no sign-up through a request link, the random tag, the opponent check, video ownership per side) stay. The rows below are kept as history.

The v1 findings (S1-S10, F1-F10 of `critiques_v1.json`) stay handled as in v2 section 13; v3 changes none of those fixes except where a v2 finding below says so (v1 S6 and F2 in 6.4 and 6.2, v1 F4 in 6.1).

The tables of 13 and 13.1 stay as v3 wrote them. Where they cite v3 names, v4 renamed them (2.4, 9.5): the attempt reason TEAM_FULL is FULL and the error TEAM_FULL is SEATS_FULL, `team_blocks` is `pool_blocks`, and the team snapshot is the pool snapshot. v3's rows 7a, 7a′, 7g and 7h of 2.13 are rows 8a, 8a′, 8g and 8h of v4's walk 1. Section 13.2 lists the checks made for v4.

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

### 13.2 Checks made for v4 (17 November)

v4 was first written without new critics. The session checked each change against the rulebook, the code at 7f18839 and v3's own findings, so that no finding fix of 13 and 13.1 is lost. Three checkers then read v4; 13.3 lists their points.

| Check | Result | Section |
|---|---|---|
| FIVB 4.1.1 (Turkish 2025-2028 edition): "Bir maç için bir takım en fazla 12 oyuncu ile …"; 14 on the score sheet only in FIVB, World and official senior competitions | per team and per match side at most 12 players; 14 not used | 2.3, 6.3 |
| FIVB 7.3.1: six players of a team always in play; 7.3.3: players outside a set's starting line-up are the set's substitutes, liberos excepted ("(Liberolar hariç)"; v4's first text left the exception out, R6); 19.1.1: at most two liberos | at most 6 starters per team and per match side; an open match at most 12 starters, 6 per side after the split; a LIBERO role per match, at most 2 per side, outside the 6 starters | 2.3, 5.8.5, 6.3 |
| FIVB 4.3.3: shirts numbered 1-20 | jerseys stay 1-99, as `review.py` line 32 builds the SCORER options | 6.3 |
| The owner's numbers | team 6 + 2 = 8 (within 12); team match roster 2 × 8 = 16 (12 starters and 4 substitutes, within 12 per side); open match 12 + 2 = 14, split 6 + 1 per side by the proposal (within 6 starters and 12 per side) | 2.3, 5.8, 6.3 |
| v3's line references | `main` (7f18839) and b2ce9b2 have the same tree (`git rev-parse 7f18839^{tree} b2ce9b2^{tree}`), so every line reference of v3 holds | header |
| `report.py` sides and names | the report keys its sides HOME and AWAY (`report.py` lines 26-28); the match payload takes `homeTeamName` and `awayTeamName` from `matches.home_team` and `away_team` (`main.py` line 140), and `MatchInput` requires two different names of 2-80 characters (`main.py` lines 78-79 and 107); an open match maps side A to HOME and B to AWAY, stores the side names there, and needs no report change | 5.8.1 |
| `MAX_VIDEO_BYTES` | 4 GiB (`main.py` line 30): v3's cost text for the 2 GiB invite quota was wrong and is corrected | v4 changes, 16.1 item 4 |
| The engine is not forked | the state machine, `settle`, the clock, the snapshot, the long-poll, the FIFO lock and the arrival rule take a pool id; the kind adapter (2.1) holds four kind-dependent parts; `pool_holders` gives one count for both kinds | 2.1, 2.5, 8.2 |
| No v3 finding reopened by typed seats | C1 (snapshot per pool, view tags with the seat type), C2/conc V1 (arrival judging unchanged), C3 (a reservation's type never changes, so no countdown or text moves), C4 (one FIFO lock for every pool), C5 (ack unchanged), C6/conc V2/R8 (recovery per pool), conc V7 (`queue_version` per pool), fidelity V7 (T13 closes waiters as FULL, T14 never applies to a FULL link), P2 (a full pool closes its join link), P3 (queue, attempt and sign-up limits per pool), S3 (removal renews the link for both kinds) | 2 |
| KVKK and ADR-0117 | an open match link opens no account before the review; open matches create no team and touch no team's data | 0.4, 5.8.3, 11.3 |
| Turkish texts | every new and changed text names its actor, keeps the predicate last, joins no clauses with a dash, uses no passive voice and states no cause the server cannot know; reviewed with the `turkce-insansi-metin` rules | 2.12, 10.8 |


### 13.3 Findings of the v4 checkers (17 November)

Three checkers read v4: fidelity (F1-F10), security (V4-S1 to V4-S3, V4-P1, V4-Q1, V4-C1, V4-C2) and volleyball (R1-R10; these R ids are new and differ from v3's R ids in 13.1). The session checked every point against this document, the FIVB rulebook (rules 7.3.3, 19.1.1 and 19.3.1.3) and the code at 7f18839 (`VolleyStatApp.kt` lines 156 and 218, `Review.kt` lines 95 and 224-236, `Report.kt` lines 403 and 450, `Components.kt` line 271, `strings.xml` lines 21 and 470), and ran `verify_v4/captain_seat_probe.py` for V4-C2. Every point held. `verify_v4.json` holds each outcome and note.

| Point | What v4 got wrong | Fix in this revision | Section |
|---|---|---|---|
| **F1** (high) | Cancel checked only for a video, so an organiser could delete the video, cancel, and let the sweep erase every player's numbers; the organiser could also remove players and lapse the roster after a result | Cancel needs no video and no side result (`RESULT_CONFIRMED`); D1m and item 27 reworded; a removal after a confirmation keeps the person's confirmed line (`open_remove_counted`) | 5.8.4, 5.8.10, 2.12 F, 10.8, 16.1 item 27 |
| **F2** (high) | A never-saved side was computed live: confirming version 0, leaving it, the read rule, a member of both teams and the other team's view were undefined | Version 0 is written as rows before a confirmation or a self-removal; only stored rows count and grant access; a member of both teams is in neither default; the other team sees a default as not saved without entries; the same for the open-match proposal | 5.8.5, 6.1, 6.3, 9.2, 9.3 |
| **F3** (medium) | "Maçı açan kişi" turned false after the role moved | "Maçı yöneten kişi" in every open-match text and in rule 7; "Maçı yöneten · %1$s"; the account texts say "yönettiğin" and, for the quota, "yüklediğin" (V4-Q1 counts on the uploader) | 2.12, 10.8 |
| **F4** (medium) | FULL texts described the present after a seat freed; after the match day several texts sent people to ask for a link the server refuses | "Bu maç doldu"; the organiser's card picks its text from the seats left now (`open_closed_full_free`) with a fixed order; `matchDayPassed` shows `link_expired_played` for every reason but CANCELLED, PLAYED and ORGANISER_GONE; `join_gone_played_open` and the reopen variant read the stored match day | 2.2, 2.12 A, C, D, F, 10.2 |
| **F5** (medium) | `open_waiting_roster` promised numbers to a player without an entry | Shown only with an entry (or while unsaved); `open_not_on_roster` for the others | 10.8 |
| **F6** (medium) | `playedOn` could move after the match; the link stayed live after the match on the match day | The date is fixed once the day began or an upload exists (`OPEN_MATCH_PLAYED`); 5.8.3, D1l and item 26 now say a person can join until the first upload or the end of the day, and the first upload closes the link (V4-S2) | 5.8.3, 5.8.4, 16.1 item 26 |
| **F7** (low) | `queue_rule`'s new sentence said the next person becomes a starter and had a clause without a subject | "Sunucu as yerini sıradaki kişiye as yeri olarak, yedek yerini yedek yeri olarak ayırır." | 2.12 D |
| **F8** (low) | The ORGANISED and JOINED limits counted cancelled matches, so cancelling freed nothing | Both count only matches that are not cancelled; texts and a test follow | 2.1, 2.4, 5.8.2, 5.8.3, 5.8.9, 14.1 B6c |
| **F9** (low) | 5.8.10 and 8.7 said a match with a result could be deleted once players left, 5.8.4 and 9.2 refused it always | DELETE after a result is allowed once nobody but the organiser holds a seat or a confirmed line; every section says so | 5.8.4, 5.8.10, 8.7, 9.2, 10.8 |
| **F10** (low) | `register_gate` had no form for open match sign-up; `open_need_account` sent outsiders into the owner's teams | `register_gate_any`; `open_need_account` points to a sign-up code from the operator (R10's wording, which V4-S1 lets through) | 2.12 A, 10.8 |
| **V4-S1** (high) | Accounts that registered through an invite without joining, and removed ex-members, could form open matches before the KVKK review | While `VOLLEYSTAT_TEAMS=operator`, creating an open match and T1 and T8 on its pool need a current team membership or an operator-code account (`403 OPEN_MATCH_MEMBERS_ONLY`, T8d); 0.4, 5.8.3, 11.3 and item 23 corrected; tests | 0.4, 2.4, 5.8.2, 5.8.3, 11.3, 14.1, 16.1 item 23 |
| **V4-P1** (high) | TEAM_DEFAULT followed the team after the match, so a later member could read and be counted for an old match | The default lists match-day members only and becomes stored rows at the first upload, at the end of the day, at a confirmation or at a self-removal; only stored rows count or grant access | 6.1, 6.3, 7.2 |
| **V4-S2** (high) | A played match's date could move to reopen joining; the link stayed live after the upload; removal with `renewLink` skipped the link guards | Date fixed after the day began or an upload; the first upload closes the link (PLAYED); one guard for every link-creating route; tests for all three paths | 2.2, 2.4, 5.8.3, 5.8.4, 9.2 |
| **V4-S3** (medium) | After a confirmed result the organiser could erase or hold back every player's numbers by removals or one save | A confirmed-roster snapshot (`match_roster_confirmed`) counts until the next confirmation, for team sides and open matches; a removal keeps the person's confirmed line; only the person's own leave removes it at once; D1m, item 27 and 16.2 corrected | 6.3, 5.8.4, 5.8.6, 7.2, 8.2, 16.1 items 27 and 31 |
| **V4-Q1** (medium) | 8 GiB of open-match video for every account and bytes moving with a transfer undid S7 | Bytes count on the uploader (`uploads.started_by`) and never move; 8 GiB with an operator code, 4 GiB through a link; separate server pools of 40 GiB personal and 60 GiB open match; no upload with fewer than 2 players; tests extended | 5.8.9, 6.5, 8.3, 10.8, 16.1 item 4 |
| **V4-C1** (medium) | A transfer and account deletion appointed an organiser without consent, ignored the recipient's limit and broke a running upload | The transfer is an offer the player accepts, refused at the ORGANISED limit and during an unfinished upload; account deletion aborts the user's uploads and leaves the match without an organiser (ORGANISER_GONE) | 3.7, 5.8.4, 8.2, 9.2, 10.8, 16.1 item 29 |
| **V4-C2** (low) | A substitute captain with 0 substitute seats broke `settle`; `memberships.seat` had a default; creation skipped the JOINED limit | `CAPTAIN_SEAT` check; no default and a CHECK on both seat columns; the JOINED limit at creation with "Ben de oynuyorum" | 2.3, 4.1, 5.8.2, 8.2 |
| **R1** (high) | The SCORER card of a team or open match had no jerseys, because the app takes them from local rosters | The review chain and the Players screen read the server roster; `review_no_roster_server` and `review_no_roster_open` with a button to the editor; the first upload stores both sides' rows; walk 2 saves jerseys before the review | 2.13, 5.8.6, 6.3, 10.3, 10.8, 14.1 B8 |
| **R2** (high) | The open-match proposal shifted when one person left, while the review used it | The proposed side is stored at the join and never moves; players see sides only after a save; the review opens only after a saved split and shows names under each side; `TeamBadge` takes the side name | 5.8.5, 8.2, 10.1, 10.9 |
| **R3** (medium) | The default roster credited a later member wearing a former member's jersey | Match-day members only, frozen into rows; `NOT_A_TEAM_MEMBER` accepts a person who already holds a row of the side | 6.3 |
| **R4** (medium) | A second recording of an open match could only become a personal match after the match day | Open match siblings that copy players and split, at any time, at most 3, outside the limits; a past-dated open match without a link stays out (item 28) | 5.8.2, 5.8.6, 9.2, 10.8, 15, 16.1 item 28 |
| **R5** (medium) | "As" named both aces and starters | The ace column reads "S. ası" ("Servis ası" for screen readers); a string test | 10.9, 14.1 B9 |
| **R6** (medium) | Rule 7.3.3 was quoted without "(Liberolar hariç)" and rosters had no place for a libero | Quote corrected; LIBERO as a per-match role, at most 2 per side, outside the 6 starters; `asLibero`; `review_libero_warning` (rule 19.3.1.3) | 2.3, 6.3, 7.3, 8.2, 10.8, 13.2, 16.1 item 30 |
| **R7** (medium) | Joiners were not told what joining shares | One sentence in both open-match share texts; `privacy_open_match` on `open/{id}` | 2.12 F, 5.8.8, 10.1 |
| **R8** (low) | B6c used tables and a route that later slices listed | B6c names `match_sides`, `match_rosters`, `matches.status` and `start_time`, `GET /v1/matches?scope=open` and their migration test | 14.1 |
| **R9** (low) | A transfer or an account deletion could strand a half-reviewed video | Transfers of captaincy and organiser role refused with `REVIEW_IN_PROGRESS` (and `UPLOAD_IN_PROGRESS`); account deletion deletes the user's team videos without a confirmed list and says so; `open_transfer_body` corrected | 3.7, 4.3, 5.8.4, 10.8 |
| **R10** (low) | `open_need_account` sent people to join a team, which takes a team seat | It points to a sign-up code that the organiser asks the operator for; item 23's cost updated | 2.12 A, 16.1 item 23 |

---

## 14. Slicing plan and tests

### 14.1 Slices

Prerequisite: done. The multi-match work is on `main` (7f18839), and so are B1 and B2 (d9b27a7); B3 is being built; and every slice starts from `main`. Server and app versions follow the repository's rule (the app needs the server version that holds what it calls); the minor number is settled at each merge.

v4 changes to the plan: B4 adds seat pools and typed seats; B5 builds the join engine on pools and tests the open-match kind from the start; the new **B6c** brings open matches (create, join, seats, sides, and the tables and the list route they need) and needs no hosting; B6b's real-phone session adds an open-match case when B6c is merged; B8 adds the open match's video, roster confirmation and result; B9 counts open matches for persons only. B0-B6 and B6c are buildable and testable on one computer without hosting; only B6b's real-phone session and B10 need an internet-reachable server.

v5 changes to the plan (R1-R6): B1 and B2 are merged, and their rows below keep v4's plan with a note on what was built. **B4** removes the gate that B1 prepared: `VOLLEYSTAT_TEAMS` and health `accounts.teams` go, `issue_operator_code` accepts SIGNUP only, B1's three reserved `operator_codes` columns stay unused, and every SIGNUP code keeps working; it builds the fixed 6 starter seats with the substitute count 0-6, the captain and creation limits, and adds ADR-0120's B4 part with the lines under ADR-0117 and ADR-0119 (0.4); B5, B6c and B7 append their ADR-0120 sentences when they ship (C4, C10 of the v5 checkers). **B5** drops the members rule and T8d and adds `share_links.created_by` and `link_signups` with the creator's sign-up limit. **B6** shows v5's first-start screen with Kayıt Kodu İste, and the code-first step and Kod Gir read a printed operator code as one (C3 of the v5 checkers). **B6c** brings the open match with its fixed 12 starter seats, its link opening sign-up by default, and the entry choice (10.10), whose four targets all exist from B6c on. **B7** adds the team storage rules of v5 (2 members, per uploader, the team pool). **B8** builds the 6 starter places with empty places shown as "Boş". **B9** carries `emptyStarterPlaces` on lines. **B10** carries the owner's decision on sign-up without a link (16.1 item 32).

| Slice | Content | Shippable because | Tests |
|---|---|---|---|
| **B0** Contract and ADR-0119 | API_V1 sections 10-12 and the error codes, API.md 0.8, ADR-0119: accounts, the sign-up and team-creation gates, typed seats with the FIVB bounds, seat pools and the one join engine (arrival order, the single-process constraint), open matches and their sign-up rule before the KVKK review, side results, the data inventory, where TLS ends. The invariant "Sunucu oyuncu adı tutmaz" becomes "Sunucu yalnızca kişinin kendisi için verdiği görünen adı tutar. Telefon kadrosundaki adlar telefonda kalır." | docs only (v5: ADR-0119 as merged records B1 and B2; ADR-0120 records the owner's decision of 18 November and comes with B4, 0.4) | review |
| **B1** Server accounts | `users`, `sessions`, `operator_codes`; auth and `/v1/me*` routes; the hash executor; the common-password list; the session cache; `access_log=False`; `forwarded_allow_ips=""`; admin CLI `signup-code`, `captain-code --team-name` (with the ADR-0119 list and `--after-kvkk`), `reset-password`, `storage --top` (as merged: `signup-code` and `reset-password` only; v5 drops the captain code, and `storage --top` goes with B3's quotas); `login_devices` and device tokens; `signup_via` (with the value OPEN_MATCH reserved); health `accounts` with `openMatchSignup: OFF` (as merged: health `accounts` is `{signup, teams}` and has no `openMatchSignup`; B4 removes `teams`, and B6c adds `openMatchSignup` with `VOLLEYSTAT_OPEN_MATCH_SIGNUP`, C5 of the v5 checkers). Existing routes still open. | additive; the old app still works | `test_auth.py` (format, NFKC, COMMON, dummy hash, register order and identical answers, expiry, sliding renewal, password change, a CAPTAIN code opens one registration and stays valid for one team of its name and for the account it registered only (as merged: a CAPTAIN code opens no registration, and B4 retires the kind), a device token skips a locked username key), `test_hash_pool.py` (503 and a fast route during a flood), `test_session_cache.py` (logout evicts at once; a CLI deletion stops polls within 60 s), deletion byte search |
| **B2** App login | `SessionStore`, the encrypted pending store, per-account folders, the legacy question, register-first, username suggestion, autofill commit, token on every call, ExoPlayer header, 401 handling, login and register screens, Profile rows, rewritten privacy rows | the server accepts and ignores the header on old routes | `AppViewModelTest` (header after login only; 401 clears session and cache and keeps the pending store; claim, 401, login by the same account: the claim goes out once and the pop-up opens, and another account does not resume the old attempt (R5); account isolation A then B), `SessionStoreTest`, `PendingStoreTest`, `UsernameSuggestionTest` ("Çağrı Öztürk" → `cagri.ozturk`), `LocalStoreTest` v2 → v3, screenshots `14_giris`, `15_hesap_olustur` (with and without the team card), `AccountE2eTest` |
| **B3** Enforcement and storage limits | `current_user` everywhere, PERSONAL and LEGACY, `access.py`, the route walk, the decision replay order, `legacy --assign`; the personal, server-wide and team quotas, the disk floor, `touched_at` and the idle-upload sweep (the open-match scope comes with B8); app maps `NOT_A_MEMBER`, `QUOTA_EXCEEDED`, `STORAGE_FULL` | today's flow works behind login | `test_access.py` (every existing operation: anonymous 401, stranger 403 never 404, owner OK; a member who joined after `played_on` gets 403 or 404 through `/v1/matches/{R}/report?jobId=J` and through `/v1/matches/{own personal match}/report?jobId=J`, S4), `test_migration_accounts.py`, `test_quotas.py` (declared size over quota 409; an invite account at 2 GiB and one unfinished upload; 20 invite accounts fill the 40 GiB personal pool and a team upload still succeeds, S7, V4-Q1; `uploads.started_by` is written at `initiate_upload`; a patched `disk_usage` gives 507; the sweep aborts an idle upload and deletes its bytes), `UploadEngineTest` (an ABORTED upload restarts from zero), all existing tests through the storage-scoped fixture |
| **B4** Teams and typed seats | team tables and routes, `seat_pools` (one per team, always 6 starter seats and 0-6 substitute seats, 2 by default, under the CHECK of 8.2, the captain on a starter seat), `memberships.seat`, `PUT …/seats` with the substitute count and moves (the engine function of 2.3, without a queue yet); no captain code: `VOLLEYSTAT_TEAMS` and health `accounts.teams` removed (a left-over variable is logged and ignored), `issue_operator_code` raising `ValueError` for any kind but SIGNUP, the CAPTAIN test of `test_auth.py` replaced and the health tests and `docs/API.md` line 18 changed (C20), B1's three reserved columns left unused; the captain limit of 3 (also on a transfer) and the creation key of 3 a day; ADR-0120's B4 part and the lines under ADR-0117 and ADR-0119 (0.4); the random tag, two-size lossy logo, members, jerseys, removal and `pool_blocks`, unblock, the new-member badge, transfer, delete; app Teams tab with the seat line, team screen with Aslar and Yedekler, `team/{id}/seats`, settings, Takım Kur with the substitute count and my seat, without a code field and without a privacy line about links, because no link exists before B5 and B6 (C18 of the v5 checkers), Çıkarılanlar | teams exist without joining | `test_teams.py` (one captain; jersey uniqueness under two threads; transfer conflict; any account creates a team without a code, and a SIGNUP code printed before B4 still opens a registration; a fourth captaincy answers `TEAM_LIMIT_REACHED` (`CAPTAIN`), also on a transfer, while account deletion still moves a captaincy; a fourth creation in a day answers 429 (`TEAMS`); the server starts with `VOLLEYSTAT_TEAMS` set and logs that it ignores it, and health has no `accounts.teams`; the tag ignores the client id and stays unique under a forced RNG collision; logo refusals: VP8L, ALPH, VP8X, EXIF, trailing bytes, wrong RIFF size; removal blocks and unblock; removal with Bağlantıyı da Yenile off lets a new account of the removed person join, and on answers 410 RENEWED, S3), `test_seats.py` (a substitute count outside 0-6 answers `SEATS_INVALID` (`SUBSTITUTES_RANGE`), a body with `starterSeats` answers 422, and an insert of another starter count or of a negative substitute count fails on the schema's CHECK, and a substitute count of 7 through the route or the creation answers `SEATS_INVALID` before any write; the captain holds a starter seat by default and a substitute seat on request; `captainSeat: SUBSTITUTE` with 0 substitute seats answers `SEATS_INVALID` (`CAPTAIN_SEAT`) and creates nothing, V4-C2; an insert into `memberships` without a seat fails on the schema; moves and counts are checked on the final state; a swap in one call passes and a single move into a full type answers `SEATS_BELOW_HOLDERS` and changes nothing), team deletion byte search, screenshots `16_takimlar` (badge, seat line), `17_takim` (Aslar and Yedekler, `team_starter_free_hint`), `18_takim_ayarlari`, `18a_as_yedek`, `18b_cikarilanlar` |
| **B5** Join server (one engine) | `app/seats.py` with the `PoolKind` adapter and the `pool_holders` view; the `open_matches` and `open_match_players` tables (schema only, so the view and the OPEN_MATCH adapter are complete); `share_links` with hashed tokens, HMAC codes, `link.key`, tombstones and the live-expiry index; resolve with its three levels; `join_attempts` keyed by pool with `seat`; claim, ack, decision, leave; typed seats in T1, T5 and T8; `SEATS_IN_USE` and the `settle` that follows a count change; the long-poll from published pool snapshots with view tags that hold the seat type and the seats left per type; arrival stamps with the in-flight entries in the same middleware, the body time limit, the rate keys and the TIMEOUT rule; `settle`; the clock (with the expired-link pass), the beat, pause recovery in the write path, the silence check and the heartbeat flush; the notifier; the FIFO writer lock in `db.connector()`; start-up recovery; sign-up by invite with its limits (200 per link, 400 per creator in 7 days through `share_links.created_by` and `link_signups`, C1 of the v5 checkers); ADR-0120's B5 sentences (0.4); the join link closing at full; the captain's live view; poll caps; the open-file limit; the code throttle keys | joining a team works end to end by code | `test_join_state_machine.py` (T1-T17 with T6b and T8c; v5 removed T8d; with a fake clock; grace edges at arrival `grace_until` − 0.01 and + 0.01 s; TIMEOUT before and after `expiresAt` with `other_session` 0 and 1, and after `grace_until`; T17's two flags; the protection tests of 2.9; a 60 s freeze recovers without DROPPED or SKIPPED; an ack 0.1 s late gets 409, the head is offered in the same transaction, and no row is added; the last seat taken with two waiters open closes them with reason FULL, fidelity V7; **typed seats:** starters fill first in queue order, then substitutes; a starter seat that frees while a substitute hold is open goes to the head and the hold keeps its type and its `expires_at`; a raised count offers at once with the raised type; a count lowered below holders + deciding of its type answers `SEATS_IN_USE` and changes nothing; a FULL pool closes its link and its queue; every case of 2.13 walk 1 as a scripted run), `test_join_engine_kinds.py` (walk 2's steps 0-8 with its variants 6′ and 6″, run as a script on an OPEN_MATCH pool of 12 + 2 seeded straight into SQLite, give the same states and reasons as walk 1's matching steps, with walk 2's seat numbers, because the CHECK of 8.2 allows no OPEN_MATCH pool of 6 + 2 (S4 of the v5 checkers); the adapter's holder limit, starter count and holder table are the only differences; an account without any team takes a seat of an open match (R6); the reasons MATCH_PLAYED and ORGANISER_GONE close open attempts like MATCH_CANCELLED), `test_join_concurrency.py` (14.3), `test_join_restart.py`, `test_fifo_lock.py` (ticket order; a nested acquire raises; never on the loop thread; a `BEGIN IMMEDIATE` that fails with `database is locked` releases its ticket and the next writer proceeds; an abandoned ticket is skipped, conc V6), `test_links.py` (only hashes stored; tombstone reasons; anonymous resolve fields; secrets only in creation answers; a renewed invite's old code answers 410 RENEWED eleven times and the account can still use the new code, R3; a new code never reuses a tombstone's HMAC; a missing link key leaves tokens working; the server refuses to start with the key inside the storage folder, and the key is not under the storage folder, S9; an expired link with nobody waiting becomes a tombstone within one tick), `test_join_payloads.py` (no logo bytes, under 2 KB, `waitedMs` present, `seat` present on OFFERED and HOLDING), account and team deletion during a queue |
| **B6** Join app | link landing, the first-start screen (v5's `first_start_body` with Hesap Oluştur and Kayıt Kodu İste, C3), Kod Gir with the paste extraction of 10.1, register-first with the team card, the pop-up with the seat line, the waiting screen with the seats left per type, outcomes with the seat word, the jersey prompt, the invite card and Paylaş, `privacy_links_team` on Takım Kur, the invite card and the register screen (C18 of the v5 checkers), keep-screen-on, focus gating, the retry policy, network classification, the resume record with the pool's kind, fresh tap versus resume, debug deep links; both text forms of 2.12 behind `target.kind` | the owner's join flow works on emulators | `JoinControllerTest` (MockWebServer: every client state of 10.2; D never above the server's under random `waitedMs` and `remainingMs`; a 15 s HOLDING poll answer does not lower D; no claim while unfocused; a screen-off lifecycle stops and restarts polling; retries at 0.5 then 1 s; a network callback polls at once; decision repeats; process death after REJECTED leads to `GET` and no claim; a recents relaunch resumes; an ack after one failed poll lands inside the window; every key of 2.12 is reached from its state, in both forms; the seat line follows `seat` and never the counts; polls send `wait=2` while ahead < deciding; Yeniden Sıraya Gir after a process death sends a claim with the stored link, fidelity V6), `CodeExtractionTest` (both share texts and the request text with 10 000 random tokens and codes, and a list of Turkish team names, always yield exactly the token or the code, R2; printed operator codes with hyphens, without them, in lower case, with spaces and inside a message always yield the operator code and never a join code, in the code-first step and in Kod Gir, C3 of the v5 checkers), a register-screen test for a health answer without `accounts.openMatchSignup` (the app shows `register_code_first` and `register_gate`, C5), `Kayıt Kodu İste` opens the mail app with the feedback address and shows `feedback_no_mail_app` without one (C3), a fresh install with the code in the clipboard reaches the pop-up without the WhatsApp re-tap (R6), screenshots `19_davet`, `19b_davet_kapali`, `20_kod_gir` (empty, paste offer), `21_katil_karar` (10 s, 3 s, logo, initials, no schedule, as, yedek, sending, time up), `22_sira` (next, ahead 2, one type left, both types left, reconnecting, no network, your turn), `23_katilim_sonucu` (every outcome, as and yedek), `24_forma`, `JoinE2eTest` and `AppJoinFlowE2eTest` against the real backend |
| **B6c** Open matches: create, join, seats and sides | server: the tables this slice needs, with their migration test (R8): `match_sides` (both rows of an open match, with `roster_version` and `roster_confirmed_version`), `match_rosters` (role with LIBERO, nullable jersey, the per-side unique jersey index), `matches.status` OPEN_MATCH and `matches.start_time`, `open_match_players.proposed_side`; `PUT /v1/open-matches/{id}` with the organiser's seat, open to every account, with the fixed 12 starter seats and the substitute count 0-12, settings with the date lock, seats, link routes behind the one guard and live view, leave, removal with blocks, the jersey suggestion, the transfer offer with accept and decline, cancel, delete before a result, `GET /v1/matches` with `scope=open` only, the OPEN_MATCH sign-up (`VOLLEYSTAT_OPEN_MATCH_SIGNUP`, default `link`), `OPEN_MATCH_LIMIT` without cancelled matches, the creation rate key, the stored side proposal and the split (`PUT …/roster` without confirmation). B7 adds the team side rows and `scope=team`; B8 adds the confirmation, the confirmed roster, `TEAM_DEFAULT` and the video; app: New Match "Açık maç", `open/new`, `open/{id}`, `open/{id}/seats`, `open/{id}/sides`, the link card and Paylaş with `open_share_text`, `privacy_links` in place of `privacy_links_team` and also on Açık Maç and the link card (C18), `open_need_account` (only under `off`), the open-match forms of every join text; the entry choice (10.10) with its route, `entrySeen` (set at the first start in every existing account folder, C8), and the rows in Takımlar and Profil; server: health `accounts.openMatchSignup` (C5) and ADR-0120's B6c sentences (0.4) | a pickup game is organised end to end on emulators by code, with no video and no hosting | `test_open_matches.py` (an account opened through an invite, one opened through an open match link and one opened with an operator code each open one, and an account without any team joins one, R6; a body with `starterSeats` answers 422 and the pool always holds 12 starter seats, R2; 3 organised and 10 joined whose day has not passed; cancelling one frees an ORGANISED slot and a JOINED slot at once, F8; 10 creations a day; `playedOn` in the past refused; the link expires at the end of the match day and no new link after it; a `playedOn` change after the day began answers `409 OPEN_MATCH_PLAYED` (DAY_STARTED), F6, V4-S2; removal with `renewLink=true` on a cancelled match answers `409 MATCH_CANCELLED` and changes nothing, V4-S2; a sibling copies players and split, has no link and counts toward no limit, and a fourth answers `409 SIBLING_LIMIT`, R4; creation with "Ben de oynuyorum" at the JOINED limit answers 409, V4-C2; under `off` an OPEN_MATCH link refuses sign-up with `OPEN_MATCH_LINK`, creates no user and moves no throttle key, and under `link`, the default, it opens sign-up with the 200 limit; the organiser's starter seat and the switch off; cancel closes the link (CANCELLED) and every open attempt (MATCH_CANCELLED) except a protected hold; cancel refused with a video; a transfer offer moves nothing until the player accepts, and an accept at the ORGANISED limit answers 409, V4-C1; account deletion deletes the match without another player and otherwise leaves it without an organiser, closes its link with ORGANISER_GONE and keeps sides and players, V4-C1; the proposal alternates by join order per type, and a leave before the first save moves no other player's proposed side, R2; a seat move before the first save re-proposes only the moved players: walk 2's single move after P4 leaves (5.8.5) and a swap of an A substitute with a B starter after step 8 each leave every proposal side with at most 6 starters and 6 substitutes and `emptyStarterPlaces` of 0 or more, S1 of the v5 checkers; players see no side before a save; the split's bounds per side, liberos included; a logged-in non-player gets 403 on GET and sees only the LinkView), `CodeExtractionTest` with both open-match share texts, `EntryChoiceTest` (10.10), screenshots `38_giris_secimi`, `32_acik_mac_ac`, `33_acik_mac` (organiser, player, cancelled), `34_taraflar` (proposal, saved, unassigned), `35_acik_mac_hesap_yok`, the open-match variants of `21_katil_karar`, `22_sira` and `23_katilim_sonucu`, `OpenMatchE2eTest` (organiser and 14 players by code, then sides) |
| **B6a** Exposure minimum | 12.2: `--host`, `--trusted-proxy`, the source keys, the start-up refusal, `VOLLEYSTAT_PUBLIC_URL`, `assetlinks.json`, `/j/` (both kinds of join link), `/m/` and `link.js` with their CSP, `ServerTarget.REMOTE`, the link host Gradle property, `privacy_server` and `privacy_tunnel` arguments, the operator checklist | nothing changes locally; a test window becomes possible | throttle tests (61 failures from X do not block Y; the circuit breaker never refuses a token or session request; a loopback proxy without a client address shares no key, R4; two IPv6 addresses in one /64 share a key), proxy header test per hosting option, start-up refusals (also `VOLLEYSTAT_PUBLIC_URL` without `--trusted-proxy`), `assetlinks.json` content, the fallback page (no inline script; the CSP header with `form-action`, `base-uri` and `frame-ancestors`; a Node test in CI parses the intent URL as `Intent.parseUri` does, with the data before the last `#` and `intent:` replaced by the scheme, and asserts that the data matches the manifest filter and keeps the fragment, R6; hostile fragments such as HTML, a second `#Intent;`, `javascript:` and overlong values create no element and leave the intent's fixed prefix and package unchanged, S6) |
| **B6b** Field test (F8) | (1) automated, always: `test_join_network.py` runs the 14.3 burst through a Python TCP delay proxy in the test helpers (150 ms each way; 2 % of reads stall 400 ms, standing in for TCP retransmissions; a mode that swallows one long-poll answer without closing the connection, run for a mid-queue waiter and for the head at the moment of the offer, conc V4; a mode that blocks all traffic for 45 s, R8), and `JoinE2eTest` runs through the same proxy on the emulator. (2) real phones, in an owner window (16.1 item 6): 30 minutes with 5-10 phones from a WhatsApp group on the owner's computer behind the chosen tunnel, with B6a on and sign-up codes printed for the people who start without a link. Cases: a newcomer without the app (APK by hand, re-tap), a newcomer with the app (register-first), a logged-in member (link, then Katıl), code paste from the message, one person on two phones, a waiting screen left alone for 3 minutes, a Wi-Fi to mobile data switch, the laptop lid closed for one minute, the tunnel stopped for one minute, the captain renewing mid-queue, the captain raising the substitute seats mid-queue, the app installed with its link not verified (Uygulamada Aç opens the pop-up, R6), two phones on different mobile networks getting different source keys (S2, R4), and, when B6c is merged, an open match link in the same group with a newcomer who has no account (sign-up through the open match link, R6), and the entry choice on a phone's first sign-in (10.10). Record per case the screens (phone screenshots), the server's counts (no names, no addresses) and any text that did not match the state. | evidence before the request slices | pass: for every holder, the remaining time at the pop-up's opening is at least 10 000 ms minus that exchange's measured round trip minus 50 ms, and the server's `expires_at` minus the phone's D is at most the measured uplink time plus 50 ms; exchanges without a stall also keep the 9.5 s floor; the pop-up always opens showing 10 or 9 (R9: a fixed 9.5 s floor failed about a third to a half of correct runs, because a 400 ms stall leaves about 9.3 s); no waiter with a live poll is SKIPPED or DROPPED, also with a swallowed answer and during the 45 s block; no ACCEPT that arrived in time is lost; every seat line and outcome names the type the server holds; in the real session, no false text, the pop-up opens showing 10 or 9, and no lost place with an open screen. Findings return to B5, B6 and B6c before B7. |
| **B7** Match requests | request links, accept and decline with every check, the opponent check, states and expiry, SOLO records (default yes), `GET /v1/matches`, the per-side upload gate with quotas, the 2-member rule (`TEAM_TOO_SMALL`), team video per uploader and the server-wide team pool (6.5, v5), the operator commands `signups`, `close-links` and `delete-match` and the two new columns of `storage --top`, `signup_via` and the teams captained (6.5, C2 of the v5 checkers), ADR-0120's B7 sentences (0.4), reviewer-only routes per video, `TEAM_MATCH`, cancel and leave with the side's video deletion; app request screens, the New Match team flow with the recorder always open, held uploads, offline request creation, Aynı Rakiple Yeni İstek, Takım Maçına Bağla, `tm/{id}`, Rakibi Onayla | requests and team videos work | `test_match_requests.py` (a request link cannot sign up; request details only to captains; the accept checks; decline stores the team's name and tag; HOME's upload refused before the opponent check; a declined PENDING request turns into SOLO, the held upload starts and A's statistics count the match, R1; Rakibim Değil; AWAY's cancel on an ACCEPTED record is AWAY's leave; a sibling of an ACCEPTED record starts ACCEPTED, and a fourth answers `409 SIBLING_LIMIT`), `test_shared_match.py` (a member of B cannot upload or analyse; one video per side; the reviewer per video; leave deletes the leaving side's videos and keeps the other side's), `test_quotas.py` additions of 6.5 (v5: a 1-member team, the uploader quota after a transfer, a full team pool), `test_admin.py` (`signups` lists the accounts that `link_signups` names and none after the 7-day sweep; `close-links` closes every live join link of the account with CLOSED and its open attempts; `delete-match` deletes the bytes before the commit; C2 of the v5 checkers), leave byte search, screenshots `25_mac_istegi`, `26_istek_ac`, `27_takim_maci` (waiting for acceptance, opponent check, both videos), `27b_kayit_bekliyor`, `MatchRequestE2eTest`, device test `RecorderAlwaysOpenTest` (record against a PENDING request, accept afterwards, the upload starts) |
| **B8** Side results, rosters and the open match's video and result | result confirm per side, `match_results` without job foreign key, typed rosters with the team default (`TEAM_DEFAULT` of the match-day members, 6 + 2 per side, 16 per team match) and its freeze into stored rows, the confirmed-roster snapshot (`match_roster_confirmed`), the LIBERO role, per side 6 starter places with empty places shown as "Boş" and stored as nothing, at most 6 substitutes and liberos together and 2 liberos (R3), a jersey optional until the confirmation, the self-removal that keeps the side's confirmation, the member read rule on stored rows only, the captaincy transfer refusals of R9; the open match: the organiser's one video with the reviewer role and no acceptance gate, the roster confirmation with jerseys for both sides, Bu Maçta Oynamadım, the result confirmation for both sides, delete refused after a result, the OPEN_MATCH storage scope; app `tm/{id}/report?job=`, Sonucu Onayla, Maç Kadrosu with as and yedek, `result_changed_*` per viewer, `open/{id}/roster`, the open match's Video Ekle and Sonucu Onayla | results can be confirmed | `test_results.py` (A's edits, re-analysis, rule change, video deletion, leave and team deletion leave B's side result byte-identical; B never confirming leaves A COUNTED; a stale revision is refused; a side result survives the deletion of its job; A confirms the list and leaves, B confirms the offer afterwards, and B is COUNTED, R7), `test_rosters.py` (a never-saved side reads to its own team as the members on the match day with their seats, and to the other team as not saved without entries, F2; a member of both teams is in neither default; the first upload, the end of the match day, a confirmation of version 0 and a self-removal from version 0 each write the default as version 1, and a later join, leave or jersey change leaves it, F2, V4-P1, R3; a member who joins after a match with an unsaved roster gets 403 on its video, is missing from its confirmed default and has no statistics line, V4-P1; X plays in jersey 7 and leaves, Y joins and takes jersey 7, and X keeps the credit while Y gets none, R3; a person with a frozen row stays valid after leaving the team; 7 starters, 3 liberos or 7 substitutes and liberos together refused, R6; a side with 5 starters confirms with one empty place, its payloads show `emptyStarterPlaces: 1`, and no row exists for the place, R3; a team seat swap after the side froze leaves its stored rows and its snapshot byte-identical, and a confirmation of version 0 runs every rule of 6.3, S1 of the v5 checkers; a confirmation with a missing jersey answers `JERSEY_MISSING`; a save after a confirmation keeps the confirmed roster counting and shows `changedSinceConfirmed`, V4-S3; a self-removal keeps the others' confirmation and removes the person's confirmed line; a captaincy transfer during an unfinished upload or with an unconfirmed list answers `UPLOAD_IN_PROGRESS` or `REVIEW_IN_PROGRESS`, R9; a team match reviewed on the uploading phone offers the stored jerseys, and a SCORER answer reaches the person's COUNTED line, R1), `test_open_match_results.py` (only the organiser uploads and reviews; one video; an upload with 1 player answers `OPEN_MATCH_TOO_SMALL`; the first upload closes a live link with PLAYED and its waiters with MATCH_PLAYED, and a join on the match day after the upload is impossible, V4-S2; the result confirmation writes both sides; a player who taps Bu Maçta Oynamadım loses access and every credit, and the others stay confirmed; delete after a result answers `RESULT_CONFIRMED` while another player holds a seat or a confirmed line and succeeds once none does, F9; cancel after a result answers `RESULT_CONFIRMED` also after the video was deleted, F1; a removal and a save after the confirmation keep every confirmed line, V4-S3; a transfer accept during an upload or before the list is confirmed answers 409, R9; the OPEN_MATCH quota counts on the uploader and stays after a transfer, V4-Q1; a confirmation of version 0 answers `JERSEY_MISSING` and writes nothing), `test_privacy_payloads.py` (11.3), screenshots `28_mac_kadrosu` (default, edited, missing jersey, libero, changed since confirmed, an empty starter place with its hint), `29_sonuc_onayi` (three viewer variants and the organiser), `36_acik_mac_kadro`, `37_inceleme_oyuncu` (SCORER card with server jerseys, `review_no_roster_server`, `review_no_roster_open`, the libero warning, `review_other_jersey` with its field and `review_other_jersey_taken`; side names under the RALLY_WINNER card), `OpenMatchE2eTest` extended to upload, review and confirm; app: the review chain's SCORER card and Players screen read the server roster (R1), the open match review waits for a saved split (R2), `TeamBadge` takes the side name (R2) |
| **B9** Statistics | `stats.py`, the two routes; app statistics screens with the kind and role of each line | read-only additions | `test_stats.py` (unknown never 0 as a property test over random rally lists; demo and unconfirmed never counted; only the own side result counts; `otherSide` never changes a number; `ROSTER_WAITING`; parts; line sums equal totals; SOLO counts for the own team only; an open match never changes any team's numbers, also when a team member plays in it; a person's open match counts after the roster and the result are confirmed, and not after Bu Maçta Oynamadım; `asStarter`, `asSubstitute` and `asLibero`; `sideWon` from the person's own side; statistics read only the confirmed roster, never a computed default; an empty starter place adds no line and no 0, and a point marked Oyuncuyu bilmiyorum makes a rostered person's missing number UNKNOWN, R3; a point on a jersey outside the roster keeps a rostered person's 0 MEASURED and reaches no person, S2 of the v5 checkers; lines carry `emptyStarterPlaces`), a string test that no ace label is the bare word "As" (R5), screenshots `30_takim_istatistigi`, `31_kisisel_istatistik` (team and open match lines) |
| **B10** Permanent hosting | nightly copies kept 7 days without `link.key`; the backup sentences in the privacy texts; recovery codes; the owner's decision on sign-up without a link or a code (16.1 item 32), with its texts; health `mode` and the FastAPI description stop saying "local-dev" only behind TLS | changes nothing locally | backup rotation deletes copies older than 7 days; `link.key` never in a copy; recovery code flow |

Each slice runs the full backend suite, `:app:testDebugUnitTest`, the screenshot job, the e2e and device jobs, and an independent review before its PR. Each slice that deletes anything extends the byte-search test. Every new Turkish string passes `turkce-insansi-metin` before its PR.

### 14.2 Fixtures and CI

- **Storage-scoped auth fixture.** The `client` fixture creates one user and one session per storage folder and sets `client.headers["Authorization"]`. A test that restarts the server on the same storage gets the same token, because sessions live in the database. `helpers.serve` does the same. Extra fixtures: `anonymous`, `stranger`, `two_teams` (two captains, a member each, an ACCEPTED record with both opponent checks), `open_match` (an organiser and 13 players with seats, the split saved), `fake_clock`, `delay_proxy`.
- **CI.** The e2e and device jobs start the backend with `VOLLEYSTAT_SIGNUP=open`, as they do since B1; `VOLLEYSTAT_OPEN_MATCH_SIGNUP` keeps its default `link`, and v5 removes `VOLLEYSTAT_TEAMS`; the tests of the gates set `link` and `off` explicitly. E2E tests register `e2e_<random>` users and delete them afterwards. `require_tests_ran.py` rises from 11 to 17 (e2e, with `OpenMatchE2eTest`) and from 2 to 4 (device); the device tests log in on the emulator, record against a PENDING request, and reach the recorder of an ACCEPTED record.

### 14.3 The concurrency test (B5)

`backend/tests/test_join_concurrency.py` runs against the real server, not `TestClient`, with the app's polling on:
1. Seed a storage folder with a team whose pool has 6 starter seats and 5 substitute seats (capacity 11, the captain on a starter seat, so 5 starter and 5 substitute seats are open), its invite, and 101 users with sessions written straight into SQLite (no 101 scrypt hashes).
2. Start `python -m app --port <free port> --analyzer none` in a subprocess on that storage.
3. A raw-socket generator (asyncio streams, two keep-alive connections per phone, or one process per 25 phones) sends 100 `POST /v1/join-attempts` at once. The test asserts from the stored `arrived_at` values that the arrivals spread less than 50 ms; otherwise the run is invalid and repeats.
4. Every phone that gets WAITING or HOLDING starts its long-poll at once with its `view` and re-polls the moment an answer arrives, during the burst, as the app does.
5. Assert: every claim answered within 3 s in CI (the owner's computer is held to p95 under 1 s); exactly 10 HOLDING and 90 WAITING; sorted by `queue_no`, the first 5 holds have seat STARTER and the next 5 SUBSTITUTE; sorted by `arrived_at`, `queue_no` increases for every pair more than 5 ms apart, and the holders are the 10 earliest arrivals within that tolerance; each waiter's `ahead` equals its rank; for each type, holders + offered + holding ≤ seats in the database; fewer than 400 poll answers during the burst (the critic measured 145 with these fixes and about 2 000 without); after the burst every WAITING attempt is in the published snapshot, and the burst's polls made no database read (conc V7).
6. The same person claims twice at once: one attempt.
7. With 90 long-polls waiting, `GET /v1/health` answers within 100 ms.
8. Nine holders accept and one starter holder rejects: the head waiter's poll returns OFFERED with seat STARTER within 0.5 s of the reject's answer; its ack returns HOLDING with `remainingMs` above 9 900.
9. The new holder does nothing and sends no TIMEOUT: the next waiter is offered between 2.0 and 2.5 s after `expiresAt`. A holder that sends TIMEOUT 0.3 s before `expiresAt`: the next waiter is offered within 0.5 s after `expiresAt`, and the hold was still HOLDING until `expiresAt`.
10. A second process holds the SQLite write lock for 2.5 s; an ACCEPT arrives at `expiresAt + 1.0`: JOINED, and nobody was offered meanwhile (C2). An ACCEPT whose headers arrive 0.1 s before `grace_until` and whose body follows 0.3 s later: JOINED, and nobody was offered (conc V1).
11. An ack arrives 0.1 s after `offer_deadline`: `409 ATTEMPT_CLOSED` (SKIPPED) by T6b, the head of the queue is offered in the same transaction, and no row is added (C5, conc V3).
12. A HOLDING poll that waited 15 s answers with `waitedMs` about 15 000 and a `remainingMs` that keeps `sentAt + waitedMs + remainingMs` within 50 ms of the true deadline (C3).
13. With maximum-size logos, every claim, ack, decision and poll answer is under 2 KB (C7).
14. Slow-marked: the SIGSTOP cases (a) to (d) of 2.11: waiters polling with an offer open; a writer queued behind a 0.6 s write at the stop; a claim sent during the stop; an ACCEPT sent during the stop. Nobody DROPPED or SKIPPED, no queue wiped, the offer has at least 10 s, and the ACCEPT is JOINED (C6, conc V2).
15. Kill the server with a hold and an offer open, restart it, and assert 2.11.
16. Final state: holders = capacity, the invite closed with reason FULL, every remaining waiter CLOSED with reason FULL.
17. Poll caps set to 10 with 30 waiters polling for 40 s: nobody DROPPED (conc V5). 400 polls from link-created accounts on closed attempts DROP or SKIP no waiter of another pool (S5).
18. A holder with two sessions sends TIMEOUT every 20 ms for 60 s: the next waiter is offered within 2.5 s after `grace_until` (S1).
19. **Two kinds at once (v4).** A second seeded pool, an open match with 12 starter and 2 substitute seats and a playing organiser, gets its own 100 claims while the team burst runs. Assert for it: 13 HOLDING (11 STARTER, then 2 SUBSTITUTE by `queue_no`) and 87 WAITING, the same order rule, and no number, order or timing of either pool that depends on the other.
20. **Seat changes mid-queue (v4; own seed since the v5 checkers, S5).** A second seeded team pool of 6 + 2 with the captain on a starter seat, filled to 6 + 1 holders, with a substitute hold open and 3 waiters (item 1's 6 + 5 cannot take two more substitute seats under the bound of 0-6). A starter member leaves, and the head is offered STARTER in the same transaction while the substitute hold keeps its type and its `expires_at`; the captain raises the substitute seats from 2 to 4, and the next two heads are offered SUBSTITUTE in one transaction and their polls answer within 0.5 s; the captain lowers the substitute seats to 3, below the substitute holders + deciding (1 + 3), gets `409 SEATS_IN_USE`, and no attempt changes.

The Android side runs `JoinConcurrencyE2eTest` in the e2e job: 100 `ApiClient` claims from a 100-thread pool against the CI backend, each followed by its long-poll loop, with the same partition assertions, so the app's HTTP stack and parsing are tested under the same burst. B6b runs the same through the delay proxy.

---

## 15. Deliberately left out

| Left out | Why |
|---|---|
| push notifications (FCM) | device tokens go to Google, a KVKK question; the app polls only while the join screen or the captain's or the organiser's live view is open |
| Google sign-in, e-mail, phone | Google later with a free OAuth client |
| recovery codes | B10, when strangers can reach the server for good |
| captain approval of joins | speed; the captain removes and blocks instead |
| several live invites per team, per-invite capacity, a one-off date per invite | not asked |
| a team or user directory, search, a previous-opponent picker | privacy |
| export endpoint, date filters, logout-all, session cap | not asked |
| reviewing the other side's video from a phone that did not add it; clip playback for the other side | a later slice that re-keys the review chain by server ids; a side that films reviews its own video instead (6.2) |
| per-part rosters | one roster per video (6.3) |
| arbitration of result disputes | each side counts only its own captain's result, so a dispute changes only the disputing side's line |
| a storage budget per creator of join links (C2 of the v5 checkers) | every link account can create its own team and invite, so each would bring a fresh budget and one person could still fill a pool with a few more registrations, while a real group would share one budget among everyone its captain invited; v5 keeps the server-wide pools, says plainly that one person can fill one alone, and gives the operator the commands of 6.5 |
| a server-wide cap on successful sign-ups (P3 item 2) | conflicts with P4 (no server-wide refusal of requests with a live token); v4 relied on the per-invite limit and the captain-code gate; since the gate is gone (R6), v5 bounds sign-ups per link (200) and per creator (400 in 7 days, 3.3), which a stranger cannot exhaust without holding that creator's link, and lists the remaining growth as a risk (16.2) |
| a 5-minute re-claim cooldown (P3 item 5, part) | a re-claim joins the end of the FIFO queue and never passes a real waiter; the cooldown would block "Yeniden Sıraya Gir" |
| a deferred deep link after a sideload | not possible outside Play; the person taps the WhatsApp link again |
| website files without the owner's permission | the website is the owner's; 12.4 |
| rankings, ratios without counts, "best player" | design.md line 61 |
| offline queueing of account actions | the app never shows an action as done before the server confirms; only a match request is created offline, by client id |
| roles beyond captain and member | not asked |
| a captain or an organiser adding a person without a link (v5) | the owner, 18 November: people are invited by a link; every seat goes through the one engine and its pop-up (R6) |
| storing the entry choice on the server (v5) | minimum personal data; the choice only opens the first screen (R5, 10.10) |
| a starter count other than 6 per team or per match side (v5) | the owner, 18 November: six on the court (R1-R3) |
| a placeholder person or a guest name for an empty starter place (v5) | a roster never invents a player; a person without an account gets no line (R3) |
| refusing a roster confirmation with fewer than 6 starters (v5) | a short-handed side could not confirm honestly; 16.1 item 34 |
| Play Store distribution | a one-time fee; APK by hand is free |
| a web app or iOS | not asked |
| minors' consent, the aydınlatma metni | the KVKK review |
| more than one server process | the snapshot, notifier, session cache, in-flight entries and FIFO lock assume one process; the ADR records it |
| the time the server wrote a poll's answer as a heartbeat (conc V4's first option) | `queue_dropped` says the server heard nothing from the app for 30 s; counting the server's own answer would make that false, so the revision lowers `POLL_WAIT` instead |
| a second live video per side of one record | one reviewer per side keeps the audit simple (8.4); a second recording of an accepted match goes to a sibling record (5.5), and of an open match to an open match sibling (5.8.6) |
| upgrading an open substitute reservation to a starter seat when a starter seat frees | a reservation's type never changes, so no pop-up text moves while it counts (2.3); owner decision 16.1 item 16 |
| the server moving members or players between starter and substitute | the captain's or the organiser's call; `team_starter_free_hint` points it out (4.3) |
| starting line-ups per set, rotations, libero replacements (FIVB 7.3.2, 19.3.2) | the video does not show who is on court; a roster lists roles only, LIBERO among them (6.3, R6) |
| 14 players per side (FIVB 4.1.1, official competitions) | amateur teams and pickup games fit 12; owner decision 16.1 item 22 |
| team statistics from open matches | an open match has no team side (5.8.7) |
| a second video of one open match, or a player's own upload | one reviewer per open match; a second recording goes to a sibling with the same players (5.8.6, R4) |
| an open match with a past date and no link, for a game organised outside the app | owner decision 16.1 item 28; such a game stays a personal match |
| reviewing a video on a phone that did not add it, after a transfer | the server refuses a transfer while a review is in progress (R9); a later slice may re-key the review chain by server ids |
| a directory or search of open matches | privacy; people join by link only (R6) |
| adding a player to an open match without the link | every seat goes through the one engine and its pop-up |
| open match links after the match day, after the first upload, or on a sibling | nobody joins after the video reached the server or after the match day; before the first upload the organiser closes the link or removes a late joiner (5.8.3, V4-S2) |
| appointing an organiser without consent | a transfer is an offer; account deletion leaves the match without an organiser (5.8.4, 3.7, V4-C1) |

---

## 16. Owner decisions and risks

### 16.1 Owner decisions

**Settled by the owner on 18 November** (v5 changes):

| Decision | The owner's answer | Where |
|---|---|---|
| Six on the court; the captain changes only the substitutes | "Kaptan ancak yedekleri değiştirebilsin. Sahada 6 kişi olmak zorunda."; the reading R1 confirmed ("Evet, böyle"): a team always has 6 starter seats, the captain sets 0-6 substitute seats and still moves members between as and yedek. Item 15 is settled; item 22 is settled for teams only. | 2.3, 4.1, 6.3 |
| No specific team uses the app | "Bu uygulamayı kullanacak net bir takım yok." No list of the owner's teams and no captain code bound to a name (R4). Item 3 is removed. | 0.4, 3.3 |
| Who creates teams and matches (R6) | "Uygulama içerisindeki herhangi biri yani hesabı oluşturmuş herhamgi biri maç ve takım oluşturuabilir." Every account creates; captains and organisers add people only by sending a link; both kinds of join link open sign-up. Item 23 is settled; item 19 is settled for who opens one. | 0.4, 4.1, 5.8 |
| A way in for a 12-person match played now and then | "Ya da 12 kişilik maç kutmak istiyorum ara sıra oynayacağız mantığında da kurabilirsin. Orayı biraz sen geliştir": a person can set up such a match on entry, and the owner left the details to the session. Only that is settled. The session's readings of it, the fixed 12 starter seats of an open match (R2, items 19 and 22) and the entry choice (R5, item 35), are defaults that the owner can still change (C7 of the v5 checkers). | 5.8, 10.10 |

The readings R2, R3 and R5 and the new limits of R6 are the session's development of the message, and the owner can still answer each: items 19 and 22 hold R2, item 34 holds R3, item 35 holds R5 and item 33 holds the limits, each with its default (C7 of the v5 checkers).

**Settled by the owner on 17 November** (v4 changes):

| Decision | The owner's answer | Where |
|---|---|---|
| 1. Teams and matches | keep teams; a team is 6 starters and 2 substitutes; a team match roster is 16 (12 + 4); captains create teams and send match requests to captains; besides that any user opens a match with 12 starters and 2 substitutes; both counts can change. The session's readings of it are items 15-27 below, each with its default. | 2.3, 4, 5.8, 6.3 |
| 2. Login | username, password and display name; Google later | 3.1 |
| 3. The join link | an invite link plus an 8-character code; v3's item 5 below is therefore settled | 2.2 |
| 4. The decision window | 10 seconds, plus a 2-second grace that the phone never shows, judged by arrival | 2.6, 2.9 |

**Still open, with v3's defaults kept.** The owner did not answer these. Every item has a default that works without the owner's action, and no default needs a paid subscription. Item 1 states what each hosting option needs and costs; the only public option without a fee is the reverse proxy with a free dynamic-DNS name, where the home connection allows it (fidelity V4).

| # | Decision | Default without the owner | What the default costs |
|---|---|---|---|
| 1 | **Hosting and tunnel** (12.3). A reverse proxy on the owner's computer: no fee, needs a free dynamic-DNS name whose terms allow this use and a home connection with a public address and an open port 443. Tailscale Funnel: TLS ends on the computer, but the free plan is non-commercial, so for this closed commercial product it means a paid plan (not planned). Cloudflare Tunnel: free tunnel, but a stable host needs a bought domain or the website domain's DNS on Cloudflare (an owner-permission change like item 2), and TLS ends at Cloudflare abroad. A free VM: often a payment card and data abroad. Whichever host: a neutral host name, because it becomes public; with Tailscale, whether tailscaled runs with `--no-logs-no-support` (S10). | no public server; everything on one computer | no use between teams in different places, no real-phone field test, no link that opens the app; codes only, on emulators and USB phones |
| 2 | **Link host** (12.4): the owner's website domain (four static files; needs the owner's explicit permission for a website change inside a planned release) or the API host | the API host once hosting exists; codes only before | links in old WhatsApp messages break whenever the API host changes; the fallback page is down whenever the owner's computer is |
| 3 | (removed in v5: the captain-code gate and the list of the owner's own teams; the owner settled both on 18 November, R4, R6) | | |
| 4 | **Storage quota numbers** (6.5, S7, D1k, V4-Q1) | 8 GiB personal video per account opened with an operator code; 2 GiB and one unfinished personal upload per account opened through a join link; open-match video counted on the uploader, 8 GiB for an account opened with an operator code and 4 GiB (one maximum-size video) for one opened through a link, one unfinished open-match upload, and no upload to an open match with fewer than 2 players; server-wide pools of 40 GiB personal and 60 GiB open-match video; 40 GiB per team; since v5 also team video per uploader, 40 GiB with an operator code and 16 GiB through a link, a server-wide team pool of 200 GiB, and no team video before a team has 2 members (item 33); 20 GiB free-disk floor; abandoned uploads swept after 24 h | a team holds about 10 maximum-size videos (4 GiB each, `MAX_VIDEO_BYTES`) before it must delete old ones (statistics stay); a member who joined through an invite holds 2 GiB of personal video, half of one maximum-size video (v3 said "about two", which was wrong), and one maximum-size open-match video at a time, so an organiser who joined through an invite deletes or waits before the next open-match upload; the checker proposed 2 GiB for link accounts, which would not fit one full-quality game (the app records FHD first, `Record.kt` line 309), so the default is 4 GiB; 15 link accounts can fill the open-match pool, and then only open-match uploads stop; a phone that pauses an upload for more than a day restarts it from zero; uploads stop when the disk nears 20 GiB free |
| 5 | **Code length** (2.2) | settled by the owner on 17 November: 8 (`K7QF-2M9X`) | two more characters to type than 6 when pasting is not used |
| 6 | **Field test with real phones** (B6b) | the automated shaped-network run only; B7-B9 continue; no team uses the app outside a field-test window until the real session passes | B7-B9 rest on emulator evidence; what only real phones show (WhatsApp's browser, a maker's screen-off rules, network switches) may rework B6 and B6c screens later |
| 7 | **One-sided team matches** against opponents not on the app (5.7) | yes | those matches carry one captain's review only; one more route and screen. A declined, expired or cancelled request also turns into a one-sided record through it (R1); without it such a recording counts only through a request the other captain accepts (16.2). Open matches do not replace it: an open match counts for persons, never for a team. |
| 8 | **Distribution** (12.5): APK by hand, a public GitHub Release, or Play Store | APK by hand, from the owner to the first captains and organisers and from them to their groups (v5) | every newcomer allows an unknown-source install and taps the link again after installing, or uses Kod Gir on the first-start screen |
| 9 | **Decision window for screen-reader users** (2.14) | 10 s for everyone | TalkBack users get the same 10 s |
| 10 | **Backups** (12.6, B10) | nightly copy on the same computer kept 7 days, without the link key | no protection against losing the disk; deleted data stays in copies up to 7 days, and the texts say so |
| 11 | **Numbers** | team seats 6 + 2, the 6 fixed (items 15 and 22); open match seats 12 + 2, the 12 fixed (items 19 and 22); invite and request validity 7 days, an open match link until the end of its match day or its first upload at the latest; 20 teams and 3 captaincies per person, 3 team creations a day (v5, item 33); 3 open matches organised and 10 joined that are not cancelled and whose day has not passed, 10 creations a day; queue 200; sign-ups per join link 200 and per creator 400 in 7 days (v5); 3 open attempts per person; heartbeat 30 s; poll wait 15 s with a read timeout of wait + 3 s (wait 2 s for a waiter who may be offered next); offer acknowledgement 8 s; grace 2 s; in-flight protection at most 5 s after a deadline; 5 acks or decisions a second per attempt; roster per side 6 starter places, at most 6 substitutes and liberos together, 2 liberos; polls 4 per account, capacity + 200 per pool and 400 per server; source limits 100 failures per 10 min; 3 sibling records per accepted record and per open match; 2 players before an open match takes a video | a ghost at the head of the queue costs the next person 8 s; a leaked join link can open up to 200 accounts before the captain renews it, and all the links of one account at most 400 in 7 days; a waiter far back polls every 15 s |
| 12 | **Renewing the link closes the queue** | yes, for invites and open match links | the waiting people must open the new link |
| 13 | **Team schedule** | weekday and time | a team invite's pop-up cannot show a one-off match day; an open match shows its own date, so a one-off game can be an open match |
| 14 | **A removal renews the live join link** (4.3, 5.8.4, S3) | the switch Bağlantıyı da Yenile starts on, for a team and an open match | the waiting and deciding people's attempts close, and the captain or the organiser shares the new link again where the removed person cannot see it |

**The readings of decision 1** (v4 changes, D1a-D1o), the choices that the v4 checkers' points needed (items 28-31, 13.3), and v5's development of the message of 18 November (items 32-35). Each is built as stated unless the owner answers otherwise.

| # | Decision | Default without the owner | What the default costs |
|---|---|---|---|
| 15 | **Team seats** (D1a, R1; 2.3, 4.1) | **settled on 18 November**: always 6 starter seats; the captain sets 0-6 substitute seats, 2 by default, capacity 6-12, the captain on a starter seat by default, and moves members between as and yedek | a group of more than 12 needs a second team or plays open matches; a team with fewer than 6 starter members has free starter seats, which the next joiners fill first |
| 16 | **Seat order and a reservation's type** (D1b; 2.3) | starters first in queue order, then substitutes; an offer's or hold's type never changes; the server never moves a member | when a starter seat frees while someone decides on a substitute seat, the next person in the queue gets the starter seat; the captain can swap the two afterwards. The other choice upgrades the open reservation and changes the pop-up's seat line while it counts. |
| 17 | **Changing counts and moving holders** (D1c; 2.3) | the captain or the organiser changes the substitute count (v5) and moves holders in one call checked on the final state; never below holders + deciding of a type; no open reservation revoked | a captain who wants fewer substitute seats while someone decides on one waits until that person decided |
| 18 | **The team match roster** (D1d; 6.3) | each side starts as its team's members on the match day with their seat types (16 = 12 + 4 with the defaults), a member of both teams in neither; the server stores that default as the side's rows at the record's first upload, at the end of the match day, at a confirmation or at a self-removal; each captain edits and confirms their own side per match; the other team never sees an unsaved default | a captain who confirms without looking credits members who were absent until they tap Bu Maçta Ben Yoktum; a former member who played but left the team on the match day, before the upload, is not in the default, and the captain cannot add them |
| 19 | **Open matches** (D1e; 5.8) | any signed-in account opens one (settled on 18 November, R6); always 12 starter seats and 0-12 substitute seats, 2 by default (R2: the session's reading and a default, because the owner asked for a 12-person match but did not say that its starter count is fixed); the organiser on a starter seat ("Ben de oynuyorum" on); the same join flow; the pop-up shows the organiser's display name, the date, the time, the venue and the seat type | one more route family and five screens; open matches mix people who may not know each other |
| 20 | **The side split** (D1f; 5.8.5) | the server stores a proposed side for each player when they join (A, B, A, B in join order within each seat type) and never moves it; players see their side only after the organiser saves the split; the review opens only after a saved split | friends who joined one after another land on opposite sides until the organiser moves them; the organiser saves the split before reviewing |
| 21 | **Open match statistics** (D1g; 5.8.6, 5.8.7) | person statistics only; they count after the organiser confirms the roster with jerseys and the result; the confirmed roster counts until the next confirmation; Bu Maçta Oynamadım removes the person and keeps the others' confirmation | an organiser who never confirms leaves every player's line uncounted; no team gets numbers from an open match |
| 22 | **FIVB bounds** (D1h; 2.3, 6.3) | exactly 6 starter seats per team (settled on 18 November, R1); 6 starter places per match side and the fixed 12 starter seats of an open match are the session's defaults (R2, R3, C7 of the v5 checkers); at most 12 players per team and per match side (rules 7.3.1 and 4.1.1); the 14 of FIVB, World and official senior competitions not used | a side of 13 or 14 for an official competition does not fit. The owner can choose 14: that means 0-8 substitute seats per team, 0-16 per open match with at most 8 per side, and a `TOO_MANY_SUBSTITUTES` bound of 8 per roster side. The bounds live in code, not in the schema (8.2), so the change needs no rebuild of `seat_pools` |
| 23 | **Open matches and sign-up** (D1i, R6; 0.4, 5.8.3, 11.3) | **settled on 18 November**: every account opens and joins open matches; an open match link opens sign-up (`VOLLEYSTAT_OPEN_MATCH_SIGNUP=link`, with `off` as an operator switch); no members rule | people the owner does not know can join an open match through its link, open an account and then create their own teams and open matches (0.4, 11.3) |
| 24 | **Who sees what in an open match** (D1j; 5.8.8, 11.1) | logged-in link holders see the organiser's display name and the match details; players see each other's display names, sides, jerseys and the video, across sides | a player shares their display name and video with everyone in the game, also with people they may not know |
| 25 | **Open match limits** (D1k; 5.8.9) | 3 organised and 10 joined that are not cancelled and whose day has not passed; 10 creations a day; one video per open match, after at least 2 players joined; open-match video counted on the uploader (item 4) | a busy organiser waits for a match day to pass, or cancels a match, before opening a fourth; a second recording of one game needs a sibling record (item 28) |
| 26 | **Joining late** (D1l; 5.8.3, 5.8.4; F6, V4-S2) | an open match link lives until the end of the match day or the record's first upload at the latest; no new link afterwards; the date is fixed once the match day began or an upload exists | a person who played but did not join before the upload or the end of the match day gets no numbers from that game; a person who did not play can still join until then, so the organiser closes the link after the match or removes that person |
| 27 | **The players' numbers and the organiser** (D1m; 5.8.4, 5.8.10; F1, F9, V4-S3) | cancel only without a video and without a side result; delete only without a side result, or once nobody but the organiser holds a seat or a confirmed line; after a confirmation the confirmed roster and result keep counting through later saves and removals; the organiser changes them only by confirming anew | an organiser cannot remove a played open match from the server while others hold its numbers; the organiser can still correct, and so drop, a player's line by confirming a new roster; a player can leave, which removes that player's line at once |
| 28 | **A second recording, and past-dated open matches** (D1o; 5.8.2, 5.8.6; R4) | a second recording of one open match goes to a sibling that copies the players and the split, at any time, at most 3, counting toward no limit; an open match with a past date and no link for a game organised outside the app is not offered | a game organised outside the app gives no person statistics; a sibling's players are the original's, so a player who missed only the second part taps Bu Maçta Oynamadım on the sibling |
| 29 | **The organiser role moving** (D1n; 3.7, 5.8.4; V4-C1, R9) | a transfer is an offer the player accepts, refused at the recipient's ORGANISED limit, during an unfinished upload and while a live video's list is not confirmed; account deletion leaves an open match with other players without an organiser; captaincy transfers are refused in the same two video states | an organiser who wants to hand over must finish the upload and the review first; an open match whose organiser deleted their account keeps its numbers as they were, and nobody can confirm them any more |
| 30 | **Liberos** (D1h; 2.3, 6.3; R6) | a LIBERO role in match rosters only, at most 2 per side, outside the 6 starters; team seats stay as and yedek, and a libero usually holds a yedek seat; `asLibero` in person statistics; a review warning for a libero's block or ace | one more role in the roster editor. The other choice keeps two roles, and `roster_body` then says that a libero is listed as yedek |
| 31 | **Confirmed rosters as snapshots** (D1g, D1m; 6.3; V4-S3) | for team sides and open matches alike, the confirmed roster is stored and counts until the next confirmation; a save, a removal or a team change does not lapse it; a self-removal removes only that person's line | a captain or an organiser who edits after confirming sees `roster_changed_since` until confirming again; the counted roster can lag behind the working one |
| 32 | **Sign-up without a link or a code** (R6; 0.4; for B10). The owner let every account create but did not say whether a person without any link may open an account. | closed, as B1 built it: a person without a link needs an operator code (`VOLLEYSTAT_SIGNUP=link`) | a person who installs the APK without a link cannot open an account alone and asks the owner or a captain. Opening sign-up (`VOLLEYSTAT_SIGNUP=open`, today for tests only) would let anyone who has the APK hold an account and create teams and open matches: more personal data of people nobody invited, before the KVKK review; more accounts for the throttles; and abuse that only the operator can clean up |
| 33 | **The limits that replace the gate** (R6; 3.3, 3.6, 4.1, 6.5) | captain of at most 3 teams, also on a transfer; 3 team creations a day; at most 400 sign-ups in 7 days through the join links one account created; team video per uploader 40 GiB with an operator code and 16 GiB through a link; a server-wide team pool of 200 GiB; no team video before a team has 2 members | a captain of more than 3 teams hands one on first; a captain whose links opened 400 accounts in a week waits, and the newcomers ask the owner for a sign-up code; a link-opened captain holds four maximum-size team videos before deleting old ones (statistics stay); one person can fill each server-wide video pool alone with the link accounts of their own invite (about 20 for the personal pool, 15 for the open-match pool, 13 with 2-member teams for the team pool), and then every account's uploads of that kind stop until the operator cleans up with the commands of 6.5; the 2-member rule only keeps an honest one-person team from storing private video and stops no one who opens a second account; a one-person team records but uploads nothing until a second member joins; the two daily creation limits start again when the server restarts (3.6) |
| 34 | **Empty starter places** (R3; 6.3, 7.1) | every match side has 6 starter places; a place without a STARTER entry shows "Boş", stores nothing and counts for nobody; the editor suggests moving a substitute who started; a confirmation with empty places is allowed | a captain who leaves a starter place empty although a substitute started credits that person as a substitute (`asSubstitute`), never as a starter; a person without an account who played gets no statistics; a point the reviewer gives to that person's jersey with Kadroda Olmayan Numara keeps the named players' zeros MEASURED, and only a point marked Oyuncuyu bilmiyorum keeps their missing numbers UNKNOWN in that part (S2 of the v5 checkers). The other choice would refuse a confirmation with fewer than 6 starters, which an honest captain of a short-handed side could not pass |
| 35 | **The entry choice** (R5; 10.10) | after the first sign-in on a phone, four choices and Şimdi Değil; skipped when the account already has a team, an open match or a personal match, and after a sign-in through a link that ends in a join or an answered request; opened after the outcome when such a link flow ends otherwise; marked seen for every account that existed before B6c; reachable again from Takımlar and Profil; nothing stored on the server, `entrySeen` on the phone | one more screen once per account and phone; a person who skips it finds the same actions on the tabs. The other choice stores the choice on the server to tailor later screens, which adds personal data for little gain |

Settled without a decision: the recorder stays open (F5, the owner's rule that the video part stays as it is); each side counts only its own captain's result (F3, required by "a team's own numbers must not depend on the other captain's goodwill"), and a side that did not film can confirm the result that the filming side's reviewer offered, even after that side left (R7); one join engine serves both kinds of join link, so a team invite and an open match link behave the same way in every state (2.1).

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
| Leaked request link | Any account can lead a team (R6), so a leaked request link can reach a team nobody knows. The requesting side's upload waits for its opponent check, and Rakibim Değil moves its recording to a one-sided record (R1). Tags help only if captains know each other's tags. |
| One-sided fallback | Without one-sided records (item 7 answered no), a requesting team's recording counts only through a request the other captain accepts; a declined or expired request leaves it uncounted (R1). |
| Differing numbers | Each team shows what its own captain confirmed, so A's points against may differ from B's points for. |
| Wrong roster | The roster is the captain's declaration. A person can leave it; the statistics screen states the rule. |
| Lost credentials | No e-mail: the operator resets until recovery codes arrive in B10. |
| Ghost waiters | A waiter whose app stopped holds a place for up to 30 s, and a ghost at the head costs the next person 8 s. |
| New members and old matches | A member who joined after a match sees its content only if the captain puts them on its roster. The default roster lists only the members of the match day and becomes stored rows by the end of that day at the latest, so a later member never enters it (6.3). |
| Demo-only setups | Demo jobs never count, so a demo server shows UNKNOWN statistics forever. |
| Open-file limits | The server raises its soft limit at start-up; a hard limit below 1 024 lowers the poll cap and is logged. |
| Team seats are not line-ups | A team's as and yedek label its usual starting six; who started a match is the captain's per-match roster (6.3). A captain who never edits the default roster credits the team's seat types of the match day to every match. |
| A freed starter seat skips a deciding substitute | With item 16's default the next person in the queue can get a starter seat while an earlier person decides on a substitute seat; the captain swaps them afterwards (2.3). |
| Open matches | They mix people who may not know each other, who then see each other's display names and video (5.8.8). Since v5 the link also opens an account for a newcomer (R6). |
| People the owner does not know (v5, R6) | Every account creates teams and open matches and invites further people, so the server holds accounts, display names, rosters and videos of people the owner has never met, before the KVKK review and without an aydınlatma metni (0.4, 11.3). The server does not check who anyone is. |
| Account growth (v5, R6) | Each account's links open at most 200 accounts each and 400 together in 7 days, but nothing bounds how many accounts invite further people. Hashing throughput (two workers, 3.5), the storage pools (6.5) and the operator's commands (6.5) bound what the accounts cost, and one person can still fill a pool alone (the next row); a server-wide cap was left out because it would let a stranger refuse every real newcomer (15). |
| Video pools (v5, R6; C2 of the v5 checkers) | One person alone can fill each server-wide video pool with their own invite: about 20 link accounts fill the personal pool, 15 the open-match pool, and 13 with 2-member teams the team pool. Then every account's uploads of that kind wait until the operator cleans up with the commands of 6.5; the other kinds and the free-disk floor keep working. No per-account or per-creator number prevents it, because every link account can create its own team and invite (6.5). |
| The organiser's role | One person reviews and confirms an open match for everyone; an organiser who never confirms leaves every player's line uncounted. After a confirmation, saves and removals do not change what counts; the organiser changes the counted lines only by confirming a new roster or result, and a player's own leave removes that player's line at once (5.8.6, 6.3, V4-S3). |
| An organiser who deletes their account | The open match stays without an organiser: its numbers stay as they were, nobody can confirm a roster or result any more, and a half-reviewed video stays unreviewed (3.7, V4-C1). |
| Transfers wait for the review | A captain or an organiser with an unfinished upload or an unconfirmed rally list cannot hand the role on until they finish or delete the video (R9). |
| Late joiners | An open match link ends with its match day or its first upload, so a person who joins after that gets no numbers from that game; a person who did not play can join before it, and only the organiser's removal keeps them out (5.8.3). |

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

v4 adds no measurement. The engine's hot paths (the claim transaction, the snapshot, the long-poll, the FIFO lock and the arrival rule) keep their structure; typed seats add one grouped count per `settle` on the primary keys of `memberships` and `open_match_players`, and the 14.3 test measures the full route with both kinds of pool (steps 19 and 20).

Sources consulted for the hosting comparison: Tailscale Funnel documentation (https://tailscale.com/kb/1223/funnel) and Tailscale's plans page (https://tailscale.com/plans/). Both are to be re-checked by the owner when choosing.
