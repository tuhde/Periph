<!-- periph-version: 1.3.2 -->
# Installing Periph

Version **1.3.2** · [Release notes](../../releases/tag/v1.3.2)

---

## Python

Linux host (the `linux` extra pulls in smbus2, spidev, gpiod, pyserial):

```sh
pip install "periph[linux]==1.3.2"
```

MicroPython, with `mpremote` from the host. Install the whole library, one
category, or a single chip; each package brings the connection files it needs:

```sh
mpremote mip install github:tuhde/Periph/python/periph@v1.3.2
mpremote mip install github:tuhde/Periph/python/periph/chips/power@v1.3.2
mpremote mip install github:tuhde/Periph/python/periph/chips/power/ina226.json@v1.3.2
```

On a networked board, `import mip; mip.install("github:...", version="v1.3.2")`
takes the same URLs. CircuitPython: copy the `periph/` package onto the board.

---

## Node.js

```sh
npm install periph@1.3.2
```

---

## Node-RED

Install category packages via Node-RED's **Manage Palette**, or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-temperature@1.3.2
npm install node-red-contrib-periph-power@1.3.2
```

Available categories: `accelerometer` · `adc-dac` · `color` · `comms` · `display` · `environmental` · `gas` · `gnss` · `gpio` · `gyroscope` · `humidity` · `io-expander` · `led` · `light` · `magnetometer` · `memory` · `motor` · `power` · `pressure` · `rfid` · `rtc` · `temperature` · `tof`

---

## Rust

```sh
cargo add periph@1.3.2
```

Or in `Cargo.toml`:

```toml
[dependencies]
periph = "1.3.2"
```

---

## C++ / Arduino

Download [`Periph-1.3.2.zip`](../../releases/download/v1.3.2/Periph-1.3.2.zip) from the release assets and install in the Arduino IDE:

**Sketch → Include Library → Add .ZIP Library…**

Or via `arduino-cli`:

```sh
arduino-cli lib install --zip-path Periph-1.3.2.zip
```

For Linux GCC or Zephyr, copy `cpp/src/` into your project and include the relevant headers.

---

## Java / Kotlin / Groovy (JVM)

Download the JARs from the [release assets](../../releases/tag/v1.3.2):

| JAR | Required by |
|-----|-------------|
| `periph-connection-1.3.2.jar` | all JVM languages |
| `periph-java-1.3.2.jar` | Java |
| `periph-kotlin-1.3.2.jar` | Kotlin |
| `periph-groovy-1.3.2.jar` | Groovy |

Install into your local Maven repository:

```sh
mvn install:install-file -Dfile=periph-connection-1.3.2.jar \
    -DgroupId=it.uhde -DartifactId=periph-connection -Dversion=1.3.2 -Dpackaging=jar

mvn install:install-file -Dfile=periph-java-1.3.2.jar \
    -DgroupId=it.uhde -DartifactId=periph-java -Dversion=1.3.2 -Dpackaging=jar
```

Then declare in `pom.xml`:

```xml
<dependency>
    <groupId>it.uhde</groupId>
    <artifactId>periph-connection</artifactId>
    <version>1.3.2</version>
</dependency>
<dependency>
    <groupId>it.uhde</groupId>
    <artifactId>periph-java</artifactId>
    <version>1.3.2</version>
</dependency>
```

For JBang, run the `install:install-file` commands above first, then reference in your script:

```java
//DEPS it.uhde:periph-connection:1.3.2
//DEPS it.uhde:periph-java:1.3.2
```
