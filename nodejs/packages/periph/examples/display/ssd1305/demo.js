// SSD1305 demo example: 1.3" 128 x 64 OLED module (SSD1305, RAM columns 4..131) over I2C on a Raspberry Pi (Node.js).
// Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VCC = 3V3 (OLED supply VCC 7..15 V from the module, no charge pump); address 0x3C (SA0 = low) or 0x3D (SA0 = high). Demo: a bouncing ball inside a border, a contrast fade, inverse flashes, hardware scroll and sleep.
'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { SSD1305Full } = require('../../../src/chips/display/ssd1305');

const connection = new I2CConnection(1, 0x3C); // Create I2C connection, (busNumber=1, addr=0x3C)
const lcd = new SSD1305Full(connection); // Create SSD1305 driver, (connection, width=128, height=64, columnOffset=4)

(async () => {
    await lcd.ready();
    // --- Draw the border once ---
    // A one-pixel border around the 128 x 64 panel; the ball bounces inside it.
    lcd.clear();                                                      // Clear framebuffer, ()
    lcd.drawRect(0, 0, 128, 64);                                      // Draw rectangle, (x, y, w, h, on=true, fill=false)
    await lcd.show();                                                 // Send framebuffer to the display, () → Promise<void>

    // --- Bounce a 6 x 6 ball at about 20 frames per second ---
    // The ball is erased and redrawn in the framebuffer, then the frame is sent with show().
    let bx = 20, by = 12, dx = 3, dy = 2;
    for (let frame = 0; frame < 200; frame++) {
        lcd.drawRect(bx, by, 6, 6, false, true);                      // Draw rectangle, (x, y, w, h, on=true, fill=false)
        bx += dx;
        by += dy;
        if (bx < 2 || bx > 120) { dx = -dx; bx += 2 * dx; }
        if (by < 2 || by > 56) { dy = -dy; by += 2 * dy; }
        lcd.drawRect(bx, by, 6, 6, true, true);                       // Draw rectangle, (x, y, w, h, on=true, fill=false)
        await lcd.show();                                             // Send framebuffer to the display, () → Promise<void>
        await new Promise((r) => setTimeout(r, 50));
    }

    // --- Fade the contrast down and back up ---
    // 255 -> 0 -> 255 in steps of 5 shows the full range of the segment drive current.
    for (let v = 255; v >= 0; v -= 5) {
        await lcd.setContrast(v);                                     // Set contrast, (value 0..255) → Promise<void>
        await new Promise((r) => setTimeout(r, 20));
    }
    for (let v = 0; v <= 255; v += 5) {
        await lcd.setContrast(v);                                     // Set contrast, (value 0..255) → Promise<void>
        await new Promise((r) => setTimeout(r, 20));
    }

    // --- Flash the inverse display twice ---
    for (let i = 0; i < 2; i++) {
        await lcd.setInvert(true);                                    // Invert display, (on) → Promise<void>
        await new Promise((r) => setTimeout(r, 400));
        await lcd.setInvert(false);                                   // Invert display, (on) → Promise<void>
        await new Promise((r) => setTimeout(r, 400));
    }

    // --- Dim mode ---
    // Dim mode lowers contrast and colour-bank brightness to the levels stored with setDimMode.
    await lcd.setDimMode(0x10, 0x10);                                 // Configure dim levels, (contrast 0..255, brightness 0..255) → Promise<void>
    await lcd.setDim(true);                                           // Dim mode, (on) → Promise<void>
    await new Promise((r) => setTimeout(r, 2000));
    await lcd.setDim(false);                                          // Dim mode, (on) → Promise<void>

    // --- Hardware scroll ---
    // The scroll engine moves all eight pages right in the controller without re-sending any data;
    // stopping rewrites the RAM from the framebuffer as the datasheet requires.
    await lcd.setScrollHorizontal(true, 0, 7, 0);                     // Set horizontal scroll, (right, startPage, endPage, interval 0..6, columns=1) → Promise<void>
    await lcd.scrollStart();                                          // Start scrolling, () → Promise<void>
    await new Promise((r) => setTimeout(r, 5000));
    await lcd.scrollStop();                                           // Stop scrolling, () → Promise<void>

    // --- Power down ---
    // Sleep turns the display off (switch VCC off afterwards); RAM and registers are kept.
    await lcd.sleep();                                                // Enter sleep, () → Promise<void>
    connection.close();
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
