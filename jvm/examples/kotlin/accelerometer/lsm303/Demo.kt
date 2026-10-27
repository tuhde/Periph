///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.LSM303Full
import it.uhde.periph.chips.accelerometer.LSM303Minimal
import it.uhde.periph.chips.accelerometer.LSM303Source
import kotlin.math.atan2
import kotlin.math.sqrt

private val NAMES = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

fun main() {
    val bus = System.getenv().getOrDefault("I2C_BUS", "1").toInt()
    I2CConnection(bus, 0x19).use { accelConnection ->
    I2CConnection(bus, 0x1E).use { magConnection ->
        val chip = LSM303Full(accelConnection, magConnection)       // Create LSM303 Full driver, (accelConnection, magConnection) → void

        chip.setRange(2)                                            // Set accelerometer range, (rangeG) → void g
        chip.setMode(LSM303Minimal.MODE_HIGH_RESOLUTION)            // Set accelerometer mode, (mode) → void
        chip.setDataRate(50)                                        // Set accelerometer data rate, (rateHz) → void Hz
        chip.setMagRange(130)                                       // Set magnetometer range, (rangeUt) → void µT
        chip.setMagDataRate(15.0)                                   // Set magnetometer data rate, (rateHz) → void Hz
        chip.setClick(0, 0x04, 0.8, 20.0, 40.0, 200.0)              // Configure click detection, (...) → void
        chip.setFreeFall(1, 0.35, 60.0)                             // Configure free-fall, (...) → void
        chip.setLatch(1, true)                                      // Latch generator 1, (generator, enabled) → void
        chip.enableInterrupt(LSM303Source.SOURCE_CLICK, 1)          // Route source to INT1, (source, pin) → void
        chip.enableInterrupt(LSM303Source.SOURCE_IA1, 1)            // Route source to INT1, (source, pin) → void
        chip.setFifo(LSM303Full.FIFO_STREAM, 16, false)             // Configure FIFO, (mode, watermark, triggerInt2) → void

        val start = System.currentTimeMillis()
        var last = start
        while (System.currentTimeMillis() - start < 60000) {
            if (System.currentTimeMillis() - last >= 1000) {
                val level = chip.fifoLevel()                        // Read FIFO level, () → Int
                val samples = chip.readFifo(-1)                     // Drain FIFO, (count) → Array<DoubleArray>
                val a = chip.readAcceleration()                     // Read 3-axis acceleration, () → DoubleArray g
                var ax = a[0]; var ay = a[1]; var az = a[2]
                if (samples.isNotEmpty()) {
                    var sx = 0.0; var sy = 0.0; var sz = 0.0
                    for (s in samples) { sx += s[0]; sy += s[1]; sz += s[2] }
                    ax = sx / samples.size; ay = sy / samples.size; az = sz / samples.size
                }
                val m = chip.readMagnetic()                         // Read 3-axis magnetic field, () → DoubleArray µT
                val field = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
                val pitch = atan2(ax, sqrt(ay * ay + az * az)) * 180 / Math.PI
                val roll = atan2(ay, az) * 180 / Math.PI
                val hdg = chip.heading(true)                        // Compute compass heading, (tiltCompensate) → Double deg
                val name = NAMES[((hdg + 11.25) / 22.5).toInt() % 16]
                val temp = chip.readTemperatureDelta()              // Read relative temperature, () → Double °C
                println("%5.1f° %s  pitch=%+.1f roll=%+.1f |B|=%.1f µT  T=%+.1f °C  fifo=%d".format(hdg, name, pitch, roll, field, temp, level))
                val isr = chip.pollInterrupt()                      // Read interrupt status, () → Int
                if ((isr and LSM303Source.SOURCE_CLICK) != 0) println("DOUBLE TAP")
                if ((isr and LSM303Source.SOURCE_IA1) != 0) println("FREE FALL")
                last = System.currentTimeMillis()
            }
            Thread.sleep(10)
        }
        println("done")
    }
    }
}
