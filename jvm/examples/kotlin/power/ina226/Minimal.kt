///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.power.Ina226Minimal

fun main() {
    I2CTransport(1, 0x40).use { transport ->                 // open I²C bus 1, device 0x40, (bus, address) → I2CTransport
        val ina = Ina226Minimal(transport, 0.1, 2.0)               // construct driver, (transport, rShunt=0.1 Ω, maxCurrent=2.0 A) → Ina226Minimal

        repeat(10) {
            val v  = ina.voltage()       // read bus voltage, () → Double V
            val vs = ina.shuntVoltage()  // read shunt voltage, () → Double V
            val c  = ina.current()       // read current, () → Double A
            val p  = ina.power()         // read power, () → Double W
            println("V=%.3f V  Vshunt=%.6f V  I=%.4f A  P=%.4f W".format(v, vs, c, p))
            Thread.sleep(1000)
        }
    }

}
