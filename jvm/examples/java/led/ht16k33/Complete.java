///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-java:1.5.4

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.led.HT16K33Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x70"));
        try (var connection = new I2CConnection(bus, addr)) {              // open I²C bus, (bus, address=0x70) → I2CConnection
            var matrix = new HT16K33Full(connection);                         // construct driver, (connection) → HT16K33Full

            matrix.clear();                                                   // blank the display, () → void
                                                                              // zeros the shadow RAM and writes it
            matrix.setPixel(2, 5, true);                                      // set one LED, (com 0–7, row 0–15, on) → void
            matrix.show();                                                    // write the shadow RAM, () → void
            System.out.println(matrix.getPixel(2, 5));                        // read a pixel from the shadow RAM, (com, row) → boolean
            matrix.writeRaw(new byte[]{(byte) 0xFF, 0x0F}, 0);                // write raw RAM bytes, (data 1–16 bytes, address 0–15) → void
                                                                              // com 0 rows 0–11 lit
            matrix.setBrightness(8);                                          // set dimming, (level 0–15) → void
                                                                              // 9/16 duty
            matrix.setBlink(1.0);                                             // set blink frequency, (0, 0.5, 1, 2 Hz) → void
                                                                              // blinks at 1 Hz
            Thread.sleep(3000);
            matrix.setBlink(0.0);
            matrix.displayOff();                                              // display output off, () → void
            matrix.displayOn();                                               // display output on, () → void
            matrix.setIntOutput(true, false);                                 // ROW15/INT pin as INT output, (enable, activeHigh) → void
                                                                              // active low interrupt output
            System.out.println(matrix.readKeys());                            // read and clear key data, () → long bitmask
            System.out.println(matrix.keyPressed());                          // read the INT flag, () → boolean
            System.out.println(matrix.pollInterrupt());                       // read keys if INT flag set, () → long (0 if none)
            matrix.onInterrupt(keys -> System.out.println(keys));             // subscribe to key presses, (callback) → void
                                                                              // polls every 20 ms when no INT pin is wired
            Thread.sleep(100);
            matrix.offInterrupt();                                            // unsubscribe, () → void
            matrix.standby();                                                 // oscillator off, () → void
            matrix.wake();                                                    // oscillator on and restore state, () → void
        }
    }
}
