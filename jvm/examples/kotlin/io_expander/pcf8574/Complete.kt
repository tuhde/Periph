///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.io_expander.Pcf8574Full

fun main() {
    I2CTransport(1, 0x20).use { transport ->                          // open I²C bus 1, device 0x20, (bus, address) → I2CTransport
        val chip = Pcf8574Full(transport)                              // construct full driver, (transport) → Pcf8574Full
                                                                       // initialises shadow to 0xFF; all pins input mode

        val port = chip.readPort()                                     // read all 8 pins, () → Int bitmask
                                                                       // returns actual logic levels on P0–P7 regardless of shadow
        println("initial port = 0x%02X".format(port))

        chip.writePort(0x0F)                                           // write all 8 pins, (mask) → Unit
                                                                       // P0–P3 driven low, P4–P7 remain input mode; updates shadow

        val p7 = chip.pin(7)                                           // get pin proxy, (n) → Pin
                                                                       // returns a Pin for P7 backed by the driver shadow
        p7.setInput()                                                  // set input mode — write 1 to shadow bit 7, () → Unit
                                                                       // releases P7 to quasi-input; external signal drives it
        p7.setOutput()                                                 // set output mode — drive P7 low, () → Unit
                                                                       // PCF8574 has no push-pull high; setHigh releases to pull-up
        p7.setHigh()                                                   // release to quasi-input (write 1), () → Unit
                                                                       // enables internal ~100 µA current source; sufficient for logic in
        p7.setLow()                                                    // drive low (write 0), () → Unit
                                                                       // open-drain sink up to 25 mA; active-low LED turns on

        val v = p7.read()                                              // read actual pin level, () → Boolean
                                                                       // reads bus, not shadow — reflects external pull or driven level
        println("P7 = $v")

        p7.toggle()                                                    // invert shadow bit 7, () → Unit
                                                                       // if last written 1 → writes 0; if last written 0 → writes 1

        chip.configureInterrupt { mask ->                              // attach change callback, ((Int) -> Unit) → Unit
                                                                       // starts a 5 ms polling thread; callback fires on any input change
            println("changed pins: 0x%02X".format(mask))
        }

        Thread.sleep(100)

        val changed = chip.clearInterrupt()                            // read port and return changed-pin bitmask, () → Int
                                                                       // compares current read to last read; clears chip INT line
        println("cleared interrupt: 0x%02X".format(changed))

        chip.stopInterrupt()                                           // stop polling thread, () → Unit
                                                                       // daemon thread; also exits automatically when JVM exits
    }
}
