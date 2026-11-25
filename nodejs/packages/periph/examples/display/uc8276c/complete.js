// UC8276C complete example: every Full method, Waveshare 4.2inch e-Paper (B) on a Raspberry Pi.
// Wiring: DIN=GPIO10, CLK=GPIO11, CS=GPIO8, DC=GPIO25, RST=GPIO17, BUSY=GPIO24. The read* calls
// need DOUT: spi-device has no 3-wire mode, so join DIN and DOUT through a ~1 kOhm resistor to MISO
// (GPIO9) and use HALF_DUPLEX_PIN. Each refresh takes about 17 s.
'use strict';
const { Default } = require('opengpio');
const { DisplayConnection, WireMode } = require('../../../src/connection/display');
const { GpioOutputPin } = require('../../../src/connection/output_pin');
const { PollingInputPin } = require('../../../src/connection/input_pin');
const { UC8276CFull, BLACK, WHITE, RED, MODE_KWR } = require('../../../src/chips/display/uc8276c');

const dc   = new GpioOutputPin(Default.output({ chip: 0, line: 25 }));   // Create D/C# pin, (chip, line=25) → OutputPin
const res  = new GpioOutputPin(Default.output({ chip: 0, line: 17 }));   // Create RES# pin, (chip, line=17) → OutputPin
const busyIn = Default.input({ chip: 0, line: 24 });
const busy = new PollingInputPin(() => (busyIn.value ? 1 : 0), 1000);    // Create BUSY_N pin, (chip, line=24) → InputPin
const connection = new DisplayConnection({
    busNumber: 0, deviceNumber: 0, maxSpeedHz: 1000000, dc, res, busy,
    wireMode: WireMode.HALF_DUPLEX_PIN,
});                                                                      // Create display connection, ({busNumber, deviceNumber, maxSpeedHz, dc, res, busy, wireMode})
const epd = new UC8276CFull(connection);                                 // Create UC8276C driver, (connection, {width=400, height=300, busyTimeoutMs=30000})
                                                                         // reset, PSR 0x0F, CDI 0xD7; panel unchanged until show()

(async () => {
    await epd.ready();
    epd.clear(WHITE);                                                    // Fill framebuffer, (color=WHITE)
                                                                         // both planes set to white
    epd.setPixel(5, 5, BLACK);                                           // Set one pixel, (x, y, color=BLACK)
                                                                         // clears the B/W bit for that pixel
    epd.drawRect(20, 20, 100, 60, RED, true);                            // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
                                                                         // filled red block
    epd.drawLine(0, 0, 399, 299, BLACK);                                 // Draw line, (x0, y0, x1, y1, color=BLACK)
                                                                         // Bresenham diagonal
    epd.blit(150, 20, [0xFF, 0x81, 0x81, 0xFF], 8, 4);                   // Copy 1-bpp bitmap, (x, y, data, w, h, color=BLACK)
                                                                         // 8 x 4 box, MSB first
    const colour = epd.getPixel(30, 30);                                 // Read framebuffer pixel, (x, y) → number colour
                                                                         // RED here
    await epd.setMode(MODE_KWR);                                         // Select pixel mode, (mode=MODE_KWR)
                                                                         // PSR.KW/R cleared: black/white/red
    await epd.setFlip(false, false);                                     // Mirror output, (horizontal, vertical)
                                                                         // PSR.SHL / UD at power-on values
    await epd.setPower(10.0, 10.0, 10.0, 16.0);                          // Set source/gate power, (vdh V, vdl V, vdhr V, vgh=16 V)
                                                                         // panel-layout PWR: +-10 V sources, +-16 V gates
    await epd.setFrameRate(50);                                          // Set frame rate, (hz 4–200)
                                                                         // nearest PLL M/N entry (M7 N4)
    await epd.setSoftStart(0x17, 0x17, 0x17);                            // Set booster soft start, (a, b, c)
                                                                         // vendor BTST phases
    await epd.setInterval(7);                                            // Set VCOM/data interval, (cdi 0–15, border=3, polarity=1)
                                                                         // keeps DDX = 01 so the framebuffer mapping holds
    await epd.setTemperatureSensor(false, 0);                            // Select temperature sensor, (external, offset -8..7 °C)
                                                                         // internal sensor, no offset
    const status = await epd.readStatus();                               // Read FLG, () → number bitfield
                                                                         // needs DOUT wired (half-duplex)
    const temperature = await epd.readTemperature();                     // Read sensor, () → number °C
                                                                         // signed internal temperature
    const revision = await epd.readRevision();                           // Read REV, (length=1) → [chipRev, lutRev]
                                                                         // panel form: chipRev is null
    await epd.show();                                                    // Refresh panel, () → Promise<void>
                                                                         // DTM1, DTM2, PON, DRF, POF; takes ~17 s
    epd.drawRect(40, 100, 80, 40, BLACK, true);                          // Draw rectangle, (x, y, w, h, color=BLACK, fill=false)
    await epd.showRegion(40, 100, 80, 40);                               // Refresh a window, (x, y, w, h)
                                                                         // sends only the window's rows (RAM saving)
    const ready = await epd.waitBusy(1000);                              // Wait for BUSY_N high, (timeoutMs) → boolean
                                                                         // true when the controller is idle
    await epd.sendCommand(0x50, [0xD7]);                                 // Send raw command, (command, data=[])
                                                                         // rewrites CDI with its default
    await epd.sleep();                                                   // Enter deep sleep, () → Promise<void>
    await epd.wake();                                                    // Reset and re-initialise, () → Promise<void>
                                                                         // framebuffer is kept
    console.log({ colour, status, temperature, revision, ready });
    connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
