///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.accelerometer.Adxl362Full
import static it.uhde.periph.chips.accelerometer.Adxl362Full.FIFO_STREAM
import static it.uhde.periph.chips.accelerometer.Adxl362Full.LINKLOOP_LOOP
import static it.uhde.periph.chips.accelerometer.Adxl362Full.NOISE_LOW
import static it.uhde.periph.chips.accelerometer.Adxl362Full.SOURCE_AWAKE
import static it.uhde.periph.chips.accelerometer.Adxl362Full.SOURCE_DATA_READY

int bus = System.getenv("SPI_BUS")?.toInteger() ?: 0
int dev = System.getenv("SPI_DEVICE")?.toInteger() ?: 0

try (SPIConnection connection = new SPIConnection(bus, dev, 0, 8_000_000)) {    // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
    Adxl362Full chip = new Adxl362Full(connection)                             // Create ADXL362 Full driver, (connection) → Adxl362Full

    int[] ids = chip.deviceId()                                                 // Read device IDs, () → int[4]
    println String.format("DEVID_AD=0x%02X DEVID_MST=0x%02X PARTID=0x%02X REVID=0x%02X",
            ids[0], ids[1], ids[2], ids[3])

    chip.setRange(4)                                                            // Set measurement range, (rangeG=4) → void
    chip.setOdr(200.0f)                                                         // Set output data rate, (odrHz=200.0) → void
    chip.setHalfBandwidth(true)                                                 // Set antialiasing bandwidth, (enabled=true) → void
    chip.setNoiseMode(NOISE_LOW)                                               // Set noise mode, (mode=NOISE_LOW) → void

    float[] xyz12 = chip.read()                                                 // Read 12-bit acceleration, () → float[3] g
    println String.format("12-bit: x=%+.3f  y=%+.3f  z=%+.3f", xyz12[0], xyz12[1], xyz12[2])

    float[] xyz8 = chip.read8bit()                                              // Read 8-bit acceleration, () → float[3] g
    println String.format(" 8-bit: x=%+.3f  y=%+.3f  z=%+.3f", xyz8[0], xyz8[1], xyz8[2])

    float t = chip.temperature()                                                // Read temperature, () → float °C
    println String.format("temperature: %.2f C", t)

    int rawStatus = chip.status()                                               // Read STATUS register, () → int
    println String.format("status: 0x%02X", rawStatus)
    println String.format("awake: %d", chip.awake() ? 1 : 0)                    // Check AWAKE bit, () → boolean
    println String.format("data_ready: %d", chip.dataReady() ? 1 : 0)          // Check DATA_READY, () → boolean
    println "fifo_entries: ${chip.fifoEntries()}"                               // Read FIFO entry count, () → int

    chip.configureFifo(FIFO_STREAM, false, 128)                                 // Configure FIFO, (mode=STREAM, storeTemp=false, watermark=128) → void
    chip.setActivityThreshold(0.5f, true)                                       // Set activity threshold, (thresholdG=0.5, referenced=true) → void
    chip.setActivityTime(5)                                                     // Set activity time, (samples=5) → void
    chip.setInactivityThreshold(0.2f, true)                                     // Set inactivity threshold, (thresholdG=0.2, referenced=true) → void
    chip.setInactivityTime(30)                                                  // Set inactivity time, (samples=30) → void
    chip.enableActivityDetection(true)                                          // Enable activity detection, (enabled=true) → void
    chip.enableInactivityDetection(true)                                        // Enable inactivity detection, (enabled=true) → void
    chip.setLinkLoopMode(LINKLOOP_LOOP)                                         // Set link/loop mode, (mode=LOOP) → void

    chip.setInterrupt(1, SOURCE_DATA_READY, true)                               // Map DATA_READY to INT1, (pin=1, source=DATA_READY, enabled=true) → void
    chip.setInterrupt(2, SOURCE_AWAKE, true)                                    // Map AWAKE to INT2, (pin=2, source=AWAKE, enabled=true) → void
    chip.setInterruptPolarity(1, true)                                          // Set INT1 active-low, (pin=1, activeLow=true) → void

    chip.selfTest(true)                                                         // Enable self-test, (enabled=true) → void
    Thread.sleep(500)
    chip.selfTest(false)                                                        // Disable self-test, (enabled=false) → void

    chip.softReset()                                                            // Soft-reset the chip, () → void

    println "===DONE: 1 passed, 0 failed==="
}