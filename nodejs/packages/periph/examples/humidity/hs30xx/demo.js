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
    // --- Optional: show and change the stored resolution (run with --resolution) ---
    // The NVM is only reachable within 10 ms after power-up, so this needs powerCycle().
    if (process.argv.includes('--resolution')) {
        console.log('resolution', await sensor.getResolution(powerCycle));        // Get NVM resolution, (powerCycle) → Promise<{humidity_bits, temperature_bits}>
        await sensor.setResolution(12, 12, powerCycle);                           // Set NVM resolution, (humidityBits, temperatureBits, powerCycle) → Promise<void>
        sensor.setMeasurementWait(15);                                            // 12-bit RH + T need about 10 ms, (ms) → void
        console.log('resolution', await sensor.getResolution(powerCycle));
    }

    // --- Hygrometer: one reading every 2 s for 30 s ---
    // Raw values and status come from the 14-bit data-fetch frame; status 0 means fresh data.
    console.log('t(s)  RH raw  T raw  status  T (C)   RH (%)  Dew (C)');
    for (let n = 0; n < 15; n++) {
        const raw = await sensor.readRaw();                              // Read raw values, () → Promise<{raw_humidity, raw_temperature, status}>
        const d = await sensor.read();                                   // Read temperature and humidity, () → Promise<{temperature_c, humidity_pct}>
        const dew = await sensor.dewPoint();                             // Dew point, () → Promise<number °C>
        console.log([n * 2, raw.raw_humidity, raw.raw_temperature, raw.status,
                     d.temperature_c.toFixed(2), d.humidity_pct.toFixed(1), dew.toFixed(2)].join('  '));
        await sleep(2000);
    }
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
