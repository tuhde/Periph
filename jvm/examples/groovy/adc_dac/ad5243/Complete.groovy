///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-groovy:1.3.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.AD5243Full

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, AD5243Full.DEFAULT_ADDRESS)    // open I²C bus, device address, (bus, address=0x2F) → I2CConnection
try {
    def pot = new AD5243Full(connection)                               // construct driver, (connection, rAb=10000.0 Ω, rWiper=160.0 Ω) → AD5243Full
    pot.setWiper(0, 100)                                                                            // Set wiper position, (channel 0–1, code 0–255) → void
    // writes [instruction, code]; the instruction byte selects the channel
    println(pot.getWiper(0))                                                                        // Read wiper position, (channel 0–1) → int 0–255
    // sends the instruction alone, then reads one byte
    pot.setResistance(0, 5000.0d)                                                                   // Set W–B resistance, (channel 0–1, ohms Ω) → void
    // inverts R = D/256·RAB + 2·RW to the nearest code
    println(pot.getResistance(0))                                                                   // Read W–B resistance, (channel 0–1) → double Ω
    // evaluates D/256·RAB + 2·RW for the current code
    pot.setBoth(32, 224)                                                                            // Write both wipers, (code1 0–255, code2 0–255) → void
    // two transactions, channel 0 then channel 1
    pot.setRatio(0, 0.25d)                                                                          // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → void
    // code = round(ratio × 256), clamped to 255
    println(pot.getRatio(0))                                                                        // Read divider ratio, (channel 0–1) → double
    // code / 256
    pot.setResistanceWa(1, 3000.0d)                                                                 // Set W–A resistance, (channel 0–1, ohms Ω) → void
    // rheostat between W and A with B open
    println(pot.getResistanceWa(1))                                                                 // Read W–A resistance, (channel 0–1) → double Ω
    // (256 − D)/256·RAB + 2·RW
    println(pot.increment(0, 5))                                                                    // Step the wiper, (channel 0–1, steps) → int 0–255
    // read, add, clamp, write; returns the new code
    pot.shutdown(1)                                                                                 // Shut channel down, (channel 0–1) → void
    // SD = 1: A opens, wiper shorts to B, RDAC kept
    println(pot.isShutdown(1))                                                                      // Query shutdown state, (channel 0–1) → boolean
    // driver-tracked; the chip cannot report it
    Thread.sleep(1000)
    pot.resume(1)                                                                                   // Leave shutdown, (channel 0–1) → void
    // rewrites the kept code with SD = 0
    pot.setRab(50000.0d, 120.0d)                                                                    // Change resistance model, (rAb Ω, rWiper Ω) → void
    // affects conversions only; no bus traffic
    pot.reset()                                                                                     // Restore power-up preset, () → void
    // writes 0x80 to both channels with SD = 0
} finally {
    connection.close()
}
