///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.adc_dac.Pcf8591Minimal

fun main() {
    I2CTransport(1, 0x48).use { transport ->             // open I²C bus 1, device 0x48, (bus, address) → I2CTransport
        val adc = Pcf8591Minimal(transport)                       // construct driver, (transport) → Pcf8591Minimal

        while (true) {
            val ch0 = adc.readChannel(0)                            // read single channel, (channel=0–3) → Int
            val all = adc.readAll()                                 // read all four channels, () → IntArray
            println("ch0=$ch0 all=${all.joinToString(" ")}")
            Thread.sleep(1000)
        }
    }
}
