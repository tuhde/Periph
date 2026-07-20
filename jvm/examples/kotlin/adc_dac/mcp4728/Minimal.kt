///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.adc_dac.Mcp4728Minimal

fun main() {
    I2CTransport(1, 0x60).use { transport ->         // open I²C bus 1, device 0x60, (bus, address) → I2CTransport
        val dac = Mcp4728Minimal(transport)                 // construct driver, (transport) → Mcp4728Minimal

        while (true) {
            dac.setVoltage(0, 0.0)   // set channel A to 0 V, (channel=0–3, fraction=0.0–1.0) → Unit
            Thread.sleep(1000)
            dac.setVoltage(0, 0.5)   // set channel A to 50% of VDD, (channel=0–3, fraction=0.0–1.0) → Unit
            Thread.sleep(1000)
            dac.setVoltage(0, 1.0)   // set channel A to VDD, (channel=0–3, fraction=0.0–1.0) → Unit
            Thread.sleep(1000)
        }
    }
}
