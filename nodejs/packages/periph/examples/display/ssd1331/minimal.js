// SSD1331 minimal example: Waveshare 0.95inch RGB OLED on a Raspberry Pi (Node.js).
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17 (no BUSY line).
'use strict';
const { Default } = require('opengpio');
const { DisplayConnection } = require('../../../src/connection/display');
const { GpioOutputPin } = require('../../../src/connection/output_pin');
const { SSD1331Minimal, BLACK, WHITE, RED, GREEN, BLUE, YELLOW, CYAN, MAGENTA } = require('../../../src/chips/display/ssd1331');

const dc   = new GpioOutputPin(Default.output({ chip: 0, line: 25 }));   // Create D/C# pin, (chip, line=25) → OutputPin
const res  = new GpioOutputPin(Default.output({ chip: 0, line: 17 }));   // Create RES# pin, (chip, line=17) → OutputPin
const connection = new DisplayConnection({ busNumber: 0, deviceNumber: 0, dc, res }); // Create display connection, (busNumber, deviceNumber, dc, res, busy=none)
const oled = new SSD1331Minimal(connection);                                        // Create SSD1331 driver, (connection)

(async () => {
    await oled.ready();
    const bars = [WHITE, YELLOW, CYAN, GREEN, MAGENTA, RED, BLUE, BLACK];
    for (let y = 0; y < oled.height; y++) {
        for (let x = 0; x < oled.width; x++) oled.setPixel(x, y, bars[Math.floor(x / 12)]);   // Set one pixel, (x, y, color=WHITE)
    }
    for (let x = 0; x < oled.width; x++) {
        oled.setPixel(x, 0, WHITE);                                      // Set one pixel, (x, y, color=WHITE)
        oled.setPixel(x, oled.height - 1, WHITE);                        // Set one pixel, (x, y, color=WHITE)
    }
    await oled.show();                                                   // Send framebuffer, () → Promise<void>
    connection.close();
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
