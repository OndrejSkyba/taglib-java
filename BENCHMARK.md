# Benchmark

## Run

```sh
./benchmark/run.py --runs 5 --large --graalvm-home /path/to/graalvm
./benchmark/run.py --runs 5 --large  # all three categories
./benchmark/run.py --runs 5          # small samples only
```

Requires Linux, Python 3.11+ and JDK 25. Native builds need CMake and a C++17 compiler. FFmpeg generates the larger files. Maven and jaudiotagger are downloaded automatically.

`--graalvm-home DIR` adds TagLib Java as a Native Image (`-O3`, compatibility CPU target, Serial GC). `--native-tools DIR` reuses native tools; `--skip-build` reuses Java artifacts. `--large-corpus DIR` reuses `pink-{004,040}mib.*` files.

Results: `benchmark/results/result.json`.

## Methodology

| Workload | Test |
|---|---|
| Read metadata | Read text tags, artwork and audio properties without decoding audio. |
| Update title | Replace the title, preserving other metadata. |
| Add 8 KiB comment | Replace the title and add an 8 KiB comment. |

- **Runs:** 5 fresh processes per engine for reads and writes; engines run serially, with rotating engine and case order.
- **Timing:** warm-cache latency including opening, reading or editing/saving, and closing. Each write uses a fresh copy; setup, copying and startup are excluded. No extra fsync.
- **Summary:** median of process medians; JSON also includes means and individual samples.
- **Checks:** failed reads/saves stop the run; unsupported workloads are excluded. Read results are consumed to prevent optimization.

| Corpus | Read warmup batches / measured batches × operations | Write warmups / measured operations |
|---|---:|---:|
| Small samples (22 formats) | 20 / 31 × 200 | 100 / 101 |
| 4 MiB files (7 formats) | 100 / 31 × 5 | 30 / 31 |
| 40 MiB files (7 formats) | 100 / 31 × 5 | 30 / 31 |


## Machine

| Machine | Value |
|---|---|
| CPU | Intel(R) Core(TM) Ultra 9 386H |
| OS | Linux 7.0.0-38-generic x86_64 |
| Storage type | NVMe SSD |
| Java (JVM) | 25.0.4.1; 512 MiB heap |
| GraalVM Native Image | CE 25.4.4.1.1; JDK 25.0.4.1.1; 512 MiB heap; `-O3`, Serial GC |
| C++ | 15.2.0; Release, `-O3 -DNDEBUG` |
| TagLib | 2.3.2 |
| jaudiotagger | 3.0.1 |

## Results

Times in **µs per operation**, ordered **C++ TagLib / TagLib Java / TagLib Java (Native Image) / jaudiotagger**. **Bold** = fastest; — = unsupported.

### Small samples

| Audio format | Read metadata (µs) | Update title (µs) | Add 8 KiB comment (µs) |
|---|---:|---:|---:|
| MP3 | **9.00** / 11.34 / 12.90 / 18.90 | **34.34** / 76.12 / 36.91 / 151.16 | **43.41** / 103.31 / 98.63 / 445.74 |
| FLAC | **6.42** / 7.25 / 7.44 / 10.90 | **47.70** / 72.66 / 57.19 / 364.97 | **38.48** / 78.36 / 82.11 / 354.58 |
| Ogg Vorbis | **16.91** / 23.43 / 27.59 / 208.15 | **31.96** / 64.88 / 44.42 / 381.69 | **52.73** / 95.19 / 97.45 / 347.69 |
| Ogg Opus | **9.61** / 15.72 / 18.57 / — | **60.34** / 92.98 / 89.00 / — | **50.32** / 85.29 / 92.67 / — |
| Ogg Speex | **10.98** / 20.10 / 24.88 / — | **41.89** / 64.80 / 62.27 / — | **43.56** / 76.67 / 82.78 / — |
| Ogg FLAC | **12.35** / 18.72 / 21.87 / — | **35.22** / 55.11 / 48.76 / — | **49.11** / 85.82 / 90.82 / — |
| M4A (MP4 audio) | 46.17 / 40.81 / 44.77 / **27.11** | 73.40 / 123.52 / **66.93** / 142.67 | **82.33** / 138.94 / 109.19 / 114.26 |
| WMA (ASF) | 41.78 / 60.21 / 66.37 / **27.85** | **119.00** / 223.52 / 161.91 / 378.74 | **109.76** / 163.69 / 136.46 / 497.00 |
| WAV | **5.19** / 6.60 / 6.91 / 12.82 | **21.98** / 47.56 / 23.52 / 104.48 | **35.26** / 93.55 / 96.50 / 190.96 |
| AIFF | **5.73** / 9.77 / 10.51 / 19.24 | **13.51** / 22.40 / 16.98 / 65.97 | **22.21** / 50.80 / 55.59 / 179.79 |
| Monkey's Audio (APE) | **5.65** / 6.01 / 6.45 / — | **12.65** / 28.59 / 15.89 / — | **19.05** / 74.02 / 82.62 / — |
| WavPack | 4.43 / **4.26** / 4.48 / — | **11.01** / 15.80 / 12.41 / — | **18.98** / 70.96 / 81.08 / — |
| Musepack (MPC) | **4.90** / 5.48 / 5.78 / — | **11.22** / 16.54 / 12.87 / — | **18.96** / 71.27 / 83.05 / — |
| True Audio (TTA) | 10.64 / **9.74** / 11.15 / — | **134.30** / 216.25 / 188.50 / — | **60.97** / 121.58 / 125.87 / — |
| DSF (DSD audio) | **3.89** / 5.94 / 6.32 / 5.16 | **13.53** / 26.06 / 16.96 / 39.44 | **21.17** / 51.84 / 52.99 / 154.53 |
| DSDIFF (DFF) | **7.04** / 12.14 / 12.89 / — | **20.12** / 55.80 / 28.20 / — | **26.27** / 69.67 / 64.32 / — |
| Tracker module (MOD) | 9.36 / **5.18** / 6.21 / — | 46.80 / 45.59 / **33.14** / — | — / — / — / — |
| Tracker module (S3M) | 9.45 / **3.77** / 4.78 / — | **24.62** / 54.80 / 45.72 / — | — / — / — / — |
| Tracker module (IT) | 6.63 / **3.47** / 4.16 / — | **26.89** / 48.91 / 28.55 / — | — / — / — / — |
| Tracker module (XM) | 55.10 / **12.83** / 16.18 / — | **226.93** / 295.55 / 300.12 / — | — / — / — / — |
| Matroska audio (MKA) | 17.95 / **8.19** / 10.27 / — | 41.38 / 116.71 / **31.48** / — | 43.88 / 96.83 / **32.83** / — |
| Shorten (SHN) | **2.43** / 5.21 / 5.04 / — | — / — / — / — | — / — / — / — |

### 4 MiB

| Audio format | Read metadata (µs) | Update title (µs) | Add 8 KiB comment (µs) |
|---|---:|---:|---:|
| MP3 | **13.84** / 30.01 / 24.44 / 41.29 | 3558.29 / 4378.16 / 4616.37 / **218.84** | **1397.35** / 1707.36 / 1851.08 / 1867.48 |
| FLAC | **11.86** / 23.41 / 19.95 / 27.06 | **32.65** / 99.22 / 46.40 / 185.14 | 2021.19 / 2244.59 / 2483.65 / **1775.55** |
| Ogg Vorbis | **29.84** / 78.91 / 104.88 / 8804.96 | **5019.08** / 7585.48 / 7879.73 / 10708.15 | **1412.36** / 1659.11 / 1830.38 / 10551.99 |
| Ogg Opus | **13.46** / 30.50 / 33.18 / — | **5011.49** / 7509.14 / 7882.16 / — | **1391.42** / 1646.97 / 1816.09 / — |
| M4A (MP4 audio) | **47.99** / 66.99 / 57.23 / 95.76 | **87.64** / 203.24 / 91.67 / 248.69 | **1454.93** / 1740.43 / 1878.62 / 1833.63 |
| WAV | **8.86** / 16.32 / 12.78 / 30.43 | 5016.52 / 7575.01 / 7852.48 / **1556.06** | 5035.19 / 7590.65 / 7927.02 / **1540.03** |
| AIFF | **14.45** / 37.77 / 26.26 / 61.55 | 921.05 / 162.11 / **66.44** / 381.91 | 886.93 / 147.99 / **86.98** / 433.92 |

### 40 MiB

| Audio format | Read metadata (µs) | Update title (µs) | Add 8 KiB comment (µs) |
|---|---:|---:|---:|
| MP3 | **13.75** / 30.15 / 25.03 / 38.95 | 41216.55 / 42211.92 / 46667.69 / **303.01** | 14067.92 / 15403.10 / 18350.70 / **11596.86** |
| FLAC | **11.59** / 25.17 / 20.21 / 24.91 | 90.23 / 155.67 / **73.36** / 238.98 | 20488.41 / 21492.14 / 25284.86 / **11578.87** |
| Ogg Vorbis | **18.64** / 51.58 / 58.37 / 2218.12 | 51946.84 / 75321.29 / 79445.15 / **18569.45** | **14378.71** / 15407.02 / 18211.35 / 18487.38 |
| Ogg Opus | **33.46** / 85.48 / 120.88 / — | **52639.21** / 75608.34 / 79175.18 / — | **14174.58** / 15367.82 / 18199.88 / — |
| M4A (MP4 audio) | **48.75** / 70.20 / 58.44 / 442.79 | 158.75 / 270.71 / **122.16** / 447.72 | 14266.94 / 15478.92 / 18407.38 / **12079.56** |
| WAV | **8.75** / 17.08 / 13.14 / 28.12 | 51935.40 / 74536.30 / 79171.62 / **11570.80** | 51957.02 / 74636.15 / 78557.21 / **11360.63** |
| AIFF | **14.47** / 41.36 / 26.80 / 56.03 | 2819.64 / 236.62 / **107.25** / 443.43 | 2715.20 / 235.69 / **127.19** / 530.34 |
