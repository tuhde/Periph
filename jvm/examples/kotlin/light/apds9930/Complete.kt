///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Apds9930Full

fun main() {
    I2CConnection(1, 0x39).use { connection ->                         // Open I2C connection, (bus=1, address=0x39) → I2CConnection
        val apds = Apds9930Full(connection)                              // Create APDS-9930 Full driver, (connection) → Apds9930Full
        Thread.sleep(110)

        apds.configureAls(0xDB, 0, false)                                // Configure ALS, (atime=0xDB, again=0, agl=false) → Unit
        apds.configureProximity(8, 0, 0, false, 0xFF)                    // Configure proximity, (ppulse=8, pgain=0, pdrive=0, pdl=false, ptime=0xFF) → Unit
        apds.disableWait()                                               // Disable wait timer, () → Unit
        apds.setAlsThresholds(100, 60000, 1)                             // Set ALS thresholds, (low=100, high=60000, persistence=1) → Unit
        apds.setProximityThresholds(10, 200, 1)                          // Set proximity thresholds, (low=10, high=200, persistence=1) → Unit
        apds.setProximityOffset(0)                                       // Set proximity offset, (offset=0) → Unit
        apds.sleepAfterInterrupt(false)                                  // Configure SAI, (enable=false) → Unit

        repeat(10) {
            Thread.sleep(110)
            val lx = apds.lux()                                            // Read ambient illuminance, () → Float lx
            val p = apds.proximity()                                       // Read proximity count, () → Int count
            val c0 = apds.ch0()                                            // Read Ch0 raw, () → Int count
            val c1 = apds.ch1()                                            // Read Ch1 raw, () → Int count
            val st = apds.status()                                         // Read STATUS decoded, () → Status
            println("lux=%.1f lx  prox=%d  ch0=%d  ch1=%d  status=$st".format(lx, p, c0, c1))
        }
        apds.clearInterrupt(0)                                            // Clear interrupts, (channel=0) → Unit
    }
}