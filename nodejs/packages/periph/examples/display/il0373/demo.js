// IL0373 demo: status card on a Waveshare 2.13inch e-Paper (D), Raspberry Pi (Node.js).
// Wiring: DIN=GPIO10, CLK=GPIO11, CS=GPIO8, DC=GPIO25, RST=GPIO17, BUSY=GPIO24.
// The printed refresh time is the number the spec's Timing Constraints are missing.
'use strict';
const { Default } = require('opengpio');
const { DisplayConnection, WireMode } = require('../../../src/connection/display');
const { GpioOutputPin } = require('../../../src/connection/output_pin');
const { PollingInputPin } = require('../../../src/connection/input_pin');
const { IL0373Full, BLACK, WHITE } = require('../../../src/chips/display/il0373');

const dc   = new GpioOutputPin(Default.output({ chip: 0, line: 25 }));   // Create D/C# pin, (chip, line=25) → OutputPin
const res  = new GpioOutputPin(Default.output({ chip: 0, line: 17 }));   // Create RES# pin, (chip, line=17) → OutputPin
const busyIn = Default.input({ chip: 0, line: 24 });
const busy = new PollingInputPin(() => (busyIn.value ? 1 : 0), 1000);    // Create BUSY pin, (chip, line=24) → InputPin
const connection = new DisplayConnection({ busNumber: 0, deviceNumber: 0, dc, res, busy }); // Create display connection, (busNumber, deviceNumber, dc, res, busy)
const epd = new IL0373Full(connection);                                 // Create IL0373 driver, (connection, {width=104, height=212, busyTimeoutMs=30000})

(async () => {
    await epd.ready();

    // --- Compose the status card in the framebuffer ---
    // White page, a one-pixel black border inset by 2 px (a frame on the very edge is
    // hidden by the panel bezel) and a black header bar across the top 24 rows.
    epd.clear(WHITE);                                                    // Fill framebuffer, (color=WHITE)
    epd.drawRect(2, 2, 100, 208, BLACK);                                 // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
    epd.drawRect(3, 3, 98, 24, BLACK, true);                              // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)

    // --- Bar chart of eight readings ---
    // Axes first, then one filled bar per value; the tallest bar is filled and the others are outlines.
    const values = [30, 55, 80, 45, 120, 90, 60, 100];
    epd.drawLine(10, 190, 95, 190, BLACK);                              // Draw line, (x0, y0, x1, y1, color=BLACK)
    epd.drawLine(10, 60, 10, 190, BLACK);                                // Draw line, (x0, y0, x1, y1, color=BLACK)
    const peak = Math.max(...values);
    values.forEach((v, i) => {
        epd.drawRect(14 + i * 10, 190 - v, 8, v, BLACK, v === peak); // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
    });

    // --- Refresh and time it ---
    // show() resolves after the whole DTM1/DTM2/DRF/POF sequence.
    const start = Date.now();
    await epd.show();                                                    // Refresh panel, () → Promise<void>
    console.log('refresh took', (Date.now() - start) / 1000, 's');

    // --- Save the image and power down ---
    // The panel keeps the picture without power; deep sleep stops the controller drawing current.
    await epd.sleep();                                                   // Enter deep sleep, () → Promise<void>
    connection.close();
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
