'use strict';

// Thermostat with one-shot sampling: read the power-on state, sample at 4 Hz,
// switch to 8 Hz and the 13-bit mode, take timed one-shot readings, then run an
// interrupt-mode thermostat around the current temperature while the sensor is
// warmed with a finger. Finally the power-on defaults are restored.

const { I2CConnection } = require('periph/src/connection/i2c');
const { TMP102Full } = require('periph/src/chips/temperature/tmp102');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, 0x48);
    const sensor = new TMP102Full(connection);                                     // Create TMP102 Full driver, (connection)

    console.log('config 0x' + (await sensor.readConfig()).toString(16));           // Read CONFIGURATION, () → number
    console.log('limits', await sensor.getTLow(), await sensor.getTHigh());        // Read T_LOW, () → number °C; Read T_HIGH, () → number °C

    // --- Continuous reading at 4 Hz for 5 s ---
    for (let i = 0; i < 20; i++) {
        const t = await sensor.readTemperature();                                  // Read temperature, () → number °C
        console.log(`${t.toFixed(4)} °C (raw ${await sensor.readRaw()})`);         // Read raw count, () → number
        await sleep(250);
    }

    // --- 8 Hz and the 13-bit extended mode ---
    await sensor.setConversionRate(8);                                             // Set conversion rate, (hz) → None
    await sensor.setExtendedMode(true);                                            // Set extended mode, (on) → None
    await sleep(200);
    console.log(`extended ${(await sensor.readTemperature()).toFixed(4)} °C`);     // Read temperature, () → number °C
    await sensor.setExtendedMode(false);                                           // Set extended mode, (on) → None
    await sensor.setConversionRate(4);                                             // Set conversion rate, (hz) → None

    // --- Shutdown and timed one-shot readings ---
    await sensor.shutdown();                                                       // Shut down, () → None
    for (let i = 0; i < 5; i++) {
        const start = Date.now();
        const t = await sensor.oneShot();                                          // One-shot reading, () → number °C
        console.log(`one-shot ${t.toFixed(4)} °C in ${Date.now() - start} ms`);
    }
    await sensor.wake();                                                           // Wake, () → None

    // --- Interrupt-mode thermostat around the current temperature ---
    const now = await sensor.readTemperature();                                    // Read temperature, () → number °C
    await sensor.setTHigh(now + 1.0);                                              // Set T_HIGH, (celsius °C) → None
    await sensor.setTLow(now - 1.0);                                               // Set T_LOW, (celsius °C) → None
    await sensor.setFaultQueue(2);                                                 // Set fault queue, (count) → None
    await sensor.setPolarity(false);                                               // Set ALERT polarity, (activeHigh) → None
    await sensor.setThermostatMode(true);                                          // Set thermostat mode, (interrupt) → None
    console.log('warm the sensor with a finger ...');
    for (let i = 0; i < 40; i++) {
        if (await sensor.pollInterrupt()) {                                        // Poll the alert state, () → boolean
            console.log(`ALERT: ${(await sensor.readTemperature()).toFixed(2)} °C, alert_state=${await sensor.alertState()}`);  // Read alert state, () → boolean
        }
        await sleep(250);
    }
    await sensor.onInterrupt((c) => console.log(`ALERT at ${c.toFixed(2)} °C`));   // Subscribe to ALERT, (callback) → None
    await sensor.offInterrupt();                                                   // Unsubscribe, () → None

    // --- Restore the power-on defaults ---
    await sensor.setThermostatMode(false);                                         // Set thermostat mode, (interrupt) → None
    await sensor.setFaultQueue(1);                                                 // Set fault queue, (count) → None
    await sensor.setTHigh(80.0);                                                   // Set T_HIGH, (celsius °C) → None
    await sensor.setTLow(75.0);                                                    // Set T_LOW, (celsius °C) → None
    await sensor.writeConfig(0x60A0);                                              // Write CONFIGURATION, (value) → None
    await sensor.reset();                                                          // General-call reset, () → None

    await connection.close();
})();
