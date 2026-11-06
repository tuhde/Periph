'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { PCF8578Full } = require('../../../src/chips/display/pcf8578');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x3C', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const lcd = new PCF8578Full(connection, 16);                 // Create PCF8578 driver, (connection, rows=16 8/16/24/32)
                                                             // clears RAM and switches the display on in mixed mode

(async () => {
    await lcd.ready();                                       // Wait for initialisation, () → void
                                                             // resolves once the RAM is cleared and the display is on
    await lcd.clear();                                       // Clear display RAM, () → void
                                                             // zeroes all 4 banks x 40 columns and the shadow
    await lcd.setPixel(1, 1);                                // Set one pixel, (x 0–23, y 0–15, on=true) → void
                                                             // writes a single RAM byte from the shadow
    await lcd.writeColumns(4, 0, [0xFF, 0x81, 0xFF]);        // Write column bytes, (x 0–23, bank 0–1, data bytes) → void
                                                             // character access mode, bit 0 = top row of the bank
    const on = await lcd.getPixel(1, 1);                     // Read pixel from shadow, (x, y) → boolean
                                                             // no bus traffic
    await lcd.fill(true);                                    // Fill display, (on=true) → void
                                                             // every displayed pixel on
    await lcd.fill(false);                                   // Fill display, (on=true) → void
                                                             // every displayed pixel off
    await lcd.drawLine(0, 0, 23, 15);                        // Draw line, (x0, y0, x1, y1, on=true) → void
                                                             // Bresenham, written as one batch of changed bytes
    await lcd.drawRect(0, 0, 24, 16);                        // Draw rectangle outline, (x, y, w, h, on=true, fill=false) → void
                                                             // border around the whole panel
    await lcd.drawRect(8, 4, 8, 8, true, true);              // Draw filled rectangle, (x, y, w, h, on=true, fill=false) → void
                                                             // solid 8x8 block in the middle
    await lcd.showBitmap(2, 8, [[0x18, 0x24, 0x42, 0x81]]);  // Blit bitmap, (x, y multiple of 8, bands array of column bytes) → void
                                                             // one 8-pixel-high band at the lower bank
    await lcd.setDisplay(PCF8578Full.DISPLAY_INVERSE);       // Set display status, (status 0 blank/1 normal/2 all on/3 inverse) → void
                                                             // inverse video; RAM is preserved
    await lcd.setDisplay(PCF8578Full.DISPLAY_NORMAL);        // Set display status, (status 0–3) → void
                                                             // back to normal video
    await lcd.setMode(24, true);                             // Set multiplex rate, (rows 8/16/24/32, mixed=true) → void
                                                             // 1:24 drive; mixed mode keeps RAM accessible
    await lcd.setMode(16, true);                             // Set multiplex rate, (rows 8/16/24/32, mixed=true) → void
                                                             // back to 1:16
    await lcd.setStartBank(1);                               // Set start bank, (bank 0–3) → void
                                                             // shows RAM bank 1 at the top (hardware scroll)
    await lcd.scroll(-1);                                    // Scroll by banks, (banks number) → void
                                                             // adds -1 to the start bank modulo banks in use
    await lcd.writeRam(16, 0, [0xAA, 0x55]);                 // Write raw RAM, (x 0–39, bank 0–3, data bytes, mode=0) → void
                                                             // character order at RAM column 16
    const data = await lcd.readRam(16, 0, 2);                // Read raw RAM, (x 0–39, bank 0–3, n, mode=0) → Buffer
                                                             // address-set commands, repeated start, then a read
    await lcd.deviceSelect(0);                               // Select device subaddress, (subaddress 0–15) → void
                                                             // 0 addresses this PCF8578; others target cascaded PCF8579s
    await lcd.reset();                                       // Re-initialise, () → void
                                                             // clears RAM and restores normal video

    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
