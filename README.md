# VolleyStat

**Voleybol Ve Veleybol** · Amatör salon voleybolu için Android öncelikli 6 × 6 maç istatistiği.

İlk yerel pilot, kamera veya dosyadan alınan videoyu yükler ve otomatik olarak inceleme kuyruğuna alır. Arka uç videonun süresini ve görüntü bilgilerini okur. Bu sürümde model bağlı olmadığı için uygulama **Elle doğrulama** modunu gösterir. Maç olayları, video konumu ve iki takımdan biriyle kaydedilir. Oyuncu istatistiği için forma numarası eklenebilir.

## Yerel kurulum

1. Python 3.9+, FFmpeg/FFprobe, Android Studio ve Android SDK 36 kurun. Arka uç ek Python paketi gerektirmez.
2. Depo kökünde arka ucu başlatın.

   ```sh
   python3 -m backend.server
   ```

3. Android Studio ile `android/` klasörünü açın. Gradle JDK olarak JDK 17 veya Gradle 8.13'ün desteklediği bir JDK seçin. `app` modülünün **debug** sürümünü çalıştırın.
4. Uygulamada **Yeni maç** ile takım adlarını girin. Android emülatörü için bağlantı seçimini değiştirmeyin. USB ile bağlı telefonda önce `adb reverse tcp:8080 tcp:8080` çalıştırıp **USB ile bağlı telefon** seçeneğini kullanın.
5. **Kamerayla kaydet** veya **Dosyalardan video seç** ile bir klip ekleyin. Ardından **Yükle ve incelemeyi başlat** seçeneğini kullanın. Pilot için H.264 MP4 tercih edin. Yükleme sınırı 4 GiB ve inceleme sınırı 12 saattir.
6. Videoyu oynatın, bir konuma gidin ve **Bu video anına olay ekle** ile etiketleyin. Bir olayın zaman etiketine dokununca video o noktadan oynar. **Olayı incele** ile kaydı düzeltin veya çıkarın. Skor için her rallide bir **Ralli sayısı** kaydı girin. **İki takımın istatistikleri** ekranı girilen olayları özetler.

Uygulama son maçı hatırlar. **Maç kimliği ve diğer maçlar** menüsündeki kimliği saklayarak önceki bir maçı yeniden açabilirsiniz. Yerel servis SQLite ve videoları `data/` altında saklar. Her veri dizini için tek arka uç süreci çalıştırın.

### Test ve derleme

```sh
python3 -m unittest discover -s backend/tests -v
cd android
./gradlew :app:assembleDebug :app:lintDebug
```

Arka uç testleri geçici bir veri dizini ve yerel HTTP sunucusu kullanır. Kendi test klibinizi üretmek için:

```sh
mkdir -p data/imports
ffmpeg -f lavfi -i testsrc2=size=640x360:rate=25 -t 5 \
  -c:v libx264 -pix_fmt yuv420p -movflags +faststart data/imports/ornek.mp4
```

### İzinli YouTube videosu

YouTube aktarımı ayrı bir yerel hazırlık aracıdır. İndirme yetkisi olan bir video için kaynak ve izin kaydıyla çalıştırın. Araç H.264 MP4, SHA-256 ve FFprobe bilgilerini `data/imports/` altında saklar. Ardından MP4'ü Android uygulamasındaki dosya seçiciyle içe aktarabilirsiniz.

```sh
brew install yt-dlp ffmpeg deno
python3 tools/import_youtube.py 'https://www.youtube.com/watch?v=VIDEO_ID' \
  --rights 'Lisans bağlantısı veya yazılı izin referansı'
```

[API, istatistik kuralları ve model entegrasyonu](docs/pilot.md)
