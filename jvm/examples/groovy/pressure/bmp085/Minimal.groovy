///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Bmp085Minimal

def conn = new I2CConnection(1, 0x77)
try {
    def sensor = new Bmp085Minimal(conn)

    while (true) {
        double t = sensor.temperature()
        double p = sensor.pressure()
        printf "temperature=%.2f °C  pressure=%.2f Pa%n", t, p
        Thread.sleep(1000)
    }
} finally {
    conn.close()
}