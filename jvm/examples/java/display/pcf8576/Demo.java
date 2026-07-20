///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.chips.display.Pcf8576Full;
import it.uhde.periph.transport.I2CTransport;

public class Demo {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x38"));
        try (var transport = new I2CTransport(bus, addr)) {

            // --- 4-digit countdown from 9999 to 0000 on a 1:4 multiplex 7-segment LCD ---
            // The PCF8576 drives four 7-segment digits from a single I2C bus; the host
            // encodes each digit using the chip's 1:4 multiplex bit layout (a/c/b/DP/f/e/g/d)
            // and writes all four with one writeRaw() call. The countdown runs once per
            // second and the terminal mirrors the value sent to the display.
            var lcd = new Pcf8576Full(transport);                          // construct driver, (transport) → Pcf8576Full

            for (int n = 9999; n >= 0; n--) {
                int d0 = (n / 1000) % 10;
                int d1 = (n / 100) % 10;
                int d2 = (n / 10) % 10;
                int d3 = n % 10;
                byte[] out = new byte[]{
                    (byte) Pcf8576Full.SEVEN_SEG[d0],                     // encode 7-segment digit, (digit 0–9) → int
                    (byte) Pcf8576Full.SEVEN_SEG[d1],                     // encode 7-segment digit, (digit 0–9) → int
                    (byte) Pcf8576Full.SEVEN_SEG[d2],                     // encode 7-segment digit, (digit 0–9) → int
                    (byte) Pcf8576Full.SEVEN_SEG[d3],                     // encode 7-segment digit, (digit 0–9) → int
                };
                lcd.writeRaw(0, out);                                      // write all four digits, (address 0, 4 bytes) → void
                System.out.printf("countdown: %04d%n", n);
                Thread.sleep(1000);
            }

            // --- Stop indicator: light only the middle segments (g) on every digit ---
            // When the counter reaches zero we replace the "0000" pattern with "----" to
            // signal that the demo has finished. Each digit's g segment is bit 1, so a
            // 0x02 byte lights just the bar across the middle.
            byte[] dash = new byte[]{0x02, 0x02, 0x02, 0x02};
            lcd.writeRaw(0, dash);                                         // write dash pattern, (address 0, 4 bytes) → void
            System.out.println("countdown complete");
        }
    }
}
