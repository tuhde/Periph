'use strict';

// Demo: a light meter with alarm.
// Prints the ID, then takes an auto-ranged lux reading every 2 s for 30 s with
// the selected gain, integration time and both channel counts. Then it sets an
// interrupt window of ±50 % around the current channel-0 count with persistence
// 2, reports pollInterrupt() while you cover the sensor, releases the
// interrupt and powers the chip down.

const { I2CConnection } = require('periph/src/connection/i2c');
const { TSL2561Full } = require('periph/src/chips/light/tsl2561');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, TSL2561Full.I2C_ADDRESS);
    const sensor = new TSL2561Full(connection);                             // Create TSL2561 Full driver, (connection)
    await sensor.init();                                                    // Run initialization sequence, () → void
    console.log('ID 0x' + (await sensor.readId()).toString(16));            // Read the ID register, () → int

    /* Light meter: auto-range picks the most sensitive setting that does not saturate. */
    for (let i = 0; i < 15; i++) {
        const lux = await sensor.autoRange();                               // Auto-range and read lux, () → float lux
        const { ch0, ch1 } = await sensor.readRaw();                        // Read both channels, () → {ch0, ch1}
        console.log(`${lux.toFixed(1)} lx  gain ${sensor.getGain()}x  ${sensor.getIntegrationTime()} ms  ch0 ${ch0}  ch1 ${ch1}`);  // Current settings, () → int
        await sleep(2000);
    }

    /* Alarm: a window of ±50 % around the current channel-0 count. */
    const { ch0 } = await sensor.readRaw();
    await sensor.setThresholds(Math.floor(ch0 / 2), Math.min(65535, Math.floor(ch0 * 1.5)));  // Set channel-0 window, (low, high) → void
    await sensor.configureInterrupt(1, 2);                                  // Level interrupt, 2 cycles outside, (mode, persist) → void
    console.log('window', await sensor.getThresholds(), '- cover the sensor now');  // Read the window, () → {low, high}
    for (let i = 0; i < 10; i++) {
        console.log('outside window:', await sensor.pollInterrupt());       // Check ch0 against the window, () → bool
        await sleep(1000);
    }

    await sensor.clearInterrupt();                                          // Release the level interrupt, () → void
    await sensor.configureInterrupt(0, 0);
    await sensor.powerDown();                                               // Power down, () → void
    console.log('powered:', await sensor.isPowered());                      // Power state, () → bool

    await connection.close();
})();
