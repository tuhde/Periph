///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.accelerometer.Adxl362Minimal

int bus = System.getenv("SPI_BUS")?.toInteger() ?: 0
int dev = System.getenv("SPI_DEVICE")?.toInteger() ?: 0

try (SPIConnection connection = new SPIConnection(bus, dev, 0, 8_000_000)) {    // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
    Adxl362Minimal chip = new Adxl362Minimal(connection)                       // Create ADXL362 driver, (connection) → Adxl362Minimal

    5.times {
        float[] xyz = chip.read()                                               // Read 3-axis acceleration, () → float[3] g
        println String.format("x=%+.3f  y=%+.3f  z=%+.3f g", xyz[0], xyz[1], xyz[2])
        Thread.sleep(100)
    }
}