// UC1611s complete example: EA DOGXL240-7 (240 x 128, 3.3 V only) over I2C on a Raspberry Pi (Node.js).
// Wiring: SDA=GPIO2, SCL=GPIO3 (/dev/i2c-1), VDD = 3V3; module pins BM0 = D13 = VDD, CD = A2 = A3 = VSS -> command 0x38 / data 0x39.
// Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD; pull-ups 2-4.7 k to 3.3 V. Complete: every Full method - drawing, contrast, invert, flip, scroll, drive tuning, partial display, RAM screens, reads, sleep, reset.
'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { UC1611sFull } = require('../../../src/chips/display/uc1611s');
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const command = new I2CConnection(1, 0x38);                                // Create command connection, (busNumber=1, addr=0x38)
const data = new I2CConnection(1, 0x39);                                   // Create data connection, (busNumber=1, addr=0x39)
const lcd = new UC1611sFull(command, data);                         // Create UC1611s driver, (command, data, width=240, height=128)

(async () => {
    await lcd.ready();
    // Framebuffer drawing: everything is composed in RAM and sent with show().
    lcd.clear();                                                      // Clear framebuffer, ()
                                                                      // zeroes every pixel of the framebuffer; the panel is unchanged until show()
    lcd.fill(true);                                                   // Fill framebuffer, (on=true)
                                                                      // sets (or clears) every pixel
    lcd.fill(false);                                                  // Fill framebuffer, (on=true)
    lcd.drawRect(0, 0, 240, 128);                                     // Draw rectangle, (x, y, w, h, on=true, fill=false)
                                                                      // one-pixel outline around the whole panel
    lcd.drawLine(0, 0, 239, 127);                                     // Draw line, (x0, y0, x1, y1, on=true)
                                                                      // Bresenham line into the framebuffer
    lcd.drawLine(0, 127, 239, 0);                                     // Draw line, (x0, y0, x1, y1, on=true)
    lcd.drawRect(20, 20, 40, 30, true, true);                         // Draw rectangle, (x, y, w, h, on=true, fill=false)
                                                                      // filled rectangle
    lcd.drawRect(30, 28, 20, 14, false, true);                        // Draw rectangle, (x, y, w, h, on=true, fill=false)
                                                                      // on=false clears pixels: a hole in the filled rectangle
    lcd.setPixel(120, 64, true);                                      // Set pixel, (x, y, on=true)
    const lit = lcd.getPixel(120, 64);                                // Read framebuffer pixel, (x, y) → boolean
                                                                      // reads the framebuffer, not the panel
    const icon = [0x3C, 0x42, 0x81, 0xA5, 0x81, 0xA5, 0x99, 0x42, 0x3C, 0x00, 0x7E, 0x81, 0x81, 0x81, 0x7E, 0x00];
    lcd.blit(180, 16, icon, 2);                                       // Copy bitmap, (x, y multiple of 8, columns, heightPages=1)
                                                                      // two page rows of 8 columns, bit 0 = top pixel
    await lcd.show();                                                 // Send framebuffer to the display, () → Promise<void>
                                                                      // per page: page + column address, then 240 data bytes in 32-byte chunks
    await sleep(2000);
    await lcd.showPage(2);                                            // Send one framebuffer page, (page 0..15) → Promise<void>
                                                                      // cheaper partial update of 8 rows
    
    // Contrast and display modes.
    await lcd.setContrast(0x70);                                      // Set contrast, (value 0..255) → Promise<void>
                                                                      // writes the potentiometer PM; lower = lighter
    await sleep(800);
    await lcd.setContrast(0x8F);                                      // Set contrast, (value 0..255) → Promise<void>
                                                                      // 0x8F is the DOG example value
    await lcd.setInvert(true);                                        // Invert display, (on) → Promise<void>
                                                                      // bit-wise inversion of what is displayed; RAM unchanged
    await sleep(800);
    await lcd.setInvert(false);                                       // Invert display, (on) → Promise<void>
    await lcd.setAllOn(true);                                         // Light all pixels, (on) → Promise<void>
                                                                      // test mode, RAM unchanged
    await sleep(800);
    await lcd.setAllOn(false);                                        // Light all pixels, (on) → Promise<void>
    await lcd.setFlip(false, true);                                   // Mirror display, (mirrorX, mirrorY) → Promise<void>
                                                                      // MY = 1: rows mirrored at once; MX is applied to RAM writes
    await sleep(800);
    await lcd.setFlip(true, false);                                   // Mirror display, (mirrorX, mirrorY) → Promise<void>
                                                                      // back to the bottom-view default
    await lcd.show();                                                 // Send framebuffer to the display, () → Promise<void>
    
    // Hardware scroll with a fixed header.
    await lcd.setFixedLines(2);                                       // Set fixed lines, (lines 0..15) → Promise<void>
                                                                      // the top 2 x 2 = 4 rows stay put
    for (let line = 0; line <= 32; line += 4) {
        await lcd.setScrollLine(line);                                // Scroll the image up, (line 0..159 - 2 * fixed) → Promise<void>
                                                                      // rows enter from the bottom as the image moves up
        await sleep(120);
    }
    await lcd.setScrollLine(0);                                       // Scroll the image up, (line 0..159 - 2 * fixed) → Promise<void>
    await lcd.setFixedLines(0);                                       // Set fixed lines, (lines 0..15) → Promise<void>
    
    // LCD drive tuning (the DOG defaults are already sent at init).
    await lcd.setBiasRatio(2);                                        // Set bias ratio, (ratio 0..3) → Promise<void>
                                                                      // 0 = 1/5, 1 = 1/10, 2 = 1/11 (reset), 3 = 1/12
    await lcd.setTemperatureCompensation(1);                          // Set temperature compensation, (curve 0..3) → Promise<void>
                                                                      // 1 = -0.10 %/degC, the DOG example value
    await lcd.setLineRate(3);                                         // Set line rate, (rate 0..3) → Promise<void>
                                                                      // 3 = 9.4 kilo-lines per second in On/Off mode
    await lcd.setPump(true);                                          // Select VLCD source, (internal) → Promise<void>
                                                                      // internal 11x charge pump
    await lcd.setPanelLoading(true);                                  // Set panel loading, (high) → Promise<void>
                                                                      // 33..55 nF panel capacitance
    await lcd.setNLineInversion(0, false);                            // Set N-line inversion, (lines 0..63, xor=false) → Promise<void>
                                                                      // 0 = off
    await lcd.setIsolationClock(1, 0);                                // Set isolation clock, (front 0..15, back 0..15) → Promise<void>
                                                                      // the reset values
    
    // Partial display: scan only the middle 64 rows.
    await lcd.setPartialDisplay(true, 32, 95);                        // Set partial display, (on, start=0, end=height-1) → Promise<void>
                                                                      // rows outside 32..95 are not scanned and save power
    await sleep(1500);
    await lcd.setPartialDisplay(false);                               // Set partial display, (on, start=0, end=height-1) → Promise<void>
                                                                      // back to the full panel
    
    // Four RAM screens: draw into screen 1, then switch to it.
    lcd.setWritePattern(1);                                           // Select RAM screen to write, (pattern 0..3)
                                                                      // show() now writes RAM screen 1
    lcd.fill(false);                                                  // Fill framebuffer, (on=true)
    lcd.drawRect(40, 30, 160, 68, true, true);                        // Draw rectangle, (x, y, w, h, on=true, fill=false)
    await lcd.show();                                                 // Send framebuffer to the display, () → Promise<void>
                                                                      // screen 0 is still displayed
    await lcd.setDisplayPattern(1);                                   // Select displayed RAM screen, (pattern 0..3) → Promise<void>
                                                                      // the display now shows RAM screen 1
    await sleep(1500);
    await lcd.setDisplayPattern(0);                                   // Select displayed RAM screen, (pattern 0..3) → Promise<void>
    lcd.setWritePattern(0);                                           // Select RAM screen to write, (pattern 0..3)
    
    // Read back over I2C.
    const status = await lcd.readStatus();                            // Read status bytes, () → Promise<Buffer>
                                                                      // NOP first, then Status1 = Ver MX MY WA DE WS MD MS, Status3 high nibble = product code 1
    console.log('status', [...status].map((b) => b.toString(16).padStart(2, '0')).join(' '));
    const ram = await lcd.readRam(0, 0, 8);                           // Read display RAM, (page, column, n) → Promise<Buffer>
                                                                      // the dummy byte after the address change is discarded
    console.log('ram', ram.toString('hex'));
    
    // Sleep, raw access, reset.
    await lcd.sleep();                                                // Enter sleep, () → Promise<void>
                                                                      // drivers, pump and clock stop; registers and RAM are kept
    await sleep(1000);
    await lcd.wake();                                                 // Leave sleep, () → Promise<void>
                                                                      // polls Status1.DE while the chip restores VLCD
    await lcd.sendCommand(0xE3);                                      // Send raw command bytes, (...bytes) → Promise<void>
                                                                      // 0xE3 = NOP
    await lcd.sendData(Buffer.alloc(8));                              // Send raw RAM data, (bytes) → Promise<void>
                                                                      // written at the current RAM address
    await lcd.reset();                                                // System Reset and reconfigure, () → Promise<void>
                                                                      // registers back to the DOG settings with the cached contrast and mapping; RAM is kept
    void lit;
    console.log(`done (I2C retries ${lcd.retryCount()}, unrecovered ${lcd.errorCount()})`);
    command.close();
    data.close();
    process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
