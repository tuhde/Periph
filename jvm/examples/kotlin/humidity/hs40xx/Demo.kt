///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.0
//DEPS it.uhde:periph-kotlin:1.5.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.HS40xxFull

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x54").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = HS40xxFull(connection)                                                         // Create HS40xx driver, (connection) → void

        // --- Humidity watchdog ---
        // Single readings for 10 s, then periodic mode with an RH-high alert (breathe on the sensor).
        val id = sensor.readId()                                                                    // Read the 32-bit sensor ID, () → long
        println("ID 0x%08X".format(id))
        repeat(5) {
            val v = sensor.read()                                                                   // Read temperature and humidity, () → double[] {°C, %RH}
            println("%.2f C, %.1f %%RH".format(v[0], v[1]))
            Thread.sleep(2000)
        }
        sensor.setAlertThresholds(rhHigh = 60.0, rhHighReset = 55.0)                                // Set alert thresholds, (rhHigh, rhHighReset, ...) → void
        sensor.enableInterrupt(HS40xxFull.SOURCE_RH_HIGH)                                           // Enable alert sources, (source) → void
        sensor.startPeriodic(1.0)                                                                   // Start periodic mode, (periodS) → void
        repeat(15) {
            Thread.sleep(1000)
            val p = sensor.readPeriodic()                                                           // Fetch the latest periodic result, () → double[] {°C, %RH, status}
            val alert = if (p[2].toInt() and HS40xxFull.SOURCE_RH_HIGH != 0) "ALERT" else "ok"
            println("%.2f C, %.1f %%RH  %s".format(p[0], p[1], alert))
        }
        sensor.stopPeriodic()                                                                       // Stop periodic mode, () → void
        sensor.softReset()                                                                          // Soft reset, () → void
    }
}
