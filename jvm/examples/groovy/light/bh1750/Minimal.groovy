///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-groovy:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.light.Bh1750Minimal

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x23").replaceFirst("^0[xX]", ""), 16)

def connection = new I2CConnection(bus, addr)
try {
    def sensor = new Bh1750Minimal(connection)                                                              // Create BH1750 driver, (connection) → Bh1750Minimal
    sensor.init()                                                                                           // Power on, () → void

    while (true) {
        def lux = sensor.read()                                                                             // Measure illuminance, () → double lx
        System.out.printf("%.1f lx%n", lux)
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
