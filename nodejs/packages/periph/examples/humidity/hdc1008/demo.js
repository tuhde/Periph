'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { HDC1008Full } = require('../../../src/chips/humidity/hdc1008');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x40', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x40) → Connection
const sensor = new HDC1008Full(connection);                               // Create HDC1008 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Write default configuration, () → void
    console.log('serial id 0x' + (await sensor.serialId()).toString(16));   // Read the 40-bit serial ID, () → number

    // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
    for (let i = 0; i < 15; i++) {
        const [t, rh] = await sensor.read();                             // Read temperature and humidity, () → [number, number]
        const g = Math.log(Math.max(rh, 0.01) / 100.0) + 17.62 * t / (243.12 + t);
        const dew = 243.12 * g / (17.62 - g);                            // Magnus dew point, °C
        console.log('%s C %s %%RH dew point %s C', t.toFixed(2), rh.toFixed(1), dew.toFixed(2));
        await sleep(2000);
    }

    // --- Heater test: 10 s ---
    const rh0 = await sensor.readHumidity();                             // () → number %RH
    const t0 = await sensor.readTemperature();                           // () → number °C
    await sensor.setHeater(true);                                        // Switch the heater, (on) → void
    await sleep(10000);
    const rh1 = await sensor.readHumidity();
    const t1 = await sensor.readTemperature();
    console.log('RH drop %s %%RH, temperature rise %s K', (rh0 - rh1).toFixed(1), (t1 - t0).toFixed(2));
    await sensor.setHeater(false);

    // --- Resolution comparison ---
    await sensor.setResolution(11, 8);                                   // Set resolution, (temperatureBits, humidityBits) → void
    const start = Date.now();
    const [t, rh] = await sensor.read();
    console.log('11/8 bit: %s C %s %%RH in %d ms', t.toFixed(2), rh.toFixed(1), Date.now() - start);
    await sensor.setResolution(14, 14);
    console.log('battery low:', await sensor.batteryLow());              // VDD below 2.8 V, () → boolean
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
