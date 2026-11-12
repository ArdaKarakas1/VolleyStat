# VolleyStat mimari özeti

## Ürün kararı

- Marka: **Voleybol Ve Veleybol**, ürün: **VolleyStat**.
- Android-first native Kotlin/Jetpack Compose; web sitesi koduyla ortaklık yok.
- Amatör indoor 6x6, iki takım, video-first, mümkün olduğunca az insan girişi.
- Tamamlanan dilim: kaydet ya da seç → hak beyanı → yükleme → SHA-256 → dosya doğrulaması. Analiz yok.
- Sonraki dilimler: analiz işi sözleşmesi → ralli önerisi → kısa kliple inceleme → doğrulanmış rapor.

## Kod ağacı

`android/app`: UI + Android sistem kamerası/video seçicisi + debug local upload client.

`android/core`: Android bağımsız takım/skor/statistik tipleri ve testler.

`backend/app/main.py`: FastAPI + SQLite, yalnızca yerel video alımı, model bulunmadığını açıkça bildiren API.

`backend/app/media.py`: ffprobe ile dosya doğrulaması. Yalnız kapsayıcıya ve video akışına bakar, görüntünün içeriğini ölçmez.

`backend/tests`: API ve medya doğrulama testleri. Klipler ffmpeg'in ürettiği sentetik test desenleridir.

## Kaynak verisi / model çıktısı ayrımı

- `PREDICTION`: bir modelin olasılıklı sonucu, doğrudan istatistiğe yazılmaz.
- `VERIFIED_EVENT`: insan tarafından onaylanmış veya ileride güvenlik/kalite politikası gereği otomatik doğrulanmış olay.
- `UNKNOWN`: video ile güvenilir şekilde belirlenemeyen durum; sıfır değildir.
- `ATTACK_ATTEMPTS`: bütün girişler kapsandığında hesaplanır; eksik kapsama `null`.
- `RALLY_WINNER`: oyuncu Kill'inden ayrı bir olaydır, bir ralli bir puan kazandırır.

## Sürümleme güvenlik kapıları

- AI doğruluğu henüz ölçülmedi; öğrenilmiş model yok.
- Video hakları onayı, eğitim izninden ayrıdır. Eğitim izni varsayılan kapalıdır.
- API yalnızca yerel geliştirme; kullanıcı auth yok, TLS yok. Üretim hizmeti olamaz.
- Yüklenen dosya ffprobe ile doğrulanır. Geçemeyen dosya `QUARANTINED` olur ve analiz edilemez. Sahne kalitesi (saha, top, sabitlik) ölçülmez.
- Worker, queue, object storage, GPU maliyet kontrolü ve retention sonradan eklenecek.
- AI veri toplama için maç bazında train/validation/test ayrımı şarttır; aynı maç karesi farklı ayrımlara girmez.
