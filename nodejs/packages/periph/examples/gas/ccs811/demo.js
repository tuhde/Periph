'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { CCS811Full } = require('../../../src/chips/gas/ccs811');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x5A', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x5A) → Connection
const sensor = new CCS811Full(connection);                               // Create CCS811 driver, (connection) → void

function classify(eco2) {
    if (eco2 < 1000) return 'good';
    if (eco2 <= 1500) return 'fair';
    return 'poor';
}

async function main() {
    await sensor.init();                                                 // Check HW_ID, start application, mode 1, () → void

    // --- Identity ---
    // HW_ID 0x81 identifies the CCS81x family; the firmware versions tell which
    // algorithm revision computes eCO2 and TVOC.
    console.log('hardware', await sensor.hardwareVersion());             // Hardware ID and version, () → [hw_id, hw_version]
    console.log('firmware', await sensor.firmwareVersion());             // Firmware versions, () → {boot, app}
    const st = await sensor.status();                                    // STATUS, () → number
    const err = await sensor.error();                                    // ERROR_ID, () → number
    console.log('status 0x%s, error 0x%s', st.toString(16), err.toString(16));

    // --- Air-quality monitor: 30 s at 1 Hz ---
    // Mode 1 delivers a sample every second. Values settle after about 20 min of
    // warm-up; a brand-new sensor needs a 48 h burn-in.
    for (let i = 0; i < 30; i++) {
        const m = await sensor.read();                                   // Next sample, () → {eco2_ppm ppm, tvoc_ppb ppb}
        console.log('%d s  eCO2 %d ppm  TVOC %d ppb  %s', i, m.eco2_ppm, m.tvoc_ppb, classify(m.eco2_ppm));
    }
    console.log('eCO2 %d ppm', await sensor.readEco2());                 // eCO2 only, () → number ppm
    console.log('TVOC %d ppb', await sensor.readTvoc());                 // TVOC only, () → number ppb

    // --- Environment compensation ---
    // Feeding the real temperature and humidity (e.g. from a BME280) corrects the
    // algorithm; without it the chip assumes 25 °C and 50 %RH.
    await sensor.setEnvironment(40.0, 23.0);                             // Compensation data, (humidityRh %, temperatureC °C) → void
    const [vRef] = await sensor.readNtc();                               // NTC voltages, () → [v_ref_mv mV, v_ntc_mv mV]
    if (vRef) {
        console.log('NTC %s ohm', (await sensor.ntcResistance(10000)).toFixed(0)); // NTC resistance, (rRefOhm Ω) → number Ω
    } else {
        console.log('no NTC circuit fitted');
    }

    // --- Threshold interrupt: only report zone changes ---
    // With INT_THRESH the chip raises nINT only when eCO2 crosses 1000 or
    // 2000 ppm by more than the hysteresis. Polling STATUS here shows each sample.
    await sensor.setThresholds(1000, 2000, 50);                          // Zone boundaries, (lowMediumPpm, mediumHighPpm, hysteresisPpm) → void
    await sensor.enableInterrupt(CCS811Full.SOURCE_THRESHOLD);           // Threshold interrupt, (source) → void
    for (let i = 0; i < 5; i++) {
        while (!((await sensor.pollInterrupt()) & CCS811Full.STATUS_DATA_READY)) { // STATUS, () → number
            await sleep(100);
        }
        console.log('event', await sensor.read());                       // Reading releases nINT, () → {eco2_ppm, tvoc_ppb}
    }
    await sensor.disableInterrupt(CCS811Full.SOURCE_THRESHOLD);          // Threshold interrupt off, (source) → void

    // --- Data-ready callback ---
    // The callback gets the STATUS byte; without an INT pin the driver polls.
    await sensor.enableInterrupt(CCS811Full.SOURCE_DATA_READY);          // Data-ready interrupt, (source) → void
    await sensor.onInterrupt(async (s) => {                              // Subscribe, (callback, intPin=null) → void
        console.log('callback 0x%s', s.toString(16), await sensor.read());
    });
    await sleep(3000);
    await sensor.offInterrupt();                                         // Unsubscribe, () → void
    await sensor.disableInterrupt(CCS811Full.SOURCE_DATA_READY);         // Data-ready interrupt off, (source) → void

    // --- Raw sensor data ---
    // The MOx sensor's current and voltage give its resistance; the algorithm
    // derives eCO2 and TVOC from it.
    const [current, voltage] = await sensor.readRaw();                   // Raw data, () → [current_ua µA, voltage_v V]
    const r = current ? voltage / (current * 1e-6) : 0;
    console.log('raw %d uA, %s V, %s ohm', current, voltage.toFixed(3), r.toFixed(0));

    // --- Slower sampling: mode 2 (every 10 s) ---
    // Pulse heating saves power; pass through idle when changing modes.
    await sensor.setMode(0);                                             // Idle, (mode) → void
    await sensor.setMode(2);                                             // Every 10 s, (mode) → void
    for (let i = 0; i < 2; i++) {
        const m = await sensor.read();                                   // Next sample, () → {eco2_ppm, tvoc_ppb}
        console.log('mode 2', m, 'ready', await sensor.dataReady());     // Ready flag, () → boolean
    }
    await sensor.setMode(0);                                             // Idle, (mode) → void
    await sensor.setMode(1);                                             // Back to every second, (mode) → void

    // --- Baseline ---
    // Save the baseline daily and restore it after a power cycle, but only after
    // about 20 min of warm-up; otherwise the chip relearns it from scratch.
    const baseline = await sensor.getBaseline();                         // Baseline, () → number
    console.log('baseline 0x%s', baseline.toString(16).padStart(4, '0'));
    await sensor.setBaseline(baseline);                                  // Restore baseline, (baseline) → void

    // --- Wrap up ---
    // A soft reset puts the chip into boot mode; the driver starts the
    // application again in mode 1.
    await sensor.softReset();                                            // Software reset, () → void
    console.log('status after reset 0x%s', (await sensor.status()).toString(16)); // STATUS, () → number
    await connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
