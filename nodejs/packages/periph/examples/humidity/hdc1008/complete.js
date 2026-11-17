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
    console.log('config 0x' + (await sensor.readConfig()).toString(16));    // Read the configuration register, () → number
    const [t, rh] = await sensor.read();                                 // Read temperature and humidity, () → [number °C, number %RH]
    console.log('%s C, %s %%RH', t.toFixed(2), rh.toFixed(1));
    console.log('temperature', (await sensor.readTemperature()).toFixed(2));   // () → number °C, single mode
    console.log('humidity', (await sensor.readHumidity()).toFixed(1));          // () → number %RH, single mode
    console.log('raw', await sensor.readRaw());                          // Read raw words, () → [number, number]
    await sensor.setResolution(11, 8);                                   // Set resolution, (temperatureBits, humidityBits) → void
    console.log('conversion time %s ms', (sensor.conversionTime() * 1000).toFixed(2));   // Typical conversion time, () → number s
    await sensor.setResolution(14, 14);
    await sensor.setHeater(true);                                        // Switch the heater, (on) → void
    console.log('heater on:', await sensor.isHeaterOn());                // Heater state, () → boolean
    await sensor.setHeater(false);
    console.log('battery low:', await sensor.batteryLow());              // VDD below 2.8 V, () → boolean
    await sensor.softwareReset();                                        // Software reset, () → void

    await sensor.onInterrupt((result) => console.log('data ready', result));   // Subscribe to data ready, (callback) → void
    await connection.write(Buffer.from([0x00]));                         // trigger one measurement
    await sleep(100);
    console.log('ready:', await sensor.pollInterrupt());                 // Data ready check, () → boolean
    await sensor.offInterrupt();                                         // Unsubscribe, () → void
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
