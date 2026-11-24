///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-groovy:1.5.4

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Ms5611Minimal

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x77").replaceFirst("^0[xX]", ""), 16)

def connection = new I2CConnection(bus, addr)
try {
    def sensor = new Ms5611Minimal(connection)                                                                      // Create MS5611 driver, (connection) → Ms5611Minimal
    sensor.init()                                                                                                   // Reset, read PROM, verify CRC-4, () → void

    while (true) {
        def v = sensor.read()                                                                                       // Read temperature and pressure, () → double[] {°C, Pa}
        System.out.printf("%.2f C, %.1f Pa%n", v[0], v[1])
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
