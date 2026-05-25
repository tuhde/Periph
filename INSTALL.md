<!-- periph-version: 1.0.1 -->
# Installing Periph

Version **1.0.1** · [Release notes](../../releases/tag/v1.0.1)

---

## Python

```sh
pip install periph==1.0.1
```

On Linux, install the SMBus transport dependency as well:

```sh
pip install smbus2
```

---

## Node.js

```sh
npm install periph@1.0.1
```

---

## Node-RED

Install category packages via Node-RED's **Manage Palette**, or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-temperature@1.0.1
npm install node-red-contrib-periph-power@1.0.1
```

Available categories: `accelerometer` · `adc-dac` · `color` · `comms` · `display` · `environmental` · `gas` · `gnss` · `gpio` · `gyroscope` · `humidity` · `io-expander` · `led` · `light` · `magnetometer` · `memory` · `motor` · `power` · `pressure` · `rfid` · `rtc` · `temperature` · `tof`

---

## Rust

```sh
cargo add periph@1.0.1
```

Or in `Cargo.toml`:

```toml
[dependencies]
periph = "1.0.1"
```

---

## C++ / Arduino

Download [`Periph-1.0.1.zip`](../../releases/download/v1.0.1/Periph-1.0.1.zip) from the release assets and install in the Arduino IDE:

**Sketch → Include Library → Add .ZIP Library…**

Or via `arduino-cli`:

```sh
arduino-cli lib install --zip-path Periph-1.0.1.zip
```

For Linux GCC or Zephyr, copy `cpp/src/` into your project and include the relevant headers.

---

## Java / Kotlin / Groovy (JVM)

Download the JARs from the [release assets](../../releases/tag/v1.0.1):

| JAR | Required by |
|-----|-------------|
| `periph-transport-1.0.1.jar` | all JVM languages |
| `periph-java-1.0.1.jar` | Java |
| `periph-kotlin-1.0.1.jar` | Kotlin |
| `periph-groovy-1.0.1.jar` | Groovy |

Install into your local Maven repository:

```sh
mvn install:install-file -Dfile=periph-transport-1.0.1.jar \
    -DgroupId=it.uhde -DartifactId=periph-transport -Dversion=1.0.1 -Dpackaging=jar

mvn install:install-file -Dfile=periph-java-1.0.1.jar \
    -DgroupId=it.uhde -DartifactId=periph-java -Dversion=1.0.1 -Dpackaging=jar
```

Then declare in `pom.xml`:

```xml
<dependency>
    <groupId>it.uhde</groupId>
    <artifactId>periph-transport</artifactId>
    <version>1.0.1</version>
</dependency>
<dependency>
    <groupId>it.uhde</groupId>
    <artifactId>periph-java</artifactId>
    <version>1.0.1</version>
</dependency>
```

For JBang, run the `install:install-file` commands above first, then reference in your script:

```java
//DEPS it.uhde:periph-transport:1.0.1
//DEPS it.uhde:periph-java:1.0.1
```
