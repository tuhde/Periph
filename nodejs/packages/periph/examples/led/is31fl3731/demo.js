'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { IS31FL3731Full } = require('../../../src/chips/led/is31fl3731');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x74', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

(async () => {
    // --- Animated matrix: auto-play movie, blinking checkerboard, breath, gamma fade ---
    // A diagonal moves across frames 1-8 and is played by the chip itself, then a
    // blinking checkerboard is shown in Picture mode, a filled frame breathes, the
    // matrix fades through the 64-step gamma table, a finite 2-loop movie ends with
    // an interrupt, and the display is shut down.
    const matrix = new IS31FL3731Full(connection);            // Create IS31FL3731 driver, (connection)
    await matrix.ready;
    await matrix.clearAllFrames();                            // Initialise all eight frames, () → void

    for (let f = 0; f < 8; f++) {
        await matrix.selectFrame(f);                          // Draw on frame f, (frame 0–7) → void
        for (let r = 0; r < 9; r++) {
            await matrix.setPixel(r, (f + r) % 16, 255);      // One diagonal LED per row, (row, col, brightness) → void
        }
    }
    await matrix.selectFrame(0);

    await matrix.startAutoPlay(0, 0, 100, 0);                 // Endless 8-frame movie, (frames, loops, delayMs, startFrame) → void
    for (let i = 0; i < 6; i++) {
        console.log('frame', await matrix.currentFrame());    // Frame being displayed, () → number
        await sleep(500);
    }
    await matrix.stopAutoPlay();                              // Picture mode, current frame kept, () → void

    await matrix.selectFrame(0);
    await matrix.clear();                                     // Blank the drawing frame, () → void
    const pixels = Buffer.alloc(144);
    for (let r = 0; r < 9; r++) for (let c = 0; c < 16; c++) pixels[r * 16 + c] = (r + c) % 2 === 0 ? 255 : 0;
    await matrix.setPixels(pixels);                           // Checkerboard, (data 144 bytes) → void
    await matrix.setBlinkAll(true);                           // Every LED blinks, (on) → void
    await matrix.enableBlink(true, 2);                        // Blink period 2 × 0.27 s, (enabled, period) → void
    await matrix.showFrame(0);                                // Show frame 1 in Picture mode, (frame) → void
    await sleep(3000);
    await matrix.enableBlink(false);
    await matrix.setBlinkAll(false);

    await matrix.fill(255);                                   // Full brightness, (brightness) → void
    await matrix.setBreath(true, 4, 4, 2);                    // Breathing, (enabled, fadeIn, fadeOut, extinguish) → void
    await sleep(5000);
    await matrix.setBreath(false);

    for (const level of IS31FL3731Full.GAMMA_64) {
        await matrix.fill(level);                             // Gamma-corrected fade, (brightness) → void
        await sleep(30);
    }

    await matrix.onInterrupt((status) => {                    // Callback when a movie ends, (callback, options) → void
        console.log('movie finished at frame', status & 0x07);
    });
    await matrix.setPictureMode();                            // Picture mode, () → void
    await matrix.startAutoPlay(0, 2, 100, 0);                 // Two loops, (frames, loops, delayMs, startFrame) → void
    await sleep(3000);
    await matrix.offInterrupt();                              // Unsubscribe, () → void

    await matrix.clearAllFrames();
    await matrix.shutdown();                                  // Display off, (on = true) → void

    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
