///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.io_expander.Pcf8575Minimal;

public class Minimal {
    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x20)) {             // open I²C bus 1, device 0x20, (bus, address) → I2CTransport
            var chip = new Pcf8575Minimal(transport);                  // construct driver, (transport) → Pcf8575Minimal

            var p0 = chip.pin(0);                                      // get pin proxy, (n=0) → Pin
            p0.setOutput();                                             // set output mode — drives P00 low, () → void

            var p8 = chip.pin(8);                                      // get pin proxy, (n=8) → Pin
            p8.setInput();                                              // set input mode — release P10 to quasi-input, () → void

            while (true) {
                int port0 = chip.readPort(0);                           // read Port 0, (port=0) → int bitmask
                int port1 = chip.readPort(1);                           // read Port 1, (port=1) → int bitmask
                if ((port1 & 0x01) == 0) p0.setLow();                  // drive P00 low (LED on), () → void
                else                       p0.setHigh();                // release P00 high (LED off), () → void
                Thread.sleep(200);
            }
        }
    }
}