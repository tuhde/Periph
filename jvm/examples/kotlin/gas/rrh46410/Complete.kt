///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-kotlin:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.gas.Rrh46410Full
import it.uhde.periph.chips.gas.Rrh46410Minimal
import java.util.HexFormat

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x38").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Rrh46410Full(connection)                                                       // Create RRH46410 driver, (connection) → Rrh46410Full
        // The module has the fixed address 0x38 and stretches the I²C clock; no bus traffic yet.
        sensor.init()                                                                               // Check product ID, start IAQ 2nd Gen mode, () → Unit
        // Retries Get Product ID for up to 1 s, verifies 0x2310, selects IAQ 2nd Gen (3 s samples).

        println(sensor.productId())                                                                 // Product ID and firmware, () → Rrh46410ProductId {productId, major, minor, patch}
        // The product ID is always 0x2310.
        println(HexFormat.of().formatHex(sensor.trackingNumber()))                                  // ASIC tracking number, () → ByteArray (6)
        // Unique per module.
        println(sensor.getMode())                                                                   // Operation mode, () → Int
        // 1 = IAQ 2nd Gen after init.

        sensor.setHumidity(45.0)                                                                    // Ambient humidity, (percent %RH) → Unit
        // Sent as round(percent / 100 × 255); the module assumes 50 %RH otherwise.
        println(sensor.read())                                                                      // Next sample, () → Rrh46410Measurement
        // Waits for the sample counter to change; values are fixed during the warm-up.
        println(sensor.waitForSample(4.0))                                                          // Wait for a new sample, (timeout s) → Boolean
        // True when the counter changed (or INT went high) within the timeout.
        println(sensor.readResults())                                                               // Latest result, () → Rrh46410Measurement?
        // Does not wait; null before the first sample of the mode.
        println(sensor.lastResult())                                                                // Last decoded result, () → Rrh46410Measurement?
        // The sample stored by read, readResults or waitForSample.
        println(sensor.airQualityLabel(2.4))                                                        // UBA rating, (iaq) → String
        // "very good", "good", "medium", "poor" or "bad".
        println("${sensor.samplePeriod()} s, ${sensor.warmupSamples()}")                          // Mode timing, (m=current) → Double s / Int
        // 3 s and 100 samples in IAQ 2nd Gen mode.
        println(sensor.status())                                                                    // Last status byte, () → Rrh46410Status
        // Error bits and the operation-mode error code (0xA = sensor damaged).

        sensor.onInterrupt({ r -> println("sample ${r.sample} IAQ ${r.iaq}") })                   // Subscribe, (callback, intPin=connection.intPin()) → Unit
        // Rising edge on INT, or a 200 ms poll comparing the sample counter.
        Thread.sleep(7000)
        sensor.offInterrupt()                                                                       // Unsubscribe, () → Unit
        // Stops the pin handler or polling thread.
        println(sensor.pollInterrupt())                                                             // New sample pending, () → Boolean
        // INT level if wired, otherwise a counter comparison.

        sensor.setMode(Rrh46410Minimal.MODE_PBAQ)                                                   // Operation mode, (mode) → Unit
        // PBAQ: TVOC and ethanol only, every 5 s; restarts the warm-up.
        println(sensor.read())                                                                      // Next sample, () → Rrh46410Measurement
        // PBAQ results have a finer resolution (raw / 1000); IAQ and eCO2 are 0.
        sensor.setMode(Rrh46410Minimal.MODE_ULP)                                                    // Operation mode, (mode) → Unit
        // ULP: one sample every 90 s at 0.23 mW.
        println("${sensor.samplePeriod()} s, ${sensor.warmupSamples()}")                          // Mode timing, (m=current) → Double s / Int
        // 90 s and 10 samples.

        sensor.configGpio(0x03)                                                                     // GPIO directions, (directions) → Unit
        // Bit 0 = GPIO1 output, bit 1 = GPIO2 output.
        sensor.setGpio(0x01)                                                                        // Drive high, (m) → Unit
        // GPIO1 high.
        println(Integer.toBinaryString(sensor.getGpio()))                                           // GPIO levels, () → Int
        // Bits 0–3 = GPIO1–GPIO4.
        sensor.clearGpio(0x01)                                                                      // Drive low, (m) → Unit
        // GPIO1 low.

        println(sensor.sendCommand(0x10, ByteArray(0), 1)[0])                                       // Raw request, (command, params=empty, responseLen=0) → ByteArray
        // Checksums added and verified; here Get Operation Mode.
        // sensor.cleanSensor(true)                                                                 // One-time cleaning, (confirm=false) → Unit
        // Runs for one minute, once in the sensor's lifetime; left disabled here.
        sensor.suspend()                                                                            // Suspend mode, () → Unit
        // Stops measuring, about 0.7 µA.
        sensor.reset()                                                                              // Reset, () → Unit
        // Waits 500 ms and verifies the product ID; the module restarts in SUSPEND.
    }
}
