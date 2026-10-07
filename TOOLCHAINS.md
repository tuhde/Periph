# Toolchain setup — Debian 13 (trixie)

Everything needed to compile every platform in this repo, on a fresh Debian
13 machine. Versions here mirror `.github/workflows/ci.yml` — if CI's pins
move, re-check that file. `debian:trixie` is also the exact container CI
uses for the Linux GCC job, so Debian 13 itself needs no substitutions there.

This covers **compiling**, not hardware testing. For flashing boards,
serial monitors, and hardware-in-loop tooling (`mpremote`, `picotool`,
`cargo-espflash`, JBang, sigrok/PulseView, etc.), see `TESTING.md`.

Run everything below as your normal user; only `apt-get` needs `sudo`.

---

## 0. Base packages

```sh
sudo apt-get update
sudo apt-get install -y --no-install-recommends \
  build-essential git curl wget pkg-config ca-certificates unzip xz-utils \
  python3 python3-venv python3-dev python3-pip
```

`python3-venv` is only needed by tools that build their own private venv
(`python/uiflow1/generate.sh`, ESP-IDF's `install.sh`) — you never activate one.

Trixie ships Python 3.13 by default, matching CI's `setup-python` pin.
Verify: `python3 --version`.

Debian's PEP 668 "externally-managed-environment" blocks bare `pip install`
outside a venv. This guide avoids venvs entirely: Python libraries come from
`apt` (`python3-*` packages), and Zephyr's pip-only tooling (`west` and its
requirements) lives in its own venv, see section 2e.

(`python/uiflow1/generate.sh` manages its own throwaway venv automatically —
nothing to set up for that one.)

---

## 1. Python (MicroPython / CircuitPython / Linux)

Syntax-checking and the Linux target both just need the interpreter above.
For the Linux host connection classes (`smbus2`, `spidev`, `gpiod`,
`pyserial`, `python-periphery`), install the apt packages and point Python at
the checkout instead of pip-installing it:

```sh
sudo apt-get install -y --no-install-recommends \
  python3-smbus2 python3-spidev python3-libgpiod python3-serial python3-periphery
echo "export PYTHONPATH=\"$PWD/python:\$PYTHONPATH\"" >> ~/.bashrc   # run from the repo root
export PYTHONPATH="$PWD/python:$PYTHONPATH"
```

(`python3-libgpiod` provides the `gpiod` module; trixie ships libgpiod 2.x,
matching the `gpiod>=2` requirement in `python/pyproject.toml`. Check with
`python3 -c 'import gpiod; print(gpiod.__version__)'`.)

MicroPython-side tooling (mip installs, on-device testing — see `TESTING.md`):

```sh
sudo apt-get install -y --no-install-recommends python3-serial micropython-mpremote
```

---

## 2. C++

### 2a. Linux GCC

Exactly CI's `linux-gcc` job:

```sh
sudo apt-get install -y --no-install-recommends g++ libgpiod-dev python3
cpp/scripts/build-all.sh linux
```

### 2b. Arduino (ESP32 + AVR cores)

```sh
mkdir -p ~/.local/bin
curl -fsSL https://raw.githubusercontent.com/arduino/arduino-cli/master/install.sh | BINDIR=~/.local/bin sh
export PATH="$HOME/.local/bin:$PATH"   # add to ~/.bashrc

arduino-cli config init --overwrite
arduino-cli config add board_manager.additional_urls \
  https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json
arduino-cli core update-index
arduino-cli core install esp32:esp32
arduino-cli core install arduino:avr
```

Compile every example/test sketch, same as CI:

```sh
cpp/test_arduino_examples.sh --fqbn esp32:esp32:esp32s3 --tests
cpp/test_arduino_examples.sh --fqbn arduino:avr:mega
```

### 2c. Raspberry Pi Pico SDK

```sh
sudo apt-get install -y --no-install-recommends cmake gcc-arm-none-eabi gcc g++ python3 ccache

git clone --depth 1 -b 2.1.1 https://github.com/raspberrypi/pico-sdk.git ~/pico-sdk
cd ~/pico-sdk && git submodule update --init --depth 1 && cd -
echo 'export PICO_SDK_PATH="$HOME/pico-sdk"' >> ~/.bashrc
export PICO_SDK_PATH="$HOME/pico-sdk"

PICO_BOARD=pico cpp/scripts/build-all.sh picosdk
```

### 2d. ESP-IDF (v6.1)

```sh
sudo apt-get install -y --no-install-recommends \
  git wget flex bison gperf cmake ninja-build ccache \
  libffi-dev libssl-dev dfu-util libusb-1.0-0 \
  python3 python3-venv

git clone --recursive -b v6.1 https://github.com/espressif/esp-idf.git ~/esp-idf
~/esp-idf/install.sh esp32

# Every new shell:
. ~/esp-idf/export.sh

cpp/scripts/build-all.sh espidf
```

If `v6.1` no longer exists as a tag/branch by the time you run this, pick
the closest `v6.x` release on the [esp-idf releases
page](https://github.com/espressif/esp-idf/tags) — the repo isn't pinned
to a patch version, only `≥5.3` (see `TESTING.md`), but matching CI's `v6.1`
avoids drift.

### 2e. Zephyr (v4.4.2)

```sh
sudo apt-get install -y --no-install-recommends \
  cmake ninja-build gperf ccache dfu-util device-tree-compiler wget \
  python3-dev python3-pip python3-setuptools python3-venv python3-wheel \
  xz-utils file make gcc gcc-multilib g++-multilib libsdl2-dev libmagic1

python3 -m venv ~/.venvs/zephyr
source ~/.venvs/zephyr/bin/activate   # run this in every shell you build Zephyr from
pip install west

west init -m https://github.com/zephyrproject-rtos/zephyr --mr v4.4.2 ~/zephyrproject
cd ~/zephyrproject
west config manifest.project-filter -- '-.*,+cmsis_6,+hal_rpi_pico'
west update --narrow -o=--depth=1
pip install -r zephyr/scripts/requirements-base.txt
west sdk install -t arm-zephyr-eabi   # downloads several GB; only the ARM SDK is needed here
```

Build, same as CI (targets the Pico 2, `rpi_pico2/rp2350a/m33`):

```sh
cd ~/zephyrproject
source zephyr/zephyr-env.sh
"$OLDPWD/cpp/scripts/build-all.sh" zephyr   # run from the Periph checkout, or pass its absolute path
```

### 2f. STM32Cube (STM32CubeF4 v1.28.3, NUCLEO-F411RE)

```sh
sudo apt-get install -y --no-install-recommends cmake gcc-arm-none-eabi libnewlib-arm-none-eabi g++ ccache

# Sparse + blobless: only Drivers/CMSIS and the HAL driver are needed (~145 MB instead of ~1 GB).
git clone --depth 1 --filter=blob:none --sparse -b v1.28.3 \
  https://github.com/STMicroelectronics/STM32CubeF4.git ~/STM32CubeF4
git -C ~/STM32CubeF4 sparse-checkout set --skip-checks Drivers/CMSIS Drivers/STM32F4xx_HAL_Driver
git -C ~/STM32CubeF4 submodule update --init --depth 1 \
  Drivers/CMSIS/Device/ST/STM32F4xx Drivers/STM32F4xx_HAL_Driver
echo 'export STM32CUBE_FW_PATH="$HOME/STM32CubeF4"' >> ~/.bashrc
export STM32CUBE_FW_PATH="$HOME/STM32CubeF4"

cpp/scripts/build-all.sh stm32cube
```

The clone alone is **not enough**: the CMSIS device headers and the HAL
driver are git submodules, and without them CMake fails with
`Cannot find source file: .../system_stm32f4xx.c`. Only those two are
needed; everything else in the repo (docs, example projects, middlewares, BSP) is large and unused.

To flash and run on a NUCLEO-F411RE (on-board ST-LINK), install
`stlink-tools` and use `cpp/test_stm32cube.sh`; it builds, flashes with
`st-flash` and reads the ST-LINK virtual COM port (`/dev/ttyACM0`, 115200
baud).

---

## 3. Node.js / Node-RED

Trixie's `apt` Node.js lags CI's pinned v24; use NodeSource:

```sh
curl -fsSL https://deb.nodesource.com/setup_24.x | sudo -E bash -
sudo apt-get install -y nodejs
```

(Alternative: `nvm install 24` if you'd rather not add a system-wide repo.)

```sh
cd nodejs
npm ci
find packages/periph/src ../nodejs/tests -name '*.js' | xargs -I{} node --check {}
```

---

## 4. Rust (Linux host + ESP32-S3)

Host toolchain via `rustup`, not `apt` (needed for per-crate toolchain
overrides like the ESP32-S3 examples' `rust-toolchain.toml`):

```sh
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable
source "$HOME/.cargo/env"

sudo apt-get install -y --no-install-recommends libudev-dev

cargo build --manifest-path rust/Cargo.toml
```

For the ESP32-S3 examples (`rust/examples/embedded/esp32s3/`, excluded from
the main workspace), esp-hal needs the Xtensa Rust fork via `espup`:

```sh
cargo install espup --locked
espup install
echo '. "$HOME/export-esp.sh"' >> ~/.bashrc   # each new shell needs this sourced
. "$HOME/export-esp.sh"

cd rust/examples/embedded/esp32s3/<category>/<chip>/minimal
cargo build --release
```

---

## 5. JVM (Java / Kotlin / Groovy)

CI pins Temurin 25; `TESTING.md` only requires Java 22+, but matching CI
avoids surprises. Trixie's `apt` OpenJDK is unlikely to be at 25 yet, so use
Adoptium's repo:

```sh
wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public | \
  sudo gpg --dearmor -o /usr/share/keyrings/adoptium.gpg
echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print $2}' /etc/os-release) main" | \
  sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt-get update
sudo apt-get install -y temurin-25-jdk
```

If Adoptium hasn't published a `trixie` suite yet, substitute `bookworm` in
the line above — the `.deb`s are compatible.

```sh
sudo apt-get install -y maven
cd jvm
mvn install --batch-mode --no-transfer-progress -DskipTests
```

Kotlin and Groovy compilers are pulled in by Maven itself
(`kotlin-maven-plugin`, `gmavenplus-plugin`) — nothing extra to install.

Optional, for running the JBang example scripts under `jvm/examples/`:

```sh
curl -Ls https://sh.jbang.dev | bash -s - app setup
```

---

## 6. Go (Linux host + TinyGo)

Check whether trixie's package already satisfies `go.mod`'s `go 1.24`:

```sh
apt-cache policy golang-go
```

If it's older, install from the official tarball instead (adjust the
filename for your architecture — see https://go.dev/dl/):

```sh
curl -LO https://go.dev/dl/go1.24.0.linux-amd64.tar.gz
sudo tar -C /usr/local -xzf go1.24.0.linux-amd64.tar.gz
echo 'export PATH="/usr/local/go/bin:$PATH"' >> ~/.bashrc
export PATH="/usr/local/go/bin:$PATH"
```

```sh
cd go
go build ./...
go vet ./...
```

TinyGo, pinned to CI's `0.41.0` (adjust the asset name for your
architecture — see the [releases page](https://github.com/tinygo-org/tinygo/releases)):

```sh
curl -LO https://github.com/tinygo-org/tinygo/releases/download/v0.41.0/tinygo_0.41.0_amd64.deb
sudo apt-get install -y ./tinygo_0.41.0_amd64.deb   # pulls in its own dependencies

tinygo build -target=pico-w -o /tmp/out.uf2 ./examples/tinygo/power/ina226/minimal
```

---

## 7. Full verification pass

Once everything above is installed, this mirrors every CI compile job:

```sh
# C++
cpp/scripts/build-all.sh linux
cpp/scripts/build-all.sh picosdk
cpp/scripts/build-all.sh espidf     # after sourcing esp-idf/export.sh
cpp/scripts/build-all.sh zephyr     # from inside ~/zephyrproject, after sourcing zephyr-env.sh
cpp/scripts/build-all.sh stm32cube  # needs STM32CUBE_FW_PATH
cpp/test_arduino_examples.sh --fqbn esp32:esp32:esp32s3 --tests
cpp/test_arduino_examples.sh --fqbn arduino:avr:mega

# Python
find python/periph python/tests python/examples -name '*.py' | xargs python3 -m py_compile

# Node.js
cd nodejs && npm ci && cd -

# JVM
cd jvm && mvn install --batch-mode --no-transfer-progress -DskipTests && cd -

# Rust
cargo build --manifest-path rust/Cargo.toml

# Go
cd go && go build ./... && go vet ./... && cd -
```
