'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { MPL3115A2Full } = require('../../../src/chips/pressure/mpl3115a2');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x60', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x60) → Connection
const sensor = new MPL3115A2Full(connection);                                       // Create MPL3115A2 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Reset and apply defaults, () → void

    // --- Weather and altitude station ---
    // Sets the local sea-level pressure, takes three barometer readings, switches to
    // altimeter mode for three altitude readings, collects 16 samples in the circular
    // FIFO and prints their minimum, maximum and mean, then arms a temperature
    // threshold on INT1 and reports the captured extremes.
    await sensor.setSeaLevelPressure(101325);                            // Sea-level reference, (pa) → void
    for (let i = 0; i < 3; i++) {
        const m = await sensor.read();                                   // Read pressure and temperature, () → {pressure_pa, temperature_c}
        console.log('%s Pa  %s C', m.pressure_pa.toFixed(2), m.temperature_c.toFixed(2));
        await sleep(5000);
    }
    for (let i = 0; i < 3; i++) {
        console.log('altitude %s m', (await sensor.readAltitude()).toFixed(2)); // Read altitude, () → number m
    }
    await sensor.setMode(false);                                         // Back to barometer, (altimeter) → void
    await sensor.setOversample(16);                                      // Faster conversions, (ratio) → void
    await sensor.fifoSetup(MPL3115A2Full.FIFO_CIRCULAR, 16);             // Circular FIFO, watermark 16, (mode, watermark) → void
    await sensor.startContinuous(0);                                     // One sample per second, (stepExp 0-15) → void
    while (!(await sensor.fifoStatus())[1]) await sleep(1000);           // Wait for the watermark flag, () → [overflow, watermark, count]
    const samples = await sensor.fifoRead();                             // Drain the FIFO, () → [[value Pa, temperature_c °C], …]
    const values = samples.map((s) => s[0]);
    const mean = values.reduce((a, b) => a + b, 0) / values.length;
    console.log('n=%d min %s max %s mean %s Pa', values.length, Math.min(...values).toFixed(2), Math.max(...values).toFixed(2), mean.toFixed(2));
    await sensor.setTarget(0, 0, Math.trunc(samples[samples.length - 1][1]) + 1, 0); // Temperature target, (value, window, temperatureC, temperatureWindow) → void
    await sensor.enableInterrupt(MPL3115A2Full.SOURCE_TTH, 1);           // Route temperature threshold to INT1, (source, pin) → void
    console.log('INT_SOURCE 0x%s', (await sensor.pollInterrupt()).toString(16)); // Read INT_SOURCE, () → number
    await sensor.stopContinuous();                                       // Back to STANDBY, () → void
    await sensor.fifoSetup(MPL3115A2Full.FIFO_OFF);                      // FIFO off, (mode) → void
    console.log(await sensor.readMinMax());                              // Captured extremes, () → {p_min, p_max, t_min, t_max}
    await connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
