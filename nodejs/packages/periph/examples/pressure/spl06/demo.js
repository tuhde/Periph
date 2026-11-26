'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { SPL06Full } = require('../../../src/chips/pressure/spl06');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x77', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x77) → Connection
const sensor = new SPL06Full(connection);                                // Create SPL06 driver, (connection) → void

async function main() {
    // --- Identity and calibration ---
    // init() checks ID = 0x10; the nine coefficients are factory-trimmed per
    // part and drive every compensated value below.
    await sensor.init();                                                 // Check ID, read coefficients, apply defaults, () → void
    console.log('ID 0x10 verified', sensor.coefficients());              // Decoded calibration, () → {c0 … c30}

    // --- Altimeter: 20 s at 1 Hz, altitude relative to the first reading ---
    // The first pressure becomes the reference level, so the altitude column
    // shows how far the sensor moved since start (about 12 Pa per metre).
    const p0 = await sensor.readPressure();                              // Reference pressure, () → number Pa
    for (let i = 0; i < 20; i++) {
        const m = await sensor.read();                                   // Temperature and pressure, () → {temperature_c °C, pressure_pa Pa}
        const t = await sensor.readTemperature();                        // Temperature only, () → number °C
        const h = await sensor.altitude(p0);                             // Altitude vs. reference, (seaLevelPa) → number m
        console.log(`${i} s  ${m.temperature_c.toFixed(2)} C (${t.toFixed(2)} C)  ${m.pressure_pa.toFixed(2)} Pa  ${h.toFixed(2)} m`);
        await sleep(1000);
    }

    // --- Oversampling: noise and measurement time at 1x, 16x and 128x ---
    // More oversampling means less noise but longer measurements; above 8x
    // the driver sets P_SHIFT automatically.
    for (const osr of [1, 16, 128]) {
        await sensor.setPressureConfig(osr, 1);                          // Pressure oversampling, (oversampling, rate) → void
        const values = [];
        const start = Date.now();
        for (let j = 0; j < 10; j++) values.push(await sensor.readPressure());  // Pressure, () → number Pa
        const per = (Date.now() - start) / 10;
        const mean = values.reduce((a, b) => a + b, 0) / values.length;
        const sd = Math.sqrt(values.reduce((a, v) => a + (v - mean) ** 2, 0) / values.length);
        console.log(`${osr}x: mean ${mean.toFixed(2)} Pa  sd ${sd.toFixed(2)} Pa  ${per.toFixed(1)} ms per reading`);
    }

    // --- Variometer: background mode at 8 Hz ---
    // The chip measures on its own; readContinuous only fetches the latest
    // results, so the host never waits for a conversion it started itself.
    await sensor.setPressureConfig(16, 8);                               // 16x, 8 Hz, (oversampling, rate) → void
    await sensor.setTemperatureConfig(1, 8);                             // 1x, 8 Hz, (oversampling, rate) → void
    await sensor.startContinuous(true, true);                            // Background mode, (pressure, temperature) → void
    let last = null;
    for (let i = 0; i < 16; i++) {
        const c = await sensor.readContinuous();                         // Latest results, () → {temperature_c, pressure_pa}
        if (last !== null) console.log(`${c.pressure_pa.toFixed(2)} Pa  climb ${(-(c.pressure_pa - last) / 12 * 8).toFixed(2)} m/s`);
        last = c.pressure_pa;
    }
    console.log('ready flags', await sensor.dataReady());                // Ready flags, () → [pressureReady, temperatureReady]
    await sensor.stop();                                                 // Standby, () → void

    // --- FIFO: let the chip collect 32 results, then read them in one go ---
    // Each entry's LSB marks it as pressure or temperature; the host can sleep
    // while the chip fills the FIFO (32 entries = 2 s at 8 Hz for both).
    await sensor.flushFifo();                                            // Empty the FIFO, () → void
    await sensor.enableFifo();                                           // FIFO on, () → void
    await sensor.startContinuous();                                      // Background mode, (pressure=true, temperature=true) → void
    for (let i = 0; i < 50 && !(await sensor.fifoStatus())[1]; i++) {    // Wait for full, () → [empty, full]
        await sleep(100);
    }
    await sensor.stop();                                                 // Standby, () → void
    const entries = await sensor.readFifo();                             // Drain FIFO, () → [{kind, value}]
    const pressures = entries.filter((e) => e.kind === 'pressure').map((e) => e.value);
    const pmean = pressures.reduce((a, b) => a + b, 0) / Math.max(pressures.length, 1);
    console.log(`${entries.length} FIFO entries, ${pressures.length} pressure, mean ${pmean.toFixed(2)} Pa`);
    await sensor.disableFifo();                                          // FIFO off, () → void

    // --- Data-ready interrupt ---
    // SDO doubles as the interrupt line; the callback receives the INT_STS bits.
    // Without an INT pin on the connection the driver polls INT_STS instead.
    await sensor.enableInterrupt(SPL06Full.SOURCE_PRESSURE);             // Pressure-ready interrupt, (source) → void
    await sensor.pollInterrupt();                                        // Clear stale status, () → number
    await sensor.onInterrupt((status) => console.log(`interrupt 0x${status.toString(16)}`));  // Subscribe, (callback, intPin=null) → void
    await sensor.startContinuous(true, true);                            // Background mode, (pressure, temperature) → void
    await sleep(2000);
    await sensor.offInterrupt();                                         // Unsubscribe, () → void
    await sensor.disableInterrupt(SPL06Full.SOURCE_PRESSURE);            // Disable source, (source) → void
    await sensor.stop();                                                 // Standby, () → void

    // --- Compensation by hand ---
    // compensate() turns raw register values into SI units with the stored
    // coefficients; useful for logging raw data and converting it later.
    await sensor.setPressureConfig(16, 1);                               // Back to 16x, (oversampling, rate) → void
    await sensor.setTemperatureConfig(1, 1);                             // Back to 1x, (oversampling, rate) → void
    const [praw, traw] = await sensor.readRaw();                         // Raw results, () → [praw, traw]
    console.log('raw', praw, traw, '->', sensor.compensate(praw, traw, 16, 1));  // Compensate, (praw, traw, pOversampling, tOversampling) → {temperature_c, pressure_pa}

    // --- Wrap up ---
    // Standby, empty FIFO and a soft reset leave the chip as after power-up
    // (plus the driver defaults).
    await sensor.stop();                                                 // Standby, () → void
    await sensor.flushFifo();                                            // Empty the FIFO, () → void
    await sensor.reset();                                                // Soft reset and defaults, () → void
    await connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
