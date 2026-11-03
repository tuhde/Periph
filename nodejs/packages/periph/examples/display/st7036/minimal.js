// ST7036 minimal example: two lines of text on an EA DOGM162W-A (2 x 16, 3.3 V) on a Raspberry Pi (Node.js).
// Wiring: SI=GPIO10 (SPI0 MOSI), SCL=GPIO11, CSB=GPIO8 (CE0), RS=GPIO25 (C/D), RESET=GPIO17; PSB=GND, E=3V3 (no BUSY line).
// Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
'use strict';
const { Default } = require('opengpio');
const { DisplayConnection } = require('../../../src/connection/display');
const { GpioOutputPin } = require('../../../src/connection/output_pin');
const { ST7036Minimal } = require('../../../src/chips/display/st7036');

const dc   = new GpioOutputPin(Default.output({ chip: 0, line: 25 }));   // Create RS (C/D) pin, (chip, line=25) → OutputPin
const res  = new GpioOutputPin(Default.output({ chip: 0, line: 17 }));   // Create RESET pin, (chip, line=17) → OutputPin
const connection = new DisplayConnection({ busNumber: 0, deviceNumber: 0, dc, res, maxSpeedHz: 1000000 }); // Create display connection, (busNumber, deviceNumber, dc, res, busy=none, maxSpeedHz=1 MHz)
const lcd = new ST7036Minimal(connection);                                  // Create ST7036 driver, (connection)

(async () => {
    await lcd.ready();
    await lcd.printAt(0, 0, 'Hello, Periph!');                       // Write text, (col, row, text) → Promise<void>
    await lcd.printAt(0, 1, 'ST7036 DOGM162');                       // Write text, (col, row, text) → Promise<void>
    connection.close();
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
