///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.1
//DEPS it.uhde:periph-kotlin:1.5.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.AD5251Full
import it.uhde.periph.chips.adc_dac.AD5251Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, AD5251Minimal.DEFAULT_ADDRESS).use { connection ->  // open I²C bus, device address, (bus, address=0x2C) → I2CConnection
        val pot = AD5251Full(connection)                               // construct driver, (connection, rAb=50000.0 Ω, rWiper=75.0 Ω) → AD5251Full
        pot.setWiper(0, 40)                                                                        // Set wiper position, (channel 0–1, code 0–63) → Unit
        // writes [instruction, code]; the instruction byte selects the channel
        println(pot.getWiper(0))                                                                    // Read wiper position, (channel 0–1) → int 0–63
        // sends the instruction alone, then reads one byte
        pot.setResistance(0, 25000.0)                                                                // Set W–B resistance, (channel 0–1, ohms Ω) → Unit
        // inverts R = D/64·RAB + RW to the nearest code
        println(pot.getResistance(0))                                                               // Read W–B resistance, (channel 0–1) → double Ω
        // evaluates D/64·RAB + RW for the current code
        pot.setBoth(8, 56)                                                                        // Write both wipers, (code0 0–63, code1 0–63) → Unit
        // one consecutive-write transaction (RDAC1 then RDAC3)
        pot.setRatio(0, 0.25)                                                                       // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → Unit
        // code = round(ratio × 64), clamped to 63
        println(pot.getRatio(0))                                                                    // Read divider ratio, (channel 0–1) → double
        // code / 64
        pot.setResistanceWa(1, 15000.0)                                                              // Set W–A resistance, (channel 0–1, ohms Ω) → Unit
        // rheostat between W and A with B open
        println(pot.getResistanceWa(1))                                                             // Read W–A resistance, (channel 0–1) → double Ω
        // (64 − D)/64·RAB + RW
        pot.increment(0)                                                                            // Step up one code, (channel=both 0–1) → Unit
        // quick command; omit the channel to address both
        pot.decrement(0)                                                                            // Step down one code, (channel=both 0–1) → Unit
        // quick command; saturates at 0
        pot.doubleCode(0)                                                                           // Raise by 6 dB, (channel=both 0–1) → Unit
        // quick command; the code doubles, saturating at 63
        pot.halve(0)                                                                                // Lower by 6 dB, (channel=both 0–1) → Unit
        // quick command; the code is halved
        pot.store()                                                                                 // Store wipers in EEMEM, (channel=both 0–1) → Unit
        // quick command, then ACK polling (≈ 26 ms)
        pot.setBoth(1, 2)
        pot.restore()                                                                               // Restore wipers from EEMEM, (channel=both 0–1) → Unit
        // quick command followed by NOP
        println(pot.getWiper(0))
        pot.writeEemem(4, 0x5A)                                                                     // Write user EEMEM byte, (index 4–15, value 0–63) → Unit
        // followed by ACK polling (≈ 26 ms)
        println(pot.readEemem(4))                                                                   // Read user EEMEM byte, (index 4–15) → int 0–63
        // pointer write, then a one-byte read
        println(pot.readTolerance(0))                                                               // Read stored RAB tolerance, (channel 0–1) → double %
        // sign-magnitude integer byte + decimal byte / 256
        println(pot.rabActual(0))                                                                   // Actual RAB of a channel, (channel 0–1) → double Ω
        // rAb × (1 + tolerance / 100)
        pot.nop()                                                                                   // Send NOP, () → Unit
        // quick command 0x80; the chip returns to idle
        pot.setRab(50000.0, 120.0)                                                                  // Change resistance model, (rAb Ω, rWiper Ω) → Unit
        // affects conversions only; no bus traffic
        pot.reset()                                                                                 // Reset from EEMEM, () → Unit
        // quick command 0xB8, then ACK polling
    }
}
