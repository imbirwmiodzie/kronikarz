# Kronikarz — POC „Kto jest na nagraniu?”

Proof of concept aplikacji na Androida, która:

1. dostaje plik wideo (np. zgrane nagranie z VHS),
2. znajduje wszystkie twarze i grupuje je w osoby,
3. dla każdej osoby wybiera najlepsze ujęcie twarzy (plus 2–3 alternatywy z innych momentów),
4. pokazuje osoby po kolei, jedna na ekranie, w stylu rodzinnego albumu,
5. pod zdjęciem ma duży przycisk nagrywania głosu i pytania pomocnicze,
6. eksportuje wszystko do ZIP-a (JSON + zdjęcia + nagrania) dla dalszego pipeline'u.

Wszystko dzieje się na telefonie. Aplikacja **nie ma uprawnienia INTERNET** i prosi tylko o mikrofon.

> **Stan weryfikacji (przeczytaj najpierw).** Kod buduje się (`assembleDebug`), 43 testy jednostkowe
> przechodzi, a algorytm grupowania sprawdziłem na prawdziwych embeddingach z filmu testowego (sekcja
> [Pomiary](#pomiary)). **Aplikacji nie uruchamiałem na telefonie ani emulatorze** — środowisko, w którym
> powstała, nie ma urządzenia ani akceleracji emulatora. Czas przetwarzania na urządzeniu jest więc
> do zmierzenia (aplikacja mierzy go sama, patrz [Jak zmierzyć czas](#jak-zmierzyć-czas-na-telefonie)),
> a interfejs wymaga pierwszego przejścia ręcznego.

---

## Spis treści

- [Budowanie i instalacja](#budowanie-i-instalacja)
- [Jak używać](#jak-używać)
- [Architektura](#architektura)
- [Przetwarzanie wideo](#przetwarzanie-wideo)
- [Parametry (ekran debug)](#parametry-ekran-debug)
- [Format eksportu](#format-eksportu)
- [Prywatność i uprawnienia](#prywatność-i-uprawnienia)
- [Licencje modeli i materiałów](#licencje-modeli-i-materiałów)
- [Testy](#testy)
- [Pomiary](#pomiary)
- [Ograniczenia i następne kroki](#ograniczenia-i-następne-kroki)

---

## Budowanie i instalacja

Wymagania: JDK 17+, Android SDK z platformą 35 (Android Studio albo same command-line tools).
Gradle pobierze się sam (wrapper).

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

```bash
./gradlew assembleDebug
```

Przy pierwszym budowaniu Gradle pobiera model SFace (38 MB) z HuggingFace do
`app/src/main/assets/models/sface.onnx` i sprawdza jego SHA-256. Model nie jest trzymany w repozytorium.
Sieci potrzebuje tylko budowanie, nie aplikacja.

Instalacja na telefonie (włączone „Debugowanie USB”):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK z trzema architekturami (arm64-v8a, armeabi-v7a, x86_64) waży ok. 130 MB, głównie przez natywne
biblioteki ONNX Runtime i ML Kit oraz model SFace (38 MB). Mniejszy APK tylko dla typowego telefonu
(ok. 80 MB):

```bash
./gradlew assembleDebug -Pabis=arm64-v8a
```

Testy jednostkowe (JVM, bez telefonu):

```bash
./gradlew testDebugUnitTest
```

## Jak używać

1. **Ekran główny** → „Wybierz nagranie” (systemowy wybór wideo, bez uprawnień do pamięci) albo
   „Wybierz z plików” (np. nagranie w Pobranych, którego nie ma w galerii). Można też w galerii
   wybrać film → „Udostępnij” → **Kronikarz**.
2. **Przetwarzanie**: pasek postępu, przejrzany czas nagrania, liczba twarzy, szacowany czas do końca,
   „Anuluj”. Ekran nie gaśnie w trakcie.
3. **Przegląd osób** (jedna osoba na ekranie, najczęściej widoczne najpierw):
   - duże zdjęcie jak odbitka z białą ramką, „Osoba 3 z 12”, „Pojawia się w 14 ujęciach”,
   - miniaturki alternatywnych ujęć: stuknięcie ustawia je jako główne,
   - pole „Imię (opcjonalnie)”,
   - pytanie pomocnicze dużą czcionką i duży okrągły przycisk nagrywania (stuknij = start, stuknij = stop,
     czas nagrania i animowany poziom dźwięku),
   - lista nagranych odpowiedzi z „Odsłuchaj” i „Usuń”,
   - „Następne pytanie”, „To ta sama osoba co…” (lista innych osób, **najbardziej podobne na początku**),
     „To nie jest osoba / błąd” (ukrycie), oba z „Cofnij”,
   - na dole zawsze widoczne „Poprzednia” / „Następna osoba”.
4. **Podsumowanie** (po ostatniej osobie albo „Wszystkie osoby”): siatka osób, „Eksportuj (udostępnij)”
   przez systemowe udostępnianie, „Zapisz eksport jako plik” (wybór miejsca w telefonie).
5. **Ukryty ekran debug**: na ekranie głównym **stuknij 5 razy szybko w tytuł** „Kto jest na nagraniu?”.

Pytania pomocnicze: po każdym nagraniu pojawia się kolejne pytanie, na które nie ma jeszcze odpowiedzi.
„Następne pytanie” pomija bieżące. Gdy wszystkie są omówione lub pominięte, pojawia się pytanie
zamykające („Czy chcesz dodać coś jeszcze…”) i przycisk powrotu do pominiętych.

Pytania są w [`app/src/main/assets/questions.json`](app/src/main/assets/questions.json). Zmiana
treści albo kolejności nie wymaga zmian w kodzie.

## Architektura

```
app/src/main/java/pl/kronikarz/poc/
├── analysis/      czysty Kotlin, testowany na JVM
│   ├── ImageMetrics.kt     Laplasjan, ostrość z jednego pola, miara przeplotu, ekspozycja
│   ├── QualityScorer.kt    ocena ujęcia twarzy
│   ├── FaceAlignment.kt    szablon 5 punktów 112×112, podobieństwo metodą najmniejszych kwadratów
│   ├── FaceClusterer.kt    śledzenie + grupowanie (patrz niżej)
│   └── ShotSelector.kt     najlepsze ujęcie + alternatywy z innych momentów
├── processing/    Android: dekodowanie, ML Kit, ONNX Runtime
│   ├── FrameSource.kt          MediaMetadataRetriever (domyślnie)
│   ├── CodecFrameSource.kt     sekwencyjny MediaCodec (eksperymentalny) + automatyczny powrót do retrievera
│   ├── FaceDetectorEngine.kt   ML Kit Face Detection (model w APK)
│   ├── FaceEmbedder.kt         SFace przez ONNX Runtime
│   ├── VideoProcessor.kt       cały potok dla jednego wideo
│   └── ProcessingManager.kt    korutyna o zasięgu aplikacji, postęp jako StateFlow, anulowanie
├── data/          modele (kotlinx.serialization), repozytorium JSON, operacje na osobach (scal/ukryj/…)
├── questions/     QuestionProvider (interfejs) + StaticQuestionProvider (JSON z assets)
├── audio/         MediaRecorder (AAC/M4A), MediaPlayer
├── export/        ZIP + udostępnianie (FileProvider) / zapis przez SAF
└── ui/            Jetpack Compose: home, processing, review, summary, debug, theme
```

### Odstępstwa od proponowanego stosu (uzasadnienie)

| Propozycja | Zastosowane | Dlaczego |
|---|---|---|
| Embeddingi w TensorFlow Lite | **SFace (MobileFaceNet) w ONNX + ONNX Runtime** | Popularne wagi MobileFaceNet/ArcFace w TFLite mają niejasną albo niekomercyjną licencję (InsightFace). SFace z OpenCV Zoo jest na Apache 2.0 i jest dostępny jako ONNX. ONNX Runtime (MIT) uruchamia go bez konwersji, którą trzeba by osobno weryfikować. |
| Room albo JSON | **JSON** w prywatnym katalogu | Ten sam kształt danych co eksport, zero migracji schematu na etapie POC, łatwe podglądanie (`adb shell run-as`). |
| Coroutines / WorkManager | **Korutyna o zasięgu aplikacji** + podtrzymany ekran | WorkManager i usługa pierwszoplanowa dokładają uprawnienia (powiadomienia, FOREGROUND_SERVICE, WAKE_LOCK…), a kryterium to „tylko mikrofon”. Przetwarzanie przeżywa obrót ekranu i przejścia między ekranami. Jeśli system zabije aplikację w tle, sesja jest oznaczana jako przerwana. |
| — | Wyrównanie twarzy do 5 punktów | SFace jest trenowany na wyrównanych twarzach. Bez wyrównania embeddingi są wyraźnie gorsze. Punkty dają ML Kit (oczy, nos, kąciki ust). Preprocessing jest zgodny z referencyjnym `cv2.FaceRecognizerSF` (sprawdzone: cos = 1.0000). |

Na przyszłe pytania generowane przez AI jest już miejsce: interfejs `QuestionProvider` ma metodę
`suspend fun current(ctx: QuestionContext): QuestionStep` i dostaje pełny kontekst osoby (nagrania,
pominięte pytania, sesja). Implementacja AI to nowa klasa. Ekranu nie trzeba zmieniać.

## Przetwarzanie wideo

1. **Próbkowanie** co `sampleIntervalMs` (domyślnie 500 ms = 2 klatki/s) przez `MediaMetadataRetriever`
   (`OPTION_CLOSEST`). Klatki szersze niż 1280 px są zmniejszane. Identyczne kolejne klatki są pomijane.
2. **Detekcja**: ML Kit, tryb dokładny, punkty twarzy, klasyfikacja (oczy, uśmiech), kąty głowy.
   Odrzucane są twarze niższe niż `minFaceHeightPx` (domyślnie 40 px), ucięte przez krawędź kadru
   (widoczne < 92% prostokąta) i bez obu oczu (profil, nie da się wyrównać).
3. **Wycinek** z marginesem 40% wokół twarzy (kwadrat, maks. 400 px, JPG) do wyświetlania.
   **Embedding** liczony jest z osobno wyrównanego wycinka 112×112.
4. **Ocena jakości ujęcia** (0–1, `QualityScorer`):

   | Składnik | Waga | Jak liczony |
   |---|---|---|
   | ostrość | 0.25 | wariancja Laplasjanu na **jednym polu** (co druga linia), na wycinku znormalizowanym do 128 px; s/(s+120) |
   | wielkość twarzy | 0.20 | wysokość / 160 px |
   | twarz na wprost | 0.20 | 1 − √(yaw² + (1.2·pitch)² + 0.3·roll²) / 45° |
   | otwarte oczy | 0.15 | 0.6·min(L, P) + 0.4·średnia (mrugnięcie jednym okiem też karane) |
   | jasność i kontrast | 0.15 | średnia blisko 128, odchylenie ≥ 45 |
   | uśmiech | 0.05 | lekki bonus |
   | **przeplot** | kara do −50% | miara „grzebieni” (niżej) |

5. **Grupowanie w osoby** (`FaceClusterer`), trzy etapy:
   1. *Śledzenie*: twarze z sąsiednich próbek w tym samym miejscu kadru (IoU > 0.3) i o podobieństwie
      > 0.35 są łączone w „ścieżkę” (dopasowanie 1:1). Zmiana pozy czy światła w obrębie jednego ujęcia
      kamery nie rozbija wtedy osoby na kilka kart.
   2. *Przypisanie zachłanne* ścieżek do grup z wysokim progiem (próg + 0.12).
   3. *Scalanie aglomeracyjne ze średnim powiązaniem*, dopóki średnie podobieństwo par twarzy
      > `similarityThreshold` (domyślnie 0.40).

   Twarze z **tej samej klatki** nigdy nie trafiają do jednej osoby (dwie osoby w kadrze to dwie osoby).
   Grupy z mniej niż `minOccurrences` (3) twarzami są odrzucane jako szum. Widać je w debug.
6. **Najlepsze ujęcie** = najwyższa ocena. **Alternatywy** (do 3) to kolejne najlepsze ujęcia odległe
   o ≥ 3 s od już wybranych, czyli z innych momentów. Zapisywane są też liczba wystąpień, wszystkie znaczniki
   czasu i średni embedding osoby (do podpowiedzi scalania).

### Specyfika VHS

- **Przeplot.** „Grzebienie” zawyżają zwykły Laplasjan: wyglądają jak ostre krawędzie. Dlatego ostrość
  liczona jest na jednym polu, a osobna miara porównuje drugą pochodną między sąsiednimi liniami (różne
  pola) z tą między liniami tego samego pola. Wcześniej obraz jest wygładzany poziomo, żeby szum VHS nie
  udawał przeplotu. Na wycinkach z testów czysty obraz daje ~0.4, zaszumiony VHS bez ruchu ~0.6–0.8,
  lekkie grzebienie ~1.0, wyraźne > 1.5. Kara rośnie liniowo od 0.75 do 1.75.
- **Docelowo wejście będzie już odprzeplotowane** (z osobnego pipeline'u przygotowania materiału).
  Wtedy kara za przeplot po prostu nie będzie się włączać. Nie trzeba niczego wyłączać.
- **Długi GOP.** Zgrania z kart przechwytujących mają często klatkę kluczową co kilka sekund.
  `MediaMetadataRetriever` dla każdej próbki dekoduje wtedy od poprzedniej klatki kluczowej, co jest wolne.
  W debug można włączyć **dekoder sekwencyjny MediaCodec**, który dekoduje film raz, po kolei. Jest
  eksperymentalny: przy błędzie aplikacja sama wraca do retrievera.

## Parametry (ekran debug)

| Parametr | Domyślnie | Zakres | Wpływ |
|---|---|---|---|
| Próbkowanie co | 500 ms | 100–2000 ms | Mniej = więcej twarzy i dokładniej, ale wolniej (czas ~liniowy) |
| Min. wysokość twarzy | 40 px | 16–160 px | Niżej = więcej małych twarzy z tła (więcej szumu, gorsze embeddingi) |
| Próg podobieństwa | 0.40 | 0.15–0.80 | Niżej = mniej duplikatów, ale ryzyko połączenia różnych osób. Wyżej = odwrotnie |
| Min. liczba wystąpień | 3 | 1–10 | Grupy poniżej progu są odrzucane jako szum |
| Maks. szerokość klatki | 1280 px | 480–1920 | Dotyczy nagrań HD z telefonu. VHS (720 px) bez zmian |
| Dokładne klatki | wł. | | Wył. = tylko klatki kluczowe (szybko, ale mniej klatek i nierówno) |
| Dekoder sekwencyjny MediaCodec | wył. | | Patrz wyżej (długi GOP) |
| Zachowaj dane do strojenia | wł. | | Zapisuje embeddingi i wszystkie wycinki (`faces.json`). Potrzebne do podglądu grup i przeliczania. Szacunkowo kilkanaście–kilkadziesiąt MB na 10 min nagrania (zależy od liczby twarzy) |

Na ekranie debug jest też:
- **statystyka sesji**: urządzenie, czas całkowity, **sekundy na minutę wideo**, rozbicie na dekodowanie,
  detekcję, embedding i grupowanie, liczba klatek i twarzy, powody odrzucenia,
- **podgląd wszystkich grup** (aktywne, ukryte, scalone, odrzucone jako szum) z wycinkami posortowanymi
  wg oceny, średnim podobieństwem wewnątrz grupy i do najbliższej innej grupy,
- **„Przelicz grupy z bieżącym progiem”**: ponowne grupowanie zapisanych twarzy bez ponownego
  dekodowania wideo, liczone w sekundę. Zablokowane, gdy sesja ma już nagrania, bo nagrania straciłyby osoby.

Domyślny próg 0.40 wynika z pomiarów niżej. Na filmie testowym najlepiej wypadło 0.36, ale w rodzinnych
nagraniach krewni są do siebie bardziej podobni niż obcy aktorzy, a POC umie osoby scalać, nie umie ich
rozdzielać. Dlatego zostawiłem zapas po stronie „lepiej duplikat niż pomyłka”. Na własnych nagraniach
warto przeliczyć grupy z progiem 0.36 i 0.44 i porównać.

## Format eksportu

„Eksportuj” tworzy `kronikarz_RRRRMMDD_GGMM.zip`:

```
session.json                 manifest (opis niżej)
faces/p01_best.jpg           najlepsze ujęcie osoby nr 1
faces/p01_alt1.jpg …         alternatywne ujęcia
audio/p01_who_1.m4a          nagranie: osoba 1, pytanie „who”, pierwsze nagranie do tego pytania
audio/p01_more_2.m4a …       (AAC w MPEG-4, mono, 44.1 kHz, 96 kb/s)
```

`session.json` (`formatVersion: 1`, czasy w UTC ISO-8601, znaczniki czasu w nagraniu w ms):

```json
{
  "format": "kronikarz-session",
  "formatVersion": 1,
  "exportedAt": "2026-11-01T12:00:00Z",
  "app": "Kronikarz POC 0.1-poc",
  "session": {
    "id": "…uuid…",
    "createdAt": "2026-11-01T11:40:00Z",
    "video": { "uri": "content://…", "displayName": "wesele_1994.mp4", "durationMs": 598000, "width": 720, "height": 576 },
    "settings": { "sampleIntervalMs": 500, "minFaceHeightPx": 40, "similarityThreshold": 0.4, "minOccurrences": 3, "…": "…" },
    "stats": { "wallClockMs": 0, "videoDurationMs": 598000, "framesSampled": 1196, "facesAccepted": 0, "device": "…", "…": "…" }
  },
  "questions": [ { "id": "who", "text": "Kim jest ta osoba? Jak się nazywa?" }, "…" ],
  "persons": [
    {
      "id": "…uuid…",
      "number": 1,
      "name": "Babcia Zosia",
      "status": "active",
      "mergedInto": null,
      "occurrenceCount": 14,
      "timestampsMs": [12000, 12500, 13000, 95500],
      "bestShot": { "file": "faces/p01_best.jpg", "timestampMs": 12500, "quality": 0.81 },
      "alternateShots": [ { "file": "faces/p01_alt1.jpg", "timestampMs": 95500, "quality": 0.74 } ],
      "bestShotChosenByUser": false,
      "recordingIds": ["…uuid…"]
    }
  ],
  "recordings": [
    {
      "id": "…uuid…",
      "personId": "…uuid…",
      "questionId": "who",
      "questionText": "Kim jest ta osoba? Jak się nazywa?",
      "file": "audio/p01_who_1.m4a",
      "durationMs": 8400,
      "createdAt": "2026-11-01T11:52:10Z"
    }
  ]
}
```

Uwagi dla dalszego pipeline'u:
- `status`: `active` | `hidden` (użytkownik: „to nie jest osoba / błąd”) | `merged` (wtedy `mergedInto`
  wskazuje osobę docelową, a jej nagrania i wystąpienia są już przeniesione). Ukryte i scalone osoby nie
  mają plików zdjęć (`bestShot: null`).
- `questionText` zapisuje treść pytania w chwili nagrania, bo pytania w konfiguracji mogą się zmienić.
- `timestampsMs` + `session.video` pozwalają wrócić do oryginalnego nagrania (np. kompilacje z osobą).
- `stats` zawiera zmierzone czasy przetwarzania, więc eksport z testów od razu niesie pomiar wydajności.

## Prywatność i uprawnienia

- **Jedyne uprawnienie: `RECORD_AUDIO`**, o które aplikacja pyta przy pierwszym nagraniu.
  Wideo wybierane jest systemowym pickerem (Photo Picker / SAF), bez uprawnień do pamięci.
- **Brak `INTERNET`.** Biblioteki ML Kit dokładają do manifestu `INTERNET` i `ACCESS_NETWORK_STATE`
  (telemetria datatransport). Są jawnie usunięte (`tools:node="remove"`). Sprawdzone na zbudowanym APK:
  ```bash
  $ANDROID_HOME/build-tools/35.0.0/aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk
  ```
  Wynik: `RECORD_AUDIO` oraz wewnętrzne `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (z podpisem
  aplikacji, dodawane automatycznie przez AndroidX; nie jest uprawnieniem użytkownika i nic nie pokazuje).
- Model detekcji jest w APK (`com.google.mlkit:face-detection`, wariant „bundled”), model embeddingów też.
  Nic nie jest pobierane w czasie działania.
- Dane leżą w prywatnym katalogu aplikacji, a kopie zapasowe w chmurze i transfer między urządzeniami są
  wyłączone (`allowBackup=false`, `data_extraction_rules.xml`). Nagranie opuszcza telefon tylko przez
  świadomy eksport.

## Licencje modeli i materiałów

| Element | Licencja | Użycie komercyjne | Uwagi |
|---|---|---|---|
| **SFace** `face_recognition_sface_2021dec.onnx` (OpenCV Zoo, autor: Yaoyao Zhong; MobileFaceNet + strata SFace) | **Apache 2.0** | tak | Pobierany przy budowaniu z [huggingface.co/opencv/face_recognition_sface](https://huggingface.co/opencv/face_recognition_sface), SHA-256 w `app/build.gradle.kts`. Uwaga: licencja dotyczy wag i kodu. Przed wdrożeniem produkcyjnym warto potwierdzić z prawnikiem kwestię zbioru treningowego (typowo CASIA-WebFace/MS1M), bo to szara strefa całej branży. |
| **ML Kit Face Detection** (bundled) | [Warunki ML Kit / Google APIs](https://developers.google.com/ml-kit/terms) | tak | Bez opłat. Model w APK, działa offline. |
| **ONNX Runtime** Android | MIT | tak | |
| Ikony mikrofonu/stopu (ścieżki Material Icons) | Apache 2.0 | tak | |
| *Tylko narzędzia testowe:* **YuNet** (OpenCV Zoo) | MIT | tak | Detektor w `tools/extract_faces.py` (zastępuje ML Kit na komputerze). Nie trafia do aplikacji. |
| *Tylko testy:* **„Tears of Steel”** | **CC BY 3.0**, © Blender Foundation, [mango.blender.org](https://mango.blender.org) | tak, z atrybucją | Film testowy. W repo są tylko pochodne embeddingi (`app/src/test/resources/fixtures/`), bez klatek. |

Nie użyłem wag InsightFace ani innych modeli „tylko do badań”, więc nie ma w POC nic, co trzeba będzie
wymienić z powodów licencyjnych.

## Testy

### Jednostkowe (JVM) — 43 testy, `./gradlew testDebugUnitTest`

| Plik | Co sprawdza |
|---|---|
| `ImageMetricsTest` | Laplasjan spada przy rozmyciu; wykrywanie przeplotu; ostrość z jednego pola nie jest zawyżana przez grzebienie; ekspozycja; skalowanie |
| `QualityScorerTest` | ostra > rozmyta; na wprost > profil / głowa w dół; otwarte oczy > mrugnięcie (też jednym okiem); duża > mała; poprawna > nie-/prześwietlona; uśmiech to tylko mały bonus; kara za przeplot |
| `FaceAlignmentTest` | odtworzenie znanego przekształcenia; kolejność lewe/prawe oko bez znaczenia; brak punktów |
| `FaceClustererSyntheticTest` | 5 osób + szum; zakaz łączenia twarzy z tej samej klatki; wpływ progu |
| `FaceClustererRealEmbeddingsTest` | **zapisane prawdziwe embeddingi** (877 + 693 twarzy, 9 osób): każda osoba ma kartę, **zero kart łączących różne osoby**, limit duplikatów, trafność podpowiedzi scalania, przewaga śledzenia, szybkość |
| `ShotSelectorTest` | najlepsze ujęcie + alternatywy odległe w czasie |
| `PersonOpsTest` | scalanie (wystąpienia, ujęcia, nagrania, imię, łańcuchy scaleń), ukrywanie, wybór ujęcia, budowanie osób |
| `ExportBuilderTest` | nazwy plików w ZIP, numeracja nagrań, osoby ukryte/scalone bez zdjęć, kształt `session.json` (formatVersion 1) |
| `StaticQuestionProviderTest` | kolejność pytań z prawdziwego `questions.json`, przejście po nagraniu, pomijanie, pytanie zamykające |

### Test ręczny na telefonie (do wykonania)

Materiał: „Tears of Steel” (CC BY 3.0), fragment 1:00–5:00, w którym pojawia się 5 osób z twarzą
widoczną wielokrotnie, plus kilka epizodycznych.

```bash
curl -LO https://download.blender.org/demo/movies/ToS/tears_of_steel_720p.mov
```

```bash
ffmpeg -ss 60 -t 240 -i tears_of_steel_720p.mov -vf scale=720:-2 -c:v libx264 -crf 22 -c:a aac tos_test.mp4
```

```bash
adb push tos_test.mp4 /sdcard/Download/
```

W aplikacji: „Wybierz z plików” → Pobrane → `tos_test.mp4`. Lista kontrolna:
- [ ] widać postęp, „Anuluj” przerywa i nic nie zostaje,
- [ ] każda z 5 głównych osób ma kartę. Duplikaty da się scalić, a właściwa osoba jest pierwsza na liście
      „To ta sama osoba co…”,
- [ ] najlepsze ujęcie to zwykle ostra twarz na wprost z otwartymi oczami,
- [ ] nagrywanie (pierwsze pyta o mikrofon), odsłuch, usuwanie. Po nagraniu pojawia się następne pytanie,
- [ ] „Eksportuj” daje ZIP z `session.json`, `faces/`, `audio/`,
- [ ] tryb samolotowy niczego nie zmienia.

### Narzędzia do strojenia na komputerze (`tools/`)

Desktopowy odpowiednik potoku (YuNet zamiast ML Kit, **ten sam** SFace i to samo wyrównanie), żeby
stroić grupowanie na własnych nagraniach bez telefonu:

```bash
python3 -m venv tools/.venv && tools/.venv/bin/pip install onnxruntime opencv-python-headless numpy
```

```bash
tools/.venv/bin/python tools/extract_faces.py --video moje.mp4 --sface app/src/main/assets/models/sface.onnx --yunet face_detection_yunet_2023mar.onnx --out tools/out/moje
```

```bash
./gradlew testDebugUnitTest --tests '*ClusteringEvalTool*' -PevalFaces=$PWD/tools/out/moje/faces.json -PevalOut=$PWD/tools/out/moje/clusters.json -PevalThreshold=0.4
```

```bash
tools/.venv/bin/python tools/contact_sheets.py --faces tools/out/moje/faces.json --clusters tools/out/moje/clusters.json --crops tools/out/moje/crops --out tools/out/moje/sheets
```

Opcja `--vhs` w `extract_faces.py` symuluje zgranie z VHS (720 px, ~330 linii luminancji, rozmyty kolor,
szum, drugie pole z poprzedniej klatki = przeplot przy ruchu). Grupowanie liczy ten sam kod Kotlina co
aplikacja (`ClusteringEvalTool`). Z `-PevalLabels=labels.json` (mapa id twarzy → osoba) wypisuje metryki
dla zakresu progów.

## Pomiary

### Jakość grupowania

Materiał: „Tears of Steel” (12 min), cały film, próbkowanie co 500 ms, min. 40 px. Twarze ręcznie
przypisane do 9 osób, które pojawiają się wielokrotnie. Przypisanie zrobiłem na arkuszach kontaktowych
grup przy ostrym progu 0.50, a wątpliwe grupy porównałem w powiększeniu i po podobieństwie embeddingów.
Pojedyncze twarze, których nie dało się przypisać, mają etykietę „nieznana” i nie wchodzą do metryk.
Detektor: YuNet (desktop). Embeddingi i grupowanie: dokładnie te z aplikacji.

„Duplikaty” to dodatkowe karty tej samej osoby, do scalenia przyciskiem. „Mieszane” to karty z twarzami
co najmniej dwóch osób; tego POC nie umie naprawić, więc to ważniejsza metryka.

| Wariant | Twarzy | Próg | Karty | Osoby znalezione | **Duplikaty** | **Karty mieszane** | Podpowiedź scalania trafna |
|---|---|---|---|---|---|---|---|
| 720p, bez śledzenia | 877 | 0.40 | 28 | 9/9 | 16 | 0 | — |
| **720p (domyślnie)** | 877 | **0.40** | **22** | **9/9** | **12** | **0** | **16/16** |
| 720p | 877 | 0.36 | 19 | 9/9 | 9 | 0 | |
| 720p | 877 | 0.30 | 18 | 9/9 | 5 | 0 | |
| symulacja VHS, bez śledzenia | 693 | 0.40 | 32 | 9/9 | 21 | 2 | — |
| **symulacja VHS (domyślnie)** | 693 | **0.40** | **28** | **9/9** | **18** | **0** | **22/25** |
| symulacja VHS | 693 | 0.36 | 24 | 9/9 | 14 | 0 | |
| symulacja VHS | 693 | 0.33 | 21 | 9/9 | 10 | 2 | |
| symulacja VHS | 693 | 0.25 | 17 | 9/9 | 8 | 4 | |

Wnioski:
- Przy domyślnych ustawieniach **żadna karta nie łączy różnych osób**, a każda osoba ma kartę.
- Duplikatów jest sporo, bo film to trudny materiał: charakteryzacja, implanty i hełmy, mocne kolorowe
  światło, profile, robot zasłaniający pół twarzy. Duplikaty to przeważnie małe fragmenty (3–20 twarzy)
  obok jednej dużej karty osoby. Lista „To ta sama osoba co…” jest sortowana po podobieństwie i właściwa
  osoba jest na niej **pierwsza w 38 z 41 przypadków**, więc scalenie to zwykle jedno stuknięcie.
- Śledzenie między próbkami zmniejsza liczbę duplikatów o ~15–25% i usuwa karty mieszane na VHS.
- Domowe nagrania (stałe światło, bez charakteryzacji) powinny dać mniej duplikatów. Za to krewni są
  bardziej podobni niż obcy aktorzy. To trzeba sprawdzić na prawdziwych kasetach.
- Grupowanie jest szybkie: 3 508 twarzy w 0.26 s na JVM (laptop/serwer). Na telefonie spodziewam się
  rzędu sekund.

Ograniczenie pomiaru: detektor na komputerze to YuNet, nie ML Kit. Inny detektor daje trochę inne
prostokąty i punkty twarzy, a więc inny zestaw twarzy, ale ten sam model embeddingów i to samo
wyrównanie. Wnioski o progach powinny się przenosić. Liczby z telefonu mogą się różnić.

### Czas przetwarzania

| Urządzenie | Nagranie | Dekoder | s / minutę wideo | Rozbicie (dekod. / detekcja / embedding) |
|---|---|---|---|---|
| *do zmierzenia* | Tears of Steel 1:00–5:00, 720×300 | MediaMetadataRetriever | | |
| *do zmierzenia* | j.w. | MediaCodec (sekwencyjny) | | |
| *do zmierzenia* | własne zgranie VHS 720×576 | oba | | |

#### Jak zmierzyć czas na telefonie

Każde przetworzenie zapisuje statystykę w sesji. Odczyt: ekran debug → wybierz sesję → „Czas: … →
X s na minutę wideo” (z rozbiciem). Te same dane trafiają do eksportu (`session.stats`), w tym model
telefonu. Proszę o wpisanie wyników do tabeli wyżej.

Wstępne oczekiwania (szacunek, nie pomiar): ML Kit w trybie dokładnym kosztuje dziesiątki ms na klatkę,
SFace na CPU kilka do kilkunastu ms na twarz, a `MediaMetadataRetriever` z `OPTION_CLOSEST` od kilkudziesięciu
ms do sekund na próbkę, zależnie od odstępu klatek kluczowych. Właśnie dlatego jest opcja dekodera
sekwencyjnego.

## Ograniczenia i następne kroki

- **Brak rozdzielania osoby** (gdyby grupowanie połączyło dwie osoby). Domyślny próg jest ustawiony
  zachowawczo. Następny krok: „To nie ta osoba” na miniaturze w podglądzie wszystkich ujęć.
- **Twarze z profilu** (bez obu oczu) są pomijane, bo nie da się ich wyrównać do szablonu.
- **Przetwarzanie w tle**: gdy użytkownik wyjdzie z aplikacji na dłużej, system może ją zatrzymać. Wtedy
  sesja jest oznaczana jako przerwana i trzeba zacząć od nowa. W wersji docelowej: usługa pierwszoplanowa
  albo backend.
- **Długie nagrania** (całe kasety): poza zakresem. Czas rośnie liniowo, a grupowanie skaluje się do
  dziesiątek tysięcy twarzy. Wąskim gardłem będzie czas dekodowania i detekcji na telefonie.
- **Dekoder MediaCodec** jest eksperymentalny (konwersja YUV w Kotlinie, nietestowana na urządzeniu).
  Ma automatyczny powrót do retrievera przy błędzie, ale nie wykryje np. złych kolorów. Do sprawdzenia
  wzrokowo w podglądzie grup.
- **Interfejs**: po polsku, duże czcionki (15–34 sp, treść 18–20 sp), przyciski min. 60–64 dp,
  kontrast: tekst główny 13.9:1, pomocniczy ≥ 7.7:1, biały na przyciskach ≥ 7.2:1, akcenty na tle ≥ 6.2:1.
  Nie testowałem z czytnikiem ekranu (TalkBack). Główne elementy mają opisy.
- Poza zakresem POC (kolejne etapy): transkrypcja, pytania generowane przez AI (`QuestionProvider` jest
  gotowy), synchronizacja, konta i zapraszanie rodziny, opisy scen, kompilacje wideo.
