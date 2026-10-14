# Benchmark

## Run

```sh
./benchmark/run.py --runs 5
./benchmark/run.py --runs 5 --large
```

Requires Linux, Python 3.11+ and JDK 25. Local native builds need CMake and a C++17 compiler; optional zlib headers enable compressed ID3 support. `--native-tools DIR` uses prebuilt native tools. Maven is provisioned automatically.
FFmpeg is needed only to generate the optional larger fixtures. `--large --large-corpus DIR` reuses an existing corpus. `--skip-build` reuses Java artifacts.

Results: `benchmark/results/result.json`. The tables below are maintained manually.

## Methodology

| Workload | Test |
|---|---|
| Read | Read text tags, artwork and audio properties without decoding audio. |
| Title | Replace the track title with a short title, preserving other metadata. |
| Growth | Replace the title and add an 8 KiB comment to exercise larger metadata saves. |

- **Runs:** 5 fresh process runs per engine for reads and writes. Engines execute serially, alternating which runs first; case order rotates.
- **Timing:** warm-cache latency from opening to closing, including reading or editing/saving. Each write starts from a fresh copy. Preparation, copying and startup are excluded; no extra fsync.
- **Summary:** median of process medians; JSON also includes the mean of process means and individual samples.
- **Operation checks:** failed opens or saves stop the run. Read results are consumed to prevent optimization; correctness is covered by separate tests. Known unsupported writes are excluded.

| Corpus | Read warmup batches / measured batches × operations | Write warmups / measured operations |
|---|---:|---:|
| 22 format fixtures | 20 / 31 × 200 | 100 / 101 |
| 1 / 10 / 50 MiB synthetic files | 10 / 31 × 5 | 10 / 31 |

Counts apply to each process run.

## Machine

| Machine | Value |
|---|---|
| CPU | Intel(R) Core(TM) Ultra 9 386H |
| OS | Linux 7.0.0-38-generic x86_64 |
| Storage type | NVMe SSD |
| Java | 25.0.4.1; 512 MiB heap |
| C++ | 15.2.0; Release, `-O3 -DNDEBUG` |
| TagLib | 2.3.2 |

## Results

Times are **microseconds (µs) per operation**, shown **C++ / Java (×Java/C++)**. Lower times are better. A multiplier of ×1.56 means Java takes 1.56 times the C++ time.

Measured on **13 October 2026**, including bounded tracker construction reads.

### Format fixtures

| Format / case | File bytes | Read (µs) | Title (µs) | Growth (µs) |
|---|---:|---:|---:|---:|
| MPEG | 4096 | 8.96 / 11.38 (×1.27) | 34.41 / 74.82 (×2.17) | 43.32 / 103.85 (×2.40) |
| FLAC | 64567 | 6.41 / 7.13 (×1.11) | 47.42 / 71.94 (×1.52) | 38.45 / 77.88 (×2.03) |
| OGG_VORBIS | 4408 | 16.85 / 22.91 (×1.36) | 32.01 / 63.95 (×2.00) | 52.74 / 94.87 (×1.80) |
| OGG_OPUS | 35506 | 9.62 / 15.37 (×1.60) | 60.71 / 92.80 (×1.53) | 50.37 / 86.01 (×1.71) |
| OGG_SPEEX | 24301 | 10.94 / 19.82 (×1.81) | 41.86 / 66.06 (×1.58) | 43.55 / 76.41 (×1.75) |
| OGG_FLAC | 9113 | 12.37 / 18.61 (×1.50) | 35.19 / 54.77 (×1.56) | 49.06 / 85.71 (×1.75) |
| MP4 | 5108 | 46.01 / 40.58 (×0.88) | 73.89 / 119.20 (×1.61) | 82.66 / 136.27 (×1.65) |
| ASF | 35416 | 42.38 / 59.35 (×1.40) | 118.78 / 221.92 (×1.87) | 108.66 / 167.97 (×1.55) |
| WAV | 14744 | 5.20 / 6.50 (×1.25) | 22.12 / 46.09 (×2.08) | 35.26 / 94.08 (×2.67) |
| AIFF | 4400 | 5.69 / 9.66 (×1.70) | 13.65 / 22.31 (×1.63) | 22.19 / 50.77 (×2.29) |
| APE | 85212 | 5.63 / 5.89 (×1.05) | 12.64 / 27.01 (×2.14) | 19.19 / 74.54 (×3.88) |
| WAVPACK | 3176 | 4.43 / 4.18 (×0.94) | 11.07 / 15.94 (×1.44) | 19.10 / 71.17 (×3.73) |
| MPC | 1588 | 4.87 / 5.38 (×1.11) | 11.26 / 16.32 (×1.45) | 18.96 / 71.26 (×3.76) |
| TRUEAUDIO | 81819 | 10.64 / 9.61 (×0.90) | 134.38 / 215.75 (×1.61) | 61.10 / 121.08 (×1.98) |
| DSF | 8284 | 3.87 / 5.87 (×1.52) | 13.44 / 26.22 (×1.95) | 21.29 / 51.29 (×2.41) |
| DSDIFF | 7186 | 7.05 / 11.90 (×1.69) | 19.98 / 53.70 (×2.69) | 26.30 / 70.39 (×2.68) |
| MOD | 3132 | 9.39 / 5.04 (×0.54) | 46.94 / 44.41 (×0.95) | — |
| S3M | 544 | 9.43 / 3.71 (×0.39) | 24.67 / 54.41 (×2.21) | — |
| IT | 644 | 6.64 / 3.41 (×0.51) | 26.83 / 47.91 (×1.79) | — |
| XM | 5471 | 55.24 / 12.71 (×0.23) | 227.93 / 295.49 (×1.30) | — |
| MATROSKA | 12390 | 17.90 / 8.15 (×0.46) | 41.59 / 111.08 (×2.67) | 43.69 / 96.39 (×2.21) |
| SHORTEN | 47533 | 2.42 / 5.16 (×2.14) | — | — |

### Larger files

| Format / case | File bytes | Read (µs) | Title (µs) | Growth (µs) |
|---|---:|---:|---:|---:|
| MPEG-001MiB | 1051394 | 13.94 / 31.29 (×2.25) | 1038.73 / 1208.03 (×1.16) | 444.07 / 531.35 (×1.20) |
| FLAC-001MiB | 1071124 | 12.04 / 25.43 (×2.11) | 38.25 / 78.52 (×2.05) | 571.12 / 589.27 (×1.03) |
| OGG_VORBIS-001MiB | 1047458 | 28.96 / 74.91 (×2.59) | 1323.56 / 1971.05 (×1.49) | 392.50 / 471.74 (×1.20) |
| OGG_OPUS-001MiB | 1055045 | 34.92 / 87.45 (×2.50) | 1280.34 / 1899.16 (×1.48) | 374.35 / 441.87 (×1.18) |
| MP4-001MiB | 1063433 | 49.04 / 67.93 (×1.39) | 85.03 / 154.83 (×1.82) | 425.96 / 499.13 (×1.17) |
| WAV-001MiB | 1048788 | 9.06 / 17.80 (×1.97) | 1304.08 / 1953.76 (×1.50) | 1336.53 / 1979.69 (×1.48) |
| AIFF-001MiB | 1048894 | 14.55 / 43.57 (×2.99) | 236.59 / 99.71 (×0.42) | 237.72 / 91.02 (×0.38) |
| MPEG-010MiB | 10488194 | 13.90 / 31.32 (×2.25) | 9070.13 / 10492.21 (×1.16) | 3342.34 / 3967.67 (×1.19) |
| FLAC-010MiB | 10636684 | 11.98 / 25.60 (×2.14) | 37.81 / 117.42 (×3.11) | 5105.45 / 5436.25 (×1.06) |
| OGG_VORBIS-010MiB | 10486465 | 17.99 / 46.18 (×2.57) | 12842.14 / 18819.34 (×1.47) | 3399.25 / 3894.38 (×1.15) |
| OGG_OPUS-010MiB | 10544688 | 32.48 / 81.04 (×2.49) | 12808.31 / 18773.44 (×1.47) | 3375.26 / 3906.35 (×1.16) |
| MP4-010MiB | 10617765 | 48.81 / 68.57 (×1.40) | 93.93 / 205.50 (×2.19) | 3535.50 / 3981.97 (×1.13) |
| WAV-010MiB | 10485972 | 9.05 / 17.64 (×1.95) | 12795.56 / 18930.14 (×1.48) | 12739.83 / 18905.41 (×1.48) |
| AIFF-010MiB | 10486078 | 14.56 / 43.37 (×2.98) | 2882.06 / 155.47 (×0.05) | 1974.69 / 144.79 (×0.07) |
| MPEG-050MiB | 52431554 | 14.01 / 32.26 (×2.30) | 44906.78 / 52344.75 (×1.17) | 17086.44 / 18734.21 (×1.10) |
| FLAC-050MiB | 53154766 | 11.92 / 26.42 (×2.22) | 84.06 / 136.41 (×1.62) | 25022.07 / 26582.71 (×1.06) |
| OGG_VORBIS-050MiB | 52428058 | 28.45 / 73.43 (×2.58) | 64482.77 / 93189.47 (×1.45) | 17342.38 / 19257.35 (×1.11) |
| OGG_OPUS-050MiB | 52719733 | 25.05 / 57.91 (×2.31) | 64279.86 / 93687.73 (×1.46) | 17298.14 / 19010.52 (×1.10) |
| MP4-050MiB | 53078815 | 49.23 / 67.79 (×1.38) | 144.03 / 231.07 (×1.60) | 17133.87 / 19107.07 (×1.12) |
| WAV-050MiB | 52429012 | 9.06 / 17.25 (×1.90) | 63958.20 / 94105.27 (×1.47) | 64262.67 / 94030.63 (×1.46) |
| AIFF-050MiB | 52429118 | 14.56 / 44.22 (×3.04) | 4185.27 / 192.97 (×0.05) | 4180.52 / 185.62 (×0.04) |
