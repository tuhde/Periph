///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.gas.Rrh46410Minimal

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x38").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Rrh46410Minimal(connection)                                                    // Create RRH46410 driver, (connection) → Rrh46410Minimal
        sensor.init()                                                                               // Check product ID, start IAQ 2nd Gen mode, () → Unit

        while (true) {
            val r = sensor.read()                                                                   // Wait for the next sample, () → Rrh46410Measurement {iaq, tvocMgM3 mg/m³, etohPpm ppm, eco2Ppm ppm, relativeIaq, sample, warmedUp}
            System.out.printf("IAQ %.1f  TVOC %.2f mg/m3  EtOH %.2f ppm  eCO2 %d ppm  %s%n", r.iaq, r.tvocMgM3,
                r.etohPpm, r.eco2Ppm, if (r.warmedUp) "ok" else "warming up")
        }
    }
}
