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
        sensor.init()                                                                               // Check product ID, start IAQ 2nd Gen mode, () → Unit

        // --- Identity ---
        // Get Product ID returns 0x2310 and the firmware version; the tracking number
        // identifies the individual module.
        val id = sensor.productId()                                                                 // Product ID and firmware, () → Rrh46410ProductId
        System.out.printf("product 0x%04X, firmware %d.%d.%d%n", id.productId, id.major, id.minor, id.patch)
        System.out.printf("tracking %s, mode %d%n", HexFormat.of().formatHex(sensor.trackingNumber()), // Tracking number, () → ByteArray
            sensor.getMode())                                                                       // Operation mode, () → Int

        // --- Humidity compensation ---
        // The MOx element reacts to humidity; feeding the real value (e.g. from a
        // humidity sensor) improves the results. The module assumes 50 %RH.
        sensor.setHumidity(42.0)                                                                    // Ambient humidity, (percent %RH) → Unit

        // --- Indoor air-quality monitor ---
        // A new sample arrives every 3 s; the first 100 samples (about 5 min) are the
        // warm-up during which the values stay fixed.
        val warmup = sensor.warmupSamples()                                                         // Warm-up length, () → Int samples
        System.out.printf("sample period %.0f s, warm-up %d samples%n", sensor.samplePeriod(), warmup) // Sample period, () → Double s
        repeat(20) {
            val r = sensor.read()                                                                   // Next sample, () → Rrh46410Measurement
            val progress = if (r.warmedUp) "ok" else "warm-up ${minOf(100, r.sample * 100 / warmup)}%"
            System.out.printf("IAQ %.1f (%s)  TVOC %.2f mg/m3  EtOH %.2f ppm  eCO2 %d ppm  rel %d  %s%n",
                r.iaq, sensor.airQualityLabel(r.iaq), r.tvocMgM3,                                   // UBA rating, (iaq) → String
                r.etohPpm, r.eco2Ppm, r.relativeIaq, progress)
        }

        // --- Interrupt instead of polling ---
        // With INT wired the driver reacts to its rising edge; otherwise it polls the
        // sample counter. The callback receives the decoded result.
        sensor.onInterrupt({ r -> System.out.printf("INT sample %d IAQ %.1f%n", r.sample, r.iaq) }) // Subscribe, (callback) → Unit
        Thread.sleep(10_000)
        sensor.offInterrupt()                                                                       // Unsubscribe, () → Unit
        while (!sensor.pollInterrupt()) Thread.sleep(500)                                           // New sample pending, () → Boolean
        if (sensor.waitForSample(4.0)) {                                                            // Wait for a new sample, (timeout s) → Boolean
            println("latest ${sensor.readResults()}")                                              // Latest result, () → Rrh46410Measurement?
        }

        // --- Other operation modes ---
        // PBAQ reports TVOC and ethanol for public buildings; ULP saves power with
        // one sample every 90 s. Each mode change restarts the warm-up.
        for ((mode, name) in listOf(Rrh46410Minimal.MODE_PBAQ to "PBAQ", Rrh46410Minimal.MODE_ULP to "ULP")) {
            sensor.setMode(mode)                                                                    // Operation mode, (mode) → Unit
            System.out.printf("%s: sample every %.0f s, warm-up %d samples%n", name,
                sensor.samplePeriod(), sensor.warmupSamples())                                      // Mode timing, () → Double s / Int
        }
        sensor.setMode(Rrh46410Minimal.MODE_PBAQ)                                                   // Operation mode, (mode) → Unit
        println("PBAQ ${sensor.read()}")                                                           // Next sample, () → Rrh46410Measurement

        // --- GPIOs ---
        // GPIO1 and GPIO2 can drive an indicator LED or a fan relay.
        sensor.configGpio(0x03)                                                                     // Both outputs, (directions) → Unit
        repeat(3) {
            sensor.setGpio(0x01)                                                                    // GPIO1 high, (m) → Unit
            Thread.sleep(200)
            sensor.clearGpio(0x01)                                                                  // GPIO1 low, (m) → Unit
            Thread.sleep(200)
        }
        println("GPIO levels ${Integer.toBinaryString(sensor.getGpio())}")                         // GPIO levels, () → Int
        println("status ${sensor.status()}")                                                       // Last status byte, () → Rrh46410Status
        println("raw mode ${sensor.sendCommand(0x10, ByteArray(0), 1)[0]}")                        // Raw request, (command, params, responseLen) → ByteArray

        // --- Wrap up ---
        // cleanSensor(true) runs the 1-minute cleaning once in the sensor's lifetime;
        // it is skipped here. Suspend stops measuring; reset restarts the module in SUSPEND.
        sensor.suspend()                                                                            // Suspend mode, () → Unit
        sensor.reset()                                                                              // Reset, () → Unit
        println("mode after reset ${sensor.getMode()}")                                            // Operation mode, () → Int
    }
}
