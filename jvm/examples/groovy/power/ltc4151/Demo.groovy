///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-groovy:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Ltc4151Full

def connection = new I2CConnection(1, 0x6A)                 // open I²C bus 1, device 0x6A, (bus, address) → I2CConnection
try {
    def ltc = new Ltc4151Full(connection, 0.02d)            // construct driver, (connection, rSense=0.02 Ω) → Ltc4151Full

    // --- High-side supply monitor ---
    // Continuous scan: print every channel once per second for 20 s.
    20.times {
        def r = ltc.readAll()                               // consistent read of all channels, () → Ltc4151Full.Readings
        printf("%.2f V  %.2f mV  %.4f A  %.3f W  adin %.3f V%n",
                r.vin, r.senseV * 1000, r.current, r.power, r.adin)
        Thread.sleep(1000)
    }

    // --- Compare snapshot conversion times ---
    // SENSE needs about 67 ms, VIN and ADIN about 33 ms.
    ['SENSE', 'VIN', 'ADIN'].eachWithIndex { String name, int ch ->
        long t0 = System.nanoTime()
        double value = ltc.snapshot(ch)                     // one snapshot conversion, (channel 0–2) → double V
        printf("%s snapshot %.4f V in %d ms%n", name, value, (System.nanoTime() - t0).intdiv(1_000_000L))
    }
    println "snapshot mode afterwards ${ltc.isSnapshotMode()}"  // snapshot mode active, () → boolean

    // --- Toggle the stuck-bus timer and page read/write ---
    // The CONTROL register shows both bits cleared, then restored.
    ltc.setStuckBusTimer(false)                             // stuck-bus reset timer, (enabled) → void
    ltc.setPageRw(false)                                    // I²C page read/write, (enabled) → void
    printf("CONTROL (timer off, page off) 0x%02X%n", ltc.readControl())  // read CONTROL, () → int
    ltc.setStuckBusTimer(true)
    ltc.setPageRw(true)
    printf("CONTROL (restored) 0x%02X%n", ltc.readControl())

    // --- NTC temperature from ADIN ---
    // A made-up ADIN / VIN code pair run through the datasheet example formula.
    printf("temperature %.1f C%n", Ltc4151Full.temperatureFromAdin(900, 1200))  // NTC example circuit, (adinCode, vinCode) → double °C

    // --- Power tally ---
    // Sample the power 25 times at 200 ms (5 s) and print min, max and average.
    double pmin = Double.MAX_VALUE
    double pmax = 0d
    double psum = 0d
    25.times {
        double p = ltc.power()                              // read load power, () → double W
        pmin = Math.min(pmin, p)
        pmax = Math.max(pmax, p)
        psum += p
        Thread.sleep(200)
    }
    printf("power min %.3f W, max %.3f W, avg %.3f W%n", pmin, pmax, psum / 25)
} finally {
    connection.close()
}
