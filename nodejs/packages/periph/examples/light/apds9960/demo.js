'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { APDS9960Full } = require('../../../src/chips/light/apds9960');

const transport = new I2CTransport(1, 0x39);
const apds = new APDS9960Full(transport);                  // Create APDS9960 driver, (transport) → APDS9960Full

// --- Monitor ambient light with adaptive integration time ---
// Start with the default 200 ms integration (ATIME=0xB6). When the clear
// channel approaches saturation (>90% of max count), halve the integration
// time by doubling ATIME to prevent overflow.
let atime = 0xB6;
apds.configureAls(atime, 1);                               // Configure ALS, (atime 0-255, again 0-3) → void

setInterval(() => {
    while (!apds.isAlsValid()) {}                          // Check ALS data valid, () → boolean

    const { clear, red, green, blue } = apds.color();      // Read all RGBC channels, () → { clear, red, green, blue }
    const lux = -0.32466 * red + 1.57837 * green + -0.73191 * blue;
    console.log(`C=${clear} R=${red} G=${green} B=${blue}  lux~${lux.toFixed(0)}`);

    // --- Adaptive integration: reduce time when saturated ---
    // At saturation the sensor clips; shortening integration recovers
    // headroom at the cost of reduced sensitivity in low light.
    if (apds.isAlsSaturated() && atime < 0xFE) {           // Check ALS saturated, () → boolean
        atime = atime + Math.floor((256 - atime) / 2);
        if (atime > 0xFE) atime = 0xFE;
        apds.configureAls(atime, 1);                       // Configure ALS, (atime 0-255, again 0-3) → void
        console.log(`[SATURATED — reducing integration time, ATIME=0x${atime.toString(16)}]`);
    }
}, 1000);
