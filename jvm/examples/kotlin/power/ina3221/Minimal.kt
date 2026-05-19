///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT
//DEPS com.pi4j:pi4j-core:2.7.0
//DEPS com.pi4j:pi4j-plugin-raspberrypi:2.7.0
//DEPS com.pi4j:pi4j-plugin-linuxfs:2.7.0

import com.pi4j.Pi4J
import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.power.Ina3221Minimal

fun main() {
    val pi4j = Pi4J.newAutoContext()                                    // initialise Pi4J, () → Context
    try {
        I2CTransport(pi4j, 1, 0x40).use { transport ->                 // open I²C bus 1, device 0x40, (bus, address) → I2CTransport
            val ina = Ina3221Minimal(transport)                         // construct driver (0.1 Ω shunt all channels), (transport) → Ina3221Minimal

            while (true) {
                for (ch in 1..3) {
                    val v = ina.voltage(ch)                             // read bus voltage, (channel=1–3) → Double V
                    val i = ina.current(ch)                             // compute current via shunt, (channel=1–3) → Double A
                    val p = ina.power(ch)                               // compute power, (channel=1–3) → Double W
                    println("CH$ch: ${"%.3f".format(v)} V  ${"%.4f".format(i)} A  ${"%.4f".format(p)} W")
                }
                println("---")
                Thread.sleep(1000)
            }
        }
    } finally {
        pi4j.shutdown()
    }
}
