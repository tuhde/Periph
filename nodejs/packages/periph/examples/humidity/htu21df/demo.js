'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { HTU21DFFull } = require('../../../src/chips/humidity/htu21df');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x40', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x40) → Connection
const sensor = new HTU21DFFull(connection);                               // Create HTU21D-F driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Soft reset, () → void
    await sensor.softReset();                                            // Soft reset, () → void
    console.log('user register 0x' + (await sensor.readUserRegister()).toString(16));   // Read the user register, () → number
    console.log('resolution', await sensor.getResolution());             // Get resolution, () → number

    // --- Five readings at 12/14 bit ---
    for (let i = 0; i < 5; i++) {
        const [t, rh] = await sensor.read();                             // Read temperature and humidity, () → [number, number]
        console.log('%s C %s %%RH (compensated %s) dew point %s C', t.toFixed(2), rh.toFixed(1),
            (await sensor.readHumidityCompensated()).toFixed(1), (await sensor.dewPoint()).toFixed(2));  // () → number %RH ; () → number °C
        await sleep(2000);
    }
    console.log('raw', await sensor.readRaw());                          // Read raw codes, () → [number, number]

    // --- 8/12 bit: shorter conversion, coarser resolution ---
    await sensor.setResolution(1);                                       // Set resolution, (res) → void
    const start = Date.now();
    const t812 = await sensor.readTemperature();                         // () → number °C
    const rh812 = await sensor.readHumidity();                           // () → number %RH
    console.log('8/12 bit: %s C %s %%RH in %d ms', t812.toFixed(2), rh812.toFixed(1), Date.now() - start);
    await sensor.setResolution(0);
    sensor.setHoldMaster(false);                                         // Use hold-master commands, (on) → void

    // --- Heater self-check: 10 s ---
    const rhBefore = await sensor.readHumidity();
    const tBefore = await sensor.readTemperature();
    await sensor.setHeater(true);                                        // Switch the heater, (on) → void
    await sleep(10000);
    console.log('heater on:', await sensor.heater());                    // Heater state, () → boolean
    console.log('RH drop %s %%RH, temperature rise %s K',
        (rhBefore - await sensor.readHumidity()).toFixed(1), ((await sensor.readTemperature()) - tBefore).toFixed(2));
    await sensor.setHeater(false);
    console.log('battery low:', await sensor.batteryLow());             // End-of-battery flag, () → boolean
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
