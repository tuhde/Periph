'use strict';
const { I2CTransport } = require('../../../packages/periph/src/transport/i2c');
const { ENS160Full } = require('../../../packages/periph/src/chips/gas/ens160');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x52', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const sensor = new ENS160Full(transport);                // Create ENS160 driver, (transport)

const fw = sensor.getFirmwareVersion();                  // Get firmware version, () → object {major, minor, release}
                                                          // switches to IDLE, issues GET_APPVER, returns to STANDARD
console.log(`Firmware: ${fw.major}.${fw.minor}.${fw.release}`);

sensor.setCompensation(25.0, 50.0);                      // Set compensation, (tempCelsius, rhPercent) → undefined
                                                          // improves accuracy with external T/RH readings

sensor.configureInterrupt(true, false, false, true, false);  // Configure interrupt, (enabled, activeHigh, pushPull, onData, onGpr) → undefined
                                                          // sets INTn pin behavior for new data notification

console.log('Waiting for warm-up...');
while (true) {                                           // Wait for valid data, () → blocks until warm
    try { sensor.readAirQuality(); break; } catch (e) { _delay(1000); }
}

const tvoc = sensor.readTvoc();                          // Read TVOC, () → number ppb
const eco2 = sensor.readEco2();                          // Read eCO2, () → number ppm
const aqi = sensor.readAqi();                            // Read AQI, () → number 1–5
const ethanol = sensor.readEthanol();                    // Read ethanol, () → number ppb
                                                          // alias of DATA_TVOC at 0x22
const r1 = sensor.readRawResistance(1);                  // Read raw resistance, (sensor=1 or 4) → number Ohms
const r4 = sensor.readRawResistance(4);                  // Read raw resistance, (sensor=1 or 4) → number Ohms
const actuals = sensor.readCompensationActuals();        // Read compensation actuals, () → object {tempCelsius, rhPercent}
                                                          // returns T/RH values used by sensor

console.log(`TVOC=${tvoc} ppb, eCO2=${eco2} ppm, AQI=${aqi}`);
console.log(`Ethanol=${ethanol} ppb, R1=${r1.toFixed(0)} Ohm, R4=${r4.toFixed(0)} Ohm`);
console.log(`Actual T=${actuals.tempCelsius.toFixed(1)} C, RH=${actuals.rhPercent.toFixed(1)} %`);

sensor.sleep();                                          // Enter deep sleep, () → undefined
                                                          // reduces current to ~10 uA
_delay(1000);
sensor.wake();                                           // Wake and resume sensing, () → undefined
                                                          // transitions IDLE then STANDARD

transport.close();
console.log('===DONE: 0 passed, 0 failed===');

function _delay(ms) {
    const start = Date.now();
    while (Date.now() - start < ms) { /* spin */ }
}
