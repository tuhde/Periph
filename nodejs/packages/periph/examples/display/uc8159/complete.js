// UC8159 complete example: every Full method, Waveshare 5.65inch e-Paper (F) on a Raspberry Pi.
// Wiring: DIN=GPIO10, CLK=GPIO11, CS=GPIO8, DC=GPIO25, RST=GPIO17, BUSY=GPIO24. The read* calls
// need DOUT: spi-device has no 3-wire mode, so join DIN and DOUT through a ~1 kOhm resistor to MISO
// (GPIO9) and use HALF_DUPLEX_PIN. Each refresh takes about 17 s.
'use strict';
const { Default } = require('opengpio');
const { DisplayConnection, WireMode } = require('../../../src/connection/display');
const { GpioOutputPin } = require('../../../src/connection/output_pin');
const { PollingInputPin } = require('../../../src/connection/input_pin');
const { UC8159Full, BLACK, WHITE, GREEN, BLUE, RED, ORANGE } = require('../../../src/chips/display/uc8159');

const dc   = new GpioOutputPin(Default.output({ chip: 0, line: 25 }));   // Create D/C# pin, (chip, line=25) → OutputPin
const res  = new GpioOutputPin(Default.output({ chip: 0, line: 17 }));   // Create RES# pin, (chip, line=17) → OutputPin
const busyIn = Default.input({ chip: 0, line: 24 });
const busy = new PollingInputPin(() => (busyIn.value ? 1 : 0), 1000);    // Create BUSY pin, (chip, line=24) → InputPin
const connection = new DisplayConnection({
    busNumber: 0, deviceNumber: 0, maxSpeedHz: 1000000, dc, res, busy,
    wireMode: WireMode.HALF_DUPLEX_PIN,
});                                                                      // Create display connection, ({busNumber, deviceNumber, maxSpeedHz, dc, res, busy, wireMode})
const epd = new UC8159Full(connection);                                 // Create UC8159 driver, (connection, {busyTimeoutMs=60000})
                                                                         // reset and the vendor init commands; panel unchanged until show()

(async () => {
    await epd.ready();
    epd.clear(WHITE);                                                    // Fill framebuffer, (color=WHITE)
                                                                         // every pixel set to white
    epd.setPixel(5, 5, BLACK);                                           // Set one pixel, (x, y, color=BLACK)
                                                                         // colour code 0..7
    epd.drawRect(20, 20, 200, 120, RED, true);                           // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
                                                                         // filled red block
    epd.drawRect(240, 20, 200, 120, BLUE);                               // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
                                                                         // blue outline
    epd.drawLine(0, 0, 599, 447, GREEN);                                 // Draw line, (x0, y0, x1, y1, color=BLACK)
                                                                         // Bresenham diagonal
    epd.blit(460, 20, [0xFF, 0x81, 0x81, 0xFF], 8, 4, ORANGE);           // Copy 1-bpp bitmap, (x, y, data, w, h, color=BLACK)
                                                                         // 8 x 4 box, MSB first
    const colour = epd.getPixel(30, 30);                                 // Read framebuffer pixel, (x, y) → number colour code
                                                                         // RED here
    await epd.setFlip(false, false);                                     // Mirror output, (horizontal, vertical)
                                                                         // PSR.SHL / UD at power-on values
    await epd.setFrameRate(0x3C);                                        // Set PLL frame rate code, (code)
                                                                         // 50 Hz, the vendor value
    await epd.setInterval(7, WHITE, 1);                                  // Set VCOM/data interval, (cdi 0–15, border=WHITE, polarity=1)
                                                                         // white border, 10 hsync
    await epd.setTemperatureSensor(false, 0);                            // Select temperature sensor, (external, offset -8..7 °C)
                                                                         // internal sensor, no offset
    const status = await epd.readStatus();                               // Read FLG, () → number bitfield
                                                                         // needs DOUT wired (half-duplex)
    const temperature = await epd.readTemperature();                     // Read sensor, () → number °C
                                                                         // signed internal temperature
    await epd.show();                                                    // Refresh panel, () → Promise<void>
                                                                         // DTM1, PON, DRF, POF; takes about 30 s
    const ready = await epd.waitBusy(1000);                              // Wait for BUSY high, (timeoutMs) → boolean
                                                                         // true when the controller is idle
    await epd.sendCommand(0x50, [0x37]);                                 // Send raw command, (command, data=[])
                                                                         // rewrites CDI with the vendor value
    await epd.sleep();                                                   // Enter deep sleep, () → Promise<void>
    await epd.wake();                                                    // Reset and re-initialise, () → Promise<void>
                                                                         // framebuffer is kept
    console.log({ colour, status, temperature, ready });
    connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
