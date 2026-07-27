///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.io_expander.Mcp23017Minimal;
import it.uhde.periph.chips.io_expander.Mcp23017Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x20)) {           // open I²C bus 1, device 0x20, (bus, address) → I2CConnection
            var chip = new Mcp23017Full(connection, 0x20);               // construct full driver, (connection, addr=0x20) → Mcp23017Full

            var p0 = chip.pin(0);                                      // get full pin proxy, (n) → Pin
                                                                // GPA0 as output
            System.out.println("GPA0 direction = output");

            p0.setHigh();                                              // drive high, () → void
            p0.setLow();                                               // drive low, () → void

            boolean level = p0.read();                                 // read actual level, () → boolean
            System.out.println("GPA0 level: " + level);

            int porta = chip.readPort(0);                             // read all 8 pins, (port=0) → int bitmask
            int portb = chip.readPort(1);                             // read all 8 pins, (port=1) → int bitmask
            System.out.printf("PORTA=0x%02X  PORTB=0x%02X%n", porta, portb);

            chip.writePort(0, 0b00001111);                            // write all 8 pins, (port, mask) → void
                                                                // GPA0–GPA3 outputs, GPA4–GPA6 inputs, GPA7 output
            chip.writePort(1, 0b11110000);                            // write all 8 pins, (port, mask) → void
                                                                // GPB0–GPB3 outputs, GPB4–GPB7 outputs

            chip.configurePullup(1, 0b01111111);                       // enable pull-ups, (port=1, mask) → void
                                                                // GPB0–GPB6 pull-ups enabled

            chip.configurePullup(0, 0x55);                            // enable pull-ups, (port=0, mask) → void
                                                                // GPA0, GPA2, GPA4, GPA6 pull-ups

            chip.configurePolarity(0, 0x00);                          // configure polarity, (port=0, mask) → void

            // Subscribe to INT on a single port (dedicated INT line per port)
            chip.onInterruptPort(0, status ->                          // subscribe to INT on one port, (port, callback, intPin) → void
                System.out.println("PORTA changed: 0x" + Integer.toHexString(status)));

            int changed = chip.pollInterrupt(0);                       // read and clear interrupt, (port=0) → int
                                                                // INTFA flags; also reads INTCAPA to clear
            System.out.println("changed on init: 0x" + Integer.toHexString(changed));

            int captured = chip.readCapture(0);                        // read INTCAP, (port=0) → int
                                                                // captured PORTA state at moment of interrupt; also clears
            System.out.println("captured PORTA: 0x" + Integer.toHexString(captured));

            var p1  = chip.pin(1);                                     // get full pin proxy, (n) → Pin
            var p15 = chip.pin(15);                                    // get full pin proxy, (n) → Pin
                                                                // GPB7 as output (output-only)

            java.util.function.Consumer<Mcp23017Full.Pin> watcher = pin ->
                System.out.println("GPA1 changed");                    // called with this pin when its state matches the trigger
            p1.watch(watcher);                                         // subscribe to pin edges, (handler, trigger=CHANGE) → void
                                                                // fires when GPA1 changes; needs onInterruptPort(0, ...) armed first
            p1.unwatch();                                              // unsubscribe pin handler, () → void

            chip.offInterruptPort(0);                                  // unsubscribe PORTA, (port=0) → void
                                                                // clears GPINTENA; stops polling

            chip.writePort(0, 0x80);                                  // write all 8 pins, (port=0, mask=0x80) → void
                                                                // GPA7 high
            chip.writePort(1, 0x00);                                  // write all 8 pins, (port=1, mask=0x00) → void
                                                                // all PORTB pins low

            int porta2 = chip.readPort(0);                            // read all 8 pins, (port=0) → int bitmask
            int portb2 = chip.readPort(1);                             // read all 8 pins, (port=1) → int bitmask
            System.out.printf("PORTA=0x%02X  PORTB=0x%02X%n", porta2, portb2);

            System.out.println("All API methods exercised.");
        }
    }
}
