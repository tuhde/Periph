///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.power.Ina226Minimal

def transport = new I2CTransport(1, 0x40)               // open I²C bus 1, device 0x40, (bus, address) → I2CTransport
try {
    def ina = new Ina226Minimal(transport, 0.1d, 2.0d)        // construct driver, (transport, rShunt=0.1 Ω, maxCurrent=2.0 A) → Ina226Minimal

    10.times {
        double v  = ina.voltage()       // read bus voltage, () → double V
        double vs = ina.shuntVoltage()  // read shunt voltage, () → double V
        double c  = ina.current()       // read current, () → double A
        double p  = ina.power()         // read power, () → double W
        printf("V=%.3f V  Vshunt=%.6f V  I=%.4f A  P=%.4f W%n", v, vs, c, p)
        Thread.sleep(1000)
    }
} finally {
    transport.close()
}
