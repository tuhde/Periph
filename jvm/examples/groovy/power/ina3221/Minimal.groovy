///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.power.Ina3221Minimal

def transport = new I2CTransport(1, 0x40)            // open I²C bus 1, device 0x40, (bus, address) → I2CTransport
try {
    def ina = new Ina3221Minimal(transport)                 // construct driver (0.1 Ω shunt all channels), (transport) → Ina3221Minimal

    while (true) {
        (1..3).each { ch ->
            def v = ina.voltage(ch)                         // read bus voltage, (channel=1–3) → double V
            def i = ina.current(ch)                         // compute current via shunt, (channel=1–3) → double A
            def p = ina.power(ch)                           // compute power, (channel=1–3) → double W
            printf("CH%d: %.3f V  %.4f A  %.4f W%n", ch, v, i, p)
        }
        println('---')
        Thread.sleep(1000)
    }
} finally {
    transport.close()
}
