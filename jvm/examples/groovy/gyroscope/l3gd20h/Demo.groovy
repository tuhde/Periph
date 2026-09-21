///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.gyroscope.L3gd20hFull

def bus = System.getenv("I2C_BUS") as int ?: 1
def addr = System.getenv("I2C_ADDR")?.replaceFirst("^0[xX]", "").toInteger(16) ?: 0x6A

def conn = new I2CConnection(bus, addr)
try {
    def gyro = new L3gd20hFull(conn)

    // --- Configure for shake detection at 190 Hz, ±500 dps ---
    gyro.configure(L3gd20hFull.ODR_190_HZ, 0, L3gd20hFull.FS_500_DPS)

    println "L3GD20H shake detector running. Shake the device..."

    while (true) {
        if (gyro.dataReady()) {                       // Check data ready
            def xyz = gyro.gyro()                     // Read angular rate
            def magnitude = Math.sqrt(xyz[0]*xyz[0] + xyz[1]*xyz[1] + xyz[2]*xyz[2])
            if (magnitude > 1.0) {
                println "SHAKE DETECTED: mag=${String.format("%.3f", magnitude)} (x=${String.format("%.3f", xyz[0])} y=${String.format("%.3f", xyz[1])} z=${String.format("%.3f", xyz[2])})"
            } else {
                println "x=${String.format("%.3f", xyz[0])} y=${String.format("%.3f", xyz[1])} z=${String.format("%.3f", xyz[2])} mag=${String.format("%.3f", magnitude)}"
            }
        }
    }
} finally {
    conn.close()
}