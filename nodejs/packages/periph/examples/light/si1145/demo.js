'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { Si1145Full } = require('../../../src/chips/light/si1145');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x60', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x60) → Connection
const sensor = new Si1145Full(connection);                               // Create Si1145 driver, (connection) → void

async function main() {
    await sensor.init();                                                 // Check PART_ID, HW_KEY, UV + ALS + PS1, () → void

    // --- Identity and calibration ---
    // PART_ID tells the Si1145/46/47 apart (one, two or three LEDs); GET_CAL returns the factory
    // calibration bytes.
    const id = await sensor.readId();                                    // Identity registers, () → {partId, revId, seqId}
    console.log('PART_ID 0x%s  REV_ID 0x%s  SEQ_ID 0x%s', id.partId.toString(16), id.revId.toString(16), id.seqId.toString(16));
    console.log('calibration', await sensor.readCalibration());          // GET_CAL, () → number[12]
    console.log('response', await sensor.readResponse());                // RESPONSE, () → {counter, error}

    // --- Proximity, UV index and light for 10 s ---
    // Proximity counts rise as a hand approaches the LED/sensor window.
    for (let i = 0; i < 10; i++) {
        const m = await sensor.read();                                   // Forced PS + ALS, () → {proximity, uv_index, visible, infrared}
        console.log('%d s  prox %d  UV %s  vis %d  ir %d', i, m.proximity, m.uv_index.toFixed(2), m.visible, m.infrared);
        await sleep(1000);
    }

    // --- LED current and photodiode ---
    // Hold a hand ~5 cm above the sensor: higher LED current gives more counts, the small
    // photodiode a fraction of the large one.
    console.log('Hold a hand above the sensor');
    await sleep(3000);
    for (const level of [1, 4, 8, 15]) {
        await sensor.setLedCurrent(1, level);                            // LED drive, (led, level) → void
        console.log('LED level %d -> %d', level, await sensor.readProximity()); // Forced PS, (channel=1) → number counts
    }
    await sensor.setLedCurrent(1, 3);
    for (const large of [false, true]) {
        await sensor.setPsPhotodiode(1, large);                          // PS photodiode, (channel, large) → void
        console.log('%s photodiode -> %d', large ? 'large' : 'small', await sensor.readProximity());
    }
    await sensor.setPsLed(1, 0x01);                                      // PS LED select, (channel, leds) → void

    // --- Gain and high range ---
    // Each gain step doubles the integration time; the high range divides the sensitivity by 14.5
    // for direct sunlight.
    for (const gain of [0, 2, 4]) {
        await sensor.setGain(gain, gain, 0);                             // ADC gains, (visible, infrared, proximity) → void
        console.log('gain %d  vis %d  ir %d', gain, await sensor.readVisible(), await sensor.readInfrared()); // Forced ALS, () → number counts
    }
    await sensor.setGain();
    await sensor.setHighRange(true, true, false);                        // Signal range, (visible, infrared, proximity) → void
    console.log('high range  vis %d  UV %s', await sensor.readVisible(), (await sensor.readUvIndex()).toFixed(2)); // Forced ALS, () → number
    await sensor.setHighRange(false, false, false);
    await sensor.setAlignment();                                         // Result alignment, ({...}) → void
    await sensor.setUvCoefficients(Si1145Full.UCOEF_DEFAULT);            // UCOEF0..3, (ucoef) → void

    // --- Autonomous mode with interrupt events and a proximity switch ---
    // PS + ALS every 100 ms; each finished PS1 measurement raises INT (or is polled).
    const THRESHOLD = 2000;
    await sensor.setChannels(true, true, true, true);                    // CHLIST, (uv, visible, infrared, ps1, ...) → void
    const events = [];
    await sensor.enableInterrupt(Si1145Full.SOURCE_PS1);                 // INT_CFG + IRQ_ENABLE, (source) → void
    await sensor.onInterrupt((s) => events.push(s));                     // Subscribe, (callback, intPin=null) → void
    await sensor.startAutonomous(0.1);                                   // Autonomous mode, (interval s, proximity, als) → void
    let near = false;
    for (let i = 0; i < 50; i++) {
        const ps1 = (await sensor.readRaw()).ps1;                        // Data registers, () → {...}
        if ((ps1 > THRESHOLD) !== near) {
            near = ps1 > THRESHOLD;
            console.log('output %s (prox %d)', near ? 'ON' : 'OFF', ps1);
        }
        await sleep(100);
    }
    await sensor.pause();                                                // PSALS_PAUSE, () → void
    await sensor.offInterrupt();                                         // Unsubscribe, () → void
    console.log('%d interrupt events', events.length);
    await sensor.disableInterrupt(Si1145Full.SOURCE_PS1);                // IRQ_ENABLE clear, (source) → void
    await sensor.pollInterrupt();                                        // IRQ_STATUS, () → number sources

    // --- Auxiliary channels ---
    console.log('temperature raw %d  VDD raw %d', await sensor.readTemperatureRaw(), await sensor.readVddRaw()); // AUX channel, () → number counts
    await sensor.force(false, true);                                     // ALS_FORCE without waiting, (proximity, als) → void
    await sleep(50);
    console.log('raw', await sensor.readRaw());                          // Data registers, () → {...}

    // --- Address and reset ---
    await sensor.setI2cAddress(0x60);                                    // I2C_ADDR + BUSADDR, (address) → void
    await sensor.reset();                                                // RESET + HW_KEY + defaults, () → void
    console.log('after reset', await sensor.read());
    await connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
