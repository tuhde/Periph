'use strict';

// Demo: an adaptive backlight controller. Arms an interrupt window of +-30 %
// around the current illuminance; whenever the light leaves the window for 2 s
// the callback maps the new level logarithmically to a backlight level of
// 10-100 %, re-centres the window and re-arms it. Prints the exponent / mantissa
// once a second and runs for 60 s - no polling of the lux registers.

const { I2CConnection } = require('periph/src/connection/i2c');
const { MAX44009Full } = require('periph/src/chips/light/max44009');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, MAX44009Full.I2C_ADDRESS);
    const sensor = new MAX44009Full(connection);                            // Create MAX44009 Full driver, (connection)
    await sensor.init();                                                    // Run initialization sequence, () → void (writes the reset state, waits 0.9 s)

    const backlight = (lx) => 10 + 90 * (Math.log10(Math.min(10000, Math.max(0.1, lx))) + 1) / 5;

    console.log('config', await sensor.getConfig());                        // Read CONFIG, () → {continuous, manual, divideBy8, integrationMs}
    const lux = await sensor.lux();                                         // Read illuminance, () → float lux
    console.log('lux', lux.toFixed(2), 'resolution', sensor.resolution().toFixed(3), 'lx/LSB');// Lux per LSB, () → float lux

    const arm = async (center) => {
        await sensor.setThresholds(center * 0.7, center * 1.3);             // Set the interrupt window, (lowLux, highLux) → void
        await sensor.enableInterrupt();                                     // Enable the interrupt, () → void
    };
    await sensor.setThresholdDelay(2.0);                                    // Set the threshold delay, (seconds) → void
    await arm(lux);
    await sensor.onInterrupt(async () => {                                  // Subscribe, (callback(status), {intPin}) → void
        const now = await sensor.lux();
        console.log('lux', now.toFixed(2), '-> backlight', backlight(now).toFixed(0), '%');
        await arm(now);   // re-centre the window around the new value
    });
    console.log('backlight', backlight(lux).toFixed(0), '%');

    for (let s = 0; s < 60; s++) {
        const raw = await sensor.readRaw();                                 // Read exponent and mantissa, () → {exponent, mantissa, overrange}
        console.log('E=' + raw.exponent, 'M=' + raw.mantissa, 'overrange=' + sensor.isOverrange());// Query the last reading, () → bool
        await sleep(1000);
    }

    await sensor.disableInterrupt();                                        // Disable the interrupt, () → void
    await sensor.offInterrupt();                                            // Unsubscribe, () → void

    await connection.close();
})();
