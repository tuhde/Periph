///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.io_expander.Mcp23017Minimal

fun main() {
    I2CTransport(1, 0x20).use { transport ->                          // open I²C bus 1, device 0x20, (bus, address) → I2CTransport
        val chip = Mcp23017Minimal(transport, 0x20)                     // construct driver, (transport, addr=0x20) → Mcp23017Minimal

        val p0 = chip.pin(0)                                           // get pin proxy, (n) → Pin
        p0.setOutput()                                                 // set GPA0 as output, () → Unit

        val p8 = chip.pin(8)                                           // get pin proxy, (n) → Pin
        p8.setInput()                                                  // set GPB0 as input, () → Unit

        while (true) {
            val porta = chip.readPort(0)                               // read all 8 pins, (port=0) → Int bitmask
            val portb = chip.readPort(1)                               // read all 8 pins, (port=1) → Int bitmask
            if ((portb shr 1 and 1) == 0) p0.setLow()                // drive GPA0 low (LED on), () → Unit
            else                           p0.setHigh()                // release GPA0 high (LED off), () → Unit
            println("PORTA=0x%02X  PORTB=0x%02X".format(porta, portb))
            Thread.sleep(200)
        }
    }
}