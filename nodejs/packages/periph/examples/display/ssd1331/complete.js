// SSD1331 complete example: every Full method on a Waveshare 0.95inch RGB OLED, Raspberry Pi (Node.js).
// Wiring: DIN=GPIO10 (SPI0 MOSI), CLK=GPIO11, CS=GPIO8 (CE0), DC=GPIO25, RST=GPIO17 (no BUSY line).
'use strict';
const { Default } = require('opengpio');
const { DisplayConnection } = require('../../../src/connection/display');
const { GpioOutputPin } = require('../../../src/connection/output_pin');
const { SSD1331Full, MODE_NORMAL, MODE_ALL_ON, MODE_ALL_OFF, MODE_INVERSE, BLACK, WHITE, RED, GREEN, BLUE, YELLOW, CYAN, MAGENTA } = require('../../../src/chips/display/ssd1331');

const dc   = new GpioOutputPin(Default.output({ chip: 0, line: 25 }));   // Create D/C# pin, (chip, line=25) → OutputPin
const res  = new GpioOutputPin(Default.output({ chip: 0, line: 17 }));   // Create RES# pin, (chip, line=17) → OutputPin
const connection = new DisplayConnection({ busNumber: 0, deviceNumber: 0, dc, res }); // Create display connection, (busNumber, deviceNumber, dc, res, busy=none)
const oled = new SSD1331Full(connection);                                        // Create SSD1331 driver, (connection)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    await oled.ready();
    oled.clear(BLACK);                                                   // Fill framebuffer, (color=BLACK)
                                                                         // sets every pixel; the display is unchanged until show()
    oled.drawRect(0, 0, 96, 64, WHITE);                                  // Rectangle outline, (x, y, w, h, color=WHITE, fill=false)
    oled.drawRect(4, 4, 30, 20, RED, true);                              // Filled rectangle, (x, y, w, h, color=WHITE, fill=false)
    oled.drawLine(0, 0, 95, 63, GREEN);                                  // Software line, (x0, y0, x1, y1, color=WHITE)
                                                                         // Bresenham diagonal
    oled.drawLine(0, 63, 95, 0, CYAN);                                   // Software line, (x0, y0, x1, y1, color=WHITE)
    const sprite = Array.from({ length: 64 }, (_, i) => ((((i >> 3) + (i & 7)) & 1) ? YELLOW : MAGENTA));
    oled.blit(60, 8, sprite, 8, 8);                                      // Copy RGB565 bitmap, (x, y, pixels, w, h)
                                                                         // checkerboard into the framebuffer
    oled.setPixel(48, 32, WHITE);                                        // Set one pixel, (x, y, color=WHITE)
    console.log(oled.getPixel(48, 32).toString(16));                     // Read framebuffer pixel, (x, y) → number RGB565
    await oled.show();                                                   // Send framebuffer, () → Promise<void>
                                                                         // window 0..95 x 0..63 then 12288 bytes
    await sleep(2000);

    oled.clear(BLUE);                                                    // Fill framebuffer, (color=BLACK)
    oled.drawRect(20, 16, 56, 32, WHITE, true);                          // Filled rectangle, (x, y, w, h, color=WHITE, fill=false)
    await oled.showRegion(16, 12, 64, 40);                               // Send region, (x, y, w, h) → Promise<void>
                                                                         // only the rows of the window are transferred
    await sleep(2000);

    await oled.hwClear(0, 0, 95, 63);                                    // Clear window in display RAM, (x0, y0, x1, y1) → Promise<void>
                                                                         // chip clears its own RAM, framebuffer untouched
    await oled.hwRect(10, 10, 76, 44, YELLOW, RED, true);                // Hardware rectangle, (x, y, w, h, lineColor, fillColor=0, fill=false)
                                                                         // outline and fill drawn by the chip
    await oled.hwLine(0, 0, 95, 63, WHITE);                              // Hardware line, (x0, y0, x1, y1, color)
    await sleep(2000);
    await oled.hwCopy(10, 10, 40, 40, 50, 20);                           // Hardware copy, (x0, y0, x1, y1, x, y)
    await sleep(2000);
    await oled.hwDim(0, 0, 47, 63);                                      // Hardware dim window, (x0, y0, x1, y1)
    await sleep(2000);

    await oled.setDisplayMode(MODE_INVERSE);                             // Display mode, (mode)
    await sleep(1500);
    await oled.setDisplayMode(MODE_ALL_ON);                              // Display mode, (mode)
    await sleep(1000);
    await oled.setDisplayMode(MODE_ALL_OFF);                             // Display mode, (mode)
    await sleep(1000);
    await oled.setDisplayMode(MODE_NORMAL);                              // Display mode, (mode)

    await oled.setContrast(0x30, 0x30, 0x30);                            // Channel contrast, (a, b, c) 0..255
    await sleep(1000);
    await oled.setContrast(0xFF, 0xFF, 0xFF);                            // Channel contrast, (a, b, c) 0..255
    await oled.setMasterCurrent(4);                                      // Master current, (n 1..16) n/16
    await sleep(1000);
    await oled.setMasterCurrent(7);                                      // Master current, (n 1..16) n/16

    await oled.setFlip(false, true);                                     // Mirror, (horizontal, vertical)
    await oled.show();                                                   // Send framebuffer, () → Promise<void>
    await sleep(1500);
    await oled.setFlip(true, true);                                      // Mirror, (horizontal, vertical)
    await oled.show();                                                   // Send framebuffer, () → Promise<void>
    await sleep(1500);

    await oled.scroll(1, 0, 64, 0, 0);                                   // Scroll, (horizontal, startRow, rows, vertical, interval 0..3)
                                                                         // continuous scroll one column per 6 frames
    await sleep(4000);
    await oled.scrollStop();                                             // Stop scrolling, ()

    await oled.dimMode(0x10, 0x10, 0x10, 0x10);                          // Dim mode, (a, b, c, precharge 0..31)
    await sleep(1500);
    await oled.setPowerSave(true);                                       // Power save, (on)
    await oled.displayOff();                                             // Display off, ()
    connection.close();
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
