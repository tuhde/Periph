'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { CCS811Full } = require('../../../src/chips/gas/ccs811');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x5A', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x5A) → Connection
// Default address 0x5A (ADDR low); 0x5B with ADDR high.
const sensor = new CCS811Full(connection);                               // Create CCS811 driver, (connection) → void
// Constructor stores the connection only; no bus traffic yet.

async function main() {
    await sensor.init();                                                 // Check HW_ID, start application, mode 1, () → void
    // Verifies HW_ID 0x81, writes APP_START if the chip is in boot mode, selects 1 s sampling.

    console.log(await sensor.hardwareVersion());                         // Hardware ID and version, () → [hw_id, hw_version]
    // HW_ID is 0x81, HW_VERSION 0x1X.
    console.log(await sensor.firmwareVersion());                         // Firmware versions, () → {boot: [major, minor, trivial], app: [...]}
    // Bootloader and application firmware, each major.minor.trivial.
    console.log((await sensor.status()).toString(16));                   // STATUS register, () → number
    // Bit 7 FW_MODE, bit 4 APP_VALID, bit 3 DATA_READY, bit 0 ERROR.
    console.log((await sensor.error()).toString(16));                    // ERROR_ID register, () → number
    // Zero unless STATUS.ERROR is set.

    console.log(await sensor.read());                                    // Next sample, () → {eco2_ppm: number ppm, tvoc_ppb: number ppb}
    // Waits for DATA_READY, then reads eCO2 and TVOC.
    console.log(await sensor.readEco2());                                // eCO2 only, () → number ppm
    // Equivalent CO2 estimated from the VOC level, 400–8192 ppm.
    console.log(await sensor.readTvoc());                                // TVOC only, () → number ppb
    // Total volatile organic compounds, 0–1187 ppb.
    console.log(await sensor.readRaw());                                 // Raw sensor data, () → [current_ua µA, voltage_v V]
    // Sense current and the voltage across the sensor.
    console.log(await sensor.dataReady());                               // New sample waiting, () → boolean
    // False right after a read, because reading the result clears DATA_READY.

    await sensor.setEnvironment(45.0, 22.5);                             // Compensation data, (humidityRh %, temperatureC °C) → void
    // Real ambient values improve accuracy; the chip assumes 50 %RH and 25 °C otherwise.
    console.log(await sensor.readNtc());                                 // NTC circuit voltages, () → [v_ref_mv mV, v_ntc_mv mV]
    // Only meaningful with a reference resistor and NTC wired to the AUX pin.
    try {
        console.log(await sensor.ntcResistance(100000));                 // NTC resistance, (rRefOhm Ω) → number Ω
        // V_NTC × R_REF / V_REF; map it to °C with the thermistor's curve.
    } catch (e) {
        console.log('no NTC circuit:', e.message);
    }

    await sensor.setThresholds(1000, 2000, 50);                          // Threshold boundaries, (lowMediumPpm=1500, mediumHighPpm=2500, hysteresisPpm=50) → void
    // eCO2 zones used by the threshold interrupt.
    const baseline = await sensor.getBaseline();                         // Algorithm baseline, () → number
    // Save it to non-volatile storage about once a day.
    console.log(baseline.toString(16));
    await sensor.setBaseline(baseline);                                  // Restore baseline, (baseline) → void
    // Restore a saved value only after about 20 min of warm-up.

    await sensor.enableInterrupt(CCS811Full.SOURCE_DATA_READY);          // nINT on new data, (source) → void
    // Sets INT_DATARDY and keeps the drive mode.
    await sensor.onInterrupt(async (st) => {                             // Subscribe, (callback, intPin=null) → void
        console.log('interrupt status 0x%s', st.toString(16), await sensor.read());
    });
    // Without an INT pin the driver polls STATUS; reading the sample releases nINT.
    await sleep(3000);
    await sensor.offInterrupt();                                         // Unsubscribe, () → void
    // Stops the pin handler or polling timer.
    console.log((await sensor.pollInterrupt()).toString(16));            // STATUS without releasing nINT, () → number
    // DATA_READY shows whether a sample is pending.
    await sensor.disableInterrupt(CCS811Full.SOURCE_DATA_READY);         // nINT off, (source) → void
    // Clears INT_DATARDY.

    await sensor.setMode(4);                                             // Drive mode, (mode 0–4) → void
    // Mode 4: raw data every 250 ms, no eCO2/TVOC updates.
    await sleep(500);
    console.log(await sensor.readRaw());                                 // Raw sensor data, () → [current_ua µA, voltage_v V]
    // RAW_DATA keeps updating in mode 4.
    await sensor.setMode(0);                                             // Drive mode, (mode 0–4) → void
    // Idle before switching to another mode.
    await sensor.setMode(1);                                             // Drive mode, (mode 0–4) → void
    // Back to one sample per second.

    await sensor.softReset();                                            // Software reset, () → void
    // SW_RESET into boot mode, then APP_START and mode 1 again.
    await connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
