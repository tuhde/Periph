'use strict';

// Complete example: exercise every method in the public API.
// Reads lux and the raw channel counts, changes gain, integration time and the
// package coefficients, auto-ranges, runs one host-timed manual integration,
// programs the interrupt window and handles interrupts, then powers down.

const { I2CConnection } = require('periph/src/connection/i2c');
const { TSL2561Full, calculateLux } = require('periph/src/chips/light/tsl2561');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, TSL2561Full.I2C_ADDRESS);
    const sensor = new TSL2561Full(connection);                             // Create TSL2561 Full driver, (connection)
    await sensor.init();                                                    // Run initialization sequence, () → void
    // CONTROL ← 0x03 and read back, TIMING ← 0x02 (1×, 402 ms), waits 450 ms

    console.log((await sensor.readId()).toString(16));                      // Read the ID register, () → int
    // bits 7:4 = 1 for the TSL2561, bits 3:0 revision
    console.log(await sensor.readLux());                                    // Read illuminance, () → float lux
    // throws on a saturated channel
    const { ch0, ch1 } = await sensor.readRaw();                            // Read both channels, () → {ch0, ch1}
    // one block read: ch0 visible+IR, ch1 IR
    console.log(await sensor.readLuxRaw());                                 // Lux plus counts, () → {lux, ch0, ch1}
    console.log(calculateLux(ch0, ch1, 1, 402, 'T'));                       // Convert counts, (ch0, ch1, gain, integrationMs, package) → float lux

    await sensor.setGain(16);                                               // Set ADC gain, (gain 1|16) → void
    // 16× for dim light; saturates indoors at ~1–2 klx
    await sensor.setIntegrationTime(101);                                   // Set integration time, (ms 14|101|402) → void
    // the conversion in progress keeps the old setting
    console.log(sensor.getGain(), sensor.getIntegrationTime());             // Current settings, () → int, () → int ms
    await sleep(250);
    sensor.setPackage('CS');                                                // Select lux coefficients, (package "T"|"FN"|"CL"|"CS") → void
    // T, FN and CL share one set; CS has its own
    console.log(await sensor.readLux());
    sensor.setPackage('T');
    console.log(await sensor.autoRange());                                  // Auto-range and read lux, () → float lux
    // 16×/402 ms … 1×/13.7 ms until no saturation and ch0 > 100

    await sensor.startManual();                                             // Start a host-timed integration, () → void
    // INTEG = 11, MANUAL = 1
    await sleep(200);
    await sensor.stopManual();                                              // Stop the integration, () → void
    // INTEG = 11, MANUAL = 0; counts are ready
    console.log(await sensor.readRaw());
    await sensor.setIntegrationTime(402);
    await sensor.setGain(1);

    await sensor.setThresholds(100, 20000);                                 // Set channel-0 window, (low, high counts 0–65535) → void
    // word writes of 0x2–0x3 and 0x4–0x5
    console.log(await sensor.getThresholds());                              // Read the window, () → {low, high}
    await sensor.configureInterrupt(1, 2);                                  // Interrupt mode, (mode 0|1|2, persist 0–15) → void
    // level interrupt after 2 cycles outside the window
    console.log(await sensor.pollInterrupt());                              // Check ch0 against the window, () → bool
    // does not clear the chip's interrupt
    await sensor.clearInterrupt();                                          // Release a level interrupt, () → void
    // command byte 0xC0
    await sensor.onInterrupt((c0) => console.log('interrupt', c0));         // Subscribe, (callback(ch0), {intPin}) → void
    // falling edge on INT, or poll once per integration
    await sleep(10000);
    await sensor.offInterrupt();                                            // Unsubscribe, () → void
    await sensor.configureInterrupt(0, 0);

    console.log(await sensor.isPowered());                                  // Power state, () → bool
    // CONTROL reads 0x03 when powered
    await sensor.powerDown();                                               // Power down, () → void
    // CONTROL = 0x00; registers keep their values
    await sensor.powerUp();                                                 // Power up, () → void
    // CONTROL = 0x03; integration restarts

    await connection.close();
})();
