'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { MS5611Full } = require('../../../src/chips/pressure/ms5611');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x77', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x77) → Connection
const sensor = new MS5611Full(connection);                                      // Create MS5611 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Reset, read PROM, verify CRC-4, () → void

    console.log(await sensor.read());                                    // Read temperature and pressure, () → {temperature_c: number °C, pressure_pa: number Pa}
                                                                         // OSR 4096, second-order compensation applied
    console.log(await sensor.readPressure());                            // Read pressure only, () → number Pa
    console.log(await sensor.readTemperature());                         // Read temperature only, () → number °C
                                                                         // converts D2 only
    const [d1, d2] = await sensor.readRaw();                             // Read raw ADC codes, () → [number, number]
                                                                         // [d1 pressure, d2 temperature], 24-bit
    console.log(d1, d2);
    console.log(sensor.compensate(d1, d2));                              // Compensate raw codes, (d1, d2, secondOrder=true) → {temperature_c, pressure_pa}
                                                                         // pure computation, no bus access
    console.log(sensor.compensate(d1, d2, false));
    sensor.setOversampling(1024);                                        // Select oversampling ratio, (osr) → void
                                                                         // 256, 512, 1024, 2048 or 4096
    console.log(sensor.getOversampling());                               // Current oversampling ratio, () → number
    console.log(sensor.conversionTime());                                // Max conversion time, (osr?) → number s
    await sensor.startConversion(MS5611Full.TEMPERATURE);               // Start a conversion, (kind, osr?) → void
                                                                         // PRESSURE = D1, TEMPERATURE = D2; non-blocking
    await sleep(sensor.conversionTime() * 1000 + 1);
    console.log(await sensor.readAdc());                                 // Read the ADC result, () → number
                                                                         // only after the conversion time, 24-bit
    sensor.setOversampling(4096);
    console.log(await sensor.readProm());                                // Read the PROM words, () → number[8]
    console.log(sensor.coefficients());                                  // Calibration coefficients, () → [c1 .. c6]
    console.log(sensor.promCrcOk());                                     // Verify the PROM CRC-4, () → boolean
    console.log(sensor.serialCode());                                    // Serial code, () → number
                                                                         // PROM word 7 bits 15:4
    console.log(await sensor.altitude());                                // Altitude from pressure, (seaLevelPa=101325) → number m
                                                                         // international barometric formula
    await sensor.reset();                                                // Reset and reload PROM, () → void
                                                                         // 0x1E, 3 ms wait
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
