///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.1
//DEPS it.uhde:periph-groovy:1.5.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.humidity.HS40xxMinimal

def bus  = System.getenv().getOrDefault("I2C_BUS", "1") as int
def addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x54").replaceFirst("^0[xX]", ""), 16)

def conn = new I2CConnection(bus, addr)
try {
    def sensor = new HS40xxMinimal(conn)                                                            // Create HS40xx driver, (connection) → void

    while (true) {
        def v = sensor.read()                                                                       // Read temperature and humidity, () → double[] {°C, %RH}
        printf("%.2f C, %.1f %%RH%n", v[0], v[1])
        Thread.sleep(2000)
    }
} finally {
    conn.close()
}
