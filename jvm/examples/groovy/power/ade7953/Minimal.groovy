///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.Ade7953Minimal

def bus  = System.getenv("I2C_BUS")?.toInteger() ?: 1
def addr = (System.getenv("I2C_ADDR") ?: "0x38").replaceFirst("^0[xX]", "").toInteger(16)

def connection = new I2CConnection(bus, addr)
try {
    def ade = new Ade7953Minimal(connection, 251.0, 30.0)             // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V)
    while (true) {
        def v = ade.voltage()                                          // Read bus voltage, () → double V
        def i = ade.current()                                          // Read load current, () → double A
        def p = ade.activePower()                                      // Read active power, () → double W
        def e = ade.activeEnergy()                                     // Read active energy, () → double Wh
        printf("V=%.2f I=%.3f P=%.2f E=%.4f%n", v, i, p, e)
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}