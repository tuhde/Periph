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

    // --- Identify the chip and show the factory calibration ---
    // The calibration bytes belong to the Silicon Labs Si114x calibration
    // structure; they let you correct part-to-part variation of the UV index.
    console.log('calibration', await sensor.readCalibration());          // Read factory calibration, () → number[12]

    // --- UV and light monitor: 20 readings, 1 s apart ---
    // Index, visible and infrared counts side by side show whether the
    // light is sunlight (high infrared share) or artificial.
    for (let i = 0; i < 20; i++) {
        const m = await sensor.read();                                   // Read UV index and ALS counts, () → {uv_index, visible, infrared}
        console.log('UV %s  vis %d  ir %d', m.uv_index.toFixed(2), m.visible, m.infrared);
        console.log('raw', await sensor.readRaw());                      // Read data registers, () → {visible, infrared, aux}
        await sleep(1000);
    }

    // --- Effect of gain and of the high-range bits on the same light ---
    // Each gain step doubles the integration time (and the count); the high
    // range divides the sensitivity by 14.5 for direct sunlight.
    for (const gain of [0, 2, 4]) {
        await sensor.setGain(gain, gain);                                // Set ADC gain, (visible 0–7, infrared 0–7) → void
        console.log('gain', gain, await sensor.read());                  // Read UV index and ALS counts, () → object
    }
    await sensor.setGain(0, 0);                                          // Set ADC gain, (visible 0–7, infrared 0–7) → void
    await sensor.setHighRange(true, true);                               // Set high signal range, (visible, infrared) → void
    console.log('high range', await sensor.read());                      // Read UV index and ALS counts, () → object
    await sensor.setHighRange(false, false);                             // Set high signal range, (visible, infrared) → void
    await sensor.setAlignment(false, false);                             // Set data alignment, (visibleLow, infraredLow) → void
    await sensor.setUvCoefficients([0x7B, 0x6B, 0x01, 0x00]);            // Set UV coefficients, (ucoef 4 bytes) → void

    // --- Auxiliary channels ---
    // The temperature value is relative only; the VDD count tracks the supply.
    console.log('temperature raw', await sensor.readTemperatureRaw());   // Read die temperature, () → number
    console.log('vdd raw', await sensor.readVddRaw());                   // Read supply voltage, () → number

    // --- Autonomous mode at 0.5 s with the interrupt ---
    // Every completed measurement raises INT; the callback prints the status.
    await sensor.startAutonomous(0.5);                                   // Start autonomous mode, (interval 0–2.05 s) → void
    await sensor.onInterrupt((status) => console.log('INT 0x' + status.toString(16)));  // Subscribe to measurements, (callback, intPin=null) → void
    await sleep(5000);
    await sensor.offInterrupt();                                         // Unsubscribe, () → void
    await sensor.pause();                                                // Pause autonomous mode, () → void
    console.log('ready', await sensor.measurementReady(), 'response', await sensor.readResponse());  // Check measurement done / read RESPONSE, () → boolean / object
    await sensor.enableInterrupt();                                      // Enable the INT pin, () → void
    await sensor.force();                                                // Start a forced measurement, () → void
    await sleep(50);
    console.log('poll', await sensor.pollInterrupt());                   // Poll interrupt status, () → number
    await sensor.disableInterrupt();                                     // Disable the INT pin, () → void

    // --- UV only, then shut down ---
    // Dropping the ALS channels shortens each measurement.
    await sensor.setChannels(true, false, false);                        // Select channels, (uv, visible, infrared, aux=false) → void
    console.log('UV only', (await sensor.readUvIndex()).toFixed(2));     // Read UV index, () → number
    await sensor.setI2cAddress(0x60);                                    // Set I²C address, (address 7-bit) → void
    await sensor.reset();                                                // Reset the chip, () → void
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
