///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Ade7953Minimal

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toIntOrNull() ?: 1
    val addr = System.getenv("I2C_ADDR")?.removePrefix("0x")?.toIntOrNull(16) ?: 0x38

    I2CConnection(bus, addr).use { connection ->
        val ade = Ade7953Minimal(connection, 251.0, 30.0)              // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V)
        while (true) {
            val v = ade.voltage()                                      // Read bus voltage, () → Double V
            val i = ade.current()                                      // Read load current, () → Double A
            val p = ade.activePower()                                  // Read active power, () → Double W
            val e = ade.activeEnergy()                                 // Read active energy, () → Double Wh
            println("V=%.2f I=%.3f P=%.2f E=%.4f".format(v, i, p, e))
            Thread.sleep(1000)
        }
    }
}