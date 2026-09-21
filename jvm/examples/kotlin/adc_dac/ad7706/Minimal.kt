///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.SPIConnection
import it.uhde.periph.chips.adc_dac.Ad7706Minimal

fun main() {
    SPIConnection(0, 0, 3, 5_000_000).use { connection ->                // open SPI bus 0, device 0, Mode 3, 5 MHz, (bus, device, mode, maxSpeedHz) → SPIConnection
        val adc = Ad7706Minimal(connection, 2.5f, Ad7706Minimal.MCLK_2_4576MHZ) // construct and initialise the AD7706, (connection, vref=2.5 V, mclkHz=2_457_600 Hz) → Ad7706Minimal
                                                                                // default configuration: gain 1, bipolar, unbuffered, 50 Hz, self-calibrated Channel 1
        while (true) {
            val v = adc.readVoltage()                                         // Read Channel 1 voltage, () → float V
            println("%.4f V".format(v))
            Thread.sleep(1000)
        }
    }
}
