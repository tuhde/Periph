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
                                                                         // decodes the per-device two-point calibration
    console.log('who am i 0x' + (await sensor.whoAmI()).toString(16)); // Read WHO_AM_I, () → number (0xBC)
                                                                         // identifies the chip on the bus
    const m = await sensor.read();                                       // Read temperature and humidity, () → {temperature_c °C, humidity_pct %rH}
                                                                         // one-shot conversion, converted with the calibration
    console.log(m.temperature_c.toFixed(2) + ' C', m.humidity_pct.toFixed(1) + ' %rH');
    console.log('humidity', (await sensor.readHumidity()).toFixed(1));  // Read humidity only, () → number %rH
                                                                         // one-shot conversion, clamped to 0–100
    console.log('temperature', (await sensor.readTemperature()).toFixed(2)); // Read temperature only, () → number °C
                                                                         // one-shot conversion
    console.log('raw humidity', await sensor.readRawHumidity());         // Read raw humidity, () → number
                                                                         // signed 16-bit ADC output
    console.log('raw temperature', await sensor.readRawTemperature());   // Read raw temperature, () → number
                                                                         // signed 16-bit ADC output
    console.log('calibration', await sensor.readCalibration());          // Read calibration, () → object
                                                                         // decoded two-point coefficients
    console.log('dew point', (await sensor.dewPoint()).toFixed(2));     // Compute dew point, () → number °C
                                                                         // Magnus formula over one measurement
    console.log('status', await sensor.status());                        // Read STATUS_REG, () → number
                                                                         // H_DA = 0x02, T_DA = 0x01
    console.log('data ready', await sensor.dataReady());                 // Check data available, () → boolean
                                                                         // both H_DA and T_DA set

    await sensor.setOutputDataRate(1);                                   // Set output data rate, (hz 0|1|7|12.5) → void
                                                                         // continuous sampling at 1 Hz
    console.log('rate', await sensor.getOutputDataRate(), 'Hz');         // Get output data rate, () → number Hz
                                                                         // decodes CTRL_REG1.ODR
    await sleep(1200);
    const c = await sensor.readContinuous();                             // Read latest sample, () → {temperature_c °C, humidity_pct %rH}
                                                                         // no conversion is started
    console.log('continuous', c);
    await sensor.setOutputDataRate(0);                                   // Set output data rate, (hz 0|1|7|12.5) → void
                                                                         // back to one-shot mode

    await sensor.setAveraging(32, 64);                                   // Set averaging, (temperatureSamples 2–256, humiditySamples 4–512) → void
                                                                         // more samples lower the noise and lengthen the conversion
    console.log('averaging', await sensor.getAveraging());               // Get averaging, () → [number, number]
                                                                         // [temperature samples, humidity samples]
    await sensor.setAveraging(16, 32);
    await sensor.setBlockDataUpdate(true);                               // Set block data update, (enabled) → void
                                                                         // keeps LSB/MSB of one sample consistent

    await sensor.setHeater(true);                                        // Switch the heater, (on) → void
                                                                         // drives off condensation; do not measure while on
    console.log('heater on:', await sensor.isHeaterOn());                // Heater state, () → boolean
                                                                         // reads CTRL_REG2.HEATER
    await sensor.setHeater(false);

    await sensor.configureDrdy(true, true, false);                       // Configure DRDY pin, (enabled, activeHigh=true, openDrain=false) → void
                                                                         // routes data-ready to the DRDY pin
    console.log('poll', await sensor.pollInterrupt());                   // Poll interrupt status, () → number
                                                                         // STATUS_REG; flags clear when data is read
    await sensor.configureDrdy(false);

    await sensor.powerDown();                                            // Power down, () → void
                                                                         // clears CTRL_REG1.PD
    await sensor.powerUp();                                              // Power up, () → void
                                                                         // sets CTRL_REG1.PD
    await sensor.onInterrupt((status) => console.log('DRDY status', status)); // Subscribe to data-ready, (callback, intPin=null) → void
                                                                         // edge on the pin or a polling timer
    await sleep(2000);
    await sensor.offInterrupt();                                         // Unsubscribe, () → void
                                                                         // disables the DRDY output
    await sensor.reboot();                                               // Reload trimming, () → void
                                                                         // sets BOOT, waits, re-reads the calibration
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
