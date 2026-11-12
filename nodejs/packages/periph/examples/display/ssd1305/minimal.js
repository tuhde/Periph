// SSD1305 minimal example: 1.3" 128 x 64 OLED module (SSD1305, RAM columns 4..131) over I2C on a Raspberry Pi (Node.js).
// Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VCC = 3V3 (OLED supply VCC 7..15 V from the module, no charge pump); address 0x3C (SA0 = low) or 0x3D (SA0 = high). Minimal use: a border and a diagonal on the 128 x 64 panel.
'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { SSD1305Minimal } = require('../../../src/chips/display/ssd1305');

const connection = new I2CConnection(1, 0x3C); // Create I2C connection, (busNumber=1, addr=0x3C)
const lcd = new SSD1305Minimal(connection); // Create SSD1305 driver, (connection, width=128, height=64, columnOffset=4)

(async () => {
    await lcd.ready();
    lcd.clear();                                                      // Clear framebuffer, ()
    for (let x = 0; x < 128; x++) {
        lcd.setPixel(x, 0);                                           // Set pixel, (x 0..127, y 0..63, on=true)
        lcd.setPixel(x, 63);                                          // Set pixel, (x 0..127, y 0..63, on=true)
        lcd.setPixel(x, Math.floor(x / 2));                           // Set pixel, (x 0..127, y 0..63, on=true)
    }
    for (let y = 0; y < 64; y++) {
        lcd.setPixel(0, y);                                           // Set pixel, (x 0..127, y 0..63, on=true)
        lcd.setPixel(127, y);                                         // Set pixel, (x 0..127, y 0..63, on=true)
    }
    await lcd.show();                                                 // Send framebuffer to the display, () → Promise<void>
    connection.close();
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
