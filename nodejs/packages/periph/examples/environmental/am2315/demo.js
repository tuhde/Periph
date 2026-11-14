'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { AM2315Full } = require('../../../src/chips/environmental/am2315');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x5C', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x5C) → Connection
const sensor = new AM2315Full(connection);                                // Create AM2315 driver, (connection) → void

// Magnus formula
function dewPoint(t, rh) {
    const g = Math.log(Math.max(rh, 0.1) / 100.0) + 17.62 * t / (243.12 + t);
    return 243.12 * g / (17.62 - g);
}

async function main() {
    const info = await sensor.readDeviceInfo();                          // Read device information, () → {model, version, device_id}
    console.log('model %d, version %d, device id 0x%s', info.model, info.version, info.device_id.toString(16).toUpperCase());

    // --- Weather-station logger: a sample every 5 s, min/max tracking ---
    let tMin = 1000, tMax = -1000, hMin = 1000, hMax = -1000;
    for (let i = 1; i <= 30; i++) {
        // The sensor measures after a communication and returns that result on the next
        // one, so every sample is the previous measurement; the driver enforces the >= 2 s
        // spacing and the CRC-16 check.
        const r = (i % 10 === 0)
            ? await sensor.readFresh()                                   // Real-time value, () → {temperature_c, humidity_pct}
            : await sensor.read();                                       // Read temperature and humidity, () → {temperature_c, humidity_pct}
        const t = r.temperature_c, rh = r.humidity_pct;
        tMin = Math.min(tMin, t); tMax = Math.max(tMax, t);
        hMin = Math.min(hMin, rh); hMax = Math.max(hMax, rh);
        console.log('%s  %s C  %s %%RH  dew point %s C  (T %s..%s, RH %s..%s)', new Date().toISOString(),
            t.toFixed(1), rh.toFixed(1), dewPoint(t, rh).toFixed(1), tMin.toFixed(1), tMax.toFixed(1), hMin.toFixed(1), hMax.toFixed(1));
        await sleep(5000);
    }

    // --- User registers as a persistent counter ---
    await sensor.writeUserRegisters(30, 0xBEEF);                         // Write user registers, (user1, user2) → void
    console.log('user registers:', await sensor.readUserRegisters());   // Read user registers, () → [number, number]
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
