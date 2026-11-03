// UC8159 minimal example: Waveshare 5.65inch e-Paper (F) on a Raspberry Pi.
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
'use strict';
const { Default } = require('opengpio');
const { DisplayConnection, WireMode } = require('../../../src/connection/display');
const { GpioOutputPin } = require('../../../src/connection/output_pin');
const { PollingInputPin } = require('../../../src/connection/input_pin');
const { UC8159Minimal, WHITE } = require('../../../src/chips/display/uc8159');

const dc   = new GpioOutputPin(Default.output({ chip: 0, line: 25 }));   // Create D/C# pin, (chip, line=25) → OutputPin
const res  = new GpioOutputPin(Default.output({ chip: 0, line: 17 }));   // Create RES# pin, (chip, line=17) → OutputPin
const busyIn = Default.input({ chip: 0, line: 24 });
const busy = new PollingInputPin(() => (busyIn.value ? 1 : 0), 1000);    // Create BUSY pin, (chip, line=24) → InputPin
const connection = new DisplayConnection({ busNumber: 0, deviceNumber: 0, dc, res, busy }); // Create display connection, (busNumber, deviceNumber, dc, res, busy)
const epd = new UC8159Minimal(connection);                              // Create UC8159 driver, (connection, {busyTimeoutMs=60000})

(async () => {
    await epd.ready();
    epd.clear(WHITE);                                                    // Fill framebuffer, (color=WHITE)
    for (let x = 0; x < 600; x++) {
        const colour = Math.floor((x * 7) / 600);                        // seven vertical bars: black .. orange
        for (let y = 0; y < 448; y++) epd.setPixel(x, y, colour);        // Set one pixel, (x, y, color=BLACK)
    }
    await epd.show();                                                    // Refresh panel (about 30 s), () → Promise<void>
    await epd.sleep();                                                   // Enter deep sleep, () → Promise<void>
    connection.close();
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
