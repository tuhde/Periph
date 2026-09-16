///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.magnetometer.Hmc5883lFull

/**
 * Electronic compass demo: reads magnetic field from HMC5883L at 15 Hz,
 * computes heading from X/Y axes using atan2(y, x), prints compass bearing (0–360°),
 * and warns if sensor is held vertically (|Z| > 30 µT) indicating tilt compensation needed.
 */
def connection = new I2CConnection(1, 0x1E)
def hmc5883l = new Hmc5883lFull(connection)

try {
    // --- Configure for electronic compass ---
    // 8-sample averaging at 15 Hz suppresses noise; ±1.3 Ga gain covers Earth's field (~0.5 Ga).
    hmc5883l.configure(15, 8, 1)

    println "Electronic compass demo — hold sensor flat, rotate horizontally"
    println "Vertical mount warning: |Z| > 30 µT indicates tilt compensation needed"
    println()

    // --- Sample and compute heading ---
    // User rotates the sensor horizontally; we compute heading from X/Y axes.
    // At n=5, user is prompted to tilt vertically to demonstrate Z-axis detection.
    10.times { n ->
        while (!hmc5883l.dataReady()) {
            Thread.sleep(1)
        }
        def (x, y, z) = hmc5883l.magneticField()

        // --- Compute heading from X and Y ---
        if (x != null && y != null) {
            def heading = Math.toDegrees(Math.atan2(y, x))
            if (heading < 0) heading += 360
            printf "Heading: %.1f°\n", heading
        }

        // --- Vertical mount detection ---
        if (z != null && Math.abs(z) > 30e-6) {
            printf "[TILT WARNING] Z=%.1f µT — tilt compensation needed\n", z * 1e6
        }

        if (n == 4) {
            println ">>> Now tilt sensor vertically <<<"
        }

        Thread.sleep(500)
    }
} finally {
    connection.close()
}