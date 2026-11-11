///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0
// SH1106 complete example: 1.3" 128 x 64 OLED module (SH1106, RAM columns 2..129) over I2C on a Raspberry Pi (JVM).
// Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VCC = 3V3; address 0x3C (SA0 = VSS) or 0x3D (SA0 = VDD1). Complete: every Full method - drawing, contrast, invert, flip, scroll, drive tuning, reads, sleep.

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.display.SH1106Full;

public class Complete {
    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x3C"));
        try (var conn = new I2CConnection(bus, addr)) {                       // open I2C connection, (bus, address=0x3C) → I2CConnection
            var lcd = new SH1106Full(conn);                                   // construct driver, (connection, width=128, height=64, columnOffset=2, internalDcdc=true) → SH1106Full

            // Framebuffer drawing: everything is composed in RAM and sent with show().
            lcd.clear();                                                      // clear framebuffer, () → void
                                                                              // zeroes every pixel of the framebuffer; the panel is unchanged until show()
            lcd.fill(true);                                                   // fill framebuffer, (on) → void
                                                                              // sets (or clears) every pixel
            lcd.fill(false);                                                  // fill framebuffer, (on) → void
            lcd.drawRect(0, 0, 128, 64, true, false);                         // draw rectangle, (x, y, w, h, on, fill) → void
                                                                              // one-pixel outline around the whole panel
            lcd.drawLine(0, 0, 127, 63, true);                                // draw line, (x0, y0, x1, y1, on) → void
                                                                              // Bresenham line into the framebuffer
            lcd.drawLine(0, 63, 127, 0, true);                                // draw line, (x0, y0, x1, y1, on) → void
            lcd.drawRect(10, 10, 30, 20, true, true);                         // draw rectangle, (x, y, w, h, on, fill) → void
                                                                              // filled rectangle
            lcd.drawRect(18, 16, 14, 8, false, true);                         // draw rectangle, (x, y, w, h, on, fill) → void
                                                                              // on=false clears pixels: a hole in the filled rectangle
            lcd.setPixel(64, 32, true);                                       // set pixel, (x, y, on) → void
            boolean lit = lcd.getPixel(64, 32);                               // read framebuffer pixel, (x, y) → boolean
                                                                              // reads the framebuffer, not the panel
            int[] iconInts = {0x3C, 0x42, 0x81, 0xA5, 0x81, 0xA5, 0x99, 0x42, 0x3C, 0x00, 0x7E, 0x81, 0x81, 0x81, 0x7E, 0x00};
            byte[] icon = new byte[iconInts.length];
            for (int i = 0; i < icon.length; i++) icon[i] = (byte) iconInts[i];
            lcd.blit(100, 16, icon, 2);                                       // copy bitmap, (x, y multiple of 8, columns, heightPages) → void
                                                                              // two page rows of 8 columns, bit 0 = top pixel
            lcd.show();                                                       // send framebuffer to the display, () → void
                                                                              // per page: page + column command, then 128 data bytes in 31-byte chunks
            Thread.sleep(2000);
            lcd.showPage(2);                                                  // send one framebuffer page, (page 0..7) → void
                                                                              // cheaper partial update of 8 rows

            // Contrast and display modes.
            lcd.setContrast(0x20);                                            // set contrast, (value 0..255) → void
                                                                              // segment drive current; reset value is 0x80
            Thread.sleep(800);
            lcd.setContrast(0x80);                                            // set contrast, (value 0..255) → void
            lcd.setInvert(true);                                              // invert display, (on) → void
                                                                              // RAM 0 = pixel on; RAM is unchanged
            Thread.sleep(800);
            lcd.setInvert(false);                                             // invert display, (on) → void
            lcd.setAllOn(true);                                               // force all pixels on, (on) → void
                                                                              // test mode, RAM is kept
            Thread.sleep(800);
            lcd.setAllOn(false);                                              // force all pixels on, (on) → void
            lcd.setFlip(true, true);                                          // mirror display, (horizontal, vertical) → void
                                                                              // segment re-map and COM scan direction; framebuffer unchanged
            Thread.sleep(1500);
            lcd.setFlip(false, false);                                        // mirror display, (horizontal, vertical) → void

            // Hardware vertical scroll and drive tuning.
            for (int line = 0; line < 64; line += 2) {
                lcd.setStartLine(line);                                       // scroll the image up, (line 0..63) → void
                                                                              // RAM line shown at COM0; the image wraps around
                Thread.sleep(30);
            }
            lcd.setStartLine(0);                                              // scroll the image up, (line 0..63) → void
            lcd.setDisplayOffset(0);                                          // set COM offset, (offset 0..63) → void
                                                                              // shifts the COM mapping
            lcd.setMultiplex(64);                                             // set multiplex ratio, (ratio 1..64) → void
                                                                              // number of driven rows
            lcd.setClock(1, 5);                                               // set clock, (divide 1..16, osc 0..15) → void
                                                                              // divide ratio 1 at the nominal oscillator frequency
            lcd.setPrecharge(2, 2);                                           // set pre-charge, (precharge 1..15, discharge 1..15) → void
                                                                              // pre-charge and discharge periods in display clocks
            lcd.setComConfig(true);                                           // set COM pin configuration, (alternative) → void
                                                                              // alternative (0x12) is the reset value and right for 128 x 64 modules
            lcd.setVcomDeselect(0x35);                                        // set VCOM deselect level, (value 0..255) → void
            lcd.setPumpVoltage(0);                                            // set pump voltage, (level 0..3) → void
                                                                              // 0 = 7.4 V, 1 = 8.0 V, 2 = 8.4 V, 3 = 9.0 V

            // Reads (I2C only) and raw commands.
            int status = lcd.readStatus();                                    // read status, () → int
                                                                              // bit 7 BUSY, bit 6 = 1 when the display is off
            byte[] ram = lcd.readPage(0, 2, 8);                               // read display RAM, (page 0..7, column, n) → byte[]
                                                                              // sets the address and discards the dummy byte
            lcd.sendCommand(0xE3);                                            // send raw command stream, (bytes...) → void
                                                                              // NOP

            // Power.
            lcd.setDisplay(false);                                            // display on/off, (on) → void
                                                                              // RAM and settings are kept
            Thread.sleep(1000);
            lcd.setDisplay(true);                                             // display on/off, (on) → void
            lcd.sleep();                                                      // enter sleep, () → void
                                                                              // display off; the DC-DC stops
            Thread.sleep(1000);
            lcd.wake();                                                       // leave sleep, () → void
                                                                              // display on and a 100 ms wait for VPP
            lcd.setDcdc(true);                                                // switch the internal DC-DC, (on) → void
                                                                              // issue while the display is off; true = 0x8B, false = 0x8A for an external VPP
            System.out.println("status 0x" + Integer.toHexString(status) + ", lit " + lit + ", ram bytes " + ram.length);
        }
    }
}
