// ST7036 demo example: a two-line status display with a bar graph on an EA DOGM162W-A (2 x 16, 3.3 V) on a Raspberry Pi (Node.js).
// Wiring: SI=GPIO10 (SPI0 MOSI), SCL=GPIO11, CSB=GPIO8 (CE0), RS=GPIO25 (C/D), RESET=GPIO17; PSB=GND, E=3V3 (no BUSY line).
// Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
'use strict';
const { Default } = require('opengpio');
const { DisplayConnection } = require('../../../src/connection/display');
const { GpioOutputPin } = require('../../../src/connection/output_pin');
const { ST7036Full } = require('../../../src/chips/display/st7036');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const dc   = new GpioOutputPin(Default.output({ chip: 0, line: 25 }));   // Create RS (C/D) pin, (chip, line=25) → OutputPin
const res  = new GpioOutputPin(Default.output({ chip: 0, line: 17 }));   // Create RESET pin, (chip, line=17) → OutputPin
const connection = new DisplayConnection({ busNumber: 0, deviceNumber: 0, dc, res, maxSpeedHz: 1000000 }); // Create display connection, (busNumber, deviceNumber, dc, res, busy=none, maxSpeedHz=1 MHz)
const lcd = new ST7036Full(connection);                                     // Create ST7036 driver, (connection, lines=2, columns=null, supply=SUPPLY_3V3, contrast=null)

(async () => {
    await lcd.ready();
    // --- Define the bar-graph glyphs ---
    // Custom characters 0..4 are columns of 1..5 filled pixel columns, so a 16-character bar
    // can show 80 steps with the eight CGRAM slots.
    for (let g = 0; g < 5; g++) {
        const pattern = (0x1F << (4 - g)) & 0x1F;
        const rows = [0, 1, 2, 3, 4, 5, 6, 7].map((r) => (r === 0 || r === 7 ? 0 : pattern));
        await lcd.createChar(g, rows);                               // Define user character, (index 0..7, rows[8]) → Promise<void>
    }

    // --- Static title line ---
    await lcd.clear();                                               // Clear display, () → Promise<void>
    await lcd.printAt(0, 0, 'Periph ST7036');                        // Write text, (col, row, text) → Promise<void>

    // --- Animate a progress bar on line 2 ---
    // Each step fills 1/5 of a character cell; a full bar is 16 x 5 = 80 steps.
    for (let step = 0; step <= 80; step++) {
        await lcd.setCursor(0, 1);                                   // Move cursor, (col, row) → Promise<void>
        for (let col = 0; col < 16; col++) {
            const fill = step - col * 5;                             // pixel columns of this cell that are filled
            if (fill >= 5) await lcd.writeChar(4);                   // Write raw character code, (code) → Promise<void>
            else if (fill > 0) await lcd.writeChar(fill - 1);        // Write raw character code, (code) → Promise<void>
            else await lcd.writeChar(0x20);                          // Write raw character code, (code) → Promise<void>
        }
        await sleep(40);
    }
    await sleep(1000);

    // --- Contrast sweep ---
    // The contrast is set once in normal use; sweeping it shows the usable range of the module.
    await lcd.printAt(0, 0, 'Contrast sweep ');                      // Write text, (col, row, text) → Promise<void>
    for (let c = 0x10; c <= 0x30; c++) {
        await lcd.setContrast(c);                                    // Set contrast, (value 0..63) → Promise<void>
        await sleep(80);
    }
    await lcd.setContrast(0x18);                                     // Set contrast, (value 0..63) → Promise<void>
    await lcd.printAt(0, 0, 'Periph ST7036   ');                     // Write text, (col, row, text) → Promise<void>
    connection.close();
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
