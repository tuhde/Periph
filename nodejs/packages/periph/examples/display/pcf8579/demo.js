'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { PCF8579Full } = require('../../../src/chips/display/pcf8579');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x3C', 16);

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

// --- Initialise a 16 x 40 column driver ---
// 1:16 multiplex gives 16 rows and 40 columns; the constructor clears the RAM
// before switching the display on so no random start-up pattern is shown.
const lcd = new PCF8579Full(connection, 0, 16);                 // Create PCF8579 driver, (connection, subaddress=0 0–15, rows=16 8/16/24/32, mixed=false)

(async () => {
    await lcd.ready();

    // --- Draw a border and both diagonals ---
    // Lines and rectangles are rendered in the shadow buffer and sent as one
    // batch of changed bytes per run of columns.
    await lcd.drawRect(0, 0, 40, 16);                        // Draw rectangle outline, (x, y, w, h, on=true, fill=false) → void
    await lcd.drawLine(0, 0, 39, 15);                        // Draw line, (x0, y0, x1, y1, on=true) → void
    await lcd.drawLine(0, 15, 39, 0);                        // Draw line, (x0, y0, x1, y1, on=true) → void

    // --- Flash the display twice with inverse video ---
    // Inverse video only changes how the RAM is shown, so the picture is kept.
    for (let i = 0; i < 2; i++) {
        await lcd.setDisplay(PCF8579Full.DISPLAY_INVERSE);   // Set display status, (status 0–3) → void
        await sleep(400);
        await lcd.setDisplay(PCF8579Full.DISPLAY_NORMAL);    // Set display status, (status 0–3) → void
        await sleep(400);
    }

    // --- Fill all four RAM banks with distinct patterns ---
    // At 1:16 only banks 0 and 1 are visible; banks 2 and 3 act as off-screen
    // buffers that the start bank can scroll into view.
    const patterns = [0x55, 0x33, 0x0F, 0xFF];
    for (let bank = 0; bank < 4; bank++) {
        await lcd.writeRam(0, bank, new Array(40).fill(patterns[bank]));  // Write raw RAM, (x 0–39, bank 0–3, data bytes, mode=0) → void
    }

    // --- Scroll through the banks with the hardware start bank ---
    // Stepping the start bank scrolls the display by 8 rows without rewriting RAM.
    for (let bank = 0; bank < 4; bank++) {
        await lcd.setStartBank(bank);                        // Set start bank, (bank 0–3) → void
        const first = await lcd.readRam(0, bank, 1);        // Read raw RAM, (x 0–39, bank 0–3, n, mode=0) → Buffer
        console.log('start bank', bank, 'first column byte', '0x' + first[0].toString(16));
        await sleep(1000);
    }

    // --- Read back the first 8 RAM columns of bank 0 ---
    // Writing a counting pattern and reading it back verifies the bus path.
    await lcd.setStartBank(0);                               // Set start bank, (bank 0–3) → void
    await lcd.writeRam(0, 0, [1, 2, 3, 4, 5, 6, 7, 8]);      // Write raw RAM, (x 0–39, bank 0–3, data bytes, mode=0) → void
    console.log(Array.from(await lcd.readRam(0, 0, 8)));     // Read raw RAM, (x 0–39, bank 0–3, n, mode=0) → Buffer

    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
