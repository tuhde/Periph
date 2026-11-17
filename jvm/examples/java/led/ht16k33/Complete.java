///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.0
//DEPS it.uhde:periph-java:1.5.0

import it.uhde.periph.chips.led.HT16K33Full;
import it.uhde.periph.connection.I2CConnection;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x38"));
        try (var connection = new I2CConnection(bus, addr)) {              // open I²C bus, (bus, address=0x38) → I2CConnection
            var lcd = new HT16K33Full(connection);                          // construct driver, (connection) → HT16K33Full
            lcd.clear();                                                  // blank the display, () → void
                                                                           // zeros all 40 columns of display RAM
            lcd.deviceSelect(0);                                          // select device on the bus, (subaddress 0–7) → void
                                                                           // sets the subaddress counter for cascaded use
            lcd.setMode(HT16K33Full.BACKPLANES_4, HT16K33Full.BIAS_1_3_FULL);  // set drive mode, (backplanes 1–4, bias 0/1) → void
                                                                           // configures 1:4 multiplex with 1/3 bias
            lcd.setBlink(HT16K33Full.BLINK_2_HZ, false);                  // set blink frequency, (frequency 0–3, alternateBank=false) → void
                                                                           // ~2 Hz blink for visual attention
            lcd.setBank(HT16K33Full.BANK_0, HT16K33Full.BANK_0);          // select RAM bank, (inputBank 0/1, outputBank 0/1) → void
                                                                           // selects rows 0-1 for both input and output

            int[] digits = {5, 6, 7, 8};
            byte[] out = new byte[digits.length];
            for (int i = 0; i < digits.length; i++) {
                out[i] = (byte) HT16K33Full.SEVEN_SEG[digits[i]];         // encode 7-segment digit, (digit 0–9) → int
            }
            lcd.writeRaw(0, out);                                          // write raw bytes, (address 0–39, data) → void
                                                                           // sets data pointer to 0 and writes all four digits

            lcd.disable();                                                 // disable display output, () → void
                                                                           // blanks the panel while keeping RAM contents
            lcd.enable();                                                  // enable display output, () → void
                                                                           // resumes output from RAM with the prior configuration
        }
    }
}
