'use strict';

const { NeoPixelTransport } = require('../../../src/transport/neopixel');
const { WS2812BFull } = require('../../../src/chips/led/ws2812b');

const SPI_BUS    = parseInt(process.env.SPI_BUS    || '0', 10);
const SPI_DEVICE = parseInt(process.env.SPI_DEVICE || '0', 10);
const N_PIXELS   = parseInt(process.env.N_PIXELS   || '30', 10);

const FRAME_MS          = 33;
const RAINBOW_DURATION  = 10000;
const STROBE_DURATION   = 2000;
const STROBE_HALF       = 50;

const transport = new NeoPixelTransport(SPI_BUS, SPI_DEVICE);  // Create NeoPixel transport, (busNumber, deviceNumber)
const strip = new WS2812BFull(transport, N_PIXELS);            // Create WS2812B full driver, (transport, n=N_PIXELS pixels)
strip.brightness = 180;                                        // Set global brightness, (value=0–255)

function hsvToRgb(h, s, v) {
    if (s === 0) { const c = Math.round(v * 255); return [c, c, c]; }
    const i = Math.floor(h * 6);
    const f = h * 6 - i;
    const p = Math.round(v * (1 - s) * 255);
    const q = Math.round(v * (1 - s * f) * 255);
    const t = Math.round(v * (1 - s * (1 - f)) * 255);
    const vv = Math.round(v * 255);
    switch (i % 6) {
        case 0: return [vv, t, p];
        case 1: return [q, vv, p];
        case 2: return [p, vv, t];
        case 3: return [p, q, vv];
        case 4: return [t, p, vv];
        default: return [vv, p, q];
    }
}

// --- Rainbow rotation: each pixel is assigned a hue offset by its position;
//     the offset is advanced each frame so the rainbow rotates around the strip.
//     Running at ~30 fps for 10 seconds gives a smooth continuous animation. ---
let hueOffset = 0;
let lastPrint = Date.now();

function rainbowFrame() {
    for (let i = 0; i < N_PIXELS; i++) {
        const h = (hueOffset + i / N_PIXELS) % 1.0;
        const [r, g, b] = hsvToRgb(h, 1.0, 1.0);
        strip.set_pixel(i, r, g, b);                           // Set pixel i to rainbow hue, (index=0–n-1, r=0–255, g=0–255, b=0–255) → void
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
    if (Date.now() - rainbowStart >= RAINBOW_DURATION) {
        clearInterval(rainbowTimer);
        startStrobe();
    }
}, FRAME_MS);

// --- Strobe effect: alternate full white and off at 10 Hz for 2 seconds.
//     Uses brightness=255 for maximum intensity then brightness=0 for off,
//     demonstrating non-destructive brightness scaling — pixel values in the
//     buffer are never zeroed. ---
function startStrobe() {
    strip.brightness = 255;                                    // Set global brightness, (value=0–255)
    strip.fill(255, 255, 255);                                 // Pre-load white into buffer, (r=0–255, g=0–255, b=0–255) → void
    let state = true;
    const strobeStart = Date.now();
    const strobeTimer = setInterval(() => {
        strip.brightness = state ? 255 : 0;                    // Set global brightness, (value=0–255)
        strip.show();                                          // Transmit buffer to strip, () → void
        state = !state;
        if (Date.now() - strobeStart >= STROBE_DURATION) {
            clearInterval(strobeTimer);
            startRainbowContinuous();
        }
    }, STROBE_HALF);
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
