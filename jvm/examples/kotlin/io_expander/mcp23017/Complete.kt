///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.io_expander.Mcp23017Minimal
import it.uhde.periph.chips.io_expander.Mcp23017Full

fun main() {
    I2CConnection(1, 0x20).use { connection ->                        // open I²C bus 1, device 0x20, (bus, address) → I2CConnection
        val chip = Mcp23017Full(connection, 0x20)                      // construct full driver, (connection, addr=0x20) → Mcp23017Full

        val p0 = chip.pin(0)                                           // get full pin proxy, (n) → Pin
                                                                // GPA0 as output
        println("GPA0 direction = output")

        p0.setHigh()                                                  // drive high, () → Unit
        p0.setLow()                                                   // drive low, () → Unit

        val level = p0.read()                                         // read actual level, () → Boolean
        println("GPA0 level: $level")

        val porta = chip.readPort(0)                                  // read all 8 pins, (port=0) → Int bitmask
        val portb = chip.readPort(1)                                  // read all 8 pins, (port=1) → Int bitmask
        println("PORTA=0x%02X  PORTB=0x%02X".format(porta, portb))

        chip.writePort(0, 0b00001111)                                 // write all 8 pins, (port, mask) → Unit
        chip.writePort(1, 0b11110000)                                 // write all 8 pins, (port, mask) → Unit

        chip.configurePullup(1, 0b01111111)                           // enable pull-ups, (port=1, mask) → Unit

        chip.configurePullup(0, 0x55)                                 // enable pull-ups, (port=0, mask) → Unit

        chip.configurePolarity(0, 0x00)                               // configure polarity, (port=0, mask) → Unit

        // Subscribe to INT on a single port (dedicated INT line per port)
        chip.onInterruptPort(0, { status ->                            // subscribe to INT on one port, (port, callback, intPin=connection.intPin()) → Unit
            println("PORTA changed: 0x" + Integer.toHexString(status))
        })

        val changed = chip.pollInterrupt(0)                            // read and clear interrupt, (port=0) → Int
                                                                       // INTFA flags; also reads INTCAPA to clear
        println("changed on init: 0x${Integer.toHexString(changed)}")

        val captured = chip.readCapture(0)                             // read INTCAP, (port=0) → Int
                                                                       // captured PORTA state at moment of interrupt; also clears
        println("captured PORTA: 0x${Integer.toHexString(captured)}")

        val p1  = chip.pin(1)                                         // get full pin proxy, (n) → Pin
        val p15 = chip.pin(15)                                        // get full pin proxy, (n) → Pin
                                                                // GPB7 as output (output-only)

        p1.watch({ pin ->                                              // subscribe to pin edges, (handler, trigger=CHANGE) → Unit
            println("GPA1 changed")                                    // called with this pin when its state matches the trigger
        })                                                            // fires when GPA1 changes; needs onInterruptPort(0, ...) armed first
        p1.unwatch()                                                  // unsubscribe pin handler, () → Unit

        chip.offInterruptPort(0)                                      // unsubscribe PORTA, (port=0) → Unit
                                                                       // clears GPINTENA; stops polling

        chip.writePort(0, 0x80)                                        // write all 8 pins, (port=0, mask=0x80) → Unit
        chip.writePort(1, 0x00)                                        // write all 8 pins, (port=1, mask=0x00) → Unit

        val porta2 = chip.readPort(0)                                 // read all 8 pins, (port=0) → Int bitmask
        val portb2 = chip.readPort(1)                                 // read all 8 pins, (port=1) → Int bitmask
        println("PORTA=0x%02X  PORTB=0x%02X".format(porta2, portb2))

        println("All API methods exercised.")
    }
}