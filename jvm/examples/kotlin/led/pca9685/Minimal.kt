///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.2
//DEPS it.uhde:periph-kotlin:1.5.2

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.led.Pca9685Minimal

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt()  ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.decode(it) } ?: 0x40
    I2CConnection(bus, addr).use { connection ->                       // open I²C bus, (bus, address=0x40) → I2CConnection
        val pwm = Pca9685Minimal(connection)   // construct driver at 1 kHz, (connection) → Pca9685Minimal
        for (step in 0..10) {
            pwm.setDuty(0, step / 10.0)   // set one channel's duty cycle, (channel 0–15, duty 0.0–1.0) → void
            Thread.sleep(200)
        }
        pwm.setDuty(0, 0.0)
    }
}
