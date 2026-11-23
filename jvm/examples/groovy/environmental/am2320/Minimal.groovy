///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.2
//DEPS it.uhde:periph-groovy:1.5.2

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.environmental.AM2320Minimal

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x5C").replaceFirst("^0[xX]", ""), 16)

def conn = new I2CConnection(bus, addr)
try {
    def sensor = new AM2320Minimal(conn)                                     // Create AM2320 driver, (connection) → void

    while (true) {
        def v = sensor.read()                                                    // Read temperature and humidity, () → double[] {°C, %RH}
        // previous measurement; reads are kept >= 2 s apart
        printf("%.1f C, %.1f %%RH%n", v[0], v[1])
    }
} finally {
    conn.close()
}
