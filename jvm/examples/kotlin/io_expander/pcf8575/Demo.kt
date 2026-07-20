///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.io_expander.Pcf8575Full

fun main() {
    I2CTransport(1, 0x20).use { transport ->                          // open I²C bus 1, device 0x20, (bus, address) → I2CTransport
        val chip = Pcf8575Full(transport)                             // construct driver, (transport) → Pcf8575Full

        chip.writePort(0, 0xFF)                                       // write Port 0, (port=0, mask) → Unit
        chip.writePort(1, 0xFF)                                       // write Port 1, (port=1, mask) → Unit

        println("Running — buttons on P10–P17 mirror to LEDs on P00–P07")
        while (true) {
            val port0 = chip.readPort(0)                             // read Port 0, (port=0) → Int bitmask
            val port1 = chip.readPort(1)                             // read Port 1, (port=1) → Int bitmask

            val buttons = port1 and 0xFF                             // P10–P17 (pressed = 0)
            val ledBits = buttons.inv() and 0xFF                      // invert: pressed → LED on (0)
            chip.writePort(0, ledBits)                               // write Port 0, (port=0, mask) → Unit

            val btnStr = (7 downTo 0).joinToString("") { i -> if ((buttons shr i) and 1) != 0 "." else "X" }
            val ledStr = (7 downTo 0).joinToString("") { i -> if ((ledBits shr i) and 1) != 0 " " else "*" }
            System.out.printf("P0=0x%02X  P1=0x%02X  buttons=[%s]  LEDs=[%s]%n", port0, port1, btnStr, ledStr)
            Thread.sleep(200)
        }
    }
}