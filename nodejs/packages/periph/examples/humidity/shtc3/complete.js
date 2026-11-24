'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { SHTC3Full } = require('../../../src/chips/humidity/shtc3');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x70', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x70) → Connection
const sensor = new SHTC3Full(connection);                            // Create SHTC3 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Soft reset and ID check, () → void

    console.log(await sensor.read());                                    // Read temperature and humidity, () → {temperature_c: number °C, humidity_pct: number %RH}
                                                                         // normal mode, both CRCs verified, humidity clamped 0-100
    console.log(await sensor.readHumidity());                            // Read humidity only, () → number %RH
    console.log(await sensor.readTemperature());                         // Read temperature only, () → number °C
    console.log(await sensor.readRaw());                                 // Read raw codes, () → [number, number]
                                                                         // [raw_temperature, raw_humidity], 16-bit
    console.log((await sensor.readId()).toString(16));                   // Read the ID word, () → number
                                                                         // (id & 0x083F) === 0x0807 for the SHTC3

    sensor.setLowPower(true);                                            // Select low-power mode, (on) → void
                                                                         // 0.7 ms measurement, slightly noisier
    console.log(await sensor.read());
    sensor.setLowPower(false);

    sensor.setClockStretching(true);                                     // Select clock stretching, (on) → void
                                                                         // needs a controller that tolerates SCL held low
    console.log(await sensor.read());
    sensor.setClockStretching(false);

    sensor.setSleepBetween(false);                                       // Sleep after every read?, (on) → void
                                                                         // off keeps the sensor idle (higher supply current)
    console.log(await sensor.read());
    sensor.setSleepBetween(true);
    await sensor.wake();                                                 // Wake-up command, () → void
    await sensor.sleep();                                                // Sleep command, () → void
    console.log(await sensor.dewPoint());                                // Compute dew point, () → number °C
                                                                         // one measurement, Magnus formula
    await sensor.softReset();                                            // Soft reset, () → void
                                                                         // 0x805D, leaves the sensor idle
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
