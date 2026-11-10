///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.AD5243Full

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, AD5243Full.DEFAULT_ADDRESS)    // open I²C bus, device address, (bus, address=0x2F) → I2CConnection
try {
    def pot = new AD5243Full(connection)                               // construct driver, (connection, rAb=10000.0 Ω, rWiper=160.0 Ω) → AD5243Full
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

    // --- Shut channel 1 down and bring it back ---
    // Shutdown opens A and shorts the wiper to B (about 0 Ω) while the RDAC keeps
    // its value, so resume restores the 2.2 kΩ setting.
    pot.shutdown(1)                                                                                 // Shut channel down, (channel 0–1) → void
    boolean sd = pot.isShutdown(1)                                                                  // Query shutdown state, (channel 0–1) → boolean
    println("shutdown: $sd")
    Thread.sleep(1000)
    pot.resume(1)                                                                                   // Leave shutdown, (channel 0–1) → void

    // --- Restore the power-up state ---
    // The chip has no reset pin, so reset() rewrites midscale to both channels.
    pot.reset()                                                                                     // Restore power-up preset, () → void
} finally {
    connection.close()
}
