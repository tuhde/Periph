///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.gas.Ens160Minimal

def transport = new I2CTransport(1, 0x52)            // open I²C bus 1, device 0x52, (bus, address=0x52) → I2CTransport
try {
    def sensor = new Ens160Minimal(transport)               // construct driver, verifies PART_ID and starts STANDARD mode, (transport) → Ens160Minimal

    println("Waiting for sensor warm-up...")
    while (true) {                                          // Wait for valid data, () → blocks until warm
        try { sensor.readAirQuality(); break } catch (Exception e) { Thread.sleep(1000) }
    }

    for (int i = 0; i < 10; i++) {
        double[] data = sensor.readAirQuality()             // read air quality, () → double[] {aqi, tvocPpb, eco2Ppm}
        printf("AQI=%.0f TVOC=%.0f ppb eCO2=%.0f ppm%n", data[0], data[1], data[2])
        Thread.sleep(1000)
    }
} finally {
    transport.close()
}
