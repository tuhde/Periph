///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.other.Mpr121Minimal

I2CConnection conn = new I2CConnection(1, 0x5A)                          // Open I2C connection, (bus=1, address=0x5A) → I2CConnection
try {
    def mpr = new Mpr121Minimal(conn)                                     // Create MPR121 driver, (connection) → Mpr121Minimal
                                                                           // resets, applies default thresholds (T=12, R=6), enters Run Mode on all 12 electrodes
    10.times {
        int t = mpr.touched()                                             // Read 12-bit touch bitmask, () → int bitmask
                                                                           // bit n=1 means ELEn is currently touched
        printf("touched=0x%03X%n", t)
        Thread.sleep(1000)
    }
} finally {
    conn.close()
}
