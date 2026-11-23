///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.2
//DEPS it.uhde:periph-groovy:1.5.2

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.color.As7262Minimal

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x49").replaceFirst("^0[xX]", ""), 16)

def conn = new I2CConnection(bus, addr)
try {
    def sensor = new As7262Minimal(conn)                                                            // Create AS7262 driver, (connection) → void
    sensor.init()                                                                                   // Reset and configure defaults, () → void

    while (true) {
        def ch = sensor.read()                                                                      // Read six calibrated channels, () → Channels
        printf("V %.1f B %.1f G %.1f Y %.1f O %.1f R %.1f%n", ch.violet, ch.blue, ch.green, ch.yellow, ch.orange, ch.red)
        printf("%.0f C%n", sensor.temperature())
        Thread.sleep(2000)
    }
} finally {
    conn.close()
}
