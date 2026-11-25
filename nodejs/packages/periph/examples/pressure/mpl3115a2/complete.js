'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { MPL3115A2Full } = require('../../../src/chips/pressure/mpl3115a2');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x60', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x60) → Connection
const sensor = new MPL3115A2Full(connection);                                       // Create MPL3115A2 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Reset and apply defaults, () → void
    console.log('who_am_i 0x%s', (await sensor.whoAmI()).toString(16)); // Read WHO_AM_I, () → number (0xC4)
    await sensor.setOversample(32);                                      // Oversample ratio, (ratio 1/2/4/…/128) → void
    console.log('oversample', sensor.getOversample());                   // Current ratio, () → number
    await sensor.setSeaLevelPressure(101325);                            // Sea-level reference, (pa) → void
    await sensor.setOffsets(0, 0, 0);                                    // Offset trims, (pressurePa, temperatureC, altitudeM) → void
    console.log(await sensor.readPressure(), await sensor.readTemperature()); // One-shot values, () → number Pa / number °C
    console.log(await sensor.read());                                    // Both together, () → {pressure_pa, temperature_c}
    console.log(await sensor.readAltitude());                            // Altimeter reading, () → number m
    await sensor.setMode(false);                                         // Back to barometer, (altimeter) → void
    await sensor.startContinuous(0);                                     // Periodic acquisition, (stepExp 0-15) → void
    console.log('active', await sensor.isActive());                      // Check ACTIVE mode, () → boolean
    console.log(await sensor.readContinuous());                          // Wait for PTDR and read, () → {value, temperature_c}
    console.log(await sensor.dataReady(), await sensor.readDelta());     // Data ready / change since previous, () → boolean / [number, number]
    await sensor.stopContinuous();                                       // Back to STANDBY, () → void
    await sensor.setTarget(100000, 200, -5, 3);                          // Threshold targets, (value, window, temperatureC, temperatureWindow) → void
    await sensor.loadCurrentAsTarget();                                  // Current output as target, () → void
    console.log(await sensor.readMinMax());                              // Captured extremes, () → {p_min, p_max, t_min, t_max}
    await sensor.clearMinMax();                                          // Clear extremes, () → void
    await sensor.fifoSetup(MPL3115A2Full.FIFO_CIRCULAR, 16);             // Configure the FIFO, (mode 0/1/2, watermark 0-63) → void
    console.log(await sensor.fifoStatus());                              // FIFO state, () → [overflow, watermark, count]
    console.log(await sensor.fifoRead());                                // Drain the FIFO, () → [[value, temperature_c], …]
    await sensor.fifoSetup(MPL3115A2Full.FIFO_OFF);                      // FIFO off, (mode, watermark = 0) → void
    await sensor.setInterruptPins(false, false, false, false);           // INT polarity / drive, (polarity1, openDrain1, polarity2, openDrain2) → void
    await sensor.enableInterrupt(MPL3115A2Full.SOURCE_DRDY, 1);          // Route a source to INT1, (source, pin 1/2) → void
    console.log('INT_SOURCE 0x%s', (await sensor.pollInterrupt()).toString(16)); // Read INT_SOURCE, () → number
    await sensor.onInterrupt((status) => console.log('INT 0x%s', status.toString(16)), { pin: 1 }); // Subscribe, (callback, {pin, intPin}) → void
    await sleep(1000);
    await sensor.offInterrupt();                                         // Unsubscribe, () → void
    await sensor.disableInterrupt(MPL3115A2Full.SOURCE_DRDY);            // Disable the source, (source) → void
    await sensor.reset();                                                // Software reset and defaults, () → void
    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
}

main().catch(err => { console.error(err); process.exit(1); });
