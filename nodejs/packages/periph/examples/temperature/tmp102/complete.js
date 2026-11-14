'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { TMP102Full } = require('periph/src/chips/temperature/tmp102');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, 0x48);
    const sensor = new TMP102Full(connection);                                     // Create TMP102 Full driver, (connection)

    const t = await sensor.readTemperature();                                      // Read temperature, () → number °C
                                                                                   // decodes TEMPERATURE, 0.0625 °C two's complement
    console.log(`temperature ${t.toFixed(4)} °C`);
    console.log('raw', await sensor.readRaw());                                    // Read raw count, () → number
                                                                                   // signed 12/13-bit count, 0.0625 °C per LSB

    console.log('config 0x' + (await sensor.readConfig()).toString(16));           // Read CONFIGURATION, () → number
                                                                                   // reset value 0x60A0
    await sensor.writeConfig(await sensor.readConfig());                           // Write CONFIGURATION, (value) → None
                                                                                   // read-only bits are masked out

    await sensor.setConversionRate(8);                                             // Set conversion rate, (hz) → None
                                                                                   // 0.25, 1, 4 or 8 Hz
    await sensor.setExtendedMode(true);                                            // Set extended mode, (on) → None
                                                                                   // EM bit, 13-bit readings up to +255.9375 °C
    await sleep(200);
    console.log(`extended ${(await sensor.readTemperature()).toFixed(4)} °C`);
    await sensor.setExtendedMode(false);                                           // Set extended mode, (on) → None
                                                                                   // back to 12 bit

    await sensor.setTHigh(30.0);                                                   // Set T_HIGH, (celsius °C) → None
                                                                                   // rounded to 0.0625 °C
    await sensor.setTLow(10.0);                                                    // Set T_LOW, (celsius °C) → None
                                                                                   // rounded to 0.0625 °C
    console.log('high', await sensor.getTHigh());                                  // Read T_HIGH, () → number °C
                                                                                   // same format as TEMPERATURE
    console.log('low', await sensor.getTLow());                                    // Read T_LOW, () → number °C
                                                                                   // same format as TEMPERATURE

    await sensor.setFaultQueue(2);                                                 // Set fault queue, (count) → None
                                                                                   // 1, 2, 4 or 6 consecutive faults
    await sensor.setPolarity(false);                                               // Set ALERT polarity, (activeHigh) → None
                                                                                   // POL bit, false = active low
    await sensor.setThermostatMode(false);                                         // Set thermostat mode, (interrupt) → None
                                                                                   // TM bit, false = comparator
    console.log('alert', await sensor.alertState());                               // Read alert state, () → boolean
                                                                                   // AL bit corrected for POL

    await sensor.shutdown();                                                       // Shut down, () → None
                                                                                   // SD bit, stops after the current conversion
    console.log(`one-shot ${(await sensor.oneShot()).toFixed(4)} °C`);             // One-shot reading, () → number °C
                                                                                   // OS=1, polls until done, restores the mode
    await sensor.wake();                                                           // Wake, () → None
                                                                                   // resume continuous conversion

    console.log('polling', await sensor.pollInterrupt());                          // Poll the alert state, () → boolean
                                                                                   // reads CONFIGURATION and TEMPERATURE
    await sensor.onInterrupt((c) => console.log(`ALERT at ${c.toFixed(2)} °C`));   // Subscribe to ALERT, (callback) → None
                                                                                   // callback(temperatureC)
    await sleep(3000);
    await sensor.offInterrupt();                                                   // Unsubscribe, () → None
                                                                                   // stop delivery

    await sensor.setConversionRate(4);                                             // Set conversion rate, (hz) → None
                                                                                   // back to the power-on default
    await sensor.setTHigh(80.0);                                                   // Set T_HIGH, (celsius °C) → None
    await sensor.setTLow(75.0);                                                    // Set T_LOW, (celsius °C) → None
    await sensor.reset();                                                          // General-call reset, () → None
                                                                                   // resets every general-call device on the bus

    await connection.close();
})();
