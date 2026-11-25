'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { SHTC3Full } = require('../../../src/chips/humidity/shtc3');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x70', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x70) → Connection
const sensor = new SHTC3Full(connection);                            // Create SHTC3 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Soft reset and ID check, () → void

    // --- Identify the chip ---
    // The ID word carries the SHTC3 product code in bits 11 and 5:0.
    console.log('id 0x' + (await sensor.readId()).toString(16));         // Read the ID word, () → number

    // --- Battery-friendly climate logger: 6 readings, 10 s apart ---
    // The sensor sleeps (0.3 uA) between the readings; every read wakes it.
    for (let i = 0; i < 6; i++) {
        const m = await sensor.read();                                   // Read temperature and humidity, () → {temperature_c: number °C, humidity_pct: number %RH}
        console.log('%s C  %s %%RH', m.temperature_c.toFixed(2), m.humidity_pct.toFixed(1));
        await sleep(10000);
    }

    // --- Compare normal and low-power mode ---
    // Low power measures in 0.7 ms instead of 10.8 ms at slightly higher noise.
    for (const low of [false, true]) {
        sensor.setLowPower(low);                                         // Select low-power mode, (on) → void
        const start = Date.now();
        const m = await sensor.read();                                   // Read temperature and humidity, () → {temperature_c: number °C, humidity_pct: number %RH}
        console.log(low ? 'low power' : 'normal   ', (Date.now() - start) + ' ms', m);
    }
    sensor.setLowPower(false);

    // --- Dew point and raw words, then finish asleep ---
    console.log('dew point', (await sensor.dewPoint()).toFixed(2), 'C'); // Compute dew point, () → number °C
    console.log('raw', await sensor.readRaw());                          // Read raw codes, () → [number, number]
    await sensor.sleep();                                                // Sleep command, () → void
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
