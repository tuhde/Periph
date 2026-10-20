// UC1611s minimal example: EA DOGXL240-7 (240 x 128, 3.3 V only) over I2C on a Raspberry Pi (Node.js).
// Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VDD = 3V3; module pins BM0 = D13 = VDD, CD = A2 = A3 = VSS -> command 0x38 / data 0x39.
// Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD; pull-ups 2-4.7 k to 3.3 V. Minimal use: a border and a diagonal on the 240 x 128 panel.
'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { UC1611sMinimal } = require('../../../src/chips/display/uc1611s');

const command = new I2CConnection(1, 0x38);                                // Create command connection, (busNumber=1, addr=0x38)
const data = new I2CConnection(1, 0x39);                                   // Create data connection, (busNumber=1, addr=0x39)
const lcd = new UC1611sMinimal(command, data);                         // Create UC1611s driver, (command, data, width=240, height=128)

(async () => {
    await lcd.ready();
    lcd.clear();                                                      // Clear framebuffer, ()
    for (let x = 0; x < 240; x++) {
        lcd.setPixel(x, 0);                                           // Set pixel, (x 0..239, y 0..127, on=true)
        lcd.setPixel(x, 127);                                         // Set pixel, (x 0..239, y 0..127, on=true)
        lcd.setPixel(x, Math.floor(x * 127 / 239));                   // Set pixel, (x 0..239, y 0..127, on=true)
    }
    for (let y = 0; y < 128; y++) {
        lcd.setPixel(0, y);                                           // Set pixel, (x 0..239, y 0..127, on=true)
        lcd.setPixel(239, y);                                         // Set pixel, (x 0..239, y 0..127, on=true)
    }
    await lcd.show();                                                 // Send framebuffer to the display, () → Promise<void>
    command.close();
    data.close();
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
