'use strict';

const { NeoPixelConnection } = require('../../../src/connection/neopixel');
const { WS2814Full } = require('../../../src/chips/led/ws2814');
const { _hsvToRgb } = require('../../../src/chips/led/_color');

const SPI_BUS    = parseInt(process.env.SPI_BUS    || '0', 10);
const SPI_DEVICE = parseInt(process.env.SPI_DEVICE || '0', 10);
const N_PIXELS   = parseInt(process.env.N_PIXELS   || '30', 10);

const FRAME_MS         = 33;    // ~30 fps
const RAINBOW_MS       = 5000;
const FLASH_MS         = 2000;
const DIM_MS           = 2000;

const connection = new NeoPixelConnection(SPI_BUS, SPI_DEVICE);  // Create NeoPixel connection, (busNumber, deviceNumber)
const strip = new WS2814Full(connection, N_PIXELS);              // Create WS2814 full driver, (connection, n=N_PIXELS pixels)

function sleep(ms) {
    return new Promise(r => setTimeout(r, ms));
}

async function main() {
    // --- Rainbow rotation using RGB channels (white=0). Each pixel is
    //     assigned a hue offset by its position; the offset advances each
    //     frame so the rainbow rotates continuously around the strip.
    //     Demonstrates that WS2814's RGBW wire order is identity (R, G, B, W
    //     with no reorder), unlike the SK6812RGBW's GRBW order. Runs at
    //     ~30 fps for 5 seconds. ---
    let hueOffset = 0;
    const rainbowStart = Date.now();
    let lastPrint = rainbowStart;
    while (Date.now() - rainbowStart < RAINBOW_MS) {
        for (let i = 0; i < N_PIXELS; i++) {
            const h = (hueOffset + i / N_PIXELS) % 1.0;
            const [r, g, b] = _hsvToRgb(h, 1.0, 1.0);
            strip.set_pixel(i, r, g, b, 0);                      // Set pixel i to rainbow hue (w=0), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → void
        }
        await strip.show();                                     // Transmit buffer to strip, () → void
        hueOffset = (hueOffset + 1.0 / (N_PIXELS * 2)) % 1.0;
        const now = Date.now();
        if (now - lastPrint >= 1000) {
            console.log(`mode=rainbow brightness=${strip.brightness}`);
            lastPrint = now;
        }
        await sleep(FRAME_MS);
    }

    // --- Warm white at full brightness for 2 seconds. r=255, g=200, b=150,
    //     w=255 blends the dedicated white element with amber-tinted RGB,
    //     exercising the white channel and the 32-bit RGBW pixel word at
    //     full brightness. ---
    await strip.fill(255, 200, 150, 255);                        // Fill all pixels warm white, (r=0–255, g=0–255, b=0–255, w=0–255) → void
    const flashStart = Date.now();
    while (Date.now() - flashStart < FLASH_MS) {
        console.log(`mode=warm-white brightness=${strip.brightness}`);
        await sleep(100);
    }

    // --- Dim warm white to 50% using the brightness property and hold for
    //     2 seconds. Demonstrates that brightness scaling is non-destructive:
    //     the stored RGBW values are unchanged, only the scale factor applied
    //     at show() time changes. ---
    strip.brightness = 128;                                      // Set global brightness, (value=0–255)
    await strip.show();                                          // Transmit buffer to strip, () → void
    const dimStart = Date.now();
    while (Date.now() - dimStart < DIM_MS) {
        console.log(`mode=warm-white-dimmed brightness=${strip.brightness}`);
        await sleep(100);
    }

    // --- Cycle to cool white at full brightness. r=200, g=210, b=255,
    //     w=255 shifts the blend toward blue, showcasing the dedicated
    //     white element paired with a cool-tinted RGB base. ---
    strip.brightness = 255;                                      // Set global brightness, (value=0–255)
    await strip.fill(200, 210, 255, 255);                        // Fill all pixels cool white, (r=0–255, g=0–255, b=0–255, w=0–255) → void
    console.log(`mode=cool-white brightness=${strip.brightness}`);

    await connection.close();
}

main();
