///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.adc_dac.Hy3131Minimal

fun main() {
    SPIConnection(0, 0, 0, 1_000_000).use { connection ->                                           // Open SPI bus 0, CS 0, Mode 0, 1 MHz, (bus, device, mode=0, maxSpeedHz=1_000_000) → SPIConnection
        val adc = Hy3131Minimal(connection, 1.2f)                                                   // Create HY3131 driver, (connection, vref=1.2 V) → Hy3131Minimal
        while (true) {
            val v = adc.readVoltage()                                                               // Read AD1 differential voltage, () → float V
            println("%.5f".format(v))
            Thread.sleep(1000)
        }
    }
}
