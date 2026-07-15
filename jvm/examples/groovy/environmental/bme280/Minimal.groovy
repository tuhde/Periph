///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.environmental.Bme280Minimal

def bus  = (System.getenv("I2C_BUS")  ?: "1").toInteger()
def addr = Integer.decode(System.getenv("I2C_ADDR") ?: "0x76")
def transport = new I2CTransport(bus, addr)          // open I²C bus, (bus, address=0x76) → I2CTransport
try {
    def sensor = new Bme280Minimal(transport)                // construct driver, verifies chip ID and loads calibration, (transport) → Bme280Minimal

    for (int i = 0; i < 5; i++) {
        double t = sensor.temperature()                      // read temperature, () → double °C
        double p = sensor.pressure()                         // read pressure, () → double hPa
        double h = sensor.humidity()                         // read humidity, () → double %RH
        printf("%.1f °C, %.1f hPa, %.1f %%RH%n", t, p, h)
        Thread.sleep(1000)
    }
} finally {
    transport.close()
}
