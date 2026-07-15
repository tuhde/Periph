///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.io_expander.Mcp23017Minimal

def transport = new I2CTransport(1, 0x20)             // open I²C bus 1, device 0x20, (bus, address) → I2CTransport
try {
    def chip = new Mcp23017Minimal(transport, 0x20)    // construct driver, (transport, addr=0x20) → Mcp23017Minimal

    def p0 = chip.pin(0)                              // get pin proxy, (n) → Pin
    p0.setOutput()                                    // set GPA0 as output, () → void

    def p8 = chip.pin(8)                              // get pin proxy, (n) → Pin
    p8.setInput()                                     // set GPB0 as input, () → void

    while (true) {
        int porta = chip.readPort(0)                  // read all 8 pins, (port=0) → int bitmask
        int portb = chip.readPort(1)                   // read all 8 pins, (port=1) → int bitmask
        if (((portb >> 1) & 1) == 0) p0.setLow()     // drive GPA0 low (LED on), () → void
        else                           p0.setHigh()    // release GPA0 high (LED off), () → void
        printf("PORTA=0x%02X  PORTB=0x%02X%n", porta, portb)
        Thread.sleep(200)
    }
} finally {
    transport.close()
}