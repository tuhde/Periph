///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.DisplayConnection;
import it.uhde.periph.connection.SysfsOutputPin;
import it.uhde.periph.connection.WireMode;
import it.uhde.periph.chips.display.St7036Full;

// ST7036 demo example: EA DOGM162W-A (2 x 16, 3.3 V) on a Raspberry Pi, a two-line status display: a title line and a bar graph built from five custom characters that fills, then a contrast sweep.
// Wiring: SI=GPIO10 (SPI0 MOSI), SCL=GPIO11, CSB=GPIO8 (CE0), RS=GPIO25 (C/D), RESET=GPIO17; PSB=GND, E=3V3 (no BUSY line).
// Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.

public class Demo {
    public static void main(String[] args) throws Exception {
        var dc = new SysfsOutputPin(25);                                                                        // create RS (C/D) pin, (gpio=25) → OutputPin
        var res = new SysfsOutputPin(17);                                                                       // create RESET pin, (gpio=17) → OutputPin
        var connection = DisplayConnection.hardware(0, 0, 1000000, dc, res, null, WireMode.SIMPLEX);            // open display connection, (bus, device, speedHz=1 MHz, dc, res, busy=null, wireMode)
        var lcd = new St7036Full(connection);                                                                   // construct driver, (connection, lines=2, columns=0 default, supply=SUPPLY_3V3, contrast=-1 preset) → St7036Full

        // --- Define the bar-graph glyphs ---
        // Custom characters 0..4 are columns of 1..5 filled pixel columns, so a 16-character bar
        // can show 80 steps with the eight CGRAM slots.
        for (int g = 0; g < 5; g++) {
            int pattern = (0x1F << (4 - g)) & 0x1F;
            int[] rows = {0, pattern, pattern, pattern, pattern, pattern, pattern, 0};
            lcd.createChar(g, rows);                                                                                // define user character, (index 0..7, rows[8])
        }

        // --- Static title line ---
        lcd.clear();                                                                                            // clear display, ()
        lcd.printAt(0, 0, "Periph ST7036");                                                                     // write text, (col, row, text)

        // --- Animate a progress bar on line 2 ---
        // Each step fills 1/5 of a character cell; a full bar is 16 x 5 = 80 steps.
        for (int step = 0; step <= 80; step++) {
            lcd.setCursor(0, 1);                                                                                    // move cursor, (col, row)
            for (int col = 0; col < 16; col++) {
                int fill = step - col * 5;                // pixel columns of this cell that are filled
                lcd.writeChar(fill >= 5 ? 4 : fill > 0 ? fill - 1 : 0x20);                                              // write raw character code, (code)
            }
            Thread.sleep(40);
        }
        Thread.sleep(1000);

        // --- Contrast sweep ---
        // The contrast is set once in normal use; sweeping it shows the usable range of the module.
        lcd.printAt(0, 0, "Contrast sweep ");                                                                   // write text, (col, row, text)
        for (int c = 0x10; c <= 0x30; c++) {
            lcd.setContrast(c);                                                                                     // set contrast, (value 0..63)
            Thread.sleep(80);
        }
        lcd.setContrast(0x18);                                                                                  // set contrast, (value 0..63)
        lcd.printAt(0, 0, "Periph ST7036   ");                                                                  // write text, (col, row, text)
        connection.close();
    }
}
