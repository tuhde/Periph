'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { HS30xxFull } = require('../../../src/chips/humidity/hs30xx');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x44', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x44) → Connection
const sensor = new HS30xxFull(connection);                               // Create HS30xx driver, (connection) → void

// Replace with code that cuts and restores the sensor supply (MOSFET or GPIO).
const powerCycle = async () => { await sleep(100); };

async function main() {
    const d = await sensor.read();                                       // Read temperature and humidity, () → Promise<{temperature_c, humidity_pct}>
                                                                         // request, 40 ms wait, 4-byte fetch; one retry on stale data
    console.log(d.temperature_c, d.humidity_pct);
    console.log(await sensor.readTemperature());                         // Read temperature only, () → Promise<number °C>
    console.log(await sensor.readHumidity());                            // Read humidity only, () → Promise<number %RH>
                                                                         // 2-byte fetch
    console.log(await sensor.readRaw());                                 // Read raw values, () → Promise<{raw_humidity, raw_temperature, status}>
                                                                         // 14-bit values; status 0 = valid, 1 = stale
    sensor.setMeasurementWait(60);                                       // Override the post-request wait, (ms) → void
                                                                         // default 40 ms; lower resolutions need less
    console.log(await sensor.dewPoint());                                // Compute dew point, () → Promise<number °C>
                                                                         // one measurement, Magnus formula

    // NVM access only works within 10 ms after power-up, so it needs the powerCycle helper above.
    console.log(await sensor.getResolution(powerCycle));                 // Get NVM resolution, (powerCycle) → Promise<{humidity_bits, temperature_bits}>
    await sensor.setResolution(12, 12, powerCycle);                      // Set NVM resolution, (humidityBits, temperatureBits, powerCycle) → Promise<void>
                                                                         // 8, 10, 12 or 14 bit; null leaves a channel unchanged
    console.log((await sensor.readId(powerCycle)).toString(16));         // Read the 32-bit sensor ID, (powerCycle) → Promise<number>
    await sensor.enterProgrammingMode();                                 // Enter programming mode, () → Promise<void>
                                                                         // only within 10 ms after power-up
    console.log((await sensor.readNvm(0x06)).toString(16));              // Read an NVM register, (reg) → Promise<number>
                                                                         // 0x06 humidity / 0x11 temperature resolution, 0x1E / 0x1F ID
    await sensor.exitProgrammingMode();                                  // Leave programming mode, () → Promise<void>
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
