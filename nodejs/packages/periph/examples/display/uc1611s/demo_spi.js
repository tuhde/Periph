// UC1611s demo example (4-wire SPI): EA DOGXL240-7 (240 x 128, 3.3 V only) on a Raspberry Pi (Node.js).
// Wiring: SI=GPIO10 (SPI0 MOSI), SCK=GPIO11, CS0=GPIO8 (CE0), CD=GPIO25, RST=GPIO17; VDD=3V3; module pins BM0 = D13 = VSS (4-wire SPI).
// Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD. Demo: a bouncing ball with partial page updates, a contrast sweep, inverse flashes, hardware scroll, a second RAM screen and sleep.
'use strict';
const { Default } = require('opengpio');
const { DisplayConnection } = require('../../../src/connection/display');
const { GpioOutputPin } = require('../../../src/connection/output_pin');
const { UC1611sSpiFull } = require('../../../src/chips/display/uc1611s');

const dc   = new GpioOutputPin(Default.output({ chip: 0, line: 25 }));   // Create CD (D/C) pin, (chip, line=25) → OutputPin
const res  = new GpioOutputPin(Default.output({ chip: 0, line: 17 }));   // Create RST pin, (chip, line=17) → OutputPin
const connection = new DisplayConnection({ busNumber: 0, deviceNumber: 0, dc, res, maxSpeedHz: 4000000 }); // Create display connection, (busNumber, deviceNumber, dc, res, busy=none, maxSpeedHz=4 MHz)
const lcd = new UC1611sSpiFull(connection);                              // Create UC1611s driver, (connection, width=240, height=128, topView=false, contrast=0x8F)

(async () => {
    await lcd.ready();
    // --- Draw the frame once ---
    // A one-pixel border around the whole 240 x 128 panel; the ball bounces inside it.
    lcd.clear();                                                      // Clear framebuffer, ()
    lcd.drawRect(0, 0, 240, 128);                                     // Draw rectangle, (x, y, w, h, on=true, fill=false)
    await lcd.show();                                                 // Send framebuffer to the display, () → Promise<void>
    
    // --- Bounce an 8 x 8 ball with partial page updates ---
    // Only the page rows the ball touches are re-sent, so the animation runs at about
    // 20 frames per second, instead of the 8 of a full-frame show().
    let bx = 20, by = 12, dx = 3, dy = 2;
    for (let frame = 0; frame < 200; frame++) {
        const oldy = by;
        lcd.drawRect(bx, by, 8, 8, false, true);                      // Draw rectangle, (x, y, w, h, on=true, fill=false)
        bx += dx;
        by += dy;
        if (bx < 2 || bx > 229) { dx = -dx; bx += 2 * dx; }
        if (by < 2 || by > 118) { dy = -dy; by += 2 * dy; }
        lcd.drawRect(bx, by, 8, 8, true, true);                       // Draw rectangle, (x, y, w, h, on=true, fill=false)
        for (let page = Math.floor(Math.min(oldy, by) / 8); page <= Math.floor((Math.max(oldy, by) + 7) / 8); page++) {
            await lcd.showPage(page);                                 // Send one framebuffer page, (page 0..15) → Promise<void>
        }
        await sleep(20);
    }
    
    // --- Contrast sweep around the working point ---
    // The contrast is set once in normal use; sweeping PM shows the usable range of the panel.
    for (let pm = 0x8F; pm >= 0x70; pm -= 2) {
        await lcd.setContrast(pm);                                    // Set contrast, (value 0..255) → Promise<void>
        await sleep(40);
    }
    for (let pm = 0x70; pm <= 0x8F; pm += 2) {
        await lcd.setContrast(pm);                                    // Set contrast, (value 0..255) → Promise<void>
        await sleep(40);
    }
    
    // --- Flash the inverse display twice ---
    for (let i = 0; i < 2; i++) {
        await lcd.setInvert(true);                                    // Invert display, (on) → Promise<void>
        await sleep(400);
        await lcd.setInvert(false);                                   // Invert display, (on) → Promise<void>
        await sleep(400);
    }
    
    // --- Hardware scroll ---
    // The image moves up in the controller, no data is re-sent; a blank band enters from the bottom
    // because RAM rows 128..159 are empty.
    for (let line = 0; line <= 32; line++) {
        await lcd.setScrollLine(line);                                // Scroll the image up, (line 0..159 - 2 * fixed) → Promise<void>
        await sleep(40);
    }
    for (let line = 32; line >= 0; line--) {
        await lcd.setScrollLine(line);                                // Scroll the image up, (line 0..159 - 2 * fixed) → Promise<void>
        await sleep(40);
    }
    
    // --- Second screen in RAM ---
    // The chip holds four black/white screens; draw a cross into screen 1 and switch to it.
    lcd.setWritePattern(1);                                           // Select RAM screen to write, (pattern 0..3)
    lcd.fill(false);                                                  // Fill framebuffer, (on=true)
    lcd.drawLine(0, 0, 239, 127);                                     // Draw line, (x0, y0, x1, y1, on=true)
    lcd.drawLine(0, 127, 239, 0);                                     // Draw line, (x0, y0, x1, y1, on=true)
    await lcd.show();                                                 // Send framebuffer to the display, () → Promise<void>
    await lcd.setDisplayPattern(1);                                   // Select displayed RAM screen, (pattern 0..3) → Promise<void>
    await sleep(2000);
    await lcd.setDisplayPattern(0);                                   // Select displayed RAM screen, (pattern 0..3) → Promise<void>
    lcd.setWritePattern(0);                                           // Select RAM screen to write, (pattern 0..3)
    await sleep(1500);
    
    // --- Power down ---
    // Sleep stops the charge pump and the drivers (below 1 uA); RAM and registers are kept.
    await lcd.sleep();                                                // Enter sleep, () → Promise<void>
    console.log('done');
    connection.close();
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
