// UC8159 demo: status card on a Waveshare 5.65inch e-Paper (F), Raspberry Pi (Node.js).
// Wiring: DIN=GPIO10, CLK=GPIO11, CS=GPIO8, DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
// The printed refresh time is the number the spec's Timing Constraints are missing.
'use strict';
const { Default } = require('opengpio');
const { DisplayConnection, WireMode } = require('../../../src/connection/display');
const { GpioOutputPin } = require('../../../src/connection/output_pin');
const { PollingInputPin } = require('../../../src/connection/input_pin');
const { UC8159Full, BLACK, WHITE } = require('../../../src/chips/display/uc8159');

const dc   = new GpioOutputPin(Default.output({ chip: 0, line: 25 }));   // Create D/C# pin, (chip, line=25) → OutputPin
const res  = new GpioOutputPin(Default.output({ chip: 0, line: 17 }));   // Create RES# pin, (chip, line=17) → OutputPin
const busyIn = Default.input({ chip: 0, line: 24 });
const busy = new PollingInputPin(() => (busyIn.value ? 1 : 0), 1000);    // Create BUSY pin, (chip, line=24) → InputPin
const connection = new DisplayConnection({ busNumber: 0, deviceNumber: 0, dc, res, busy }); // Create display connection, (busNumber, deviceNumber, dc, res, busy)
const epd = new UC8159Full(connection);                                 // Create UC8159 driver, (connection, {busyTimeoutMs=60000})

(async () => {
    await epd.ready();

    // --- Compose the colour chart in the framebuffer ---
    // White page, seven vertical bars (black, white, green, blue, red, yellow, orange) and a
    // one-pixel black frame inset by 4 px (a frame on the very edge is hidden by the bezel).
    epd.clear(WHITE);                                                    // Fill framebuffer, (color=WHITE)
    for (let c = 0; c < 7; c++) {
        epd.drawRect(20 + c * 80, 40, 80, 368, c, true);                 // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
    }
    epd.drawRect(4, 4, 592, 440, BLACK);                                 // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)

    // --- Refresh and time it ---
    // show() resolves after the whole DTM1/PON/DRF/POF sequence: about 30 s.
    const start = Date.now();
    await epd.show();                                                    // Refresh panel, () → Promise<void>
    console.log('refresh took', (Date.now() - start) / 1000, 's');

    // --- Save the image and power down ---
    // The panel keeps the picture without power; deep sleep stops the controller drawing current.
    await epd.sleep();                                                   // Enter deep sleep, () → Promise<void>
    connection.close();
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
