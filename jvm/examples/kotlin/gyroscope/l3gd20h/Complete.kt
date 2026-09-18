///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.gyroscope.L3gd20hFull

fun main() {
    val bus = System.getenv("I2C_BUS")?.toIntOrNull() ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { it.replaceFirst("^0[xX]", "").toIntOrNull(16) } ?: 0x6A

    I2CConnection(bus, addr).use { conn ->
        val gyro = L3gd20hFull(conn)

        gyro.configure(L3gd20hFull.ODR_190_HZ, 0, L3gd20hFull.FS_500_DPS)  // Configure

        gyro.configureHpFilter(L3gd20hFull.HPM_NORMAL, 0)  // Configure HPF
        gyro.enableHpFilter(true)                           // Enable HPF

        gyro.configureFifo(L3gd20hFull.FIFO_FIFO, 10)       // Configure FIFO
        gyro.enableFifo(true)                                // Enable FIFO

        gyro.setPowerMode(L3gd20hFull.POWER_NORMAL)         // Set power mode

        val who = gyro.whoAmI()                             // Read WHO_AM_I
        println("WHO_AM_I: 0x${who.toString(16).uppercase()}")

        val temp = gyro.temperature()                       // Read temperature
        println("Temperature: $temp")

        while (true) {
            if (gyro.dataReady()) {                         // Check data ready
                val xyz = gyro.gyro()                       // Read angular rate
                println("x=${xyz[0]:.3f} y=${xyz[1]:.3f} z=${xyz[2]:.3f} rad/s")

                val raw = gyro.gyroRaw()                    // Read raw

                val level = gyro.fifoLevel()                // FIFO level
                if (level > 0) {
                    val samples = gyro.readFifo()           // Read FIFO
                    println("FIFO: ${samples.size} samples")
                }
            }
            Thread.sleep(10)
        }
    }
}