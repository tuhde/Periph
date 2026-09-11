///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.Adxl345Minimal

def bus  = System.getenv("I2C_BUS")  ? Integer.parseInt(System.getenv("I2C_BUS")) : 1
def addr = System.getenv("I2C_ADDR") ? Integer.parseInt(System.getenv("I2C_ADDR").replaceFirst("^0[xX]", ""), 16) : 0x53

def connection = new I2CConnection(bus, addr)
try {
    def accel = new Adxl345Minimal(connection)                         // construct driver and verify DEVID, (connection) → Adxl345Minimal

    // --- 50-sample stationary tilt characterization at 10 Hz ---
    // With the sensor flat and the Z axis up, gravity should project entirely
    // onto Z. Tilting the board visibly redistributes the 1 *g* magnitude
    // across X and Y; the total vector magnitude stays near 1 *g*.
    def samples = 50
    def magMin = Double.POSITIVE_INFINITY
    def magMax = Double.NEGATIVE_INFINITY

    (0..<samples).each { n ->
        def xyz = accel.read()                                        // Read 3-axis acceleration, () → double[] g, g, g
        def mag = Math.sqrt(xyz[0] * xyz[0] + xyz[1] * xyz[1] + xyz[2] * xyz[2])
        if (mag < magMin) magMin = mag
        if (mag > magMax) magMax = mag
        println(String.format("%2d  x=%+.3f  y=%+.3f  z=%+.3f  |a|=%.3f g", n, xyz[0], xyz[1], xyz[2], mag))
        Thread.sleep(100)
    }

    println(String.format("min |a|=%.3f g  max |a|=%.3f g", magMin, magMax))
} finally {
    connection.close()
}