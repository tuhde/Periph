'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { HTS221Full } = require('../../../src/chips/humidity/hts221');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x5F', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x5F) → Connection
const sensor = new HTS221Full(connection);                            // Create HTS221 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Read calibration and start one-shot mode, () → void

    // --- Identify the chip and show the factory calibration ---
    // Every HTS221 stores its own two-point calibration; the driver read it
    // during init() and uses it for every conversion.
    console.log('who am i 0x' + (await sensor.whoAmI()).toString(16)); // Read WHO_AM_I, () → number (0xBC)
    console.log('calibration', await sensor.readCalibration());          // Read calibration, () → object

    // --- One-shot hygrometer: 10 readings, 2 s apart ---
    // Each read() starts a conversion; the dew point shows what the pair
    // means for condensation risk.
    for (let i = 0; i < 10; i++) {
        const m = await sensor.read();                                   // Read temperature and humidity, () → {temperature_c °C, humidity_pct %rH}
        const dew = await sensor.dewPoint();                             // Compute dew point, () → number °C
        console.log(m.temperature_c.toFixed(2) + ' C  ' + m.humidity_pct.toFixed(1) + ' %rH  dew ' + dew.toFixed(2) + ' C');
        console.log('raw', await sensor.readRawHumidity(), await sensor.readRawTemperature()); // Read raw outputs, () → number
        console.log('h', (await sensor.readHumidity()).toFixed(1), 't', (await sensor.readTemperature()).toFixed(2)); // Read single values, () → number
        await sleep(2000);
    }

    // --- Continuous sampling at 1 Hz and 12.5 Hz ---
    // dataReady() paces the loop; the sample count per second follows the rate.
    await sensor.setBlockDataUpdate(true);                               // Set block data update, (enabled) → void
    for (const hz of [1, 12.5]) {
        await sensor.setOutputDataRate(hz);                              // Set output data rate, (hz 0|1|7|12.5) → void
        console.log('rate', await sensor.getOutputDataRate(), 'Hz');     // Get output data rate, () → number Hz
        let count = 0;
        const end = Date.now() + 3000;
        while (Date.now() < end) {
            if (await sensor.dataReady()) {                              // Check data available, () → boolean
                await sensor.readContinuous();                           // Read latest sample, () → object
                count++;
            }
            await sleep(5);
        }
        console.log((count / 3).toFixed(1) + ' samples/s');
    }
    await sensor.setOutputDataRate(0);                                   // Set output data rate, (hz 0|1|7|12.5) → void

    // --- Noise at lowest and highest averaging ---
    // Ten one-shot samples each; the spread of the raw humidity shrinks as
    // the averaging grows.
    for (const [t, h] of [[2, 4], [256, 512]]) {
        await sensor.setAveraging(t, h);                                 // Set averaging, (temperatureSamples, humiditySamples) → void
        console.log('averaging', await sensor.getAveraging());           // Get averaging, () → [number, number]
        const raws = [];
        for (let i = 0; i < 10; i++) raws.push(await sensor.readRawHumidity()); // Read raw humidity, () → number
        console.log('raw humidity spread', Math.max(...raws) - Math.min(...raws));
    }
    await sensor.setAveraging(16, 32);                                   // Set averaging, (temperatureSamples, humiditySamples) → void

    // --- Heater: drive off condensation for 10 s ---
    // Humidity falls and temperature rises while the heater runs; measure
    // before and after, never during, then let the sensor cool.
    const before = await sensor.read();                                  // Read temperature and humidity, () → object
    await sensor.setHeater(true);                                        // Switch the heater, (on) → void
    console.log('heater on:', await sensor.isHeaterOn());                // Heater state, () → boolean
    await sleep(10000);
    await sensor.setHeater(false);                                       // Switch the heater, (on) → void
    const after = await sensor.read();                                   // Read temperature and humidity, () → object
    console.log('before', before, 'after', after);

    // --- Data-ready notification for 5 s ---
    // Uses the DRDY pin when the connection has one, otherwise a polling timer.
    await sensor.configureDrdy(true, true, false);                       // Configure DRDY pin, (enabled, activeHigh=true, openDrain=false) → void
    await sensor.setOutputDataRate(1);                                   // Set output data rate, (hz 0|1|7|12.5) → void
    await sensor.onInterrupt((status) => console.log('DRDY status', status)); // Subscribe to data-ready, (callback, intPin=null) → void
    await sleep(5000);
    await sensor.offInterrupt();                                         // Unsubscribe, () → void
    console.log('poll', await sensor.pollInterrupt(), 'status', await sensor.status()); // Poll interrupt status, () → number
    await sensor.setOutputDataRate(0);

    // --- Power cycle and reload ---
    await sensor.powerDown();                                            // Power down, () → void
    await sensor.powerUp();                                              // Power up, () → void
    await sensor.reboot();                                               // Reload trimming, () → void
    console.log('done:', (await sensor.read()).temperature_c.toFixed(2), 'C');
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
