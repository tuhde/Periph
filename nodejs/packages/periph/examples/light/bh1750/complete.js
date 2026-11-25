'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { BH1750Full } = require('../../../src/chips/light/bh1750');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x23', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x23) → Connection
const sensor = new BH1750Full(connection);                               // Create BH1750 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Power on, () → void
                                                                         // opcode 0x01; the chip powers down by itself after each one-time measurement

    console.log(await sensor.read());                                    // Measure illuminance, () → number lx
                                                                         // one-time H-resolution (1 lx), waits 180 ms
    console.log(await sensor.readRaw());                                 // Read the raw count, () → number
                                                                         // 16-bit, one one-time measurement in the current mode
    console.log(await sensor.measureOnce(BH1750Full.MODE_H2));           // One-time measurement, (mode MODE_H|MODE_H2|MODE_L) → number lx
                                                                         // MODE_H2 = 0.5 lx steps, MODE_L = 4 lx steps and 24 ms
    console.log(sensor.measurementTime());                               // Maximum measurement time, (mode=MODE_H) → number s
    console.log(sensor.luxPerCount());                                   // Lux per raw count, (mode=MODE_H) → number

    await sensor.startContinuous(BH1750Full.MODE_H);                     // Start continuous mode, (mode=MODE_H) → void
                                                                         // first result after measurementTime()
    await sleep(200);
    console.log(await sensor.readContinuous());                          // Read the latest result, () → number lx
                                                                         // no wait

    await sensor.setMeasurementTime(138);                                // Set MTreg, (mtreg 31-254) → void
                                                                         // default 69; larger = more sensitive and slower
    console.log(sensor.getMeasurementTimeRegister());                    // Get MTreg, () → number
                                                                         // cached, the chip has no read-back
    await sensor.setSensitivity(1.0);                                    // Set sensitivity, (factor 0.45-3.68) → void
                                                                         // MTreg = round(69 x factor)

    await sensor.powerDown();                                            // Power down, () → void
    await sensor.powerOn();                                              // Power on, () → void
    await sensor.reset();                                                // Clear the data register, () → void
                                                                         // powers on first; MTreg and mode are kept
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
