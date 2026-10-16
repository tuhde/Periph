// SSD1331 demo example: a colour test card on a Waveshare 0.95inch RGB OLED, Raspberry Pi (Node.js).
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17 (no BUSY line).
'use strict';
const { Default } = require('opengpio');
const { DisplayConnection } = require('../../../src/connection/display');
const { GpioOutputPin } = require('../../../src/connection/output_pin');
const { SSD1331Full, rgb, BLACK, WHITE, RED, GREEN, BLUE, YELLOW, CYAN, MAGENTA } = require('../../../src/chips/display/ssd1331');

const dc   = new GpioOutputPin(Default.output({ chip: 0, line: 25 }));   // Create D/C# pin, (chip, line=25) → OutputPin
const res  = new GpioOutputPin(Default.output({ chip: 0, line: 17 }));   // Create RES# pin, (chip, line=17) → OutputPin
const connection = new DisplayConnection({ busNumber: 0, deviceNumber: 0, dc, res }); // Create display connection, (busNumber, deviceNumber, dc, res, busy=none)
const oled = new SSD1331Full(connection);                                        // Create SSD1331 driver, (connection)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    await oled.ready();

    // --- Draw the test card into the framebuffer ---
    // Eight 12-pixel colour bars over a 26-row gray ramp show that all channels and the
    // 5-6-5 depth work; the one-pixel frame shows that the whole 96 x 64 area is addressed.
    const bars = [WHITE, YELLOW, CYAN, GREEN, MAGENTA, RED, BLUE, BLACK];
    oled.clear(BLACK);                                                   // Fill framebuffer, (color=BLACK)
    for (let x = 0; x < 96; x++) {
        oled.drawRect(x, 2, 1, 30, bars[Math.floor(x / 12)], true);      // Filled rectangle, (x, y, w, h, color=WHITE, fill=false)
        const g = Math.round(x * 255 / 95);
        oled.drawRect(x, 34, 1, 26, rgb(g, g, g), true);                 // Filled rectangle, (x, y, w, h, color=WHITE, fill=false)
    }
    oled.drawRect(0, 0, 96, 64, WHITE);                                  // Rectangle outline, (x, y, w, h, color=WHITE, fill=false)

    // --- Send it once ---
    await oled.show();                                                   // Send framebuffer, () → Promise<void>
    await sleep(3000);

    // --- Animate with hardware drawing ---
    // The chip draws the marker straight into its RAM: no framebuffer transfer per step.
    for (let step = 0; step < 60; step++) {
        const x = 4 + (step * 3) % 80;
        await oled.show();                                               // Send framebuffer, () → Promise<void>
        await oled.hwRect(x, 36, 10, 10, WHITE, RED, true);             // Hardware rectangle, (x, y, w, h, lineColor, fillColor=0, fill=false)
        await sleep(50);
    }

    // --- Scroll the test card ---
    // The chip scrolls its own RAM, one column every 6 frames.
    await oled.show();                                                   // Send framebuffer, () → Promise<void>
    await oled.scroll(1, 0, 64, 0, 0);                                   // Scroll, (horizontal, startRow, rows, vertical, interval 0..3)
    connection.close();
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
