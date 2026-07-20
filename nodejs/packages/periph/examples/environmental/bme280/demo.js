'use strict';
const { I2CTransport } = require('../../../src/transport/i2c');
const { BME280Full } = require('../../../src/chips/environmental/bme280');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x76', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);

function stats(arr) {
    if (!arr.length) return [0, 0, 0];
    const sum = arr.reduce((a, b) => a + b, 0);
    return [Math.min(...arr), sum / arr.length, Math.max(...arr)];
}

async function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

(async () => {
    // --- Weather monitoring preset: forced mode, ×1/×1/×1, filter off ---
    // BME280 datasheet "weather monitoring" preset: minimum power,
    // single-shot, 8 ms typ / 9.3 ms max per cycle. Sleep between samples
    // to demonstrate battery-friendly indoor monitoring.
    const bme = new BME280Full(transport);             // Create BME280 driver, (transport, busType='i2c')
    bme.configure(BME280Full.OSRS_X1, BME280Full.OSRS_X1, BME280Full.OSRS_X1, BME280Full.MODE_FORCED, BME280Full.FILTER_OFF, BME280Full.T_SB_0_5_MS);  // Configure chip, (osrsT=×1, osrsP=×1, osrsH=×1, mode=forced, filter=off, tSb=0) → void

    const temps = [], hums = [], pressures = [], alts = [], dews = [];
    for (let n = 0; n < 10; n++) {
        const t = bme.temperature();                   // Read temperature, () → number °C
        const p = bme.pressure();                      // Read pressure, () → number hPa
        const h = bme.humidity();                      // Read humidity, () → number %RH
        const a = bme.altitude();                      // Compute altitude, (seaLevelHpa=1013.25) → number m
        const d = bme.dewPoint();                      // Compute dew point, () → number °C
        temps.push(t); hums.push(h); pressures.push(p); alts.push(a); dews.push(d);
        console.log(`${n}: ${t.toFixed(1)} C, ${h.toFixed(1)} %RH, ${p.toFixed(1)} hPa, dew=${d.toFixed(1)} C, alt=${a.toFixed(1)} m`);
        await sleep(1000);
    }

    // --- Half-way: breathe gently on the sensor for 3 seconds ---
    // User exposes the sensor to humid exhaled air; humidity climbs from
    // ~40 %RH toward ~80 %RH, dew point spikes toward ambient temperature,
    // pressure stays flat, temperature rises only slightly. Demonstrates
    // the humidity channel's response and the dew-point alarm use case.
    console.log('--- Breathe gently on the sensor for 3 seconds ---');
    await sleep(3000);
    {
        const t = bme.temperature();                   // Read temperature, () → number °C
        const p = bme.pressure();                      // Read pressure, () → number hPa
        const h = bme.humidity();                      // Read humidity, () → number %RH
        const d = bme.dewPoint();                      // Compute dew point, () → number °C
        temps.push(t); hums.push(h); pressures.push(p); dews.push(d);
        console.log(`after breath: ${t.toFixed(1)} C, ${h.toFixed(1)} %RH, ${p.toFixed(1)} hPa, dew=${d.toFixed(1)} C`);
    }

    const [tMin, tAvg, tMax] = stats(temps);
    const [hMin, hAvg, hMax] = stats(hums);
    const [pMin, pAvg, pMax] = stats(pressures);
    const [dMin, dAvg, dMax] = stats(dews);
    console.log(`T:    ${tMin.toFixed(1)}/${tAvg.toFixed(1)}/${tMax.toFixed(1)} C`);
    console.log(`RH:   ${hMin.toFixed(1)}/${hAvg.toFixed(1)}/${hMax.toFixed(1)} %`);
    console.log(`P:    ${pMin.toFixed(1)}/${pAvg.toFixed(1)}/${pMax.toFixed(1)} hPa`);
    console.log(`dew:  ${dMin.toFixed(1)}/${dAvg.toFixed(1)}/${dMax.toFixed(1)} C`);

    transport.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
