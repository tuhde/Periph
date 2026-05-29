'use strict';
const { I2CTransport } = require('../../../packages/periph/src/transport/i2c');
const { ENS160Minimal } = require('../../../packages/periph/src/chips/gas/ens160');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x52', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const sensor = new ENS160Minimal(transport);             // Create ENS160 driver, (transport)

console.log('Waiting for sensor warm-up...');
while (true) {                                           // Wait for valid data, () → blocks until warm
    try { sensor.readAirQuality(); break; } catch (e) { _delay(1000); }
}

for (let i = 0; i < 10; i++) {
    const data = sensor.readAirQuality();                // Read air quality, () → object {aqi, tvocPpb, eco2Ppm}
    console.log(`AQI=${data.aqi} TVOC=${data.tvocPpb} ppb eCO2=${data.eco2Ppm} ppm`);
    _delay(1000);
}
transport.close();
console.log('===DONE: 0 passed, 0 failed===');

function _delay(ms) {
    const start = Date.now();
    while (Date.now() - start < ms) { /* spin */ }
}
