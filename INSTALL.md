<!-- periph-version: 1.6.0 -->
# Installing Periph

Version **1.6.0** · [Release notes](../../releases/tag/v1.6.0)

---

## Python

Linux host (the `linux` extra pulls in smbus2, spidev, gpiod, pyserial):

```sh
pip install "periph[linux]==1.6.0"
```

MicroPython, with `mpremote` from the host. Install the whole library, one
category, or a single chip; each package brings the connection files it needs:

```sh
mpremote mip install github:tuhde/Periph/python/periph@v1.6.0
mpremote mip install github:tuhde/Periph/python/periph/chips/power@v1.6.0
mpremote mip install github:tuhde/Periph/python/periph/chips/power/ina226.json@v1.6.0
```

On a networked board, `import mip; mip.install("github:...", version="v1.6.0")`
takes the same URLs. CircuitPython: copy the `periph/` package onto the board.

---

## Debian 13 / Raspberry Pi OS (APT)

Signed packages for Debian 13 (trixie) and Raspberry Pi OS based on it, for
amd64, arm64 and armhf, from [tuhde.github.io/periph-apt](https://tuhde.github.io/periph-apt).
Add the repository once:

```sh
sudo curl -fsSLo /usr/share/keyrings/periph-archive-keyring.gpg \
  https://tuhde.github.io/periph-apt/periph-archive-keyring.gpg
printf '%s\n' 'Types: deb' 'URIs: https://tuhde.github.io/periph-apt' 'Suites: trixie' \
  'Components: main' 'Signed-By: /usr/share/keyrings/periph-archive-keyring.gpg' \
  | sudo tee /etc/apt/sources.list.d/periph.sources
sudo apt update
```

Then install what you need; `apt upgrade` picks up new releases:

| Package | Contents |
|---------|----------|
| `python3-periph` | Python module; recommends the Linux bus libraries (smbus2, spidev, libgpiod, pyserial, periphery) |
| `libperiph-dev` | C++17 static library and headers: `g++ app.cpp $(pkg-config --cflags --libs periph)`, or CMake `find_package(periph)` + `target_link_libraries(app periph::periph)` |
| `sigrok-decoders-periph` | Protocol decoders for sigrok-cli and PulseView |

```sh
sudo apt install python3-periph libperiph-dev sigrok-decoders-periph
```

---

## Node.js

```sh
npm install periph@1.6.0
```

---

## Node-RED

Install category packages via Node-RED's **Manage Palette**, or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-temperature@1.6.0
npm install node-red-contrib-periph-power@1.6.0
```

Available categories: `accelerometer` · `adc-dac` · `color` · `comms` · `display` · `environmental` · `gas` · `gnss` · `gyroscope` · `humidity` · `io-expander` · `led` · `light` · `magnetometer` · `memory` · `motor` · `power` · `pressure` · `rfid` · `rtc` · `temperature` · `tof`

---

## Rust

```sh
cargo add periph@1.6.0
```

Or in `Cargo.toml`:

```toml
[dependencies]
periph = "1.6.0"
```

---

## C++ / Arduino

Download [`Periph-cpp-1.6.0.zip`](../../releases/download/v1.6.0/Periph-cpp-1.6.0.zip) from the release assets and install in the Arduino IDE:

**Sketch → Include Library → Add .ZIP Library…**

Or via `arduino-cli`:

```sh
arduino-cli lib install --zip-path Periph-cpp-1.6.0.zip
```

For Linux GCC on Debian 13 / Raspberry Pi OS, install `libperiph-dev` from the [APT repository](#debian-13--raspberry-pi-os-apt). Elsewhere, build and install the library with `cmake -S cpp/linux -B build && cmake --build build && sudo cmake --install build` (needs libgpiod v2). For Zephyr, add `cpp/` as a module (`cpp/zephyr/module.yml`).

---

## Java / Kotlin / Groovy (JVM)

Download the JARs from the [release assets](../../releases/tag/v1.6.0):

| JAR | Required by |
|-----|-------------|
| `periph-connection-1.6.0.jar` | all JVM languages |
| `periph-java-1.6.0.jar` | Java |
| `periph-kotlin-1.6.0.jar` | Kotlin |
| `periph-groovy-1.6.0.jar` | Groovy |

Install into your local Maven repository:

```sh
mvn install:install-file -Dfile=periph-connection-1.6.0.jar \
    -DgroupId=it.uhde -DartifactId=periph-connection -Dversion=1.6.0 -Dpackaging=jar

mvn install:install-file -Dfile=periph-java-1.6.0.jar \
    -DgroupId=it.uhde -DartifactId=periph-java -Dversion=1.6.0 -Dpackaging=jar
```

Then declare in `pom.xml`:

```xml
<dependency>
    <groupId>it.uhde</groupId>
    <artifactId>periph-connection</artifactId>
    <version>1.6.0</version>
</dependency>
<dependency>
    <groupId>it.uhde</groupId>
    <artifactId>periph-java</artifactId>
    <version>1.6.0</version>
</dependency>
```

For JBang, run the `install:install-file` commands above first, then reference in your script:

```java
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0
```
