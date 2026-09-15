///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.gyroscope.L3g4200dFull

def connection = new I2CConnection(1, 0x68)                      // open I²C bus 1, device 0x68
try {
    def gyro = new L3g4200dFull(connection, false)                 // construct driver, verifies chip ID, (connection, spi=false) → L3g4200dFull
    def cid = gyro.whoAmI()                                        // read WHO_AM_I, () → int
                                                                    // returns 0xD3 for L3G4200D
    gyro.configure(L3g4200dFull.ODR_200_HZ, 0, L3g4200dFull.FS_500_DPS)  // configure chip, (odr, bandwidth, fullScale) → void
    gyro.enableAxes(true, true, true)                              // enable axes, (x, y, z) → void
    gyro.setFullScale(L3g4200dFull.FS_2000_DPS)                   // set full scale, (fullScale 250/500/2000) → void
    def ready = gyro.dataReady()                                   // check data ready, () → boolean
    def status = gyro.status()                                     // read STATUS, () → int
    def temp = gyro.temperature()                                  // read temperature, () → int
    gyro.enableHighpass(0, 4)                                      // enable high-pass, (mode, cutoff) → void
    gyro.disableHighpass()                                         // disable high-pass, () → void
    gyro.setInterrupt(true, false, true, false, true, false, false, true)  // configure INT1, (...) → void
    gyro.setThreshold('x' as char, 87.5f)                          // set X threshold, (axis, thresholdDps) → void
    gyro.setDuration(4, false)                                     // set INT1 duration, (samples, wait) → void
    gyro.setDataReadyPin(true)                                     // route DRDY to INT2, (enable) → void
    gyro.enableFifo(L3g4200dFull.FIFO_STREAM, 10)                  // enable FIFO, (mode, watermark) → void
    gyro.disableFifo()                                             // disable FIFO, () → void
    def samples = gyro.fifoSamples()                               // read FIFO count, () → int
    gyro.powerDown()                                               // enter power-down, () → void
    gyro.wakeUp()                                                  // wake from power-down, () → void
    gyro.sleep()                                                   // enter sleep mode, () → void
    def intSrc = gyro.readIntSource()                              // read & clear INT1_SRC, () → int
    def xyz = gyro.angularRate()                                   // read X/Y/Z angular rate, () → float[3] rad/s
    printf("X=%.2f Y=%.2f Z=%.2f rad/s, T=%d, ready=%b, status=0x%02X, fifo=%d, src=0x%02X, cid=0x%02X%n",
            xyz[0], xyz[1], xyz[2], temp, ready, status, samples, intSrc, cid)
} finally {
    connection.close()
}
