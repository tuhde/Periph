'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { Si7021Full } = require('../../../src/chips/humidity/si7021');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x40', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x40) → Connection
const sensor = new Si7021Full(connection);                                // Create Si7021 driver, (connection) → void

async function main() {
    await sensor.softReset();                                            // Software reset, () → void
    console.log('device type 0x' + (await sensor.deviceType()).toString(16));      // Device type, () → number
    console.log('serial number 0x' + (await sensor.serialNumber()).toString(16));  // Electronic serial number, () → bigint
    console.log('firmware 0x' + (await sensor.firmwareRevision()).toString(16));   // Firmware revision, () → number
    console.log('VDD low:', await sensor.vddLow());                      // Supply low flag, () → boolean

    // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
    for (let i = 0; i < 15; i++) {
        const [t, rh] = await sensor.read();                             // Read temperature and humidity, () → [number, number]
        console.log('%s C %s %%RH dew point %s C', t.toFixed(2), rh.toFixed(1), (await sensor.dewPoint()).toFixed(2)); // Compute dew point, () → number °C
        await sleep(2000);
    }

    // --- Value accessors and raw codes ---
    console.log('RH', await sensor.readHumidity(), 'T', await sensor.readTemperature()); // () → number ; () → number
    console.log('T of last RH', await sensor.readTemperaturePrevious());  // () → number
    console.log('raw', await sensor.readRawHumidity(), await sensor.readRawTemperature()); // () → number ; () → number
    sensor.setClockStretching(false);                                    // Use hold-master commands, (on) → void
    const reg = await sensor.readUserRegister();                         // Read USER_REG_1, () → number
    await sensor.writeUserRegister(reg);                                 // Write USER_REG_1, (value) → void

    // --- Resolution comparison ---
    for (const [rhBits, tBits] of [[12, 14], [10, 13], [11, 11], [8, 12]]) {
        await sensor.setResolution(rhBits, tBits);                       // Set resolution, (rhBits, temperatureBits) → void
        const start = process.hrtime.bigint();
        const [t, rh] = await sensor.read();
        const ms = Number(process.hrtime.bigint() - start) / 1e6;
        console.log('%d/%d bit: %s C %s %%RH, %s ms (typ. %s ms), resolution %j', rhBits, tBits, t.toFixed(2), rh.toFixed(1),
            ms.toFixed(1), ((await sensor.conversionTime()) * 1000).toFixed(1), await sensor.getResolution()); // () → number s ; () → [number, number]
    }
    await sensor.setResolution(12, 14);

    // --- Heater test: 10 s ---
    const rhBefore = await sensor.readHumidity();
    const tBefore = await sensor.readTemperature();
    await sensor.setHeater(true);                                        // Switch the heater, (on) → void
    await sleep(10000);
    console.log('heater on:', await sensor.isHeaterOn());                // Heater state, () → boolean
    console.log('RH drop %s %%RH, temperature rise %s K', (rhBefore - await sensor.readHumidity()).toFixed(1),
        ((await sensor.readTemperature()) - tBefore).toFixed(2));
    await sensor.setHeater(false);
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
