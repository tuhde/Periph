///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.gas.Ccs811Minimal

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x5A").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Ccs811Minimal(connection)                                                      // Create CCS811 driver, (connection) → Ccs811Minimal
        sensor.init()                                                                               // Check HW_ID, start application, mode 1, () → Unit

        while (true) {
            val m = sensor.read()                                                                   // Wait for the next sample, () → Measurement {eco2Ppm ppm, tvocPpb ppb}
            System.out.printf("eCO2 %d ppm, TVOC %d ppb%n", m.eco2Ppm, m.tvocPpb)
        }
    }
}
