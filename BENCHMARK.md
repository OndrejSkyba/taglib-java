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
| TagLib Java | 1.2.1 |
| C++ TagLib | 2.3.2 |
| jaudiotagger | 3.0.1 |

## Results

Times in **µs per operation**, ordered **C++ TagLib / TagLib Java / TagLib Java (Native Image) / jaudiotagger**. **Bold** = fastest; — = unsupported.

### Small samples

| Audio format | Read metadata (µs) | Update title (µs) | Add 8 KiB comment (µs) |
|---|---:|---:|---:|
| MP3 | **8.23** / 10.23 / 11.03 / 17.38 | **31.54** / 68.05 / 32.09 / 141.83 | **39.55** / 90.59 / 82.13 / 420.90 |
| FLAC | **5.87** / 6.44 / 6.76 / 9.91 | 43.44 / 49.81 / **32.64** / 350.90 | **35.16** / 65.80 / 65.04 / 340.16 |
| Ogg Vorbis | **15.37** / 20.28 / 23.38 / 189.41 | **29.07** / 60.27 / 39.92 / 347.29 | **48.09** / 87.18 / 87.05 / 317.53 |
| Ogg Opus | **8.77** / 13.20 / 15.13 / — | 55.38 / 32.81 / **28.11** / — | **45.88** / 74.23 / 76.33 / — |
| Ogg Speex | **9.96** / 15.10 / 16.35 / — | 38.25 / 25.11 / **21.57** / — | **39.74** / 67.25 / 70.13 / — |
| Ogg FLAC | **11.24** / 16.50 / 19.68 / — | 32.17 / 39.22 / **31.74** / — | **44.74** / 78.70 / 80.46 / — |
| M4A (MP4 audio) | 42.15 / 36.29 / 40.93 / **24.66** | 67.01 / 110.38 / **60.17** / 130.71 | **75.26** / 126.78 / 99.39 / 104.07 |
| WMA (ASF) | 38.40 / 53.59 / 60.07 / **25.47** | 108.41 / 159.68 / **100.51** / 351.17 | **100.51** / 149.44 / 125.40 / 451.30 |
| WAV | **4.73** / 5.84 / 6.25 / 11.65 | **20.56** / 42.11 / 21.86 / 97.59 | **32.62** / 84.79 / 85.10 / 173.99 |
| AIFF | **5.18** / 8.57 / 9.50 / 17.21 | **12.88** / 20.70 / 15.61 / 60.82 | **20.33** / 46.77 / 50.05 / 161.68 |
| Monkey's Audio (APE) | **5.14** / 5.38 / 5.88 / — | **11.45** / 24.56 / 14.56 / — | **17.37** / 67.84 / 74.29 / — |
| WavPack | 4.02 / **3.81** / 4.07 / — | **9.85** / 14.46 / 11.28 / — | **17.52** / 64.70 / 72.46 / — |
| Musepack (MPC) | **4.46** / 4.89 / 5.27 / — | **9.86** / 14.77 / 11.69 / — | **17.36** / 64.55 / 73.36 / — |
| True Audio (TTA) | 9.67 / **8.55** / 9.68 / — | 122.82 / 81.16 / **51.46** / — | **55.78** / 94.47 / 95.68 / — |
| DSF (DSD audio) | **3.52** / 5.32 / 5.74 / 4.80 | **12.09** / 22.72 / 15.46 / 35.07 | **19.68** / 46.55 / 46.93 / 139.50 |
| DSDIFF (DFF) | **6.40** / 10.81 / 11.65 / — | **18.44** / 48.66 / 25.45 / — | **24.28** / 62.73 / 56.40 / — |
| Tracker module (MOD) | 8.59 / **4.60** / 5.60 / — | 42.83 / 40.66 / **30.44** / — | — / — / — / — |
| Tracker module (S3M) | 8.61 / **3.39** / 4.28 / — | **22.60** / 49.97 / 41.68 / — | — / — / — / — |
| Tracker module (IT) | 6.03 / **3.10** / 3.77 / — | **24.58** / 43.22 / 25.98 / — | — / — / — / — |
| Tracker module (XM) | 50.09 / **11.49** / 14.49 / — | **205.84** / 269.90 / 278.17 / — | — / — / — / — |
| Matroska audio (MKA) | 16.37 / **7.25** / 9.76 / — | 37.29 / 106.48 / **28.55** / — | 39.90 / 88.94 / **29.02** / — |
| Shorten (SHN) | **2.21** / 4.70 / 4.61 / — | — / — / — / — | — / — / — / — |

### 4 MiB

| Audio format | Read metadata (µs) | Update title (µs) | Add 8 KiB comment (µs) |
|---|---:|---:|---:|
| MP3 | **12.60** / 24.82 / 19.93 / 38.76 | 3250.78 / 1010.77 / 1041.18 / **202.84** | 1284.82 / **1019.58** / 1084.36 / 1761.71 |
| FLAC | **10.74** / 20.62 / 17.73 / 24.55 | **32.04** / 102.66 / 43.80 / 178.62 | 1849.08 / **939.60** / 1058.76 / 1693.95 |
| Ogg Vorbis | **27.23** / 60.36 / 60.50 / 7969.99 | 4559.46 / **969.11** / 1021.91 / 9805.21 | 1298.39 / **1013.25** / 1071.04 / 9637.34 |
| Ogg Opus | **12.30** / 26.02 / 24.15 / — | 4547.93 / **930.98** / 1006.11 / — | 1281.36 / **997.79** / 1067.49 / — |
| M4A (MP4 audio) | **43.75** / 60.40 / 52.66 / 81.26 | **82.63** / 192.23 / 83.45 / 241.44 | 1328.89 / **1054.00** / 1122.30 / 1748.21 |
| WAV | **8.08** / 14.95 / 11.73 / 27.95 | 4562.46 / **996.73** / 1030.01 / 1469.23 | 4583.29 / **1024.01** / 1102.15 / 1415.92 |
| AIFF | **13.15** / 34.69 / 23.44 / 57.80 | 905.32 / 153.30 / **58.99** / 371.80 | 887.64 / 146.05 / **80.05** / 397.36 |

### 40 MiB

| Audio format | Read metadata (µs) | Update title (µs) | Add 8 KiB comment (µs) |
|---|---:|---:|---:|
| MP3 | **12.54** / 24.47 / 20.07 / 35.48 | 34163.46 / 9173.15 / 11030.07 / **295.61** | 13163.51 / **9216.69** / 10789.24 / 10958.25 |
| FLAC | **10.59** / 21.60 / 18.08 / 22.63 | 76.61 / 151.48 / **75.35** / 234.33 | 18720.54 / **9131.72** / 11101.33 / 10954.35 |
| Ogg Vorbis | **17.12** / 41.49 / 42.42 / 2041.26 | 47057.39 / **9053.14** / 10616.50 / 18559.89 | 13293.31 / **9117.08** / 11171.93 / 18569.71 |
| Ogg Opus | **30.57** / 58.88 / 57.66 / — | 47602.73 / **9000.58** / 10795.83 / — | 13270.00 / **9131.85** / 11018.91 / — |
| M4A (MP4 audio) | **44.53** / 61.34 / 52.49 / 390.79 | 143.19 / 252.76 / **117.59** / 427.66 | 13078.79 / **9210.16** / 11060.07 / 11172.19 |
| WAV | **8.02** / 14.83 / 11.74 / 25.27 | 47107.55 / **8995.95** / 10594.41 / 10868.18 | 47332.14 / **9023.23** / 10952.18 / 10741.19 |
| AIFF | **13.17** / 35.34 / 23.72 / 50.16 | 2700.35 / 213.97 / **100.90** / 412.55 | 2732.14 / 224.93 / **124.00** / 497.20 |
