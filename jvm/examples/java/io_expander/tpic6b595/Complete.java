///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.SiPoConnection;
import it.uhde.periph.chips.io_expander.Tpic6b595Minimal;
import it.uhde.periph.chips.io_expander.Tpic6b595Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        // Hardware SPI bus 0, device 0; RCK line 17; SRCLR line 16; G line 15
        try (var connection = SiPoConnection.hardware(0, 0, 17, 16, 15)) { // open SiPo connection, (bus, device, rckLine, srclrLine, gLine) → SiPoConnection
            // --- Tpic6b595Minimal ---
            var chip1 = new Tpic6b595Minimal(connection);                      // construct minimal driver, (connection, numDevices=1) → Tpic6b595Minimal
                                                                              // initialises every output to OFF (shadow zeroed, latched once)

            var p0 = chip1.pin(0);                                              // get pin proxy, (n=0) → Pin
            p0.setHigh();                                                       // set DRAIN0 ON, () → void
                                                                              // sets shadow[0] bit 0, reverses the cascade, shifts out and pulses RCK
            p0.setLow();                                                        // set DRAIN0 OFF, () → void
                                                                              // clears shadow[0] bit 0, retransmits and latches
            p0.toggle();                                                        // invert shadow bit, () → void

            boolean state = p0.read();                                          // read shadow bit, () → boolean
                                                                              // returns the shadow bit (no bus read — SiPo is write-only)
            p0.set(true);                                                       // OutputPin set, (high=true) → void

            chip1.fill(true);                                                   // set every output ON, (value=true) → void
                                                                              // fills every shadow byte with 0xFF and retransmits — fast "all on" path
            chip1.fill(false);                                                  // set every output OFF, (value=false) → void
                                                                              // fills every shadow byte with 0x00 and retransmits — fast "all off" path
            chip1.off();                                                        // turn every output off, () → void
                                                                              // shorthand for fill(false); the safe initial state

            chip1.writePort(0, 0xA5);                                           // write port 0, (port=0, mask=0xA5) → void
                                                                              // sets DRAIN{1,3,5,7} ON, DRAIN{0,2,4,6} OFF

            // --- Tpic6b595Full (two cascaded devices) ---
            try (var connection2 = SiPoConnection.hardware(0, 0, 17, 16, 15)) {
                var chip2 = new Tpic6b595Full(connection2, 2);                  // construct full driver, (connection, numDevices=2) → Tpic6b595Full
                                                                              // two cascaded devices — 16 outputs total (DRAIN0..DRAIN15)

                int[] bytes = { 0x01, 0x80 };
                chip2.writeAll(bytes);                                          // write all device bytes, (values=[0x01, 0x80]) → void
                                                                              // updates both shadow bytes and performs one transmit + latch

                chip2.clear();                                                  // pulse SRCLR, () → void
                                                                              // clears the shift register only; outputs unaffected until next RCK pulse
                chip2.setOutputEnable(false);                                   // force every output off via G, (enabled=false) → void
                                                                              // drives G HIGH, blanking outputs without disturbing the shadow register
                chip2.setOutputEnable(true);                                    // re-enable outputs, (enabled=true) → void
                                                                              // drives G LOW; outputs resume from the previously-latched state

                System.out.println("state=" + state);
            }
        }
    }
}
