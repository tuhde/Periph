///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.other.Mpr121Full;

public class Demo {
    private static final String[] NOTES = {
        "C4", "C#4", "D4", "D#4", "E4", "F4", "F#4", "G4", "G#4", "A4", "A#4", "B4",
    };

    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x5A)) {            // Open I2C connection, (bus=1, address=0x5A) → I2CConnection
            var mpr = new Mpr121Full(connection);                        // Create MPR121 driver, (connection) → Mpr121Full
                                                                       // defaults, all 12 electrodes enabled
            int previous = 0;
            for (int i = 0; i < 1000; i++) {
                int mask = mpr.touched();                                // Read 12-bit touch bitmask, () → int bitmask
                                                                       // bit n=1 means ELEn is currently touched
                int newlyPressed = mask & ~previous;
                int newlyReleased = (~mask) & previous;
                for (int n = 0; n < 12; n++) {
                    if ((newlyPressed & (1 << n)) != 0) {
                        System.out.printf("NOTE ON:  %s%n", NOTES[n]);
                    }
                    if ((newlyReleased & (1 << n)) != 0) {
                        System.out.printf("NOTE OFF: %s%n", NOTES[n]);
                    }
                }
                previous = mask;
                Thread.sleep(50);
            }
        }
    }
}
