///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.gyroscope.L3gd20hMinimal

def bus = System.getenv("I2C_BUS") as int ?: 1
def addr = System.getenv("I2C_ADDR")?.replaceFirst("^0[xX]", "").toInteger(16) ?: 0x6A

def conn = new I2CConnection(bus, addr)
try {
    def gyro = new L3gd20hMinimal(conn)

    while (true) {
        def xyz = gyro.gyro()  // Read angular rate, () -> float[] rad/s
        println "x=${String.format("%.3f", xyz[0])} y=${String.format("%.3f", xyz[1])} z=${String.format("%.3f", xyz[2])} rad/s"
        Thread.sleep(100)
    }
} finally {
    conn.close()
}