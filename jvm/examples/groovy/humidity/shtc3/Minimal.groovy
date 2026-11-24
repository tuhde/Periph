///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-groovy:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.Shtc3Minimal

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x70").replaceFirst("^0[xX]", ""), 16)

def connection = new I2CConnection(bus, addr)
try {
    def sensor = new Shtc3Minimal(connection)                                                          // Create SHTC3 driver, (connection) → Shtc3Minimal
    sensor.init()                                                                                       // Soft reset and ID check, () → void

    while (true) {
        def v = sensor.read()                                                                           // Read temperature and humidity, () → double[] {°C, %RH}
        System.out.printf("%.2f C, %.1f %%RH%n", v[0], v[1])
        Thread.sleep(2000)
    }
} finally {
    connection.close()
}
