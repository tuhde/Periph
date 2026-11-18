'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { IS31FL3731Full } = require('../../../src/chips/led/is31fl3731');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x74', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

(async () => {
    const matrix = new IS31FL3731Full(connection);            // Create IS31FL3731 driver, (connection)
    await matrix.ready;

    await matrix.clear();                                     // Switch all LEDs of the drawing frame off, () → void
    await matrix.setPixel(2, 5, 200);                         // Set one LED's PWM brightness, (row 0–8, col 0–15, brightness 0–255) → void
    console.log(matrix.getPixel(2, 5));                       // Read a pixel from the shadow copy, (row, col) → number 0–255
    await matrix.setPixels(Buffer.from(Array.from({ length: 144 }, (_, i) => i)));  // Write a whole frame, (data 144 bytes, row-major) → void
    await matrix.fill(40);                                    // Set every LED's PWM brightness, (brightness 0–255) → void
    await matrix.setLedEnabled(0, 0, false);                  // LED-control (on/off) bit, (row, col, on) → void
    await matrix.setLedEnabled(0, 0, true);
    await matrix.setBlink(1, 1, true);                        // Blink-enable bit of one LED, (row, col, on) → void
    await matrix.setBlinkAll(false);                          // Blink-enable bit of every LED, (on) → void
    await matrix.enableBlink(true, 2);                        // Blink on, (enabled, period 0–7 × 0.27 s) → void
                                                              // 0.54 s period, 50 % duty
    await sleep(2000);
    await matrix.enableBlink(false);
    await matrix.selectFrame(1);                              // Frame subsequent drawing writes to, (frame 0–7) → void
    await matrix.fill(100);
    await matrix.copyFrame(1, 2);                             // Copy PWM values between frames, (src, dst) → void
    await matrix.clearFrame(2);                               // Re-initialise one frame, (frame 0–7) → void
    await matrix.clearAllFrames();                            // Re-initialise all eight frames, () → void
    await matrix.selectFrame(0);
    await matrix.showFrame(0);                                // Display a frame in Picture mode, (frame 0–7) → void
    await matrix.setPictureMode();                            // Picture mode, () → void
    await matrix.startAutoPlay(2, 1, 110, 0);                 // Auto Frame Play, (frames 0–7, loops 0–7, delayMs, startFrame) → void
                                                              // frames/loops 0 = all/endless; plays 2 frames once
    await sleep(1000);
    console.log(await matrix.frameState());                   // Read FRAME_STATE, () → number
    console.log(await matrix.currentFrame());                 // Frame being displayed, () → number 0–7
    console.log(await matrix.movieFinished());                // Movie-finished flag (clears it), () → boolean
    await matrix.stopAutoPlay();                              // Back to Picture mode on the current frame, () → void
    await matrix.setBreath(true, 3, 3, 2);                    // Breath function, (enabled, fadeIn, fadeOut, extinguish 0–7) → void
    await sleep(3000);
    await matrix.setBreath(false);
    await matrix.setIntensityControl(false);                  // Share frame 1's PWM with all frames, (useFrame1) → void
    await matrix.setAudioGain(6, true, false);                // Audio gain, (gainDb 0–21 step 3, agc, fast) → void
    await matrix.setAudioRate(10);                            // Audio ADC interval, (a 0–255, × 46 µs) → void
    await matrix.setAudioSync(false);                         // Audio intensity modulation, (enabled) → void
    await matrix.setAudioMode(false);                         // Audio Frame Play on/off, (enabled) → void
    console.log(await matrix.readFunctionRegister(0x03));     // Raw function-page read, (reg 0–0x0C) → number
    await matrix.writeFunctionRegister(0x03, 0x0B);           // Raw function-page write, (reg, value) → void
    console.log(await matrix.pollInterrupt());                // FRAME_STATE, clears INT, () → number
    await matrix.shutdown();                                  // Software shutdown, (on = true) → void
    await matrix.shutdown(false);                             // Back to normal operation

    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
