///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.AXP2101Minimal

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toIntOrNull() ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.parseInt(it.replaceFirst("^0[xX]".toRegex(), ""), 16) } ?: 0x34
    I2CConnection(bus, addr).use { connection ->
        val chip = AXP2101Minimal(connection)               // Create AXP2101 driver, (connection)
        repeat(10) {
            val v = chip.batteryVoltage()                    // Read battery voltage, () → Double V
            val pct = chip.batteryPercent()                  // Read state of charge, () → Int %
            val state = chip.chargeState()                   // Read charge state, () → Int (0 trickle … 5 none)
            val vbus = chip.vbusPresent()                    // Check input source, () → Boolean
            println("%.3f V  %d %%  state=%d  vbus=%b".format(v, pct, state, vbus))
            Thread.sleep(1000)
        }
    }
}
