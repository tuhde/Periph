'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { HTU31DFull } = require('../../../src/chips/humidity/htu31d');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x40', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x40) → Connection
const sensor = new HTU31DFull(connection);                            // Create HTU31D driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Soft reset, () → void
                                                                         // 0x1E, waits 15 ms; highest resolution, heater off
    const m = await sensor.read();                                       // Read temperature and humidity, () → {temperature_c: number °C, humidity_pct: number %RH}
                                                                         // OSR 3 / 3 by default, both CRCs verified, humidity clamped 0-100
    console.log(m);
    console.log(await sensor.readHumidity());                            // Read humidity only, () → number %RH
    console.log(await sensor.readTemperature());                         // Read temperature only, () → number °C
    console.log(await sensor.readRaw());                                 // Read raw codes, () → [t_raw, rh_raw]
                                                                         // 16-bit unsigned

    sensor.setResolution(1, 2);                                          // Set oversampling, (osrRh 0-3, osrT 0-3) → void
                                                                         // higher OSR = finer resolution, longer conversion
    console.log(sensor.getResolution());                                 // Get oversampling, () → [osrRh, osrT]
    console.log(sensor.conversionTime());                                // Typical conversion time, () → number s
    sensor.setResolution(3, 3);

    await sensor.startConversion();                                      // Start a conversion, () → void
                                                                         // non-blocking; wait conversionTime() before reading
    await sleep(30);
    console.log(await sensor.readResult());                              // Read last conversion result, () → {temperature_c, humidity_pct}

    await sensor.setHeater(true);                                        // Switch the heater, (on) → void
                                                                         // 0x04; readings drift while the heater runs
    console.log(await sensor.isHeaterOn());                              // Heater state, () → boolean
    await sensor.setHeater(false);
    console.log((await sensor.diagnostic()).toString(16));               // Read the diagnostic byte, () → number
    console.log(await sensor.diagnosticFlags());                         // Named diagnostic flags, () → object
    console.log((await sensor.serialNumber()).toString(16));             // Read the serial number, () → number
                                                                         // 24-bit, CRC verified
    console.log(await sensor.dewPoint());                                // Compute dew point, () → number °C
                                                                         // one measurement, Magnus formula
    await sensor.softReset();                                            // Soft reset, () → void
                                                                         // 0x1E, waits 15 ms; heater off, OSR 3 / 3
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
