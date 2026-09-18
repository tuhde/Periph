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

    gyro.configure(L3gd20hFull.ODR_190_HZ, 0, L3gd20hFull.FS_500_DPS)  // Configure

    gyro.configureHpFilter(L3gd20hFull.HPM_NORMAL, 0)  // Configure HPF
    gyro.enableHpFilter(true)                           // Enable HPF

    gyro.configureFifo(L3gd20hFull.FIFO_FIFO, 10)       // Configure FIFO
    gyro.enableFifo(true)                                // Enable FIFO

    gyro.setPowerMode(L3gd20hFull.POWER_NORMAL)         // Set power mode

    def who = gyro.whoAmI()                             // Read WHO_AM_I
    println "WHO_AM_I: 0x${Integer.toHexString(who).toUpperCase()}"

    def temp = gyro.temperature()                       // Read temperature
    println "Temperature: $temp"

    while (true) {
        if (gyro.dataReady()) {                         // Check data ready
            def xyz = gyro.gyro()                       // Read angular rate
            println "x=${String.format("%.3f", xyz[0])} y=${String.format("%.3f", xyz[1])} z=${String.format("%.3f", xyz[2])} rad/s"

            def raw = gyro.gyroRaw()                    // Read raw

            def level = gyro.fifoLevel()                // FIFO level
            if (level > 0) {
                def samples = gyro.readFifo()           // Read FIFO
                println "FIFO: ${samples.size} samples"
            }
        }
        Thread.sleep(10)
    }
} finally {
    conn.close()
}