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

    // --- Identify the chip and check its health ---
    // The serial number is unique per device; the diagnostic flags report NVM
    // and out-of-range errors.
    console.log('serial 0x' + (await sensor.serialNumber()).toString(16));    // Read the serial number, () → number
    console.log('diagnostic', await sensor.diagnosticFlags());                // Named diagnostic flags, () → object

    // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
    // The dew point shows how close the air is to condensing on a surface.
    for (let i = 0; i < 15; i++) {
        const m = await sensor.read();                                   // Read temperature and humidity, () → {temperature_c °C, humidity_pct %RH}
        const dew = await sensor.dewPoint();                             // Compute dew point, () → number °C
        console.log('%s C  %s %%RH  dew %s C', m.temperature_c.toFixed(2), m.humidity_pct.toFixed(1), dew.toFixed(2));
        console.log('h', (await sensor.readHumidity()).toFixed(1), 't', (await sensor.readTemperature()).toFixed(2), 'raw', await sensor.readRaw());  // Read single values and raw codes
        await sleep(2000);
    }

    // --- Compare the four oversampling settings ---
    // Higher OSR gives finer resolution at the cost of a longer conversion.
    for (let osr = 0; osr < 4; osr++) {
        sensor.setResolution(osr, osr);                                  // Set oversampling, (osrRh 0-3, osrT 0-3) → void
        const start = Date.now();
        const m = await sensor.read();                                   // Read temperature and humidity, () → {temperature_c, humidity_pct}
        console.log('OSR', sensor.getResolution(), 'typ', (sensor.conversionTime() * 1000).toFixed(2), 'ms', 'read', Date.now() - start, 'ms', m);  // Get oversampling and typical conversion time
    }
    sensor.setResolution(3, 3);

    // --- Heater: drive off condensation for 10 s ---
    // Humidity falls and temperature rises while the heater runs; measure
    // before and after, never during, then let the sensor cool.
    const before = await sensor.read();                                  // Read temperature and humidity, () → {temperature_c, humidity_pct}
    await sensor.setHeater(true);                                        // Switch the heater, (on) → void
    console.log('heater on:', await sensor.isHeaterOn(), (await sensor.diagnostic()).toString(16));  // Heater state and diagnostic byte, () → boolean / number
    await sleep(10000);
    await sensor.setHeater(false);                                       // Switch the heater, (on) → void
    const after = await sensor.read();                                   // Read temperature and humidity, () → {temperature_c, humidity_pct}
    console.log('before', before, 'after', after);

    // --- Non-blocking conversion ---
    // Start the conversion, do other work, then collect the result.
    await sensor.startConversion();                                      // Start a conversion, () → void
    await sleep(sensor.conversionTime() * 1250);
    console.log('result', await sensor.readResult());                    // Read last conversion result, () → {temperature_c, humidity_pct}

    // --- Reset ---
    await sensor.softReset();                                            // Soft reset, () → void
    console.log('done: %s C', (await sensor.read()).temperature_c.toFixed(2));
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
