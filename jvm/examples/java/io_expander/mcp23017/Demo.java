///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.io_expander.Mcp23017Full;

/**
 * MCP23017 demo — Knight Rider scanner with button override.
 *
 * Hardware:
 *   GPA0–GPA6: seven LEDs (anode → VCC via 220Ω, cathode → pin; active-high)
 *   GPB0–GPB6: seven push buttons (pin → GND when pressed; pull-ups enabled)
 *
 * Runs a Knight Rider scanning pattern on PORTA. Pressing a button overrides
 * the scanner and lights the matching LED. The loop reads GPIOB every 100 ms,
 * builds the output mask from the button state (inverted, since active-low),
 * ORs it with the scanner position unless a button is pressed, then writes
 * to OLATA.
 */
public class Demo {
    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x20)) {             // open I²C bus 1, device 0x20, (bus, address) → I2CTransport
            var chip = new Mcp23017Full(transport, 0x20);               // construct full driver, (transport, addr=0x20) → Mcp23017Full

            // Enable pull-ups on PORTB inputs (GPB0–GPB6) so idle buttons read high.
            chip.configurePullup(1, 0b01111111);                       // enable pull-ups, (port=1, mask) → void

            System.out.println("Running — press buttons GPB0–GPB6 to light corresponding LEDs");

            int position = 0;
            int direction = 1;

            while (true) {
                int portb = chip.readPort(1);                          // read all 8 pins, (port=1) → int bitmask
                                                                // GPB0–GPB6 buttons: pressed = 0 (active-low)

                int buttons = portb & 0x7F;        // mask GPA7 (output-only)
                int pressed = (~buttons) & 0x7F;   // invert: pressed button = bit 1

                int scanner = 1 << position;

                int output = (pressed != 0)
                    ? pressed | (1 << 7)   // keep GPA7 high (output-only)
                    : scanner | (1 << 7);

                chip.writePort(0, output);                                 // write all 8 pins, (port=0, mask) → void

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 7; i++) {
                    sb.append((output >> i) & 1 == 1 ? '*' : ' ');
                }
                System.out.printf("PORTA=0x%02X  [%s]  buttons=0x%02X%n",
                    output, sb.toString(), buttons);

                position += direction;
                if (position == 6) direction = -1;
                if (position == 0) direction =  1;

                Thread.sleep(100);
            }
        }
    }
}