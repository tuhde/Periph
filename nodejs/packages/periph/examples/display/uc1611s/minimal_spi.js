// UC1611s minimal example (4-wire SPI): EA DOGXL240-7 (240 x 128, 3.3 V only) on a Raspberry Pi (Node.js).
// Wiring: SI=GPIO10 (SPI0 MOSI), SCK=GPIO11, CS0=GPIO8 (CE0), CD=GPIO25, RST=GPIO17; VDD=3V3; module pins BM0 = D13 = VSS (4-wire SPI).
// Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD. Minimal use: a border and a diagonal on the 240 x 128 panel.
'use strict';
const { Default } = require('opengpio');
const { DisplayConnection } = require('../../../src/connection/display');
const { GpioOutputPin } = require('../../../src/connection/output_pin');
const { UC1611sSpiMinimal } = require('../../../src/chips/display/uc1611s');

const dc   = new GpioOutputPin(Default.output({ chip: 0, line: 25 }));   // Create CD (D/C) pin, (chip, line=25) → OutputPin
const res  = new GpioOutputPin(Default.output({ chip: 0, line: 17 }));   // Create RST pin, (chip, line=17) → OutputPin
const connection = new DisplayConnection({ busNumber: 0, deviceNumber: 0, dc, res, maxSpeedHz: 4000000 }); // Create display connection, (busNumber, deviceNumber, dc, res, busy=none, maxSpeedHz=4 MHz)
const lcd = new UC1611sSpiMinimal(connection);                              // Create UC1611s driver, (connection, width=240, height=128)

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
    connection.close();
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
