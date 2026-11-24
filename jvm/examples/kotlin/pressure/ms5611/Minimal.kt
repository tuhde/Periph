///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-kotlin:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Ms5611Minimal

fun main() {
    val bus  = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    val addr = System.getenv().getOrDefault("I2C_ADDR", "0x77").replaceFirst("^0[xX]", "").toInt(16)

    I2CConnection(bus, addr).use { connection ->
        val sensor = Ms5611Minimal(connection)                                                                      // Create MS5611 driver, (connection) → Ms5611Minimal
        sensor.init()                                                                                               // Reset, read PROM, verify CRC-4, () → void

        while (true) {
            val v = sensor.read()                                                                                   // Read temperature and pressure, () → double[] {°C, Pa}
            System.out.printf("%.2f C, %.1f Pa%n", v[0], v[1])
            Thread.sleep(1000L)
        }
    }
}
