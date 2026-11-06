'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { ADS1114Full } = require('periph/src/chips/adc_dac/ads1114');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, ADS1114Full.I2C_ADDRESS);
    const adc = new ADS1114Full(connection);                                        // Create ADS1114 Full driver, (connection)
                                                                                    // no I²C traffic; Config word is rebuilt per conversion
    const raw = await adc.readRaw();                                                // Read raw code, () → number
                                                                                    // single-shot conversion, signed 16-bit
    const v = await adc.readVoltage();                                              // Read voltage, () → number V
                                                                                    // raw × lsb()
    console.log(`raw ${raw} voltage ${v.toFixed(5)} V`);
    await adc.setGain(ADS1114Full.PGA_4_096);                                       // Set gain, (pga 0–5) → None
                                                                                    // ±4.096 V full scale
    console.log(`gain ${adc.getGain()}`);
    const fs = adc.fullScale();                                                     // Full scale, () → number V
                                                                                    // ±FSR of the current gain
    const lsb = adc.lsb();                                                          // LSB, () → number V
                                                                                    // fullScale() / 32768
    console.log(`full scale ${fs} V, lsb ${lsb} V`);
    await adc.setDataRate(ADS1114Full.DR_250);                                      // Set data rate, (dr 0–7) → None
                                                                                    // DR field of the Config word
    const sps = adc.dataRateSps();                                                  // Data rate, () → number SPS
                                                                                    // samples per second of the current setting
    console.log(`rate ${adc.getDataRate()} = ${sps} SPS`);
    await adc.startContinuous();                                                    // Start continuous, () → None
                                                                                    // MODE ← 0
    await sleep(50);
    const craw = await adc.readContinuousRaw();                                     // Read continuous raw, () → number
                                                                                    // CONVERSION without touching Config
    const cv = await adc.readContinuous();                                          // Read continuous, () → number V
                                                                                    // readContinuousRaw() × lsb()
    console.log(`continuous ${craw} ${cv.toFixed(5)} V`);
    await adc.stopContinuous();                                                     // Stop continuous, () → None
                                                                                    // MODE ← 1
    const busy = await adc.isConverting();                                          // Check conversion, () → boolean
                                                                                    // CONFIG.OS == 0
    const cfg = await adc.readConfig();                                             // Read CONFIG, () → number
                                                                                    // raw 16-bit register
    console.log(`converting ${busy}, config 0x${cfg.toString(16)}`);
    await adc.setThresholds(0.5, 3.0);                                              // Set thresholds, (low V, high V) → None
                                                                                    // converted with the current lsb(), clamped
    await adc.setLowThresholdRaw(4000);                                             // Set low threshold, (raw) → None
                                                                                    // LO_THRESH ← raw & 0xFFFF
    await adc.setHighThresholdRaw(20000);                                           // Set high threshold, (raw) → None
                                                                                    // HI_THRESH ← raw & 0xFFFF
    const [lo, hi] = await adc.getThresholdsRaw();                                  // Get thresholds, () → [number, number]
                                                                                    // read back, sign-extended
    console.log(`thresholds ${lo} ${hi}`);
    await adc.configureComparator(ADS1114Full.COMP_WINDOW, ADS1114Full.ACTIVE_LOW, true, ADS1114Full.QUEUE_2);// Configure comparator, (mode, polarity, latch=false, queue=QUEUE_1) → None
                                                                                    // window mode, latching, 2-sample queue
    await adc.clearAlert();                                                         // Clear alert, () → None
                                                                                    // reads CONVERSION to release the latch
    await adc.disableComparator();                                                  // Disable comparator, () → None
                                                                                    // COMP_QUE ← 11
    await adc.enableInterrupt(ADS1114Full.SOURCE_CONVERSION_READY);                 // Enable interrupt, (source) → None
                                                                                    // ALERT/RDY signals conversion-ready
    const status = await adc.pollInterrupt();                                       // Poll interrupt, () → number source
                                                                                    // enabled source if ALERT/RDY is asserted
    console.log(`poll ${status}`);
    await adc.onInterrupt((s) => console.log(`alert ${s}`));                        // Subscribe, (callback) → None
                                                                                    // pin edge, or a polling loop without an InputPin
    await sleep(1000);
    await adc.offInterrupt();                                                       // Unsubscribe, () → None
                                                                                    // stops delivery
    await adc.disableInterrupt(ADS1114Full.SOURCE_CONVERSION_READY);                // Disable interrupt, (source) → None
                                                                                    // releases ALERT/RDY
    await adc.reset();                                                              // Reset, () → None
                                                                                    // writes the reset values back

    await connection.close();
})();
