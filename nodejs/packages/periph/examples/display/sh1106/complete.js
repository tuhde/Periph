// SH1106 complete example: 1.3" 128 x 64 OLED module (SH1106, RAM columns 2..129) over I2C on a Raspberry Pi (Node.js).
// Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VCC = 3V3; address 0x3C (SA0 = VSS) or 0x3D (SA0 = VDD1). Complete: every Full method - drawing, contrast, invert, flip, scroll, drive tuning, reads, sleep.
'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { SH1106Full } = require('../../../src/chips/display/sh1106');

const connection = new I2CConnection(1, 0x3C); // Create I2C connection, (busNumber=1, addr=0x3C)
const lcd = new SH1106Full(connection); // Create SH1106 driver, (connection, width=128, height=64, columnOffset=2, internalDcdc=true)

(async () => {
    await lcd.ready();
    // Framebuffer drawing: everything is composed in RAM and sent with show().
    lcd.clear();                                                      // Clear framebuffer, ()
                                                                      // zeroes every pixel of the framebuffer; the panel is unchanged until show()
    lcd.fill(true);                                                   // Fill framebuffer, (on=true)
                                                                      // sets (or clears) every pixel
    lcd.fill(false);                                                  // Fill framebuffer, (on=true)
    lcd.drawRect(0, 0, 128, 64);                                      // Draw rectangle, (x, y, w, h, on=true, fill=false)
                                                                      // one-pixel outline around the whole panel
    lcd.drawLine(0, 0, 127, 63);                                      // Draw line, (x0, y0, x1, y1, on=true)
                                                                      // Bresenham line into the framebuffer
    lcd.drawLine(0, 63, 127, 0);                                      // Draw line, (x0, y0, x1, y1, on=true)
    lcd.drawRect(10, 10, 30, 20, true, true);                         // Draw rectangle, (x, y, w, h, on=true, fill=false)
                                                                      // filled rectangle
    lcd.drawRect(18, 16, 14, 8, false, true);                         // Draw rectangle, (x, y, w, h, on=true, fill=false)
                                                                      // on=false clears pixels: a hole in the filled rectangle
    lcd.setPixel(64, 32, true);                                       // Set pixel, (x, y, on=true)
    const lit = lcd.getPixel(64, 32);                                 // Read framebuffer pixel, (x, y) → boolean
                                                                      // reads the framebuffer, not the panel
    const icon = Buffer.from([0x3C, 0x42, 0x81, 0xA5, 0x81, 0xA5, 0x99, 0x42, 0x3C, 0x00, 0x7E, 0x81, 0x81, 0x81, 0x7E, 0x00]);
    lcd.blit(100, 16, icon, 2);                                       // Copy bitmap, (x, y multiple of 8, columns, heightPages=1)
                                                                      // two page rows of 8 columns, bit 0 = top pixel
    await lcd.show();                                                 // Send framebuffer to the display, () → Promise<void>
                                                                      // per page: page + column command, then 128 data bytes in 31-byte chunks
    await new Promise((r) => setTimeout(r, 2000));
    await lcd.showPage(2);                                            // Send one framebuffer page, (page 0..7) → Promise<void>
                                                                      // cheaper partial update of 8 rows

    // Contrast and display modes.
    await lcd.setContrast(0x20);                                      // Set contrast, (value 0..255) → Promise<void>
                                                                      // segment drive current; reset value is 0x80
    await new Promise((r) => setTimeout(r, 800));
    await lcd.setContrast(0x80);                                      // Set contrast, (value 0..255) → Promise<void>
    await lcd.setInvert(true);                                        // Invert display, (on) → Promise<void>
                                                                      // RAM 0 = pixel on; RAM is unchanged
    await new Promise((r) => setTimeout(r, 800));
    await lcd.setInvert(false);                                       // Invert display, (on) → Promise<void>
    await lcd.setAllOn(true);                                         // Force all pixels on, (on) → Promise<void>
                                                                      // test mode, RAM is kept
    await new Promise((r) => setTimeout(r, 800));
    await lcd.setAllOn(false);                                        // Force all pixels on, (on) → Promise<void>
    await lcd.setFlip(true, true);                                    // Mirror display, (horizontal, vertical) → Promise<void>
                                                                      // segment re-map and COM scan direction; framebuffer unchanged
    await new Promise((r) => setTimeout(r, 1500));
    await lcd.setFlip(false, false);                                  // Mirror display, (horizontal, vertical) → Promise<void>

    // Hardware vertical scroll and drive tuning.
    for (let line = 0; line < 64; line += 2) {
        await lcd.setStartLine(line);                                 // Scroll the image up, (line 0..63) → Promise<void>
                                                                      // RAM line shown at COM0; the image wraps around
        await new Promise((r) => setTimeout(r, 30));
    }
    await lcd.setStartLine(0);                                        // Scroll the image up, (line 0..63) → Promise<void>
    await lcd.setDisplayOffset(0);                                    // Set COM offset, (offset 0..63) → Promise<void>
                                                                      // shifts the COM mapping
    await lcd.setMultiplex(64);                                       // Set multiplex ratio, (ratio 1..64) → Promise<void>
                                                                      // number of driven rows
    await lcd.setClock(1, 5);                                         // Set clock, (divide 1..16, osc 0..15) → Promise<void>
                                                                      // divide ratio 1 at the nominal oscillator frequency
    await lcd.setPrecharge(2, 2);                                     // Set pre-charge, (precharge 1..15, discharge 1..15) → Promise<void>
                                                                      // pre-charge and discharge periods in display clocks
    await lcd.setComConfig(true);                                     // Set COM pin configuration, (alternative) → Promise<void>
                                                                      // alternative (0x12) is the reset value and right for 128 x 64 modules
    await lcd.setVcomDeselect(0x35);                                  // Set VCOM deselect level, (value 0..255) → Promise<void>
    await lcd.setPumpVoltage(0);                                      // Set pump voltage, (level 0..3) → Promise<void>
                                                                      // 0 = 7.4 V, 1 = 8.0 V, 2 = 8.4 V, 3 = 9.0 V

    // Reads (I2C only) and raw commands.
    const status = await lcd.readStatus();                            // Read status, () → Promise<number>
                                                                      // bit 7 BUSY, bit 6 = 1 when the display is off
    const ram = await lcd.readPage(0, 2, 8);                          // Read display RAM, (page 0..7, column, n) → Promise<Buffer>
                                                                      // sets the address and discards the dummy byte
    await lcd.sendCommand(0xE3);                                      // Send raw command stream, (...bytes) → Promise<void>
                                                                      // NOP

    // Power.
    await lcd.setDisplay(false);                                      // Display on/off, (on) → Promise<void>
                                                                      // RAM and settings are kept
    await new Promise((r) => setTimeout(r, 1000));
    await lcd.setDisplay(true);                                       // Display on/off, (on) → Promise<void>
    await lcd.sleep();                                                // Enter sleep, () → Promise<void>
                                                                      // display off; the DC-DC stops
    await new Promise((r) => setTimeout(r, 1000));
    await lcd.wake();                                                 // Leave sleep, () → Promise<void>
                                                                      // display on and a 100 ms wait for VPP
    await lcd.setDcdc(true);                                          // Switch the internal DC-DC, (on) → Promise<void>
                                                                      // issue while the display is off; true = 0x8B, false = 0x8A for an external VPP
    console.log(`status 0x${status.toString(16)}, lit ${lit}, ram ${ram.toString("hex")}`);
    connection.close();
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
