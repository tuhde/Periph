///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.gyroscope.L3g4200dFull
import it.uhde.periph.chips.gyroscope.L3g4200dFull.Companion.FIFO_STREAM
import it.uhde.periph.chips.gyroscope.L3g4200dFull.Companion.FS_2000_DPS
import it.uhde.periph.chips.gyroscope.L3g4200dFull.Companion.FS_500_DPS
import it.uhde.periph.chips.gyroscope.L3g4200dFull.Companion.ODR_200_HZ

fun main() {
    I2CConnection(1, 0x68).use { connection ->                  // open I²C bus 1, device 0x68
        val gyro = L3g4200dFull(connection)                       // construct driver, (connection) → L3g4200dFull
        val cid = gyro.whoAmI()                                    // read WHO_AM_I, () → Int
                                                                    // returns 0xD3 for L3G4200D
        gyro.configure(ODR_200_HZ, 0, FS_500_DPS)                  // configure chip, (odr, bandwidth, fullScale) → Unit
        gyro.enableAxes(true, true, true)                         // enable axes, (x, y, z) → Unit
        gyro.setFullScale(FS_2000_DPS)                            // set full scale, (fullScale 250/500/2000) → Unit
        val ready = gyro.dataReady()                               // check data ready, () → Boolean
        val status = gyro.status()                                 // read STATUS, () → Int
        val temp = gyro.temperature()                              // read temperature, () → Int
        gyro.enableHighpass(0, 4)                                 // enable high-pass, (mode 0-3, cutoff 0-9) → Unit
        gyro.disableHighpass()                                     // disable high-pass, () → Unit
        gyro.setInterrupt(xHigh = true, yHigh = true, zHigh = true, latch = true)  // configure INT1, (...) → Unit
        gyro.setThreshold('x', 87.5f)                            // set X threshold, (axis, thresholdDps) → Unit
        gyro.setDuration(4, false)                                // set INT1 duration, (samples, wait) → Unit
        gyro.setDataReadyPin(true)                                // route DRDY to INT2, (enable) → Unit
        gyro.enableFifo(FIFO_STREAM, 10)                          // enable FIFO, (mode 0-4, watermark) → Unit
        gyro.disableFifo()                                         // disable FIFO, () → Unit
        val samples = gyro.fifoSamples()                          // read FIFO count, () → Int
        gyro.powerDown()                                          // enter power-down, () → Unit
        gyro.wakeUp()                                              // wake from power-down, () → Unit
        gyro.sleep()                                               // enter sleep mode, () → Unit
        val intSrc = gyro.readIntSource()                          // read & clear INT1_SRC, () → Int
        val (x, y, z) = gyro.angularRate()                        // read X/Y/Z angular rate, () → Triple<Float, Float, Float> rad/s
        println("X=%.2f Y=%.2f Z=%.2f rad/s, T=%d, ready=%b, status=0x%02X, fifo=%d, src=0x%02X, cid=0x%02X"
                .format(x, y, z, temp, ready, status, samples, intSrc, cid))
    }
}
