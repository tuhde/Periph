///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Spl06Minimal

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x77").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Spl06Minimal(connection)                                                       // Create SPL06 driver, (connection) → Spl06Minimal
        sensor.init()                                                                               // Check ID, read coefficients, apply defaults, () → void
        
        while (true) {
            val m = sensor.read()                                                                   // Measure temperature and pressure, () → Measurement {temperatureC °C, pressurePa Pa}
            System.out.printf("%.2f Pa, %.2f C%n", m.pressurePa, m.temperatureC)
            Thread.sleep(1000)
        }
    }
}
