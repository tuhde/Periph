'use strict';

// Complete example: exercise every method in the public API.
// Reads the illuminance and the raw exponent / mantissa, switches between
// automatic and manual ranging and between the measurement modes, programs
// the interrupt window and delay and handles one interrupt.

const { I2CConnection } = require('periph/src/connection/i2c');
const { MAX44009Full } = require('periph/src/chips/light/max44009');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, MAX44009Full.I2C_ADDRESS);
    const sensor = new MAX44009Full(connection);                            // Create MAX44009 Full driver, (connection)
    await sensor.init();                                                    // Run initialization sequence, () → void (writes the reset state, waits 0.9 s)

    console.log(await sensor.lux());                                        // Read illuminance, () → float lux
    // 2^E × M × 0.045; 188006.4 on overrange
    console.log(await sensor.readRaw());                                    // Read exponent and mantissa, () → {exponent, mantissa, overrange}
    // one consistent sample: LUX_HIGH, LUX_LOW, LUX_HIGH
    console.log(sensor.isOverrange());                                      // Query the last reading, () → bool
    // true if the last exponent field was 15
    console.log(sensor.resolution());                                       // Lux per LSB, () → float lux
    // 2^E × 0.045 for the last reading

    await sensor.setContinuous(true);                                       // Select measurement mode, (continuous bool) → void
    // true: a new measurement as soon as one finishes
    await sensor.setManual(100, false);                                     // Select manual range, (integrationMs, divideBy8=false) → void
    // nearest of 800/400/200/100/50/25/12.5/6.25 ms
    console.log(await sensor.getConfig());                                  // Read CONFIG, () → {continuous, manual, divideBy8, integrationMs}
    await sensor.setAutomatic();                                            // Select automatic range, () → void
    // the chip chooses the current divider and integration time
    await sensor.setContinuous(false);                                      // Back to one measurement per 800 ms, (continuous bool) → void

    await sensor.setThresholds(50.0, 500.0);                                // Set the interrupt window, (lowLux, highLux) → void
    // 4 mantissa MSBs: about 1/16 to 1/8 of the value
    console.log(await sensor.getThresholds());                              // Read the window, () → {lowLux, highLux}
    await sensor.setThresholdDelay(2.0);                                    // Set the threshold delay, (seconds) → void
    // 0–25.5 s in 0.1 s steps
    console.log(await sensor.getThresholdDelay());                          // Read the delay, () → float s

    await sensor.enableInterrupt();                                         // Enable the interrupt, () → void
    // INT_ENABLE = 1
    await sensor.onInterrupt((status) => console.log('interrupt', status)); // Subscribe, (callback(status), {intPin}) → void
    // falling edge on INT, or a 100 ms poll without a pin
    await sleep(10000);
    console.log(await sensor.pollInterrupt());                              // Read and clear INT_STATUS, () → int
    // bit 0 = SOURCE_THRESHOLD
    await sensor.offInterrupt();                                            // Unsubscribe, () → void
    await sensor.disableInterrupt();                                        // Disable the interrupt, () → void
    // INT_ENABLE = 0; also clears a pending status

    await connection.close();
})();
