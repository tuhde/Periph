'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { HS40xxFull } = require('../../../src/chips/humidity/hs40xx');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x54', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x54) → Connection
const sensor = new HS40xxFull(connection);                               // Create HS40xx driver, (connection) → void

async function main() {
    // --- Humidity watchdog ---
    // Single readings for 10 s, then periodic mode with an RH-high alert (breathe on the sensor).
    console.log('ID 0x' + (await sensor.readId()).toString(16));         // Read the 32-bit sensor ID, () → Promise<number>
    for (let n = 0; n < 5; n++) {
        const d = await sensor.read();                                       // Read temperature and humidity, () → Promise<{temperature_c, humidity_pct}>
        console.log('%s C, %s %%RH', d.temperature_c.toFixed(2), d.humidity_pct.toFixed(1));
        await sleep(2000);
    }

    await sensor.setAlertThresholds({ rhHigh: 60, rhHighReset: 55 });    // Set alert thresholds, ({rhHigh, rhHighReset, ...}) → Promise<void>
    await sensor.enableInterrupt(HS40xxFull.SOURCE_RH_HIGH);             // Enable alert sources, (source) → Promise<void>
    await sensor.startPeriodic(1);                                       // Start periodic mode, (periodS) → Promise<void>
    for (let n = 0; n < 15; n++) {
        await sleep(1000);
        const p = await sensor.readPeriodic();                               // Fetch the latest periodic result, () → Promise<{temperature_c, humidity_pct, status}>
        const alert = (p.status & HS40xxFull.SOURCE_RH_HIGH) ? 'ALERT' : 'ok';
        console.log('%s C, %s %%RH  %s', p.temperature_c.toFixed(2), p.humidity_pct.toFixed(1), alert);
    }

    await sensor.stopPeriodic();                                         // Stop periodic mode, () → Promise<void>
    await sensor.softReset();                                            // Soft reset, () → Promise<void>
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
