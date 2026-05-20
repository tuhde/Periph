///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.power.Ina219Minimal

fun main() {
    I2CTransport(1, 0x40).use { transport ->                // open I²C bus 1, device 0x40, (bus, address) → I2CTransport
        val ina = Ina219Minimal(transport)                         // construct driver, (transport, rShunt=0.1 Ω, maxCurrent=2.0 A) → Ina219Minimal

        while (true) {
            val v  = ina.voltage()       // read bus voltage, () → Double V
            val vs = ina.shuntVoltage()  // read shunt voltage, () → Double V
            val i  = ina.current()       // read current, () → Double A
            val p  = ina.power()         // read power, () → Double W
            println("V_bus=%.3f V  V_shunt=%.6f V  I=%.4f A  P=%.4f W".format(v, vs, i, p))
            Thread.sleep(1000)
        }
    }

}
