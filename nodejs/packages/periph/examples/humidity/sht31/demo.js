'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { SHT31Full } = require('../../../src/chips/humidity/sht31');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x44', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x44) → Connection
const sensor = new SHT31Full(connection);                                // Create SHT31 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Wait for the soft reset, () → void
    await sensor.softReset();                                            // Soft reset, () → void
    console.log('status 0x' + (await sensor.readStatus()).toString(16)); // Read status register, () → number

    // --- Single shots at all three repeatability levels ---
    for (const level of ['low', 'medium', 'high']) {
        sensor.setRepeatability(level);                                  // Select repeatability, (level) → void
        const start = process.hrtime.bigint();
        const [t, rh] = await sensor.read();                             // Read temperature and humidity, () → [number, number]
        const ms = Number(process.hrtime.bigint() - start) / 1e6;
        console.log('%s %s C %s %%RH (%s ms)', level.padEnd(6), t.toFixed(2), rh.toFixed(1), ms.toFixed(1));
    }
    sensor.setClockStretching(false);                                    // Use clock-stretching commands, (on) → void
    console.log('raw', await sensor.readRaw());                          // Read raw words, () → [number, number]
    console.log('T', await sensor.readTemperature(), 'RH', await sensor.readHumidity()); // Read temperature only, () → number ; Read humidity only, () → number

    // --- Periodic mode at 2 mps: ten samples ---
    await sensor.startPeriodic(2, 'high');                               // Start periodic mode, (rate, repeatability='high', art=false) → void
    for (let got = 0; got < 10;) {
        const res = await sensor.fetch();                                // Fetch the latest periodic result, () → [number, number] | null
        if (res === null) { await sleep(50); continue; }
        got++;
        console.log('periodic %d: %s C %s %%RH', got, res[0].toFixed(2), res[1].toFixed(1));
    }
    await sensor.stopPeriodic();                                         // Stop periodic mode, () → void

    // --- Heater test: 10 s ---
    const before = await sensor.readTemperature();
    await sensor.setHeater(true);                                        // Switch the heater, (on) → void
    await sleep(10000);
    console.log('heater status bit:', !!((await sensor.readStatus()) & 0x2000)); // Read status register, () → number
    console.log('temperature rise: %s K', ((await sensor.readTemperature()) - before).toFixed(2));
    await sensor.setHeater(false);
    await sensor.clearStatus();                                          // Clear status flags, () → void
    console.log('status 0x' + (await sensor.readStatus()).toString(16));
    console.log('dew point %s C', (await sensor.dewPoint()).toFixed(2)); // Compute dew point, () → number °C
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
