///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-kotlin:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.AD5251Full
import it.uhde.periph.chips.adc_dac.AD5251Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, AD5251Minimal.DEFAULT_ADDRESS).use { connection ->  // open I²C bus, device address, (bus, address=0x2C) → I2CConnection
        val pot = AD5251Full(connection)                               // construct driver, (connection, rAb=50000.0 Ω, rWiper=75.0 Ω) → AD5251Full
        val vdd = 3.3

        // --- Step channel 0 through divider ratios ---
        // With A at VDD and B at GND the wiper voltage is ratio × VDD; the code is
        // round(ratio × 64), so 0.999 lands on the top code 63.
        for (ratio in doubleArrayOf(0.0, 0.25, 0.5, 0.75, 0.999)) {
            pot.setRatio(0, ratio)                                                                  // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → Unit
            val code = pot.getWiper(0)                                                              // Read wiper position, (channel 0–1) → int 0–63
            val actual = pot.getRatio(0)                                                            // Read divider ratio, (channel 0–1) → double
            println("ratio %.3f -> code %3d (%.4f) -> %.3f V".format(ratio, code, actual, actual * vdd))
            Thread.sleep(500)
        }

        // --- Use channel 1 as a 20 kΩ rheostat ---
        // The 64 steps quantise the resistance (~781 Ω per code on a 50 kΩ part), so
        // the readback differs slightly from the request.
        pot.setResistance(1, 20000.0)                                                                // Set W–B resistance, (channel 0–1, ohms Ω) → Unit
        val r = pot.getResistance(1)                                                                // Read W–B resistance, (channel 0–1) → double Ω
        println("rheostat %.1f ohm, error %.1f ohm".format(r, r - 20000.0))

        // --- Step and scale the wiper with the quick commands ---
        // increment/decrement move one code, double/halve change the level by 6 dB.
        pot.setWiper(0, 16)                                                                         // Set wiper position, (channel 0–1, code 0–63) → Unit
        pot.increment(0)                                                                            // Quick command, (channel=both 0–1) → Unit
        println("increment -> ${pot.getWiper(0)}")
        pot.decrement(0)                                                                            // Quick command, (channel=both 0–1) → Unit
        println("decrement -> ${pot.getWiper(0)}")
        pot.doubleCode(0)                                                                           // Quick command, (channel=both 0–1) → Unit
        println("double -> ${pot.getWiper(0)}")
        pot.halve(0)                                                                                // Quick command, (channel=both 0–1) → Unit
        println("halve -> ${pot.getWiper(0)}")

        // --- Make the settings persistent ---
        // store() writes the wipers to EEMEM (≈ 26 ms, ACK polling); after a change,
        // restore() brings the stored values back.
        pot.store()                                                                                 // Store wipers in EEMEM, (channel=both 0–1) → Unit
        pot.setBoth(1, 2)                                                                           // Write both wipers, (code0 0–63, code1 0–63) → Unit
        pot.restore()                                                                               // Restore wipers from EEMEM, (channel=both 0–1) → Unit
        println("restored: ${pot.getWiper(0)} ${pot.getWiper(1)}")

        // --- User EEMEM bytes and the factory tolerance ---
        pot.writeEemem(4, 0x40)                                                                     // Write user EEMEM byte, (index 4–15, value 0–63) → Unit
        println("eemem 4: ${pot.readEemem(4)}")                                                     // Read user EEMEM byte, (index 4–15) → int 0–63
        println("tolerance %.3f %% RAB %.1f ohm".format(pot.readTolerance(0), pot.rabActual(0)))    // Read stored RAB tolerance, (channel 0–1) → double %; actual RAB, (channel 0–1) → double Ω

        // --- Restore the stored state ---
        // reset() restores both wipers from EEMEM and waits for the write cycle.
        pot.reset()                                                                                 // Reset from EEMEM, () → Unit
    }
}
