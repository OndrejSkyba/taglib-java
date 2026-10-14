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
| MP3 | **9.00** / 11.57 / 11.99 / 19.05 | **34.24** / 75.66 / 36.06 / 153.66 | **43.25** / 104.43 / 101.29 / 447.20 |
| FLAC | **6.42** / 7.25 / 7.35 / 10.95 | 47.52 / 52.82 / **35.27** / 363.38 | **38.54** / 72.47 / 73.13 / 351.55 |
| Ogg Vorbis | **16.93** / 23.03 / 26.37 / 208.79 | **31.85** / 63.71 / 44.11 / 375.99 | **52.65** / 97.70 / 97.36 / 337.65 |
| Ogg Opus | **9.62** / 14.95 / 16.91 / — | 60.48 / 36.40 / **31.53** / — | **50.41** / 82.11 / 85.99 / — |
| Ogg Speex | **10.97** / 16.85 / 17.98 / — | 41.93 / 27.56 / **23.92** / — | **43.54** / 74.83 / 78.84 / — |
| Ogg FLAC | **12.38** / 18.67 / 21.55 / — | **35.23** / 42.32 / 35.32 / — | **49.00** / 87.72 / 90.80 / — |
| M4A (MP4 audio) | 46.17 / 40.73 / 44.38 / **27.20** | 73.63 / 122.81 / **66.81** / 139.65 | **82.45** / 140.93 / 110.17 / 112.28 |
| WMA (ASF) | 41.81 / 60.36 / 65.95 / **27.89** | 118.98 / 174.69 / **112.24** / 379.95 | **109.12** / 164.13 / 133.98 / 490.68 |
| WAV | **5.21** / 6.57 / 6.84 / 12.93 | **22.12** / 45.14 / 23.40 / 102.94 | **35.39** / 96.67 / 97.07 / 191.24 |
| AIFF | **5.70** / 9.77 / 10.24 / 19.35 | **13.61** / 22.15 / 17.05 / 65.37 | **22.10** / 52.72 / 55.67 / 179.21 |
| Monkey's Audio (APE) | **5.65** / 6.00 / 6.40 / — | **12.65** / 27.30 / 15.85 / — | **19.09** / 77.22 / 82.62 / — |
| WavPack | 4.43 / **4.21** / 4.48 / — | **10.92** / 15.85 / 12.44 / — | **18.99** / 74.73 / 81.39 / — |
| Musepack (MPC) | **4.90** / 5.49 / 5.76 / — | **11.21** / 16.46 / 12.95 / — | **19.03** / 73.98 / 82.48 / — |
| True Audio (TTA) | 10.64 / **9.65** / 10.82 / — | 134.40 / 88.79 / **57.47** / — | **61.06** / 108.79 / 112.15 / — |
| DSF (DSD audio) | **3.89** / 5.96 / 6.27 / 5.31 | **13.51** / 26.18 / 16.66 / 39.02 | **21.16** / 53.25 / 51.95 / 152.73 |
| DSDIFF (DFF) | **7.04** / 12.07 / 12.63 / — | **20.14** / 55.05 / 27.73 / — | **26.22** / 72.08 / 63.95 / — |
| Tracker module (MOD) | 9.37 / **5.20** / 6.18 / — | 46.95 / 44.81 / **33.23** / — | — / — / — / — |
| Tracker module (S3M) | 9.45 / **3.81** / 4.71 / — | **24.51** / 53.96 / 46.12 / — | — / — / — / — |
| Tracker module (IT) | 6.63 / **3.50** / 4.12 / — | **26.75** / 48.12 / 28.74 / — | — / — / — / — |
| Tracker module (XM) | 55.17 / **12.72** / 15.97 / — | **226.91** / 295.30 / 307.91 / — | — / — / — / — |
| Matroska audio (MKA) | 17.91 / **7.97** / 10.25 / — | 41.20 / 115.20 / **30.93** / — | 43.65 / 96.35 / **32.85** / — |
| Shorten (SHN) | **2.44** / 5.24 / 5.04 / — | — / — / — / — | — / — / — / — |

### 4 MiB

| Audio format | Read metadata (µs) | Update title (µs) | Add 8 KiB comment (µs) |
|---|---:|---:|---:|
| MP3 | **13.73** / 29.05 / 21.75 / 41.38 | 3568.75 / 1105.64 / 1095.84 / **217.37** | 1387.94 / **1109.91** / 1142.14 / 1864.99 |
| FLAC | **11.80** / 22.16 / 19.68 / 26.72 | **33.19** / 105.53 / 43.49 / 188.06 | 2039.81 / **1010.15** / 1108.51 / 1781.49 |
| Ogg Vorbis | **29.70** / 66.71 / 68.67 / 8749.66 | 5020.93 / **1043.27** / 1055.96 / 10707.12 | 1399.44 / **1078.01** / 1126.28 / 10444.74 |
| Ogg Opus | **13.44** / 28.43 / 27.05 / — | 4988.65 / **1010.33** / 1040.16 / — | 1371.58 / **1075.55** / 1117.65 / — |
| M4A (MP4 audio) | **47.88** / 66.95 / 57.11 / 93.67 | **87.31** / 204.51 / 88.93 / 246.28 | 1448.57 / **1136.65** / 1160.77 / 1848.92 |
| WAV | **8.69** / 16.36 / 12.80 / 30.10 | 5031.63 / 1077.91 / **1059.31** / 1560.09 | 5024.47 / **1102.54** / 1127.30 / 1543.96 |
| AIFF | **14.35** / 37.96 / 25.69 / 61.71 | 907.62 / 162.34 / **60.17** / 383.59 | 873.51 / 144.35 / **86.14** / 429.44 |

### 40 MiB

| Audio format | Read metadata (µs) | Update title (µs) | Add 8 KiB comment (µs) |
|---|---:|---:|---:|
| MP3 | **13.68** / 29.89 / 22.11 / 39.24 | 38080.63 / 9568.53 / 11422.01 / **317.80** | 14251.75 / **9697.12** / 11266.98 / 11851.43 |
| FLAC | **11.58** / 25.16 / 19.92 / 25.47 | 85.90 / 151.43 / **77.92** / 241.23 | 20570.07 / **9572.40** / 11460.54 / 11894.63 |
| Ogg Vorbis | **18.69** / 48.36 / 49.24 / 2250.50 | 52298.60 / **9380.61** / 10820.44 / 18514.70 | 14300.14 / **9490.77** / 11273.00 / 18523.06 |
| Ogg Opus | **33.52** / 66.30 / 65.87 / — | 52792.23 / **9548.58** / 11096.66 / — | 14326.89 / **9544.05** / 11238.42 / — |
| M4A (MP4 audio) | **48.85** / 69.11 / 57.37 / 451.62 | 151.36 / 260.25 / **119.04** / 442.54 | 13985.83 / **9550.39** / 11323.67 / 12100.74 |
| WAV | **8.79** / 17.27 / 13.01 / 27.72 | 52041.50 / **9236.78** / 10596.25 / 11783.89 | 52292.91 / **9295.72** / 11585.52 / 11745.07 |
| AIFF | **14.34** / 40.85 / 25.97 / 56.38 | 2751.27 / 211.47 / **97.29** / 443.86 | 2772.62 / 219.59 / **124.64** / 540.24 |
