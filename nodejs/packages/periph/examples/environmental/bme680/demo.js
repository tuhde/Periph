'use strict';
const { I2CTransport } = require('../../../packages/periph/src/transport/i2c');
const { BME680Full } = require('../../../packages/periph/src/chips/environmental/bme680');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x76', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);

// --- Room air quality probe: 4-in-1 sensor polling with VOC event ---
// Polls all four sensors once every 5 seconds for 5 minutes (60 ticks).
// At tick 30, the user is prompted to expose the sensor to a VOC source
// (isopropyl alcohol, marker pen). Gas resistance drops sharply on exposure
// and recovers over the remaining ticks, demonstrating raw VOC sensitivity
// without the closed-source BSEC library.
const bme = new BME680Full(transport);                   // Create BME680 driver, (transport)
bme.configure(BME680Full.OSRS_X2, BME680Full.OSRS_X16, BME680Full.OSRS_X1, BME680Full.MODE_FORCED, BME680Full.FILTER_15);  // Configure chip, (osrsT=×2, osrsP=×16, osrsH=×1, mode=forced, filter=15) → undefined
bme.setHeater(320, 150);                                // Configure heater profile 0, (tempC=320, durationMs=150) → undefined

const temps = [], hums = [], pressures = [], gases = [];
for (let n = 0; n < 60; n++) {
    if (n === 30) {
        console.log('--- Expose sensor to VOC source now (alcohol/marker) ---');
    }
    const r = bme.readAll();                             // Read all sensors in one cycle, () → object
    temps.push(r.temperature);
    pressures.push(r.pressure);
    hums.push(r.humidity);
    if (!isNaN(r.gasResistance)) gases.push(r.gasResistance);
    console.log(`${n}: ${r.temperature.toFixed(1)} C, ${r.humidity.toFixed(1)} %RH, ${r.pressure.toFixed(1)} hPa, ${r.gasResistance.toFixed(0)} Ohm`);
}

function stats(arr) {
    if (!arr.length) return { min: 0, avg: 0, max: 0 };
    const min = Math.min(...arr);
    const max = Math.max(...arr);
    const avg = arr.reduce((s, v) => s + v, 0) / arr.length;
    return { min, avg, max };
}

const ts = stats(temps);
const hs = stats(hums);
const ps = stats(pressures);
const gs = stats(gases);
console.log(`T: ${ts.min.toFixed(1)}/${ts.avg.toFixed(1)}/${ts.max.toFixed(1)} C`);
console.log(`RH: ${hs.min.toFixed(1)}/${hs.avg.toFixed(1)}/${hs.max.toFixed(1)} %`);
console.log(`P: ${ps.min.toFixed(1)}/${ps.avg.toFixed(1)}/${ps.max.toFixed(1)} hPa`);
console.log(`R_gas: ${gs.min.toFixed(0)}/${gs.avg.toFixed(0)}/${gs.max.toFixed(0)} Ohm`);
if (gs.min > 0) console.log(`VOC response ratio: ${(gs.max / gs.min).toFixed(1)}x`);

transport.close();
console.log('===DONE: 0 passed, 0 failed===');
