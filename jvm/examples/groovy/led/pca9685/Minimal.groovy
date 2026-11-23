///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.1
//DEPS it.uhde:periph-groovy:1.5.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.led.Pca9685Minimal

int bus  = System.getenv("I2C_BUS")  ? System.getenv("I2C_BUS").toInteger()  : 1
String addrStr = System.getenv("I2C_ADDR") ?: "0x40"
int addr = Integer.decode(addrStr)

I2CConnection connection = new I2CConnection(bus, addr)            // open I²C bus, (bus, address=0x40) → I2CConnection
try {
    def pwm = new Pca9685Minimal(connection)   // construct driver at 1 kHz, (connection) → Pca9685Minimal
    for (int step in 0..10) {
        pwm.setDuty(0, step / 10.0)   // set one channel's duty cycle, (channel 0–15, duty 0.0–1.0) → void
        Thread.sleep(200)
    }
    pwm.setDuty(0, 0.0)
} finally {
    connection.close()
}
