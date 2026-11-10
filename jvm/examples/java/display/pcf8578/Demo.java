///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.display.PCF8578Full;

public class Demo {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x3C"));
        try (var connection = new I2CConnection(bus, addr)) {   // open I²C bus, (bus, address=0x3C) → I2CConnection
            // --- Initialise a 16 x 24 dot-matrix panel ---
            // 1:16 multiplex gives 16 rows and 24 columns; the constructor clears the RAM
            // before switching the display on so no random start-up pattern is shown.
            var lcd = new PCF8578Full(connection, 16);   // construct driver, (connection, rows=16 8/16/24/32) → PCF8578Full

            // --- Draw a border and both diagonals ---
            // Lines and rectangles are rendered in the shadow buffer and sent as one
            // batch of changed bytes per run of columns.
            lcd.drawRect(0, 0, 24, 16, true, false);   // draw rectangle outline, (x, y, w, h, on, fill) → void
            lcd.drawLine(0, 0, 23, 15, true);   // draw line, (x0, y0, x1, y1, on) → void
            lcd.drawLine(0, 15, 23, 0, true);   // draw line, (x0, y0, x1, y1, on) → void

            // --- Flash the display twice with inverse video ---
            // Inverse video only changes how the RAM is shown, so the picture is kept.
            for (int i = 0; i < 2; i++) {
                lcd.setDisplay(PCF8578Full.DISPLAY_INVERSE);   // set display status, (status 0–3) → void
                Thread.sleep(400);
                lcd.setDisplay(PCF8578Full.DISPLAY_NORMAL);   // set display status, (status 0–3) → void
                Thread.sleep(400);
            }

            // --- Fill all four RAM banks with distinct patterns ---
            // At 1:16 only banks 0 and 1 are visible; banks 2 and 3 act as off-screen
            // buffers that the start bank can scroll into view.
            int[] patterns = {0x55, 0x33, 0x0F, 0xFF};
            for (int bank = 0; bank < 4; bank++) {
                byte[] col = new byte[24];
                java.util.Arrays.fill(col, (byte) patterns[bank]);
                lcd.writeRam(16, bank, col, PCF8578Full.ACCESS_CHARACTER);   // write raw RAM, (x 0–39, bank 0–3, data bytes, mode) → void
            }

            // --- Scroll through the banks with the hardware start bank ---
            // Stepping the start bank scrolls the display by 8 rows without rewriting RAM.
            for (int bank = 0; bank < 4; bank++) {
                lcd.setStartBank(bank);   // set start bank, (bank 0–3) → void
                byte[] first = lcd.readRam(16, bank, 1, PCF8578Full.ACCESS_CHARACTER);   // read raw RAM, (x 0–39, bank 0–3, n, mode) → byte[]
                System.out.printf("start bank %d first column byte 0x%02X%n", bank, first[0]);
                Thread.sleep(1000);
            }

            // --- Read back the first 8 RAM columns of bank 0 ---
            // RAM columns 0-7 are never displayed at 1:16, so they serve as scratch
            // storage; the chip supports reads in mixed mode, so the bytes can be verified.
            lcd.setStartBank(0);   // set start bank, (bank 0–3) → void
            lcd.writeRam(0, 0, new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, PCF8578Full.ACCESS_CHARACTER);   // write raw RAM, (x 0–39, bank 0–3, data bytes, mode) → void
            System.out.println(java.util.Arrays.toString(lcd.readRam(0, 0, 8, PCF8578Full.ACCESS_CHARACTER)));   // read raw RAM, (x 0–39, bank 0–3, n, mode) → byte[]
        }
    }
}
