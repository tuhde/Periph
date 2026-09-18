'use strict';

const { SPIConnection } = require('../../../src/connection/spi');
const { APA102Full } = require('../../../src/chips/led/apa102');

const SPI_BUS     = parseInt(process.env.SPI_BUS     || '0', 10);
const SPI_DEVICE  = parseInt(process.env.SPI_DEVICE  || '0', 10);
const N_PIXELS    = parseInt(process.env.N_PIXELS    || '30', 10);
const FRAME_MS    = 16;          // ~60 fps
const RAINBOW_MS  = 10000;

const connection = new SPIConnection(SPI_BUS, SPI_DEVICE, { mode: 0, maxSpeedHz: 1_000_000 });  // Create SPI connection, (busNumber, deviceNumber, mode=0, 1MHz)
const strip = new APA102Full(connection, N_PIXELS);                                              // Create APA102 full driver, (connection, n=N_PIXELS pixels)

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

// --- 13-bit effective color depth demonstration ---
// First pass: full hardware brightness (31) for maximum drive current
// Second pass: hardware brightness 1 (1/31 current) to show hardware vs software dimming

let hueOffset = 0;
let lastPrint = Date.now();

async function rainbowFrame(hwBrightness, label) {
    for (let i = 0; i < N_PIXELS; i++) {
        const h = (hueOffset + i / N_PIXELS) % 1.0;
        const [r, g, b] = hsvToRgb(h, 1.0, 1.0);
        strip.set_pixel(i, r, g, b, hwBrightness);                                            // Set pixel i to rainbow hue at hw brightness, (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
    }
    await strip.show();                                                                       // Transmit buffer to strip, () → void
                                                                                                // applies software brightness scaling then calls connection.write()
    hueOffset = (hueOffset + 1.0 / (N_PIXELS * 2)) % 1.0;
    const now = Date.now();
    if (now - lastPrint >= 1000) {
        console.log(label + ' hue_offset=' + hueOffset.toFixed(3));
        lastPrint = now;
    }
}

// --- Pass 1: Full hardware brightness (31) ---
// Rainbow sweep at hardware brightness 31 uses full 8-bit PWM channels + 5-bit
// hardware current control = 13-bit effective depth per channel.
strip.brightness = 255;                                                                       // Set global software brightness, (value=0–255)
const rainbowStart1 = Date.now();
const rainbowTimer1 = setInterval(async () => {
    await rainbowFrame(31, 'rainbow hw_brightness=31');
    if (Date.now() - rainbowStart1 >= RAINBOW_MS) {
        clearInterval(rainbowTimer1);
        startPass2();
    }
}, FRAME_MS);

// --- Pass 2: Low hardware brightness (1) ---
// Same 8-bit RGB values but hardware brightness=1 (1/31 drive current).
// Demonstrates hardware current control vs software brightness scaling.
async function startPass2() {
    strip.brightness = 255;                                                                   // Set global software brightness, (value=0–255)
    hueOffset = 0;
    lastPrint = Date.now();
    const rainbowStart2 = Date.now();
    const rainbowTimer2 = setInterval(async () => {
        await rainbowFrame(1, 'rainbow hw_brightness=1');
        if (Date.now() - rainbowStart2 >= RAINBOW_MS) {
            clearInterval(rainbowTimer2);
            await strip.off();                                                                // Turn off all pixels, () → void
            await connection.close();
            process.exit(0);
        }
    }, FRAME_MS);
}

process.on('SIGINT', async () => {
    await strip.off();
    await connection.close();
    process.exit(0);
});