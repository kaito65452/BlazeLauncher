# Panduan Uji Pagi di Perangkat Fisik (`SM-N975F` — Samsung Galaxy Note 10+)

> **Tujuan**: Memverifikasi instalasi & peluncuran `26.3 Rust Edition` pada APK rilis terbaru hasil CI branch `feat/stage-l` (Stage L-A: engine tertanam tanpa menu Horizon + pengaturan milik launcher, serta bundled `assets/engine-data.zip`).
>
> **Catatan keamanan**: Jangan bagikan token mentah atau file log lengkap sebelum memeriksa bahwa token hanya tampil sebagai `token_len=...` dan UUID terpotong 8 karakter (`xxxxxxxx...`).

---

## Checklist Utama Stage L-A (Tanpa Menu Horizon / Kembali Bersih ke Blaze Launcher)

Pastikan game masih bisa dimainkan memakai kontrol sentuh bawaan engine (kontrol sentuh tetap aktif pada tahap Stage L-A ini), **tetapi tidak ada lagi** tombol `"H"`, `"..."`, `"Horizon Control Hub"`, watermark `"Horizon 26.3 (Rust)"`, ataupun menu peluncur `"Horizon Android"`:

1. **Layar Judul (`Title Screen`) -> Tombol Kembali (`Back` Android) & Tombol `Quit Game`**:
   - Luncurkan `26.3-Rust` dari Blaze Launcher -> masuk ke layar judul Minecraft (pastikan teks *"Not affiliated with Mojang or Microsoft"* tetap ada di pojok kanan bawah, dan **tidak ada** teks *"Horizon 26.3 (Rust)"*, tombol `"H"`, atau `"..."`).
   - Tekan **Back** sistem Android di layar judul utama -> **Hasil yang diharapkan**: `RustActivity` menutup bersih, proses `:rust` berakhir, dan **Blaze Launcher langsung kembali ke depan** tanpa pernah menampilkan menu `"Horizon Android"`.
   - Luncurkan ulang `26.3-Rust`, lalu ketuk tombol **Quit Game** -> **Hasil yang diharapkan**: kembali ke **Blaze Launcher** tanpa menu `"Horizon Android"`.
2. **Dalam Game (`Pause Menu`) -> Tombol `Back` Android & `Save and Quit to Title`**:
   - Masuk ke sebuah world -> tekan **Back** sistem Android di dalam game -> **Hasil yang diharapkan**: membuka/menutup **Pause Menu Minecraft** (bukan langsung keluar ke launcher).
   - Dari Pause Menu, pilih **Save and Quit to Title** -> kembali ke layar judul Minecraft, lalu tekan **Back** atau **Quit Game** -> **Hasil yang diharapkan**: world tersimpan aman dan kembali bersih ke **Blaze Launcher**.
3. **Pindah Aplikasi dan Kembali (`App Switch & Return`)**:
   - Saat berada di layar judul atau di dalam world, tekan tombol **Home / Recents**, buka aplikasi lain sebentar, lalu kembali ke Blaze Launcher / game -> **Hasil yang diharapkan**: kembali ke sesi game yang sedang berjalan tanpa muncul menu `"Horizon Android"`. Jika Android sempat mematikan proses `:rust` di latar belakang lalu Anda membuka ulang tanpa sesi tertunda, aplikasi **kembali ke Blaze Launcher** dengan bersih.
4. **Putus Koneksi Paksa (`Forced Disconnect` / Error)**:
   - Matikan Wi-Fi/data saat bergabung ke server multiplayer (atau paksa putus koneksi) -> **Hasil yang diharapkan**: layar Disconnect Minecraft tampil atau jika terjadi error fatal, kembali bersih ke **Blaze Launcher** (tidak pernah jatuh ke menu `"Horizon Android"`).
5. **Peluncuran Segar (`Fresh Launch`) & Pengaturan Milik Launcher**:
   - Buka **Pengaturan -> Launcher -> Pengembang** (ketuk versi 5x di tab *Tentang* jika belum aktif), lalu ubah:
     - **Skala render Rust Edition** (mis. `75%`)
     - **Sensitivitas lihat sentuh Rust Edition** (mis. `150%`)
     - **Tampilkan kontrol engine Rust** (`OFF` lalu uji lagi `ON`)
   - Luncurkan `26.3-Rust` segar -> **Hasil yang diharapkan**: skala render, sensitivitas kamera sentuh, dan visibilitas kontrol mengikuti pengaturan dari Blaze Launcher (`blaze_engine_settings.json`).
6. **Yang Perlu Dikirim Balik**:
   - Hasil lulus/gagal ke-5 skenario di atas (apakah pernah melihat menu `"Horizon Android"` / `"H"` / `"..."` / `"Control Hub"`?).
   - Buka **Pengembang -> `Salin laporan`** (`Copy report`) dan tempel bagian:
     - `=== Engine Login Policy ===`
     - `=== Engine UI & Embedded Policy ===` (wajib ada `engine ui: launcher window disabled | embedded feature on`, `last_exit_reason:`, `settings_source:`)
     - `=== Render Startup & Exit Summary ===`

---

## Ringkasan Cepat Task K (Jalur Cepat Tanpa `javac` di Perangkat)

1. APK yang dibuat dengan `BUNDLE_ENGINE_DATA=true` menyertakan **`assets/engine-data.zip`** (berisi tabel hasil generate bawaan upstream Starling — **tidak ada** kompilasi `javac` 25 ataupun Minecraft `datagen` di HP!).
2. **Cara menyalakan / mematikan fitur ini di CI** (untuk build berikutnya):
   - Aktif: `gh variable set BUNDLE_ENGINE_DATA --body "true"`
   - Mati (kembali ke jalur lambat/eksperimental `javac` on-device): `gh variable set BUNDLE_ENGINE_DATA --body "false"`
3. **Alur instalasi di HP**:
   - **Unduh (`Download`) -> Game -> pilih `26.3` -> pilih runtime `Rust Edition (Eksperimental)` -> tekan `Instal` (`Install`)**.
   - **Perkiraan durasi (BELUM TERUJI di perangkat / `UNVERIFIED`)**: targetnya **puluhan detik hingga beberapa menit** (bergantung kecepatan Wi-Fi untuk objek aset yang belum ada di cache ZL2), tanpa fase `javac` `:jvm`. Jika file aset ZL2 `26.3` sudah ada di `game_home`, `server.jar` dan 114 library Java **tidak diunduh**.
4. **Apa yang perlu Anda kirim balik jika ada masalah atau setelah berhasil**:
   - Perkiraan waktu selesai instalasi, apakah muncul error/macet, apakah game `26.3` terbuka normal lewat tombol `Luncurkan`, dan hasil **Pengembang -> `Salin laporan`** (`Copy report`) setelah menguji.

---

## 1. Unduh & Pasang APK Rilis Terbaru (Uji Update In-Place)

1. Buka GitHub Actions repositori `kaito65452/BlazeLauncher`, pilih workflow **`CI`** paling atas yang berstatus **hijau (`success`)** pada branch **`feat/overnight-stages`** (run hijau terbaru setelah Task X/W/P — misalnya run [`43729106715`](https://github.com/kaito65452/BlazeLauncher/actions/runs/43729106715) `versionCode=27530606` atau run lebih baru di atasnya).
2. Unduh artifact bernama **`signed-apks`**, lalu ekstrak file APK **`BlazeLauncher-*.apk`**.
   > **Catatan Perbaikan Terbaru (Task X / W / P)**:
   > - Masalah sebelumnya di APK `27516547` (`Untrusted download host: libraries.minecraft.net`) sudah diperbaiki di Task X (`blaze-prepare/src/download.rs`) bersama refresh hash manifest/asset index 26.3 (`0007-pin-refresh-26.3.patch`). Error yang masih muncul sekarang menampilkan host pelanggar, URL tanpa token/query, daftar host yang diizinkan, dan langkah lanjut.
   > - Jangan menjalankan `cargo`, `gradle`, atau build berat secara lokal di HP; seluruh kompilasi & unit test berjalan di CI (`Parallel Fast Checks` & `CI`).
   > - Preflight real-data 26.3 di CI (`preflight-real.yml`) belum bisa di-dispatch di GitHub Actions sampai branch ini di-merge ke `main` (aturan GitHub untuk workflow `workflow_dispatch`), jadi uji instalasi di device tetap menjadi verifikasi akhir di Android `:jvm` `Internal-25`.
3. Pasang APK langsung menimpa aplikasi Blaze Launcher yang sudah terpasang di **SM-N975F** (**jangan** uninstall terlebih dahulu):
   - **Lulus jika**: Android memasang sebagai pembaruan (update in-place) tanpa error konflik signature atau downgrade `versionCode`, dan seluruh akun/pengaturan lama tetap ada.

---

## 2. Siapkan Akun Microsoft & Buka Mode Pengembang (Opsional untuk Diagnostik)

1. Buka Blaze Launcher -> pastikan **akun Microsoft** yang memiliki Minecraft: Java Edition aktif dan login normal lewat alur Microsoft launcher biasa.
2. Untuk mengaktifkan menu diagnostik **Pengembang**:
   - Buka **Pengaturan** -> tab **Tentang** -> ketuk kartu info versi **Blaze Launcher** sebanyak **5 kali**.
   - Setelah ketukan ke-2 muncul toast sisa ketukan; di ketukan ke-5 muncul **"Developer mode enabled"**.
   - Buka **Pengaturan -> Launcher** -> pastikan di paling atas muncul bagian **`Pengembang`** (toggle `Mode pengembang`, `Laporan Kapabilitas Perangkat`, `Jalankan Spike Rust Edition`, `Siapkan data engine (spike)`, dan `Salin laporan`).

---

## 3. Uji Instalasi Normal `26.3` dengan `Rust Edition` (Stage D + Stage B)

1. Di panel navigasi kiri, buka **Unduh** (`Download`) -> kategori **Game**.
2. Pilih versi **Minecraft `26.3`** (Rilis).
3. Pada layar konfigurasi instalasi (mod loader / addons):
   - Periksa apakah bagian **`Runtime`** muncul dengan pilihan **Java Edition** dan **Rust Edition (Eksperimental)**.
   - Ketuk **Rust Edition (Eksperimental)**.
   - **Periksa**: semua opsi mod loader Java harus otomatis hilang/nonaktif disertai catatan *"Mod loader Java dinonaktifkan saat Rust Edition dipilih"*, dan **Nama Versi** default menjadi **`26.3-Rust`** (pastikan juga untuk versi lain selain `26.3`, misalnya `1.21.4`, kartu `Runtime` tidak menawarkan Rust Edition).
4. Ketuk tombol **Instal** (`Install`) dan tunggu proses selesai:
   - Tahap mengunduh file vanilla `26.3` dari Mojang + tahap **`Menyiapkan data engine Rust Edition 26.3`** (`libblaze_prepare.so` + OpenJDK 25 `:jvm`).
   - Catat perkiraan lama waktu persiapan (terutama kompilasi + eksekusi exporter data Mojang di `:jvm`) dan apakah ada lag berlebih atau error.
   - *(Opsional uji batal)*: jika ingin menguji cancel Stage B, tekan **Batal** lewat Pengembang -> `Siapkan data engine (spike)` saat berjalan dan pastikan tidak macet lalu bisa diulang lagi sampai muncul **`Data engine 26.3 siap`**.
5. Setelah instalasi selesai, buka daftar **Versi** -> pastikan entri `26.3-Rust` menampilkan badge/label kecil **`Rust Edition`**.

### Perkiraan Durasi, Ukuran Unduhan & Ruang Penyimpanan `26.3` (`UNVERIFIED`)

Angka berikut adalah **estimasi dari ukuran manifest resmi Mojang `26.3`** (`version_manifest_v2.json` / `assetIndex id=34`) dan **belum diukur langsung di `SM-N975F` (`UNVERIFIED`)**:

| Komponen (`26.3`) | Jumlah / Rincian Manifest | Ukuran Unduhan Mentah |
|---|---|---:|
| `client.jar` | 1 berkas | `41,483,720` B (`39.56 MiB`) |
| `server.jar` | 1 berkas | `62,294,556` B (`59.41 MiB`) |
| `libraries` | 114 entri manifest | `116,648,633` B (`111.24 MiB`) |
| `assetIndex` (`id=34`) | 1 berkas JSON | `597,235` B (`0.57 MiB`) |
| Objek non-audio aset | 188 objek | `110,694,508` B (`105.57 MiB`) |
| Objek audio `.ogg` | 4,961 objek | `376,664,246` B (`359.22 MiB`) |
| **Total unduhan bersih vanilla** *(tanpa kontrol sentuh kecil)* | 5,266 berkas | **`708,382,898` B (`~675.6 MiB`)** |

- **Catatan reuse**: jika aset/library/`client.jar` ZL2 versi `26.3` sudah ada di `game_home`, Phase 1 memeriksa SHA-1 lalu memakai ulang berkas yang cocok (sehingga unduhan efektif turun ke ~`60–115 MiB` untuk `server.jar` + objek yang belum tercache, bergantung kondisi penyimpanan lokal — `UNVERIFIED`).
- **Perkiraan penggunaan disk (`UNVERIFIED`)**:
  - **Puncak sementara saat persiapan**: sekitar **`~1.4–2.0 GiB`** ruang kosong disarankan (unduhan mentah ~`676 MiB` + ekstraksi JAR/aset + unpack `server.jar` sementara di `.local/prepare-work/26.3/` + `.local/horizon-client/26.3/` + `engine-data.zip`).
  - **Setelah selesai**: sekitar **`~0.9–1.3 GiB`** untuk data `.local/horizon-client/26.3/` + `engine-data.zip` + cache `.local/prepare-work/26.3/` (`UNVERIFIED`).
- **Perkiraan durasi di `SM-N975F` (`UNVERIFIED`)**: sekitar **`~2–8 menit` total** pada Wi-Fi stabil (`~1–4 menit` unduhan/verifikasi paralel, `~15–45 detik` ekstrak & zip Rust `libblaze_prepare.so`, plus `~1–3 menit` kompilasi & eksekusi exporter + Mojang datagen di `:jvm` JRE 25 — `UNVERIFIED` pada perangkat nyata).

---

## 4. Uji Penolakan Akun Lokal & Peluncuran Normal `Play` (Stage D + Stage A)

1. **Uji blokir akun non-Microsoft (WAJIB LULUS)**:
   - Di menu Akun, buat atau pilih sementara akun **Lokal / Offline**.
   - Pilih versi **`26.3-Rust`**, lalu tekan **`Luncurkan`** (`Launch`).
   - **Lulus jika**: Launcher **MENOLAK** dan menampilkan error *"Rust Edition memerlukan akun Microsoft yang terverifikasi."*, serta **TIDAK** membuka `RustActivity` (`:rust`) maupun JVM.
2. **Uji mode offline tanpa internet (jika cache sesi perlu verifikasi)**:
   - Kembalikan akun ke akun **Microsoft**, matikan Wi-Fi/data bila dialog gagal refresh muncul, lalu coba luncurkan `26.3-Rust`.
   - **Lulus jika**: tombol **Skip / Lewati** offline pada dialog refresh akun tidak muncul untuk versi `Rust Edition`.
3. **Uji peluncuran sukses dengan akun Microsoft**:
   - Nyalakan internet, pilih akun **Microsoft** yang valid dan versi **`26.3-Rust`**, lalu tekan **`Luncurkan`**.
   - **Lulus jika**: `:rust` (`RustActivity`) terbuka dan **langsung** masuk ke loading/game Minecraft `26.3` tanpa berhenti di menu launcher native `Horizon Android` (menu internal `Horizon Android` hanya muncul saat membuka *Developer -> Jalankan Spike Rust Edition*), tidak menjalankan JVM Java Minecraft, tidak menampilkan pesan *"This APK is missing its engine data. Install a complete build."*, dan memakai akun Microsoft yang diserahkan dari Blaze Launcher tanpa OAuth mandiri di dalam engine.

---

## 5. Uji Perbaikan Layar, Keyboard, Gesture & Document Picker di `:rust` (Stage C)

Saat game/menu Rust Edition terbuka di **SM-N975F**:
1. **Cutout & Area Aman (`C2`)**: Periksa tepi layar dekat kamera punch-hole serta bar status/navigasi — teks/tombol utama UI tidak tertutup lubang kamera.
2. **Edge Back Gesture (`C2`)**: Geser dari tepi kiri/kanan di area gameplay tengah untuk menguji apakah gesture eksklusi bekerja dan tidak langsung menutup `:rust` secara tidak sengaja.
3. **Keyboard Virtual (`C1`)**: Ketuk kolom teks/chat -> pastikan keyboard Samsung muncul dan viewport layar menyesuaikan (`adjustResize`) sehingga kolom input tetap terlihat.
4. **Document Picker Android SAF (`C1`)**: Jika ada menu pilih file / impor (world/resource pack lewat picker sistem Android), pilih satu file `content://` dan pastikan aplikasi kembali normal ke `:rust` tanpa hang/crash.

---

## 6. Ambil & Periksa Laporan Diagnostik (`Salin laporan`)

1. Setelah menutup `:rust` (atau jika terjadi crash/ANR), buka kembali Blaze Launcher -> **Pengaturan -> Launcher -> Pengembang -> `Salin laporan`** (`Copy report`).
2. Tempel laporan ke aplikasi catatan/editor teks, lalu periksa 4 hal penting:
   - **`=== Engine Login Policy ===`**: wajib tertulis `engine login: disabled`.
   - **`=== Engine Data Check ===`**: wajib tertulis `prepared=true`, path di `<filesDir>/engine-data`, rincian fase terakhir, dan angka `peak_rss_kb`.
   - **`=== Render Startup & Exit Summary ===`**: periksa apakah `pipelines_created_ms=...`, `surface_to_first_frame_ms=...`, `backend=...`, `adapter=...` (Vulkan / Mali), serta `exit reason` tercatat dengan rapi. Jika Anda menguji Document Picker, periksa apakah baris `document_result req=4101 res=... jniDelivered=true` muncul.
   - **Keamanan Redaksi**: pastikan **tidak ada** token panjang/JWT mentah dan UUID hanya tampil sebagai 8 karakter pertama diikuti `...` serta `token_len=<angka>`.

---

## 7. Ringkasan Catatan yang Perlu Dikirim Kembali (Jika Ada Temuan)

Catat hasil singkat 5 poin berikut untuk sesi berikutnya:
1. Apakah update APK menimpa versi lama berhasil tanpa uninstall? (Ya/Tidak)
2. Berapa menit proses **`26.3-Rust`** (Instal -> Siap)? Ada error di fase mana?
3. Apakah akun Lokal/Offline ditolak saat menekan `Luncurkan`? (Ya/Tidak)
4. Bagaimana tampilan/performa di `SM-N975F` (apakah masuk menu utama / world, FPS/keluar tiba-tiba, keyboard, cutout, gesture)?
5. Tempel isi bagian `Engine Login Policy`, `Engine Data Check`, dan `Render Startup & Exit Summary` dari **`Salin laporan`**.
