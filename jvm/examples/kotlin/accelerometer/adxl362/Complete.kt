///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.accelerometer.Adxl362Full
import it.uhde.periph.chips.accelerometer.Adxl362Full.Companion.FIFO_STREAM
import it.uhde.periph.chips.accelerometer.Adxl362Full.Companion.LINKLOOP_LOOP
import it.uhde.periph.chips.accelerometer.Adxl362Full.Companion.NOISE_LOW
import it.uhde.periph.chips.accelerometer.Adxl362Full.Companion.SOURCE_AWAKE
import it.uhde.periph.chips.accelerometer.Adxl362Full.Companion.SOURCE_DATA_READY

fun main() {
    val bus = System.getenv("SPI_BUS")?.toIntOrNull() ?: 0
    val dev = System.getenv("SPI_DEVICE")?.toIntOrNull() ?: 0

    SPIConnection(bus, dev, 0, 8_000_000).use { connection ->                    // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
        val chip = Adxl362Full(connection)                                      // Create ADXL362 Full driver, (connection) → Adxl362Full

        val ids = chip.deviceId()                                              // Read device IDs, () → IntArray (DEVID_AD, DEVID_MST, PARTID, REVID)
        println("DEVID_AD=0x%02X DEVID_MST=0x%02X PARTID=0x%02X REVID=0x%02X"
                .format(ids[0], ids[1], ids[2], ids[3]))

        chip.setRange(4)                                                       // Set measurement range, (rangeG=4) → Unit
        chip.setOdr(200.0f)                                                    // Set output data rate, (odrHz=200.0) → Unit
        chip.setHalfBandwidth(true)                                            // Set antialiasing bandwidth, (enabled=true) → Unit
        chip.setNoiseMode(NOISE_LOW)                                           // Set noise mode, (mode=NOISE_LOW) → Unit

        val xyz12 = chip.read()                                                // Read 12-bit acceleration, () → DoubleArray g
        println("12-bit: x=${xyz12[0].format("+%.3f")}  y=${xyz12[1].format("+%.3f")}  z=${xyz12[2].format("+%.3f")}")

        val xyz8 = chip.read8bit()                                             // Read 8-bit acceleration, () → DoubleArray g
        println(" 8-bit: x=${xyz8[0].format("+%.3f")}  y=${xyz8[1].format("+%.3f")}  z=${xyz8[2].format("+%.3f")}")

        val t = chip.temperature()                                             // Read temperature, () → Double °C
        println("temperature: %.2f C".format(t))

        val rawStatus = chip.status()                                          // Read STATUS register, () → Int
        println("status: 0x%02X".format(rawStatus))
        println("awake: ${if (chip.awake()) 1 else 0}")                         // Check AWAKE bit, () → Boolean
        println("data_ready: ${if (chip.dataReady()) 1 else 0}")               // Check DATA_READY, () → Boolean
        println("fifo_entries: ${chip.fifoEntries()}")                         // Read FIFO entry count, () → Int

        chip.configureFifo(FIFO_STREAM, false, 128)                            // Configure FIFO, (mode=STREAM, storeTemp=false, watermark=128) → Unit
        chip.setActivityThreshold(0.5, true)                                   // Set activity threshold, (thresholdG=0.5, referenced=true) → Unit
        chip.setActivityTime(5)                                                 // Set activity time, (samples=5) → Unit
        chip.setInactivityThreshold(0.2, true)                                  // Set inactivity threshold, (thresholdG=0.2, referenced=true) → Unit
        chip.setInactivityTime(30)                                              // Set inactivity time, (samples=30) → Unit
        chip.enableActivityDetection(true)                                      // Enable activity detection, (enabled=true) → Unit
        chip.enableInactivityDetection(true)                                    // Enable inactivity detection, (enabled=true) → Unit
        chip.setLinkLoopMode(LINKLOOP_LOOP)                                    // Set link/loop mode, (mode=LOOP) → Unit

        chip.setInterrupt(1, SOURCE_DATA_READY, true)                          // Map DATA_READY to INT1, (pin=1, source=DATA_READY, enabled=true) → Unit
        chip.setInterrupt(2, SOURCE_AWAKE, true)                               // Map AWAKE to INT2, (pin=2, source=AWAKE, enabled=true) → Unit
        chip.setInterruptPolarity(1, true)                                     // Set INT1 active-low, (pin=1, activeLow=true) → Unit

        chip.selfTest(true)                                                    // Enable self-test, (enabled=true) → Unit
        Thread.sleep(500)
        chip.selfTest(false)                                                   // Disable self-test, (enabled=false) → Unit

        chip.softReset()                                                       // Soft-reset the chip, () → Unit

        println("===DONE: 1 passed, 0 failed===")
    }
}