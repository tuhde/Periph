'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { HDC1080Full } = require('../../../src/chips/humidity/hdc1080');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x40', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x40) → Connection
const sensor = new HDC1080Full(connection);                               // Create HDC1080 driver, (connection) → void

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
    console.log('manufacturer 0x' + (await sensor.manufacturerId()).toString(16));   // Manufacturer ID (0x5449), () → number
    console.log('device 0x' + (await sensor.deviceId()).toString(16));                 // Device ID (0x1050), () → number
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
