# Fark analizi: V4 gereksinimleri ve mevcut kod

**Tarih:** 12 Kasım 2026 · **Kod sürümü:** 0.2.0 · **Kaynak:** V4 devir raporu ve ekleri

Durum etiketleri:

- **TEST EDİLDİ**: Kod var ve otomatik testi bu sürümde çalıştırıldı.
- **KISMEN**: Bir bölümü var. Eksik bölüm satırda yazılıdır.
- **YOK**: Henüz geliştirilmedi. Yazılım işidir, dış bağımlılık beklemez.
- **DIŞ BAĞIMLILIK**: Gerçek veri, hesap, cihaz, bütçe ya da hukuki onay gerektirir.
- **ARAŞTIRMA**: Hangi yöntemin işe yaradığı ölçülmeden karar verilemez.

Hiçbir yapay zekâ bileşeni çalışmıyor. Hiçbir doğruluk ölçülmedi. G0–G8 kapılarının tamamı `NOT_MEASURED` durumundadır.

## 1. Fonksiyonel gereksinimler (FR-01 … FR-20)

| ID | Özellik | Öncelik | Durum | Açıklama |
|---|---|---|---|---|
| FR-01 | Modern Android arayüzü | P0 | KISMEN | Compose ile tek ekran var. Gezinme, küçük ekran ve erişilebilirlik testleri yok. |
| FR-02 | Hesap ve takım taslağı | P0 | KISMEN | İki takım adı ve doğrulaması var. Hesap yok. |
| FR-03 | Galeriden video | P0 | KISMEN | Sistem Photo Picker bağlı. Cihazda denenmedi. Küçük resim ve süre gösterimi yok. |
| FR-04 | Kamera video kaydı | P0 | KISMEN | Sistem kamera uygulaması çağrılıyor. Kayıt durumu, boş alan uyarısı ve kurtarma yok. |
| FR-05 | Kamera yerleşim rehberi | P0 | YOK | CameraX önizlemesi ve saha çerçevesi gerekir. |
| FR-06 | Video kabul kontrolü | P0 | KISMEN | Codec, süre, çözünürlük, kare hızı ve hak beyanı sunucuda **TEST EDİLDİ**. Sahne kalitesi ve saha ROI'si yok. |
| FR-07 | Kesintiye dayanıklı yükleme | P0 | KISMEN | Sunucu protokolü (offset, devam, SHA-256) **TEST EDİLDİ**. Uygulama kopan yüklemeyi kendisi sürdürmüyor ve arka planda çalışmıyor (WorkManager yok). |
| FR-08 | Analiz iş durumu | P0 | YOK | İş kuyruğu ve durum makinesi yok. Analiz ucu yalnızca `503` döner. |
| FR-09 | Ralli tahmini | P0 | DIŞ BAĞIMLILIK | İzinli video ve gold etiket gerekir. |
| FR-10 | Olası takım kazananı | P0 | DIŞ BAĞIMLILIK | FR-09'a bağlı. |
| FR-11 | Doğrulama kuyruğu | P0 | YOK | Kart sözleşmesi, karar kaydı ve ekran yok. Model olmadan sahte (MOCK etiketli) önerilerle geliştirilebilir. |
| FR-12 | İki takım raporu | P0 | KISMEN | Kotlin çekirdeğinde skor ve hücum kuralları **TEST EDİLDİ**. Rapor API'si, kapsam (coverage) sözleşmesi ve ekran yok. |
| FR-13 | Ralli bazında video oynatma | P0 | YOK | Klip sunma ucu ve oynatıcı yok. |
| FR-14 | Oyuncu eşlemesi | P1 | DIŞ BAĞIMLILIK | |
| FR-15 | Tam aksiyon çıkarımı | P1 | DIŞ BAĞIMLILIK | |
| FR-16 | Eğitim kullanım izni | P0 | KISMEN | Varsayılan kapalı ve `allowTraining: true` reddi **TEST EDİLDİ**. Ayrı onay ve geri çekme akışı yok. |
| FR-17 | Veriyi ve videoyu silme | P0 | KISMEN | Video silme ucu **TEST EDİLDİ**. Türev veriler olmadığı için ilişki haritası ve uygulama ekranı yok. |
| FR-18 | Canlı kamera analizi | P2 | Kapsam dışı | Kayıtlı video analizi kanıtlandıktan sonra. |
| FR-19 | Cihaz üzerinde model | P2 | Kapsam dışı | |
| FR-20 | Sosyal medya çıktısı | P1 | YOK | |

## 2. Android ekranları (S00 … S15)

| Ekran | Durum | Açıklama |
|---|---|---|
| S00 Karşılama | YOK | |
| S01 Hesap, izin, gizlilik | YOK | Hesap sistemi DIŞ BAĞIMLILIK (kimlik sağlayıcı seçimi). |
| S02 Ana sayfa | YOK | |
| S03 Maç kurulumu | KISMEN | Takım adları var. Tarih, set sayısı ve kadro yok. |
| S04 Kaynak seçimi | KISMEN | İki düğme var, ayrı ekran değil. |
| S05 Kamera ve çekim rehberi | YOK | Sistem kamerası kullanılıyor. |
| S06 Galeri seçimi | KISMEN | Seçim ve dosya boyutu var. Küçük resim ve süre yok. |
| S07 Kalite ve saha bölgesi | KISMEN | Sunucunun dosya kontrol sonucu gösteriliyor. Önizleme ve dört nokta saha seçimi yok. |
| S08 Yükleme | KISMEN | İlerleme çubuğu var. Durdur, devam et ve Wi-Fi bekle yok. |
| S09 Analiz durumu | YOK | |
| S10 Doğrulama kuyruğu | YOK | Ürünün kalbi. Sıradaki dilimlerin hedefi. |
| S11 Ralli kanıt oynatıcı | YOK | |
| S12 Oyuncu eşleştirme | YOK | |
| S13 Rapor | YOK | |
| S14 Paylaşım | YOK | |
| S15 Dosyalar ve gizlilik | YOK | |

Android uygulaması emülatörde ya da gerçek cihazda **çalıştırılmadı**. Derleme, lint ve birim testleri GitHub Actions üzerinde koşar. Cihaz testi DIŞ BAĞIMLILIK'tır (emülatörlü bir bilgisayar ya da telefon gerekir).

## 3. API sözleşmesi (V3 bölüm "Video-first API")

| V3 rotası | Durum | Mevcut karşılığı |
|---|---|---|
| `POST /videos` | KISMEN | `POST /matches/{id}/uploads`. `durationMs`, `captureSource`, `rightsDeclarationId` alanları yok. |
| `POST /videos/{id}/uploads` | TEST EDİLDİ | `PUT /uploads/{id}/chunks` |
| `GET /videos/{id}/uploads/{session}` | TEST EDİLDİ | `GET /uploads/{id}` |
| `POST /videos/{id}/finalize` | TEST EDİLDİ | `POST /uploads/{id}/complete` |
| `GET /videos/{id}/quality` | KISMEN | Dosya düzeyi var. Sahne ölçümleri `NOT_MEASURED`. |
| `PATCH /videos/{id}/court-roi` | YOK | |
| `POST /videos/{id}/analyses` | KISMEN | Yalnızca `503` ve `409`. İş oluşturma ve idempotency anahtarı yok. |
| `GET /analyses/{jobId}` | YOK | |
| `POST /analyses/{jobId}/cancel` | YOK | |
| `GET /analyses/{jobId}/review-cards` | YOK | |
| `GET /analyses/{jobId}/evidence/{id}` | YOK | |
| `POST /review-cards/{id}/decisions` | YOK | |
| `POST /analyses/{jobId}/publish-report` | YOK | |
| `GET /matches/{id}/reports/latest` | YOK | |
| `DELETE /videos/{id}` | KISMEN | Dosya ve kayıt siliniyor. Türev ilişki haritası yok. |
| `PATCH /videos/{id}/training-permission` | YOK | |

Yol biçimi farkı (`/v1/uploads/…` ve V3'teki `/api/v1/videos/…/uploads`) analiz işi sözleşmesi yazılırken tek sürümlü OpenAPI belgesinde kapatılacak. O belge yazılmadan Android ve arka uçta birbirine bağlı yeni uç geliştirilmeyecek.

Veri modeli: `matches`, `uploads`, `videos` tabloları var. `analysis_jobs`, `rally_predictions`, `action_predictions`, `review_decisions`, `verified_events`, `stat_snapshots`, `dataset_permissions`, `dataset_manifests`, `artifact_registry` **YOK**.

Altyapı: Kimlik doğrulama, kiracı sınırı, HTTPS, PostgreSQL, nesne depolama, iş kuyruğu ve GPU işçisi **YOK**. Kod işi yazılabilir, çalıştırılması DIŞ BAĞIMLILIK'tır (bulut hesabı ve bütçe).

## 4. Yapay zekâ bileşenleri

| Bileşen | Durum | Ne gerekiyor |
|---|---|---|
| Oyuncu tespiti | ARAŞTIRMA + DIŞ BAĞIMLILIK | Hazır eğitilmiş kişi tespit modeli değerlendirilecek. Model ve ağırlık lisansı tek tek denetlenecek. Ölçüm için izinli amatör salon videosu şart. |
| Top tespiti ve takibi | ARAŞTIRMA + DIŞ BAĞIMLILIK | Tek sabit telefonda top birkaç piksel kalır. En yüksek teknik risk (R01). Küçük nesne takibi için ince ayar verisi gerekir. |
| Saha tespiti | ARAŞTIRMA | İlk çözüm kullanıcının dört köşeyi işaretlemesidir (model gerektirmez). Otomatik tespit sonra. |
| Ralli bölümleme | ARAŞTIRMA + DIŞ BAĞIMLILIK | İlk aday model gerektirmeyen hareket ve ses tabanlı baseline'dır (deney B1). Ölçüm için 20–50 gold ralli gerekir. |
| Aksiyon tanıma | DIŞ BAĞIMLILIK | Sınıf başına yeterli etiket olmadan başlanamaz. |
| Oyuncu kimliği | ARAŞTIRMA + DIŞ BAĞIMLILIK | Forma numarası okunabilirliği tek telefonda belirsiz (SP-04). Yüz tanıma kapsam dışı. |
| Ralli kazananı | ARAŞTIRMA + DIŞ BAĞIMLILIK | Skor tabelası olmayan amatör videoda güven düzeyi bilinmiyor (SP-03). |
| İstatistik üretimi | KISMEN | Kural çekirdeği var. Doğrulanmış olaydan rapora giden hat yok. Bu parça model beklemeden yazılabilir. |
| Değerlendirme hattı | YOK | F1@1s, seçici kesinlik, kapsam, güven aralığı hesapları sentetik veriyle yazılıp test edilebilir. |
| `UNKNOWN`, `LOW_CONFIDENCE`, `NEEDS_REVIEW` | KISMEN | Çekirdek eksik veride `null` döner. Tahmin ve inceleme durum sözlüğü henüz kodda yok. |

## 5. Veri hattı

| İş | Durum | Açıklama |
|---|---|---|
| Etiket şeması (`ANNOTATION_SCHEMA_V3.json`) ve CSV şablonları | KISMEN | Devir paketinde duruyor. Depoya ve doğrulayıcı koda alınmadı. |
| Etiket dosyası doğrulayıcı | YOK | Şema, zaman aralığı, sıra ve tekil bitiş olayı kuralları. Sentetik veriyle test edilebilir. |
| Maç bazlı ayrım ve sızıntı denetimi | YOK | `match_group_id` ile ayrım, hash ile yakın kopya denetimi. |
| Video kaynak envanteri | DIŞ BAĞIMLILIK | Envanterdeki bağlantıların lisansı kaynak bazında doğrulanmadı. Hiçbir video indirilmedi. |
| Gold etiketler | DIŞ BAĞIMLILIK | İki bağımsız etiketçi ve izinli video gerekir. Hiç gold veri yok. |

## 6. Bu sürümde doğrulananlar

| Kanıt | Sonuç |
|---|---|
| Arka uç `pytest` | 16 test geçti (API 7, medya doğrulama 9). Klipler sentetik ffmpeg test desenidir. |
| Kotlin çekirdek testleri | 5 test geçti. |
| Android derleme, lint, uygulama birim testleri | GitHub Actions üzerinde. Sonuç PR'daki çalıştırmadadır. |
| Emülatör ve cihaz testi | **Yapılmadı.** |
| Model doğruluğu | **Ölçülmedi.** Model yok. |

## 7. Sıradaki dilimler

Sıra bağımlılığa göredir. 1–4 arası gerçek video beklemez.

1. **Sözleşme (MERGE-0/1/2):** Analiz işi, tahmin, inceleme kartı, karar, doğrulanmış olay ve rapor için sürümlü OpenAPI ve durum sözlüğü. Diğer bütün işler buna bağlıdır.
2. **İstatistik motoru:** Doğrulanmış olaylardan iki takım raporu. Her metrikte `eligibleN / observedN / verifiedN` ve `value: null, status: UNKNOWN`. Saf fonksiyon, sentetik olaylarla tam test.
3. **Analiz işi ve inceleme kuyruğu (arka uç):** Durum makinesi, idempotency, sürüm denetimli karar, audit. Model yerine açıkça `MOCK` etiketli öneri üreticisi.
4. **Android gezinme ve ekranlar:** S02, S03, S08, S09, S10, S11, S13. WorkManager ile kaldığı yerden yükleme.
5. **Veri araçları:** Etiket doğrulayıcı, ayrım ve sızıntı denetimi, değerlendirme metrikleri.
6. **Ralli baseline (deney B1):** İzinli video gelince. Gelmeden yalnız altyapısı yazılır.

## 8. Arda'dan beklenenler

| Konu | Neden gerekli |
|---|---|
| Kendi çektiğin 1–2 amatör salon maçı videosu ve oyuncuların çekim izni | Model seçimi ve ölçüm izinli video olmadan başlayamaz. Kendi kaydın en temiz hak durumudur. |
| Emülatörde ya da telefonda bir deneme | Uygulamanın cihazda açıldığı henüz doğrulanmadı. |
| Bulut ve GPU kararı | Ücretli hizmet onayı sende. O zamana kadar her şey yerelde çalışır. |
| Depo görünürlüğü | Depo herkese açık. Kapalı olmasını istersen GitHub ayarından değiştirirsin. |
