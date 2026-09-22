///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-groovy:1.2.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.gyroscope.L3g4200dFull

def connection = new I2CConnection(1, 0x68)                      // open I²C bus 1, device 0x68
try {
    // --- Rotation detector: 200 Hz, ±500 dps, FIFO stream with watermark 10 ---
    def gyro = new L3g4200dFull(connection, false)                 // construct driver, verifies chip ID, (connection, spi=false) → L3g4200dFull
    gyro.configure(L3g4200dFull.ODR_200_HZ, 0, L3g4200dFull.FS_500_DPS)  // configure chip, (odr, bandwidth, fullScale) → void
    gyro.enableHighpass(0, 4)                                      // enable high-pass, (mode=0, cutoff=4) → void
                                                                    // cutoff index 4 at 200 Hz ODR ≈ 1 Hz; strips DC drift
    gyro.enableFifo(L3g4200dFull.FIFO_STREAM, 10)                  // enable FIFO, (mode=stream=2, watermark=10) → void

    def threshold = 90.0 * Math.PI / 180.0
    def alerts = 0

    // --- Loop: wait for FIFO watermark, drain, compute mean, alert on threshold ---
    50.times {
        while (gyro.fifoSamples() < 10) {                          // read FIFO count, () → int
            Thread.sleep(5)
        }
        def burst = gyro.readFifo()                               // drain FIFO, () → List<float[]>
        if (burst.isEmpty()) return
        def mx = 0f; def my = 0f; def mz = 0f
        burst.each { s -> mx += s[0]; my += s[1]; mz += s[2] }
        def count = burst.size()
        mx /= count; my /= count; mz /= count
        if (Math.abs(mx) > threshold || Math.abs(my) > threshold || Math.abs(mz) > threshold) {
            alerts++
            printf("ALERT  X=%.2f Y=%.2f Z=%.2f rad/s%n", mx, my, mz)
        } else {
            printf("       X=%.2f Y=%.2f Z=%.2f rad/s%n", mx, my, mz)
        }
        Thread.sleep(20)
    }
    printf("Total alerts: %d / 50%n", alerts)
} finally {
    connection.close()
}
