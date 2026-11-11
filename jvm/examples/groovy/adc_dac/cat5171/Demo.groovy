///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-groovy:1.4.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.adc_dac.CAT5171Full

def bus = System.getenv("I2C_BUS") ? Integer.parseInt(System.getenv("I2C_BUS")) : 1

def connection = new I2CConnection(bus, CAT5171Full.DEFAULT_ADDRESS)                                // open I²C bus, device address, (bus, address=0x2C) → I2CConnection
try {
    def pot = new CAT5171Full(connection)                                                           // construct driver, (connection, rAb=100000.0 Ω, rWiper=50.0 Ω) → CAT5171Full
    final double vdd = 3.3d

    // --- Sweep the wiper across the full range ---
    // Zero scale is not 0 Ω (it is the wiper resistance) and code 255 sits one
    // LSB below the RAB end.
    for (int code = 0; code < 256; code += 16) {
        pot.setWiper(code)                                                                          // Set wiper position, (code 0–255) → void
        double r = pot.getResistance()                                                              // Read W–B resistance, () → double Ω
        println("code ${code} -> ${r} Ω")
        Thread.sleep(200)
    }

    // --- Set a 22 kΩ rheostat ---
    // The 256 steps quantise the resistance (~390 Ω per code on a 100 kΩ part),
    // so the readback differs slightly from the request.
    pot.setResistance(22000.0d)                                                                     // Set W–B resistance, (ohms Ω) → void
    double rheo = pot.getResistance()                                                               // Read W–B resistance, () → double Ω
    println("rheostat ${rheo} Ω, error ${rheo - 22000.0d} Ω")

    // --- Shutdown, resume, midscale and reset ---
    // Shutdown opens A and shorts the wiper to B while the WCR keeps its value,
    // so resume restores the setting; midscale and reset both return to 0x80.
    int kept = pot.getWiper()                                                                       // Read wiper position, () → int 0–255
    pot.shutdown()                                                                                  // Shut down, () → void
    boolean sd = pot.isShutdown()                                                                   // Query shutdown state, () → boolean
    println("shutdown: ${sd} kept ${pot.getWiper() == kept}")
    Thread.sleep(1000)
    pot.resume()                                                                                    // Leave shutdown, () → void
    pot.midscale()                                                                                  // Move wiper to midscale, () → void
    pot.reset()                                                                                     // Midscale reset, () → void

    // --- Use the wiper as a voltage divider ---
    // With A at VDD and B at GND the wiper voltage is ratio × VDD; the code is
    // round(ratio × 256), so 0.999 lands on the top code 255.
    for (double ratio : [0.0d, 0.25d, 0.5d, 0.75d, 0.999d]) {
        pot.setRatio(ratio)                                                                         // Set divider ratio, (ratio 0.0–1.0) → void
        double actual = pot.getRatio()                                                              // Read divider ratio, () → double
        println("ratio ${ratio} -> ${actual} -> ${actual * vdd} V")
        Thread.sleep(500)
    }
} finally {
    connection.close()
}
