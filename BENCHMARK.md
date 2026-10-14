# Benchmark

## Run

```sh
./benchmark/run.py --runs 5 --large  # all three categories
./benchmark/run.py --runs 5          # small samples only
```

Requires Linux, Python 3.11+ and JDK 25. Native builds need CMake and a C++17 compiler. FFmpeg generates the larger files. Maven and jaudiotagger are downloaded automatically.

`--native-tools DIR` reuses native tools; `--skip-build` reuses Java artifacts. `--large-corpus DIR` reuses `pink-{004,040}mib.*` files.

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
| 4 MiB files (7 formats) | 10 / 31 × 5 | 10 / 31 |
| 40 MiB files (7 formats) | 10 / 31 × 5 | 10 / 31 |


## Machine

| Machine | Value |
|---|---|
| CPU | Intel(R) Core(TM) Ultra 9 386H |
| OS | Linux 7.0.0-38-generic x86_64 |
| Storage type | NVMe SSD |
| Java | 25.0.4.1; 512 MiB heap for both Java engines |
| C++ | 15.2.0; Release, `-O3 -DNDEBUG` |
| TagLib | 2.3.2 |
| jaudiotagger | 3.0.1 |

## Results

Times in **µs per operation**, ordered **C++ TagLib / TagLib Java / jaudiotagger**. **Bold** = fastest; — = unsupported.

### Small samples

| Audio format | Read metadata (µs) | Update title (µs) | Add 8 KiB comment (µs) |
|---|---:|---:|---:|
| MP3 | **8.19** / 10.59 / 17.24 | **31.14** / 74.20 / 149.24 | **39.56** / 95.40 / 425.66 |
| FLAC | **5.83** / 6.46 / 10.00 | **43.32** / 68.19 / 352.23 | **35.10** / 72.08 / 339.59 |
| Ogg Vorbis | **15.34** / 20.87 / 189.58 | **29.08** / 60.98 / 350.76 | **47.95** / 87.52 / 311.97 |
| Ogg Opus | **8.72** / 14.05 / — | **55.34** / 84.76 / — | **45.81** / 78.20 / — |
| Ogg Speex | **9.92** / 18.15 / — | **38.24** / 60.01 / — | **39.65** / 70.77 / — |
| Ogg FLAC | **11.24** / 16.41 / — | **32.01** / 51.04 / — | **44.64** / 79.02 / — |
| M4A (MP4 audio) | 42.03 / 36.83 / **24.74** | **66.93** / 119.01 / 133.93 | **75.06** / 128.38 / 105.54 |
| WMA (ASF) | 38.19 / 54.12 / **25.52** | **108.67** / 214.46 / 355.84 | **99.54** / 152.50 / 456.64 |
| WAV | **4.75** / 5.86 / 11.71 | **19.99** / 45.16 / 102.58 | **32.20** / 86.15 / 176.66 |
| AIFF | **5.19** / 8.75 / 17.56 | **12.46** / 21.05 / 61.72 | **20.10** / 46.81 / 163.47 |
| Monkey's Audio (APE) | **5.15** / 5.39 / — | **11.54** / 27.71 / — | **17.50** / 67.57 / — |
| WavPack | 4.04 / **3.82** / — | **9.78** / 14.73 / — | **17.36** / 65.17 / — |
| Musepack (MPC) | **4.43** / 4.91 / — | **9.69** / 15.43 / — | **17.21** / 65.79 / — |
| True Audio (TTA) | 9.72 / **8.73** / — | **123.14** / 202.12 / — | **55.77** / 113.90 / — |
| DSF (DSD audio) | **3.53** / 5.35 / 4.76 | **11.96** / 24.56 / 37.32 | **19.47** / 47.23 / 140.54 |
| DSDIFF (DFF) | **6.41** / 10.87 / — | **18.37** / 55.01 / — | **24.07** / 64.75 / — |
| Tracker module (MOD) | 8.51 / **4.57** / — | 42.67 / **42.43** / — | — / — / — |
| Tracker module (S3M) | 8.59 / **3.37** / — | **22.36** / 50.97 / — | — / — / — |
| Tracker module (IT) | 6.02 / **3.13** / — | **24.60** / 46.86 / — | — / — / — |
| Tracker module (XM) | 50.55 / **11.49** / — | **207.20** / 270.12 / — | — / — / — |
| Matroska audio (MKA) | 16.33 / **7.36** / — | **37.54** / 121.09 / — | **39.84** / 91.71 / — |
| Shorten (SHN) | **2.19** / 4.68 / — | — / — / — | — / — / — |

### 4 MiB

| Audio format | Read metadata (µs) | Update title (µs) | Add 8 KiB comment (µs) |
|---|---:|---:|---:|
| MP3 | **18.93** / 50.57 / 73.28 | 3251.34 / 4074.65 / **228.20** | **1281.63** / 1664.86 / 1849.25 |
| FLAC | **16.65** / 39.00 / 39.61 | **31.79** / 136.50 / 224.02 | 1841.66 / 2107.76 / **1733.13** |
| Ogg Vorbis | **38.41** / 88.44 / 8048.24 | **4588.78** / 6931.48 / 9832.18 | **1284.68** / 1616.42 / 9754.28 |
| Ogg Opus | **18.77** / 37.19 / — | **4547.93** / 6921.92 / — | **1267.56** / 1600.64 / — |
| M4A (MP4 audio) | **67.53** / 92.86 / 114.12 | **81.57** / 252.64 / 284.87 | **1319.69** / 1652.55 / 1824.13 |
| WAV | **11.90** / 29.75 / 45.25 | 4566.76 / 6973.26 / **1511.63** | 4589.02 / 7006.06 / **1480.03** |
| AIFF | **19.41** / 71.69 / 96.03 | 857.90 / **206.09** / 444.16 | 866.13 / **189.05** / 446.49 |

### 40 MiB

| Audio format | Read metadata (µs) | Update title (µs) | Add 8 KiB comment (µs) |
|---|---:|---:|---:|
| MP3 | **18.96** / 48.57 / 69.19 | 34301.26 / 38651.20 / **325.62** | 12873.72 / 14488.09 / **10666.39** |
| FLAC | **16.84** / 38.32 / 40.67 | **75.55** / 210.18 / 280.44 | 18430.51 / 20135.94 / **10879.90** |
| Ogg Vorbis | **26.31** / 58.46 / 2067.55 | 46816.33 / 68267.21 / **17425.59** | **13112.63** / 14719.06 / 17357.49 |
| Ogg Opus | **42.25** / 88.86 / — | **47522.87** / 68752.64 / — | **13031.44** / 14708.14 / — |
| M4A (MP4 audio) | **68.09** / 97.43 / 442.01 | **141.45** / 332.06 / 483.14 | 12897.84 / 14826.44 / **11231.74** |
| WAV | **12.14** / 28.76 / 42.98 | 46865.45 / 68077.33 / **10941.76** | 47037.11 / 68356.59 / **10826.68** |
| AIFF | **19.72** / 71.63 / 87.19 | 2780.63 / **285.45** / 469.83 | 2710.43 / **274.29** / 538.04 |
