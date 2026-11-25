'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { Si7021Full } = require('../../../src/chips/humidity/si7021');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x40', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x40) → Connection
const sensor = new Si7021Full(connection);                                // Create Si7021 driver, (connection) → void

async function main() {
    const [t, rh] = await sensor.read();                                 // Read temperature and humidity, () → [number °C, number %RH]
                                                                         // RH without hold master, temperature of the same conversion
    console.log(t, rh);
    console.log(await sensor.readHumidity());                            // Read humidity only, () → number %RH
    console.log(await sensor.readTemperature());                         // Read temperature (new conversion), () → number °C
    console.log(await sensor.readTemperaturePrevious());                 // Temperature of the last RH measurement, () → number °C
                                                                         // command 0xE0, no new conversion
    console.log(await sensor.readRawHumidity(), await sensor.readRawTemperature()); // Read raw codes, () → number ; () → number
                                                                         // 16-bit codes, status bits included

    await sensor.setResolution(11, 11);                                  // Set resolution, (rhBits, temperatureBits) → void
                                                                         // (12, 14) / (8, 12) / (10, 13) / (11, 11)
    console.log(await sensor.getResolution());                           // Get resolution, () → [number, number]
    console.log(await sensor.conversionTime());                          // Typical conversion time, () → number s
    await sensor.setResolution(12, 14);

    await sensor.setHeater(true);                                        // Switch the heater, (on) → void
                                                                         // about 3.1 mA; readings drift while on
    console.log(await sensor.isHeaterOn());                              // Heater state, () → boolean
    await sensor.setHeater(false);
    console.log(await sensor.vddLow());                                  // Supply low flag, () → boolean
                                                                         // true when VDD is below about 1.9 V
    const reg = await sensor.readUserRegister();                         // Read USER_REG_1, () → number
                                                                         // 0x3A after reset
    await sensor.writeUserRegister(reg);                                 // Write USER_REG_1, (value) → void
                                                                         // reserved bits are preserved
    sensor.setClockStretching(false);                                    // Use hold-master commands, (on) → void
                                                                         // needs bus-layer support; off polls the NACKed read header

    console.log((await sensor.serialNumber()).toString(16));             // Electronic serial number, () → bigint
    console.log((await sensor.deviceType()).toString(16));               // Device type, () → number
                                                                         // 0x15 = Si7021
    console.log((await sensor.firmwareRevision()).toString(16));         // Firmware revision, () → number
                                                                         // 0xFF = firmware 1.0
    console.log(await sensor.dewPoint());                                // Compute dew point, () → number °C
                                                                         // one measurement, Magnus formula
    await sensor.softReset();                                            // Software reset, () → void
                                                                         // 0xFE, waits 15 ms
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
