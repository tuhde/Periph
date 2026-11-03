// IL0373 minimal example: Waveshare 2.13inch e-Paper (D) on a Raspberry Pi.
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
'use strict';
const { Default } = require('opengpio');
const { DisplayConnection, WireMode } = require('../../../src/connection/display');
const { GpioOutputPin } = require('../../../src/connection/output_pin');
const { PollingInputPin } = require('../../../src/connection/input_pin');
const { IL0373Minimal, BLACK, WHITE } = require('../../../src/chips/display/il0373');

const dc   = new GpioOutputPin(Default.output({ chip: 0, line: 25 }));   // Create D/C# pin, (chip, line=25) → OutputPin
const res  = new GpioOutputPin(Default.output({ chip: 0, line: 17 }));   // Create RES# pin, (chip, line=17) → OutputPin
const busyIn = Default.input({ chip: 0, line: 24 });
const busy = new PollingInputPin(() => (busyIn.value ? 1 : 0), 1000);    // Create BUSY pin, (chip, line=24) → InputPin
const connection = new DisplayConnection({ busNumber: 0, deviceNumber: 0, dc, res, busy }); // Create display connection, (busNumber, deviceNumber, dc, res, busy)
const epd = new IL0373Minimal(connection);                              // Create IL0373 driver, (connection, {width=104, height=212, busyTimeoutMs=30000})

(async () => {
    await epd.ready();
    epd.clear(WHITE);                                                    // Fill framebuffer, (color=WHITE)
    epd.setPixel(10, 10, BLACK);                                         // Set one pixel, (x, y, color=BLACK)
    for (let x = 20; x < 100; x++) epd.setPixel(x, 40, BLACK);             // Set one pixel, (x, y, color=BLACK)
    await epd.show();                                                    // Refresh panel , () → Promise<void>
    await epd.sleep();                                                   // Enter deep sleep, () → Promise<void>
    connection.close();
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
