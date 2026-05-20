'use strict';

const { NeoPixelTransport } = require('../../../src/transport/neopixel');
const { SK6812RGBWFull } = require('../../../src/chips/led/sk6812rgbw');

const SPI_BUS    = parseInt(process.env.SPI_BUS    || '0', 10);
const SPI_DEVICE = parseInt(process.env.SPI_DEVICE || '0', 10);
const N_PIXELS   = parseInt(process.env.N_PIXELS   || '30', 10);

const FRAME_MS        = 33;
const RAINBOW_MS      = 10000;
const WARM_MS         = 2000;
const WARM_HALF       = 100;

const transport = new NeoPixelTransport(SPI_BUS, SPI_DEVICE);  // Create NeoPixel transport, (busNumber, deviceNumber)
const strip = new SK6812RGBWFull(transport, N_PIXELS);         // Create SK6812RGBW full driver, (transport, n=N_PIXELS pixels)
strip.brightness = 180;                                        // Set global brightness, (value=0–255)

const { _hsvToRgb } = require('../../../src/chips/led/_color');

// --- Rainbow rotation: each pixel is assigned a hue offset by its position;
//     the offset advances each frame so the rainbow rotates around the strip.
//     RGB channels only (w=0) for 10 seconds at ~30 fps. ---
let hueOffset = 0;
let lastPrint = Date.now();

function rainbowFrame() {
    for (let i = 0; i < N_PIXELS; i++) {
        const h = (hueOffset + i / N_PIXELS) % 1.0;
        const [r, g, b] = _hsvToRgb(h, 1.0, 1.0);
        strip.set_pixel(i, r, g, b, 0);                       // Set pixel i to rainbow hue (w=0), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → void
    }
    strip.show();                                              // Transmit buffer to strip, () → void
    hueOffset = (hueOffset + 1.0 / (N_PIXELS * 2)) % 1.0;
    const now = Date.now();
    if (now - lastPrint >= 1000) {
        console.log('rainbow hue_offset=' + hueOffset.toFixed(3));
        lastPrint = now;
    }
}

const rainbowStart = Date.now();
const rainbowTimer = setInterval(() => {
    rainbowFrame();
    if (Date.now() - rainbowStart >= RAINBOW_MS) {
        clearInterval(rainbowTimer);
        startWarm();
    }
}, FRAME_MS);

// --- Warm-white strobe: showcases the dedicated white element.
//     All four channels active (r=255, g=200, b=150, w=255) gives a warm,
//     high-CRI white; toggling at 5 Hz for 2 seconds draws the eye to the
//     difference between mixed-RGB white and the native W element. ---
function startWarm() {
    strip.brightness = 255;                                    // Set global brightness, (value=0–255)
    strip.fill(255, 200, 150, 255);                            // Pre-load warm white (RGB+W) into buffer, (r=0–255, g=0–255, b=0–255, w=0–255) → void
    let state = true;
    const warmStart = Date.now();
    const warmTimer = setInterval(() => {
        strip.brightness = state ? 255 : 0;                    // Toggle brightness on/off, (value=0–255)
        strip.show();                                          // Transmit buffer to strip, () → void
        state = !state;
        if (Date.now() - warmStart >= WARM_MS) {
            clearInterval(warmTimer);
            startRainbowContinuous();
        }
    }, WARM_HALF);
}

// --- Return to continuous rainbow ---
function startRainbowContinuous() {
    strip.brightness = 180;                                    // Set global brightness, (value=0–255)
    hueOffset = 0;
    lastPrint = Date.now();
    setInterval(() => {
        rainbowFrame();
    }, FRAME_MS);
}

process.on('SIGINT', () => {
    strip.off();
    transport.close();
    process.exit(0);
});
