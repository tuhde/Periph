'use strict';
const { I2CTransport } = require('../../../src/transport/i2c');
const { BME680Full } = require('../../../src/chips/environmental/bme680');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x76', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const bme = new BME680Full(transport);                   // Create BME680 driver, (transport)
const cid = bme.chipId();                              // Read chip ID, () → number
                                                        // returns 0x61 for BME680
console.log('chip_id=' + cid.toString(16));
bme.configure(BME680Full.OSRS_X1, BME680Full.OSRS_X1, BME680Full.OSRS_X1, BME680Full.MODE_SLEEP, BME680Full.FILTER_0);  // Configure chip, (osrsT 0–5, osrsP 0–5, osrsH 0–5, mode 0/1, filter 0–7) → undefined
                                                        // writes ctrl_hum, config, ctrl_meas in correct order
bme.setOversampling(BME680Full.OSRS_X4, BME680Full.OSRS_X2, BME680Full.OSRS_X1);  // Set oversampling, (osrsT 0–5, osrsP 0–5, osrsH 0–5) → undefined
                                                        // changes conversion time vs resolution trade-off
bme.setFilter(BME680Full.FILTER_7);                     // Set IIR filter, (coeff 0–7) → undefined
                                                        // applies to temperature and pressure only
bme.setHeater(320, 150);                                // Configure heater profile 0, (tempC, durationMs) → undefined
                                                        // sets target temperature and on-time for gas measurement
bme.setHeaterProfile(1, 200, 100);                      // Configure heater profile 1, (index 0–9, tempC, durationMs) → undefined
bme.selectHeaterProfile(0);                            // Select active profile, (index 0–9) → undefined
bme.setGasEnabled(true);                                // Enable gas conversion, (enabled) → undefined
bme.setHeaterOff(false);                                // Control heater override, (off) → undefined
bme.setAmbientTemperature(25.0);                        // Override ambient for heater calc, (tempC) → undefined
                                                        // re-applies the active heater profile
const st = bme.status();                               // Read status register, () → number
                                                        // bit 7 = new_data, bit 6 = gas_measuring, bit 5 = measuring
const r = bme.readAll();                                // Read all sensors in one cycle, () → object
                                                        // returns {temperature, pressure, humidity, gasResistance}
const gv = bme.gasValid();                             // Check gas validity, () → boolean
const hs = bme.heaterStable();                         // Check heater stability, () → boolean
bme.reset();                                           // Soft reset chip, () → undefined
                                                        // re-reads calibration and re-applies configuration
console.log(`T=${r.temperature.toFixed(1)} C, P=${r.pressure.toFixed(1)} hPa, RH=${r.humidity.toFixed(1)} %, R_gas=${r.gasResistance.toFixed(0)} Ohm`);
transport.close();
console.log('===DONE: 0 passed, 0 failed===');
