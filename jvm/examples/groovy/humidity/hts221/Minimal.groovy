///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-groovy:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.Hts221Minimal

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x5F").replaceFirst("^0[xX]", ""), 16)

def connection = new I2CConnection(bus, addr)
try {
    def sensor = new Hts221Minimal(connection)                                                        // Create HTS221 driver, (connection) → void

    while (true) {
        def v = sensor.read()                                                                         // Read temperature and humidity, () → double[] {°C, %rH}
        System.out.printf("%.2f C, %.1f %%rH%n", v[0], v[1])
        Thread.sleep(2000)
    }
} finally {
    connection.close()
}
