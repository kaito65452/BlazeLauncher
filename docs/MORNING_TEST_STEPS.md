# Panduan Uji Pagi di Perangkat Fisik (`SM-N975F` — Samsung Galaxy Note 10+)

> **Tujuan**: Memverifikasi semua butir `UNVERIFIED` dari Stage A sampai Stage D pada APK rilis terbaru hasil CI branch `feat/overnight-stages`.
>
> **Catatan keamanan**: Jangan bagikan token mentah atau file log lengkap sebelum memeriksa bahwa token hanya tampil sebagai `token_len=...` dan UUID terpotong 8 karakter (`xxxxxxxx...`).

---

## 1. Unduh & Pasang APK Rilis Terbaru (Uji Update In-Place)

1. Buka GitHub Actions repositori `kaito65452/BlazeLauncher`, pilih workflow **`CI`** paling atas yang berstatus **hijau (`success`)** pada branch **`feat/overnight-stages`** (untuk kode Stage D terbaru lihat run `43673263364` atau run hijau terbaru setelah dokumentasi).
2. Unduh artifact bernama **`signed-apks`**, lalu ekstrak file APK **`BlazeLauncher-*.apk`**.
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
   - **Lulus jika**: `:rust` (`RustActivity`) langsung terbuka, tidak menjalankan JVM Java Minecraft, tidak menampilkan pesan *"This APK is missing its engine data. Install a complete build."*, dan menampilkan nama akun Microsoft dari launcher tanpa menu login/OAuth mandiri yang berfungsi di dalam engine.

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
