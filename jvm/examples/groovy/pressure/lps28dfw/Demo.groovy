///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Lps28dfwFull

def connection = new I2CConnection(1, (short) 0x5C)                     // open I²C bus 1, device 0x5C, (bus, address=0x5C) → I2CConnection
try {
    // --- High-resolution depth/altitude logger: Mode 1, 64-sample average, 25 Hz ---
    // 64× averaging achieves ~1.1 Pa rms noise; Mode 1 keeps full 0.244 Pa resolution.
    def sensor = new Lps28dfwFull(connection)                            // construct driver, (connection) → Lps28dfwFull
    sensor.configure(Lps28dfwFull.ODR_25_HZ, Lps28dfwFull.AVG_64, Lps28dfwFull.FS_MODE_1, true, Lps28dfwFull.LFPF_ODR_OVER_4)  // configure chip, (odr=25 Hz, avg=64, fs_mode=1, lpfEn=true, lpfCfg=ODR/4) → void

    // --- Sample every 500 ms for 30 s; report pressure, temperature, altitude ---
    // Sea-level reference uses the ISA standard (1013.25 hPa).
    int samples = 0
    for (int n = 0; n < 60; n++) {
        double[] vals = sensor.read()                                    // read both values, () → double[2] (hPa, °C)
        double p = vals[0]
        double t = vals[1]
        double alt = 44330.0 * (1.0 - Math.pow(p / 1013.25, 1.0 / 5.255))
        double elapsed = (n + 1) * 0.5
        System.out.printf("%.1fs  %.2f hPa  %.2f C  %.1f m%n", elapsed, p, t, alt)
        samples++
        Thread.sleep(500)
    }
    System.out.println("Total samples: " + samples)
} finally {
    connection.close()
}