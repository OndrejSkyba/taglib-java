# Parity tests

JUnit tests compare this Java port with upstream C++ TagLib using the same inputs,
checking metadata, audio properties, editing, and saved bytes.

## Requirements

- JDK 25.
- C/C++ toolchain with C++17 support, CMake 3.16+, and Make (or Ninja).
- Bash, standard Unix utilities, sha256sum, flock, and ripgrep.
- curl and tar to download/extract the default upstream release.
- zlib development files for compressed ID3 comparisons.
- Internet access for initial dependency and upstream downloads.
- Optional: FFmpeg for additional media checks; Git for upstream version/ref overrides.

## Run

From the repository root:

```sh
./parity/run           # All parity tests (also: ./parity/run all)
./parity/run mp4       # One component
./parity/run list      # Available components
./parity/run fixtures  # Prepare upstream source/samples without building or testing
./parity/run --help    # Usage (also: -h)
```

The runner downloads the pinned upstream release, builds the C++ reference
executables, and runs the comparisons. Results are saved to `parity/target/results.json`.

Options can be combined with a component:

```sh
./parity/run mp4 --test 'Mp4AudioPropertiesAndTagEditingTest' # Test class/method pattern
./parity/run --output /path/to/results.json                 # Custom JSON report path
./parity/run --upstream-version 2.1.0                       # Release version (X.Y.Z)
./parity/run --upstream-ref v2.1.0                          # Git tag, branch, or full SHA
./parity/run --upstream-source /path/to/taglib              # Local source with tests/data
```

The pinned release is the default; upstream selectors are mutually exclusive. Local
sources are used without modification. Relative output paths resolve from the repository root
and must end in `.json`. `--test` accepts Maven Surefire patterns such as
`'ClassName#methodName'` and still applies the selected component filter.

Equivalent Maven command: `./mvnw -Pparity verify`.
