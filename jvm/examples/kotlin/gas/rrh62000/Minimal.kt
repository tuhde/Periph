///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.gas.Rrh62000Minimal

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x69").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Rrh62000Minimal(connection)                                                    // Create RRH62000 driver, (connection, accessIntervalMs=1000) → Rrh62000Minimal
        sensor.init()                                                                               // Wait for start-up, check firmware version, () → Unit

        while (true) {
            val r = sensor.read()                                                                   // Wait for a new sample, () → Rrh62000Measurement {pm1UgM3, pm2_5UgM3, pm10UgM3 µg/m³, temperatureC °C, humidityRh %RH, tvoc µg/m³, eco2Ppm ppm, iaq}
            System.out.printf("PM1 %.1f  PM2.5 %.1f  PM10 %.1f ug/m3  %.2f C  %.2f %%RH  TVOC %d ug/m3  eCO2 %d ppm  IAQ %.2f%n",
                r.pm1UgM3, r.pm2_5UgM3, r.pm10UgM3, r.temperatureC, r.humidityRh, r.tvoc, r.eco2Ppm, r.iaq)
        }
    }
}
