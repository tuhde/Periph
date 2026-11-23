///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.1
//DEPS it.uhde:periph-java:1.5.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.led.HT16K33Full;

public class Demo {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x70"));
        try (var connection = new I2CConnection(bus, addr)) {              // open I²C bus, (bus, address=0x70) → I2CConnection
            // --- Scrolling pixel, brightness sweep, blink and key matrix on a 16x8 matrix ---
            // A single lit pixel visits all 16 x 8 positions, the brightness is swept from
            // 0 to 15, the display blinks at 2, 1 and 0.5 Hz, then key presses are reported
            // for 20 s via the INT output before standby and wake.
            var matrix = new HT16K33Full(connection);                         // construct driver, (connection) → HT16K33Full

            for (int com = 0; com < HT16K33Full.COMMONS; com++) {
                for (int row = 0; row < HT16K33Full.ROWS; row++) {
                    matrix.clear();                                           // blank the display, () → void
                    matrix.setPixel(com, row, true);                          // set one LED, (com 0–7, row 0–15, on) → void
                    matrix.show();                                            // write the shadow RAM, () → void
                    Thread.sleep(50);
                }
            }

            byte[] allOn = new byte[16];
            java.util.Arrays.fill(allOn, (byte) 0xFF);
            matrix.writeRaw(allOn, 0);                                        // all LEDs on, (data, address) → void
            for (int level = 0; level < 16; level++) {
                matrix.setBrightness(level);                                  // sweep dimming, (level 0–15) → void
                Thread.sleep(100);
            }

            for (double f : new double[]{2.0, 1.0, 0.5, 0.0}) {
                matrix.setBlink(f);                                           // set blink frequency, (0, 0.5, 1, 2 Hz) → void
                Thread.sleep(3000);
            }

            matrix.setIntOutput(true, false);                                 // INT output, active low, (enable, activeHigh) → void
            for (int i = 0; i < 1000; i++) {                                  // 1000 x 20 ms = 20 s
                long keys = matrix.pollInterrupt();                           // read keys if pending, () → long bitmask
                if (keys != 0) System.out.println("keys: 0x" + Long.toHexString(keys));
                Thread.sleep(20);
            }

            matrix.standby();                                                 // oscillator off, () → void
            Thread.sleep(1000);
            matrix.wake();                                                    // oscillator on, state restored, () → void
        }
    }
}
