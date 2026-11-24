'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { Si1132Full } = require('../../../src/chips/light/si1132');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x60', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                  // Create I²C connection, (bus=1, addr=0x60) → Connection
const sensor = new Si1132Full(connection);                                 // Create Si1132 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Verify PART_ID and start forced mode, () → void
                                                                         // writes HW_KEY, UV coefficients and CHLIST
    console.log(await sensor.read());                                    // Read UV index and ALS counts, () → {uv_index, visible, infrared}
                                                                         // forced measurement of the enabled channels
    console.log('uv index', await sensor.readUvIndex());                 // Read UV index, () → number
                                                                         // forced measurement, AUX_DATA / 100
    console.log('visible', await sensor.readVisible());                  // Read visible counts, () → number
                                                                         // forced measurement, 16-bit raw count
    console.log('infrared', await sensor.readInfrared());                // Read infrared counts, () → number
                                                                         // forced measurement, 16-bit raw count
    console.log('raw', await sensor.readRaw());                          // Read data registers, () → {visible, infrared, aux}
                                                                         // latest values, no measurement is started
    console.log('temperature raw', await sensor.readTemperatureRaw());   // Read die temperature, () → number
                                                                         // auxiliary channel, relative only
    console.log('vdd raw', await sensor.readVddRaw());                   // Read supply voltage, () → number
                                                                         // auxiliary channel with AUX_ADCMUX = 0x75

    await sensor.setChannels(true, true, true);                          // Select channels, (uv, visible, infrared, aux=false) → void
                                                                         // writes CHLIST; uv and aux cannot be combined
    await sensor.setGain(2, 2);                                          // Set ADC gain, (visible 0–7, infrared 0–7) → void
                                                                         // integration time x 2^n plus the recommended recovery count
    await sensor.setGain(0, 0);
    await sensor.setHighRange(false, false);                             // Set high signal range, (visible, infrared) → void
                                                                         // true divides the sensitivity by 14.5 (direct sunlight)
    await sensor.setAlignment(true, true);                               // Set data alignment, (visibleLow, infraredLow) → void
                                                                         // true reports the least significant 16 bits
    await sensor.setUvCoefficients([0x7B, 0x6B, 0x01, 0x00]);            // Set UV coefficients, (ucoef 4 bytes) → void
                                                                         // datasheet defaults for an open-air UV index
    console.log('calibration', await sensor.readCalibration());          // Read factory calibration, () → number[12]
                                                                         // GET_CAL then registers 0x22–0x2D

    await sensor.force();                                                // Start a forced measurement, () → void
                                                                         // ALS_FORCE without waiting
    await sleep(50);
    console.log('ready', await sensor.measurementReady());               // Check measurement done, () → boolean
                                                                         // IRQ_STATUS.ALS_INT
    console.log('response', await sensor.readResponse());                // Read command response, () → {counter, error}
                                                                         // RESPONSE decoded, error 0 = none
    console.log('poll', await sensor.pollInterrupt());                   // Poll interrupt status, () → number
                                                                         // reads IRQ_STATUS and clears it

    await sensor.startAutonomous(0.5);                                   // Start autonomous mode, (interval 0–2.05 s) → void
                                                                         // MEAS_RATE in 31.25 µs steps then ALS_AUTO
    await sensor.onInterrupt((status) => console.log('INT status 0x' + status.toString(16)));  // Subscribe to measurements, (callback, intPin=null) → void
                                                                         // falling edge on INT, else polling
    await sleep(2000);
    await sensor.offInterrupt();                                         // Unsubscribe, () → void
    await sensor.pause();                                                // Pause autonomous mode, () → void
                                                                         // ALS_PAUSE
    await sensor.setI2cAddress(0x60);                                    // Set I²C address, (address 7-bit) → void
                                                                         // I2C_ADDR parameter then BUSADDR, volatile
    await sensor.reset();                                                // Reset the chip, () → void
                                                                         // RESET, waits, writes HW_KEY again
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
