///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Apds9930Full

I2CConnection conn = new I2CConnection(1, 0x39)                          // Open I2C connection, (bus=1, address=0x39) → I2CConnection
try {
    def apds = new Apds9930Full(conn)                                     // Create APDS-9930 Full driver, (connection) → Apds9930Full
                                                                           // exposes ALS and proximity configuration methods
    Thread.sleep(110)

    apds.configureAls(0xDB, 0, false)                                     // Configure ALS, (atime=0xDB, again=0, agl=false) → void
                                                                           // sets ALS integration time to 101 ms with 1x gain
    apds.configureProximity(8, 0, 0, false, 0xFF)                         // Configure proximity, (ppulse=8, pgain=0, pdrive=0, pdl=false, ptime=0xFF) → void
                                                                           // 8 LED pulses at 100 mA, 1x gain, no reduced drive
    apds.disableWait()                                                    // Disable wait timer, () → void
                                                                           // clears WEN in ENABLE
    apds.setAlsThresholds(100, 60000, 1)                                  // Set ALS thresholds, (low=100, high=60000, persistence=1) → void
                                                                           // fires after 1 consecutive out-of-range Ch0 count
    apds.setProximityThresholds(10, 200, 1)                               // Set proximity thresholds, (low=10, high=200, persistence=1) → void
                                                                           // fires on a single proximity reading outside [10, 200]
    apds.setProximityOffset(0)                                            // Set proximity offset, (offset=0) → void
                                                                           // clears any prior offset
    apds.sleepAfterInterrupt(false)                                       // Configure SAI, (enable=false) → void
                                                                           // chip stays in normal operation after an interrupt

    10.times {
        Thread.sleep(110)
        float lx = apds.lux()                                             // Read ambient illuminance, () → float lx
                                                                           // combines Ch0 and Ch1 with IR-compensation coefficients
        int p = apds.proximity()                                          // Read proximity count, () → int count
                                                                           // 16-bit ADC value
        int c0 = apds.ch0()                                               // Read Ch0 raw, () → int count
                                                                           // 16-bit ADC value of visible + IR channel
        int c1 = apds.ch1()                                               // Read Ch1 raw, () → int count
                                                                           // 16-bit ADC value of IR-only channel
        def st = apds.status()                                            // Read STATUS decoded, () → Status
        printf("lux=%.1f lx  prox=%d  ch0=%d  ch1=%d  status=%s%n", lx, p, c0, c1, st.toString())
    }
    apds.clearInterrupt(0)                                                // Clear interrupts, (channel=0) → void
                                                                           // 0=both, 1=ALS, 2=proximity — issues special-function command
} finally {
    conn.close()
}