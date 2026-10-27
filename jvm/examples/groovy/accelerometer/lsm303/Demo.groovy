///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.LSM303Full
import it.uhde.periph.chips.accelerometer.LSM303Minimal
import it.uhde.periph.chips.accelerometer.LSM303Source

def NAMES = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
             "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"] as String[]

def bus = System.getenv().getOrDefault("I2C_BUS", "1") as int
def accelConn = new I2CConnection(bus, 0x19)
def magConn = new I2CConnection(bus, 0x1E)
try {
    def chip = new LSM303Full(accelConn, magConn)               // Create LSM303 Full driver, (accelConnection, magConnection) → void

    chip.setRange(2)                                            // Set accelerometer range, (rangeG) → void g
    chip.setMode(LSM303Minimal.MODE_HIGH_RESOLUTION)            // Set accelerometer mode, (mode) → void
    chip.setDataRate(50)                                        // Set accelerometer data rate, (rateHz) → void Hz
    chip.setMagRange(130)                                       // Set magnetometer range, (rangeUt) → void µT
    chip.setMagDataRate(15d)                                    // Set magnetometer data rate, (rateHz) → void Hz
    chip.setClick(0, 0x04, 0.8d, 20d, 40d, 200d)               // Configure click detection, (...) → void
    chip.setFreeFall(1, 0.35d, 60d)                             // Configure free-fall, (...) → void
    chip.setLatch(1, true)                                      // Latch generator 1, (generator, enabled) → void
    chip.enableInterrupt(LSM303Source.SOURCE_CLICK, 1)          // Route source to INT1, (source, pin) → void
    chip.enableInterrupt(LSM303Source.SOURCE_IA1, 1)            // Route source to INT1, (source, pin) → void
    chip.setFifo(LSM303Full.FIFO_STREAM, 16, false)             // Configure FIFO, (mode, watermark, triggerInt2) → void

    def start = System.currentTimeMillis()
    def last = start
    while (System.currentTimeMillis() - start < 60000) {
        if (System.currentTimeMillis() - last >= 1000) {
            def level = chip.fifoLevel()                        // Read FIFO level, () → int
            def samples = chip.readFifo(-1)                     // Drain FIFO, (count) → double[][]
            def a = chip.readAcceleration()                     // Read 3-axis acceleration, () → double[] g
            double ax = a[0], ay = a[1], az = a[2]
            if (samples.length > 0) {
                double sx = 0, sy = 0, sz = 0
                for (s in samples) { sx += s[0]; sy += s[1]; sz += s[2] }
                ax = sx / samples.length; ay = sy / samples.length; az = sz / samples.length
            }
            def m = chip.readMagnetic()                         // Read 3-axis magnetic field, () → double[] µT
            def field = Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
            def pitch = Math.atan2(ax, Math.sqrt(ay * ay + az * az)) * 180 / Math.PI
            def roll = Math.atan2(ay, az) * 180 / Math.PI
            def hdg = chip.heading(true)                        // Compute compass heading, (tiltCompensate) → double deg
            def name = NAMES[((hdg + 11.25) / 22.5) as int % 16]
            def temp = chip.readTemperatureDelta()              // Read relative temperature, () → double °C
            printf("%5.1f° %s  pitch=%+.1f roll=%+.1f |B|=%.1f µT  T=%+.1f °C  fifo=%d%n", hdg, name, pitch, roll, field, temp, level)
            def isr = chip.pollInterrupt()                      // Read interrupt status, () → int
            if ((isr & LSM303Source.SOURCE_CLICK) != 0) println("DOUBLE TAP")
            if ((isr & LSM303Source.SOURCE_IA1) != 0) println("FREE FALL")
            last = System.currentTimeMillis()
        }
        Thread.sleep(10)
    }
    println("done")
} finally {
    accelConn.close()
    magConn.close()
}
