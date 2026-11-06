///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-kotlin:1.3.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.AD5248Full

fun main() {
    I2CConnection(1, 0x2C).use { connection ->                   // open I²C bus 1, device 0x2C, (bus, address) → I2CConnection
        val pot = AD5248Full(connection, 10000.0, 160.0)         // construct driver, (connection, rAb=10000 Ω, rWiper=160 Ω) → AD5248Full

        // --- Wiper position ---
        pot.setWiper(0, 100)                                     // set wiper, (channel 0-1, code 0-255) → void
                                                                 // writes [channel << 7, code]; code clamped to 0-255
        val code = pot.getWiper(0)                               // read wiper, (channel 0-1) → int 0–255
                                                                 // sends the instruction byte alone, then reads one byte
        println("code=%d".format(code))
        pot.setBoth(10, 20)                                      // set both wipers, (code1 0-255, code2 0-255) → void
                                                                 // two transactions, one per channel
        // --- Resistance ---
        pot.setResistance(1, 2200.0)                             // set W-B resistance, (channel 0-1, ohms) → void
                                                                 // nearest code of R = code/256*RAB + 2*RW, clamped
        val ohms = pot.getResistance(1)                          // read W-B resistance, (channel 0-1) → double Ω
                                                                 // code/256*RAB + 2*RW
        println("ch1 ohm=%.1f".format(ohms))
        // --- Ratio and increment ---
        pot.setRatio(0, 0.5)                                     // set wiper ratio, (channel 0-1, ratio 0.0-1.0) → void
                                                                 // code = round(ratio*256) clamped to 255; needs terminal A wired
        val ratio = pot.getRatio(0)                              // read wiper ratio, (channel 0-1) → double 0.0–1.0
                                                                 // code / 256
        println("ratio=%.3f".format(ratio))
        val next = pot.increment(0, 5)                           // step wiper, (channel 0-1, steps) → int 0–255
                                                                 // read-modify-write, clamped to 0-255; negative steps decrement
        println("next=%d".format(next))
        // --- Shutdown ---
        pot.shutdown(1)                                          // shut channel down, (channel 0-1) → void
                                                                 // opens terminal A, shorts wiper to B, RDAC kept
        val down = pot.isShutdown(1)                             // query driver-tracked state, (channel 0-1) → boolean
                                                                 // the chip cannot be read back
        println("shutdown=%s".format(down))
        pot.resume(1)                                            // leave shutdown, (channel 0-1) → void
                                                                 // rewrites the RDAC with SD = 0
        // --- Model and reset ---
        pot.setRab(10000.0, 160.0)                               // set resistance model, (rAb Ω, rWiper Ω) → void
                                                                 // no I²C traffic; affects conversions only
        pot.reset()                                              // reset to power-up preset, () → void
                                                                 // writes midscale 0x80 to both channels with SD = 0
    }
}
