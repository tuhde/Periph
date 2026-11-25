///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-groovy:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.AD5252Full

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, AD5252Full.DEFAULT_ADDRESS)    // open I²C bus, device address, (bus, address=0x2C) → I2CConnection
try {
    def pot = new AD5252Full(connection)                               // construct driver, (connection, rAb=10000.0 Ω, rWiper=75.0 Ω) → AD5252Full
    final double vdd = 3.3d

    // --- Step channel 0 through divider ratios ---
    // With A at VDD and B at GND the wiper voltage is ratio × VDD; the code is
    // round(ratio × 256), so 0.999 lands on the top code 255.
    for (double ratio : [0.0d, 0.25d, 0.5d, 0.75d, 0.999d]) {
        pot.setRatio(0, ratio)                                                                      // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → void
        int code = pot.getWiper(0)                                                                  // Read wiper position, (channel 0–1) → int 0–255
        double actual = pot.getRatio(0)                                                             // Read divider ratio, (channel 0–1) → double
        println(String.format("ratio %.3f -> code %3d (%.4f) -> %.3f V", ratio, code, actual, actual * vdd))
        Thread.sleep(500)
    }

    // --- Use channel 1 as a 2.2 kΩ rheostat ---
    // The 256 steps quantise the resistance (~39 Ω per code on a 10 kΩ part), so
    // the readback differs slightly from the request.
    pot.setResistance(1, 2200.0d)                                                                   // Set W–B resistance, (channel 0–1, ohms Ω) → void
    double r = pot.getResistance(1)                                                                 // Read W–B resistance, (channel 0–1) → double Ω
    println(String.format("rheostat %.1f ohm, error %.1f ohm", r, r - 2200.0d))

    // --- Step and scale the wiper with the quick commands ---
    // increment/decrement move one code, double/halve change the level by 6 dB.
    pot.setWiper(0, 64)                                                                             // Set wiper position, (channel 0–1, code 0–255) → void
    pot.increment(0)                                                                                // Quick command, (channel=both 0–1) → void
    println("increment -> ${pot.getWiper(0)}")
    pot.decrement(0)                                                                                // Quick command, (channel=both 0–1) → void
    println("decrement -> ${pot.getWiper(0)}")
    pot.doubleCode(0)                                                                               // Quick command, (channel=both 0–1) → void
    println("double -> ${pot.getWiper(0)}")
    pot.halve(0)                                                                                    // Quick command, (channel=both 0–1) → void
    println("halve -> ${pot.getWiper(0)}")

    // --- Make the settings persistent ---
    // store() writes the wipers to EEMEM (≈ 26 ms, ACK polling); after a change,
    // restore() brings the stored values back.
    pot.store()                                                                                     // Store wipers in EEMEM, (channel=both 0–1) → void
    pot.setBoth(1, 2)                                                                               // Write both wipers, (code0 0–255, code1 0–255) → void
    pot.restore()                                                                                   // Restore wipers from EEMEM, (channel=both 0–1) → void
    println("restored: ${pot.getWiper(0)} ${pot.getWiper(1)}")

    // --- User EEMEM bytes and the factory tolerance ---
    pot.writeEemem(4, 0x40)                                                                         // Write user EEMEM byte, (index 4–15, value 0–255) → void
    println("eemem 4: ${pot.readEemem(4)}")                                                         // Read user EEMEM byte, (index 4–15) → int 0–255
    println(String.format("tolerance %.3f %% RAB %.1f ohm", pot.readTolerance(0), pot.rabActual(0)))// Read stored RAB tolerance, (channel 0–1) → double %; actual RAB, (channel 0–1) → double Ω

    // --- Restore the stored state ---
    // reset() restores both wipers from EEMEM and waits for the write cycle.
    pot.reset()                                                                                     // Reset from EEMEM, () → void
} finally {
    connection.close()
}
