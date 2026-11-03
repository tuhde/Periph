// ST7036 complete example: every Full method on an EA DOGM162W-A (2 x 16, 3.3 V) on a Raspberry Pi (Node.js).
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
    await lcd.clear();                                               // Clear display, () → Promise<void>
                                                                     // writes spaces to all of DDRAM, cursor to home (2 ms)
    await lcd.printAt(0, 0, 'Complete example');                     // Write text, (col, row, text) → Promise<void>
    await lcd.setCursor(0, 1);                                       // Move cursor, (col, row) → Promise<void>
    await lcd.write('Cursor + blink');                               // Write text at the cursor, (text) → Promise<void>
    await lcd.cursor(true, true);                                    // Cursor, (on, blink=false) → Promise<void>
    await sleep(2000);
    await lcd.cursor(false, false);                                  // Cursor, (on, blink=false) → Promise<void>

    const heart = [0x00, 0x0A, 0x1F, 0x1F, 0x0E, 0x04, 0x00, 0x00];
    const arrow = [0x04, 0x02, 0x1F, 0x02, 0x04, 0x00, 0x00, 0x00];
    await lcd.createChar(0, heart);                                  // Define user character, (index 0..7, rows[8]) → Promise<void>
                                                                     // 8 rows of 5 bits into CGRAM, DDRAM address restored
    await lcd.createChar(1, arrow);                                  // Define user character, (index 0..7, rows[8]) → Promise<void>
    await lcd.clear();                                               // Clear display, () → Promise<void>
    await lcd.setCursor(0, 0);                                       // Move cursor, (col, row) → Promise<void>
    await lcd.write('I ');                                           // Write text at the cursor, (text) → Promise<void>
    await lcd.writeChar(0);                                          // Write raw character code, (code) → Promise<void>
    await lcd.write(' custom ');                                     // Write text at the cursor, (text) → Promise<void>
    await lcd.writeChar(1);                                          // Write raw character code, (code) → Promise<void>
    await sleep(2000);

    await lcd.setContrast(0x08);                                     // Set contrast, (value 0..63) → Promise<void>
    await lcd.printAt(0, 1, 'low contrast');                         // Write text, (col, row, text) → Promise<void>
    await sleep(1500);
    await lcd.setContrast(0x30);                                     // Set contrast, (value 0..63) → Promise<void>
    await lcd.printAt(0, 1, 'high contrast');                        // Write text, (col, row, text) → Promise<void>
    await sleep(1500);
    await lcd.setContrast(0x18);                                     // Set contrast, (value 0..63) → Promise<void>
                                                                     // 0x18 is the DOGM162 3.3 V preset
    await lcd.setBias(false);                                        // LCD bias, (quarter=false) → Promise<void>, 1/5 bias
    await lcd.setFollower(true, 5);                                  // Voltage follower, (on, ratio 0..7) → Promise<void>, 1 + Rb/Ra = 2.5
    await lcd.setBooster(true);                                      // Voltage booster, (on) → Promise<void>

    await lcd.clear();                                               // Clear display, () → Promise<void>
    await lcd.setEntryMode(true, false);                             // Entry mode, (increment, shift=false) → Promise<void>
    await lcd.printAt(0, 0, 'Shift display ->');                     // Write text, (col, row, text) → Promise<void>
    await lcd.printAt(0, 1, '0123456789ABCDEF');                     // Write text, (col, row, text) → Promise<void>
    await sleep(1000);
    for (let i = 0; i < 6; i++) {
        await lcd.shiftDisplay(true);                                // Shift display, (right) → Promise<void>
        await sleep(300);
    }
    await lcd.returnHome();                                          // Return home, () → Promise<void>, undoes the display shift
    await lcd.shiftCursor(true);                                     // Shift cursor, (right) → Promise<void>
    await lcd.setDdramAddress(0x40);                                 // Set DDRAM address, (address) → Promise<void>
    await sleep(1000);

    await lcd.display(false);                                        // Display on/off, (on) → Promise<void>
    await sleep(800);
    await lcd.display(true);                                         // Display on/off, (on) → Promise<void>
    connection.close();
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
