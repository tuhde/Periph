'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { BME688Full } = require('../../../src/chips/environmental/bme688');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x76', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

function dewPoint(t, rh) {
    // Magnus formula, SI units (degC, %RH)
    const g = Math.log(Math.max(rh, 0.01) / 100) + 17.62 * t / (243.12 + t);
    return 243.12 * g / (17.62 - g);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    // --- Room air probe with parallel mode ---
    // Part 1: ten forced-mode readings (T, RH, hPa, Ohm, dew point), one per 5 s.
    // Part 2: parallel mode with a 3-step heater profile (320, 250 and 180 degC;
    // repeats 1, 2, 1; shared wait 100 ms) for one minute, printing the gas
    // resistance of every heater step, then back to sleep.
    const bme = new BME688Full(connection);               // Create BME688 driver, (connection)
    await bme.configure(BME688Full.OSRS_X2, BME688Full.OSRS_X16, BME688Full.OSRS_X1, BME688Full.MODE_SLEEP, BME688Full.FILTER_15);  // Configure chip, (osrsT=×2, osrsP=×16, osrsH=×1, mode=sleep, filter=15) → undefined
    await bme.setHeater(320, 150);                        // Configure heater profile 0, (tempC=320, durationMs=150) → undefined

    console.log('--- forced mode ---');
    for (let n = 0; n < 10; n++) {
        const r = await bme.readAll();                    // Read all sensors in one cycle, () → object
        console.log(`${n}: ${r.temperature.toFixed(1)} C, ${r.humidity.toFixed(1)} %RH, ${r.pressure.toFixed(1)} hPa, ${r.gasResistance.toFixed(0)} Ohm, dew ${dewPoint(r.temperature, r.humidity).toFixed(1)} C`);
        await sleep(5000);
    }

    console.log('--- parallel mode ---');
    await bme.startParallel([[320, 1], [250, 2], [180, 1]], 100);  // Start parallel mode, (steps=[[tempC, repeats]], sharedWaitMs=100) → undefined
    for (let n = 0; n < 60; n++) {
        for (const f of await bme.readParallel()) {       // Read the new data fields, () → array of objects
            console.log(`step ${f.step} #${f.subIndex}: ${f.temperature.toFixed(1)} C, ${f.humidity.toFixed(1)} %RH, ${f.gasResistance.toFixed(0)} Ohm (valid=${f.gasValid}, stable=${f.heaterStable})`);
        }
        await sleep(1000);
    }
    await bme.stopParallel();                             // Back to sleep, () → undefined

    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
