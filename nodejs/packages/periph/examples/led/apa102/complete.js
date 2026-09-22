'use strict';

const { SPIConnection } = require('../../../src/connection/spi');
const { APA102Full } = require('../../../src/chips/led/apa102');

const SPI_BUS    = parseInt(process.env.SPI_BUS    || '0', 10);
const SPI_DEVICE = parseInt(process.env.SPI_DEVICE || '0', 10);

const connection = new SPIConnection(SPI_BUS, SPI_DEVICE, { mode: 0, maxSpeedHz: 1_000_000 });  // Create SPI connection, (busNumber, deviceNumber, mode=0, 1MHz)
const strip = new APA102Full(connection, 8);                                                   // Create APA102 full driver, (connection, n=8 pixels)

(async () => {
    // fill — set all pixels and send immediately
    await strip.fill(255, 0, 0);                                                                // Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255) → void
                                                                                                // stores brightness/B/G/R in buffer and calls connection.write()

    // set individual pixels then show
    strip.set_pixel(0, 255, 0, 0);                                                              // Set pixel 0 to red (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                                                                                                // writes brightness, B, G, R bytes into internal buffer at position index*4
    strip.set_pixel(1, 0, 255, 0);                                                              // Set pixel 1 to green (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                                                                                                // writes brightness, B, G, R bytes into internal buffer at position index*4
    strip.set_pixel(2, 0, 0, 255);                                                              // Set pixel 2 to blue (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                                                                                                // writes brightness, B, G, R bytes into internal buffer at position index*4
    await strip.show();                                                                         // Transmit buffer to strip, () → void
                                                                                                // applies software brightness scaling then calls connection.write()

    // set_pixels — write multiple pixels at once
    strip.set_pixels([[255,0,0],[0,255,0],[0,0,255],                                           // Set pixels from array of [r,g,b] or [r,g,b,brightness], (colors=Array<[r,g,b]|[r,g,b,brightness]>) → void
                      [255,255,0],[0,255,255],[255,0,255],
                      [128,128,128],[255,255,255]]);
                                                                                                // writes entries sequentially from pixel 0; ignores extras beyond strip length
    await strip.show();                                                                         // Transmit buffer to strip, () → void
                                                                                                // applies software brightness scaling then calls connection.write()

    // set_pixels with per-pixel hardware brightness
    strip.set_pixels([[255,0,0,31],[255,0,0,16],[255,0,0,8],[255,0,0,4],                     // Set pixels with varying hardware brightness, (colors=Array<[r,g,b,brightness]>) → void
                      [0,255,0,31],[0,255,0,16],[0,255,0,8],[0,255,0,4]]);
    await strip.show();                                                                         // Transmit buffer to strip, () → void
                                                                                                // applies software brightness scaling then calls connection.write()

    // brightness — global software scale applied at show() time
    strip.brightness = 64;                                                                      // Set global software brightness, (value=0–255)
                                                                                                // stored RGB value is scaled: sent = stored * brightness / 255; hardware brightness byte unchanged
    await strip.show();                                                                         // Transmit buffer to strip, () → void
                                                                                                // applies software brightness scaling then calls connection.write()
    strip.brightness = 255;                                                                     // Set global software brightness, (value=0–255)
                                                                                                // stored RGB value is scaled: sent = stored * brightness / 255; hardware brightness byte unchanged

    // fill_hsv — fill all pixels from HSV colour
    await strip.fill_hsv(0.0, 1.0, 1.0);                                                       // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                                                                // converts HSV to RGB then calls fill(); hue 0.0 = red
    await strip.fill_hsv(0.333, 1.0, 1.0);                                                     // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                                                                // converts HSV to RGB then calls fill(); hue 0.333 = green
    await strip.fill_hsv(0.667, 1.0, 1.0);                                                     // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                                                                // converts HSV to RGB then calls fill(); hue 0.667 = blue

    // rotate — shift pixel buffer left, then show
    strip.set_pixels([[255, 0, 0], ...Array(7).fill([0, 0, 0])]);                              // Set pixels from array of [r,g,b], (colors=Array<[r,g,b]>) → void
                                                                                                // writes entries sequentially from pixel 0; ignores extras beyond strip length
    await strip.show();                                                                         // Transmit buffer to strip, () → void
                                                                                                // applies software brightness scaling then calls connection.write()
    for (let i = 0; i < 7; i++) {
        strip.rotate(1);                                                                        // Rotate pixel buffer left, (steps=1) → void
                                                                                                // shifts buffer by steps pixel positions; wraps around; does not send
        await strip.show();                                                                     // Transmit buffer to strip, () → void
                                                                                                // applies software brightness scaling then calls connection.write()
    }

    await strip.off();                                                                          // Turn off all pixels, () → void
                                                                                                // equivalent to fill(0, 0, 0)
    await connection.close();
})();