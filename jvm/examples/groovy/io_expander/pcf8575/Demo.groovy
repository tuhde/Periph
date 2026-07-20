///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.io_expander.Pcf8575Full

def transport = new I2CTransport(1, 0x20)             // open I²C bus 1, device 0x20, (bus, address) → I2CTransport
try {
    def chip = new Pcf8575Full(transport)             // construct driver, (transport) → Pcf8575Full

    chip.writePort(0, 0xFF)                           // write Port 0, (port=0, mask) → void
    chip.writePort(1, 0xFF)                           // write Port 1, (port=1, mask) → void

    println("Running — buttons on P10–P17 mirror to LEDs on P00–P07")
    while (true) {
        int port0 = chip.readPort(0)                  // read Port 0, (port=0) → int bitmask
        int port1 = chip.readPort(1)                  // read Port 1, (port=1) → int bitmask

        int buttons = port1 & 0xFF                     // P10–P17 (pressed = 0)
        int ledBits = (~buttons) & 0xFF                // invert: pressed → LED on (0)
        chip.writePort(0, ledBits)                    // write Port 0, (port=0, mask) → void

        def btnStr = (7..0).collect { i -> (buttons >> i) & 1 ? '.' : 'X' }.join('')
        def ledStr = (7..0).collect { i -> (ledBits >> i) & 1 ? ' ' : '*' }.join('')
        printf("P0=0x%02X  P1=0x%02X  buttons=[%s]  LEDs=[%s]%n", port0, port1, btnStr, ledStr)
        Thread.sleep(200)
    }
} finally {
    transport.close()
}