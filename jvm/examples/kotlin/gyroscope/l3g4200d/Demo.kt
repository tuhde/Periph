///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.gyroscope.L3g4200dFull
import it.uhde.periph.chips.gyroscope.L3g4200dFull.Companion.FIFO_STREAM
import it.uhde.periph.chips.gyroscope.L3g4200dFull.Companion.FS_500_DPS
import it.uhde.periph.chips.gyroscope.L3g4200dFull.Companion.ODR_200_HZ

fun main() {
    I2CConnection(1, 0x68).use { connection ->                  // open I²C bus 1, device 0x68
        // --- Rotation detector: 200 Hz, ±500 dps, FIFO stream with watermark 10 ---
        // 200 Hz ODR gives 5 ms per sample — fast enough to catch hand motion
        // but not so noisy that the FIFO drains before the watermark is reached.
        val gyro = L3g4200dFull(connection)                       // construct driver, (connection) → L3g4200dFull
        gyro.configure(ODR_200_HZ, 0, FS_500_DPS)                  // configure chip, (odr=200Hz, bandwidth=0, fullScale=500dps) → Unit
        gyro.enableHighpass(0, 4)                                 // enable high-pass, (mode=0, cutoff=4) → Unit
                                                                    // cutoff index 4 at 200 Hz ODR ≈ 1 Hz; strips DC drift
        gyro.enableFifo(FIFO_STREAM, 10)                          // enable FIFO, (mode=stream=2, watermark=10) → Unit

        val threshold = (90.0 * Math.PI / 180.0).toFloat()
        var alerts = 0

        // --- Loop: wait for FIFO watermark, drain, compute mean, alert on threshold ---
        for (n in 0 until 50) {
            while (gyro.fifoSamples() < 10) {                     // read FIFO count, () → Int
                Thread.sleep(5)
            }
            val burst = gyro.readFifo()                            // drain FIFO, () → List<Triple<Float, Float, Float>>
            if (burst.isEmpty()) continue
            var mx = 0f; var my = 0f; var mz = 0f
            for (s in burst) { mx += s.first; my += s.second; mz += s.third }
            val count = burst.size
            mx /= count; my /= count; mz /= count
            if (kotlin.math.abs(mx) > threshold || kotlin.math.abs(my) > threshold || kotlin.math.abs(mz) > threshold) {
                alerts++
                println("ALERT  X=%.2f Y=%.2f Z=%.2f rad/s".format(mx, my, mz))
            } else {
                println("       X=%.2f Y=%.2f Z=%.2f rad/s".format(mx, my, mz))
            }
            Thread.sleep(20)
        }
        println("Total alerts: $alerts / 50")
    }
}
