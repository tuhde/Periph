///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.2
//DEPS it.uhde:periph-groovy:1.5.2

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.AD5252Full

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, AD5252Full.DEFAULT_ADDRESS)    // open I²C bus, device address, (bus, address=0x2C) → I2CConnection
try {
    def pot = new AD5252Full(connection)                               // construct driver, (connection, rAb=10000.0 Ω, rWiper=75.0 Ω) → AD5252Full
    pot.setWiper(0, 100)                                                                            // Set wiper position, (channel 0–1, code 0–255) → void
    // writes [instruction, code]; the instruction byte selects the channel
    println(pot.getWiper(0))                                                                        // Read wiper position, (channel 0–1) → int 0–255
    // sends the instruction alone, then reads one byte
    pot.setResistance(0, 5000.0d)                                                                   // Set W–B resistance, (channel 0–1, ohms Ω) → void
    // inverts R = D/256·RAB + RW to the nearest code
    println(pot.getResistance(0))                                                                   // Read W–B resistance, (channel 0–1) → double Ω
    // evaluates D/256·RAB + RW for the current code
    pot.setBoth(32, 224)                                                                            // Write both wipers, (code0 0–255, code1 0–255) → void
    // one consecutive-write transaction (RDAC1 then RDAC3)
    pot.setRatio(0, 0.25d)                                                                          // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → void
    // code = round(ratio × 256), clamped to 255
    println(pot.getRatio(0))                                                                        // Read divider ratio, (channel 0–1) → double
    // code / 256
    pot.setResistanceWa(1, 3000.0d)                                                                 // Set W–A resistance, (channel 0–1, ohms Ω) → void
    // rheostat between W and A with B open
    println(pot.getResistanceWa(1))                                                                 // Read W–A resistance, (channel 0–1) → double Ω
    // (256 − D)/256·RAB + RW
    pot.increment(0)                                                                                // Step up one code, (channel=both 0–1) → void
    // quick command; omit the channel to address both
    pot.decrement(0)                                                                                // Step down one code, (channel=both 0–1) → void
    // quick command; saturates at 0
    pot.doubleCode(0)                                                                               // Raise by 6 dB, (channel=both 0–1) → void
    // quick command; the code doubles, saturating at 255
    pot.halve(0)                                                                                    // Lower by 6 dB, (channel=both 0–1) → void
    // quick command; the code is halved
    pot.store()                                                                                     // Store wipers in EEMEM, (channel=both 0–1) → void
    // quick command, then ACK polling (≈ 26 ms)
    pot.setBoth(1, 2)
    pot.restore()                                                                                   // Restore wipers from EEMEM, (channel=both 0–1) → void
    // quick command followed by NOP
    println(pot.getWiper(0))
    pot.writeEemem(4, 0x5A)                                                                         // Write user EEMEM byte, (index 4–15, value 0–255) → void
    // followed by ACK polling (≈ 26 ms)
    println(pot.readEemem(4))                                                                       // Read user EEMEM byte, (index 4–15) → int 0–255
    // pointer write, then a one-byte read
    println(pot.readTolerance(0))                                                                   // Read stored RAB tolerance, (channel 0–1) → double %
    // sign-magnitude integer byte + decimal byte / 256
    println(pot.rabActual(0))                                                                       // Actual RAB of a channel, (channel 0–1) → double Ω
    // rAb × (1 + tolerance / 100)
    pot.nop()                                                                                       // Send NOP, () → void
    // quick command 0x80; the chip returns to idle
    pot.setRab(50000.0, 120.0)                                                                      // Change resistance model, (rAb Ω, rWiper Ω) → void
    // affects conversions only; no bus traffic
    pot.reset()                                                                                     // Reset from EEMEM, () → void
    // quick command 0xB8, then ACK polling
} finally {
    connection.close()
}
