///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.led.SAA1064Full;
import java.util.Map;

public class Demo {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x38"));
        try (var connection = new I2CConnection(bus, addr)) {                      // open I²C bus, (bus, address=0x38) → I2CConnection
            var display = new SAA1064Full(connection);                  // construct driver, (connection) → SAA1064Full

            // --- Four-digit counter ---
            // A fast counter from 0 to 9999, the decimal point sweeping across the four
            // positions, the segment current stepped through 3/9/15/21 mA, a switch to
            // static mode (only digits 1 and 2 stay lit), a 2 s segment test and the
            // status byte (the PR flag is set after power-up and clears on the first read).
            for (int n = 0; n < 10000; n += 37) {
                display.showNumber(n);                                  // show the counter, (value 0–9999) → void
                Thread.sleep(10);
            }
            display.showDigits(new int[]{1, 2, 3, 4});                  // show four digits, (digits) → void
            for (int pos : new int[]{1, 2, 3, 4}) {
                display.setDecimalPoint(pos, true);                     // light a decimal point, (position, on) → void
                Thread.sleep(400);
                display.setDecimalPoint(pos, false);
            }
            for (int ma : new int[]{3, 9, 15, 21}) {
                display.setCurrent(ma);                                 // step the segment current, (ma) → void
                display.showNumber(8888);
                Thread.sleep(1000);
            }
            display.setCurrent(12);
            display.setMode(false);                                     // static mode, (dynamic) → void
            Thread.sleep(2000);
            display.setMode(true);
            display.segmentTest(true);                                  // segment test, (on) → void
            Thread.sleep(2000);
            display.segmentTest(false);
            System.out.println(display.readStatus());                   // read the status byte, () → int
            System.out.println(display.powerResetFlag());               // read the PR flag, () → boolean
            display.clear();                                            // blank all digits, () → void
        }
    }
}
