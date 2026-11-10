///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.power.AXP2101Minimal

def bus  = System.getenv("I2C_BUS")?.toInteger() ?: 1
def addr = System.getenv("I2C_ADDR")?.replaceFirst("^0[xX]", "")?.toInteger(16) ?: 0x34
def connection = new I2CConnection(bus, addr)
try {
    def chip = new AXP2101Minimal(connection)       // Create AXP2101 driver, (connection)
    10.times {
        def v = chip.batteryVoltage()                // Read battery voltage, () → double V
        def pct = chip.batteryPercent()              // Read state of charge, () → int %
        def state = chip.chargeState()               // Read charge state, () → int (0 trickle … 5 none)
        def vbus = chip.vbusPresent()                // Check input source, () → boolean
        printf("%.3f V  %d %%  state=%d  vbus=%b%n", v, pct, state, vbus)
        Thread.sleep(1000)
    }
} finally {
    connection.close()
}
