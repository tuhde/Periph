///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-groovy:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Spl06Minimal

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x77").replaceFirst("^0[xX]", ""), 16)

def connection = new I2CConnection(bus, addr)
try {
    def sensor = new Spl06Minimal(connection)                                                       // Create SPL06 driver, (connection) → Spl06Minimal
    sensor.init()                                                                                   // Check ID, read coefficients, apply defaults, () → void
    
    while (true) {
        def m = sensor.read()                                                                       // Measure temperature and pressure, () → Measurement {temperatureC °C, pressurePa Pa}
        System.out.printf("%.2f Pa, %.2f C%n", m.pressurePa(), m.temperatureC())
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
