///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Ade7953Full

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toIntOrNull() ?: 1
    val addr = System.getenv("I2C_ADDR")?.removePrefix("0x")?.toIntOrNull(16) ?: 0x38

    I2CConnection(bus, addr).use { connection ->
        val ade = Ade7953Full(connection, 251.0, 30.0)                 // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V)

        // --- Prepare the chip: configure overcurrent threshold and let the
        //     chip's own IRQ pin alert on overcurrent ---
        ade.configureOvercurrent(40.0)                                    // Configure overcurrent, (threshold) → Unit

        // --- Sample at 1 Hz and emit one structured line per cycle ---
        // The energy accumulator resets on read by default (RSTREAD = 1), so
        // activeEnergy() returns watt-hours accumulated since the previous
        // call. Callers wanting a running total accumulate the returned deltas
        // themselves.
        println("%-10s %-10s %-10s %-12s".format("V", "A", "W", "Wh/s"))
        while (true) {
            val v = ade.voltage()                                        // Read bus voltage, () → Double V
            val i = ade.current()                                        // Read load current, () → Double A
            val p = ade.activePower()                                    // Read active power, () → Double W
            val e = ade.activeEnergy()                                   // Read active energy, () → Double Wh
            println("%-10.2f %-10.3f %-10.2f %-12.5f".format(v, i, p, e))
            Thread.sleep(1000)
        }
    }
}