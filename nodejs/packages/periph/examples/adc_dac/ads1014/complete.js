'use strict';

// Exercises every method in the ADS1014 public API: single-shot reads, PGA
// gain, data rate, continuous conversion, the comparator and thresholds, and
// the ALERT/RDY interrupt routing.

const { I2CConnection } = require('periph/src/connection/i2c');
const { ADS1014Full } = require('periph/src/chips/adc_dac/ads1014');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, ADS1014Full.I2C_ADDRESS);
    const adc = new ADS1014Full(connection);                                       // Create ADS1014 Full driver, (connection)
                                                                                   // no I2C traffic; defaults ±2.048 V, 1600 SPS

    const raw = await adc.readRaw();                                               // Read raw code, () → number (−2048…2047)
                                                                                   // single-shot AIN0 − AIN1, polls OS until idle
    const v = await adc.readVoltage();                                             // Read voltage, () → number V
                                                                                   // raw × full-scale / 2048
    console.log('raw', raw, `voltage ${v.toFixed(4)} V`);

    await adc.setGain(ADS1014Full.PGA_1_024);                                      // Set PGA, (pga 0–5) → None
                                                                                   // ±1.024 V range, 0.5 mV per code
    console.log('gain', adc.getGain());                                            // Read PGA code, () → number
                                                                                   // cached PGA[2:0]
    console.log('full scale', adc.fullScale(), 'V');                               // Read full-scale range, () → number V
                                                                                   // ±FSR of the current gain
    console.log('lsb', adc.lsb(), 'V');                                            // Read code size, () → number V
                                                                                   // fullScale() / 2048

    await adc.setDataRate(ADS1014Full.DR_250);                                     // Set data rate, (dr 0–6) → None
                                                                                   // DR_250 = 250 SPS
    console.log('dr code', adc.getDataRate());                                     // Read data-rate code, () → number
                                                                                   // cached DR[2:0]
    console.log('sps', adc.dataRateSps());                                         // Read data rate, () → number SPS
                                                                                   // table lookup of the DR code

    await adc.startContinuous();                                                   // Start continuous conversion, () → None
                                                                                   // MODE = 0 at the current data rate
    await sleep(20);
    console.log('continuous raw', await adc.readContinuousRaw());                  // Read latest code, () → number
                                                                                   // reads CONVERSION without touching Config
    console.log('continuous', (await adc.readContinuous()).toFixed(4), 'V');       // Read latest voltage, () → number V
                                                                                   // raw × lsb()
    await adc.stopContinuous();                                                    // Stop continuous conversion, () → None
                                                                                   // MODE = 1, back to single-shot
    console.log('converting', await adc.isConverting());                           // Check conversion in progress, () → boolean
                                                                                   // CONFIG.OS = 0 (single-shot only)
    console.log('config 0x' + (await adc.readConfig()).toString(16));              // Read CONFIG register, () → number
                                                                                   // raw 16-bit register

    await adc.setThresholds(-0.2, 0.5);                                            // Set both thresholds, (low V, high V) → None
                                                                                   // converted with lsb(), clamped to the code range
    await adc.setLowThresholdRaw(-200);                                            // Set low threshold, (raw −2048…2047) → None
                                                                                   // LO_THRESH = raw << 4
    await adc.setHighThresholdRaw(500);                                            // Set high threshold, (raw −2048…2047) → None
                                                                                   // HI_THRESH = raw << 4
    console.log('thresholds', await adc.getThresholdsRaw());                       // Read thresholds, () → [number, number]
                                                                                   // sign-extended 12-bit codes

    await adc.configureComparator(ADS1014Full.COMP_WINDOW, ADS1014Full.ACTIVE_LOW, true, ADS1014Full.QUEUE_2);  // Configure comparator, (mode, polarity, latch, queue) → None
                                                                                   // window mode, latching, 2 consecutive conversions
    await adc.clearAlert();                                                        // Release latched alert, () → None
                                                                                   // reads CONVERSION
    await adc.disableComparator();                                                 // Disable comparator, () → None
                                                                                   // COMP_QUE = 11, ALERT/RDY high-impedance

    await adc.enableInterrupt(ADS1014Full.SOURCE_CONVERSION_READY);                // Route conversion-ready to ALERT/RDY, (source) → None
                                                                                   // HI_THRESH = 0x8000, LO_THRESH = 0, COMP_QUE = 00
    console.log('status', await adc.pollInterrupt());                              // Poll ALERT/RDY, () → number source
                                                                                   // INT pin level, else CONFIG.OS fallback
    await adc.disableInterrupt(ADS1014Full.SOURCE_CONVERSION_READY);               // Release ALERT/RDY, (source) → None
                                                                                   // COMP_QUE = 11

    await adc.enableInterrupt(ADS1014Full.SOURCE_COMPARATOR);                      // Route comparator to ALERT/RDY, (source) → None
                                                                                   // restores the thresholds set above
    await adc.onInterrupt((status) => console.log('alert source', status));        // Subscribe to ALERT/RDY, (callback) → None
                                                                                   // pin edge via connection.intPin, else a 5 ms polling loop
    await sleep(1000);
    await adc.offInterrupt();                                                      // Unsubscribe, () → None
                                                                                   // stops delivery
    await adc.reset();                                                             // Reset to power-up defaults, () → None
                                                                                   // rewrites CONFIG and both thresholds

    await connection.close();
})();
