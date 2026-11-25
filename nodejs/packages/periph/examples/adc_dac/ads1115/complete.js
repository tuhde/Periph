'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { ADS1115Full } = require('periph/src/chips/adc_dac/ads1115');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, ADS1115Full.I2C_ADDRESS);
    const adc = new ADS1115Full(connection);                                        // Create ADS1115 Full driver, (connection)
                                                                                    // no I²C traffic; Config word is rebuilt per conversion
    const raw = await adc.readRaw(2);                                               // Read raw code, (channel=0) → number
                                                                                    // single-shot conversion, signed 16-bit
    const v = await adc.readVoltage(2);                                             // Read voltage, (channel=0) → number V
                                                                                    // raw × lsb()
    console.log(`raw ${raw} voltage ${v.toFixed(5)} V`);
    await adc.setGain(ADS1115Full.PGA_4_096);                                       // Set gain, (pga 0–5) → None
                                                                                    // ±4.096 V full scale
    console.log(`gain ${adc.getGain()}`);
    const fs = adc.fullScale();                                                     // Full scale, () → number V
                                                                                    // ±FSR of the current gain
    const lsb = adc.lsb();                                                          // LSB, () → number V
                                                                                    // fullScale() / 32768
    console.log(`full scale ${fs} V, lsb ${lsb} V`);
    await adc.setDataRate(ADS1115Full.DR_250);                                      // Set data rate, (dr 0–7) → None
                                                                                    // DR field of the Config word
    const sps = adc.dataRateSps();                                                  // Data rate, () → number SPS
                                                                                    // samples per second of the current setting
    console.log(`rate ${adc.getDataRate()} = ${sps} SPS`);
    const d = await adc.readDifferential(ADS1115Full.DIFF_2_3);                     // Read differential, (pair 0–3) → number V
                                                                                    // single-shot MUX = pair
    const draw = await adc.readDifferentialRaw(ADS1115Full.DIFF_0_1);               // Read differential raw, (pair 0–3) → number
                                                                                    // signed 16-bit code
    console.log(`differential ${d.toFixed(6)} V, raw ${draw}`);
    await adc.startContinuous(ADS1115Full.MUX_AIN0_GND);                            // Start continuous, (mux=MUX_AIN0_GND 0–7) → None
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
    await adc.configureComparator(ADS1115Full.COMP_WINDOW, ADS1115Full.ACTIVE_LOW, true, ADS1115Full.QUEUE_2);// Configure comparator, (mode, polarity, latch=false, queue=QUEUE_1) → None
                                                                                    // window mode, latching, 2-sample queue
    await adc.clearAlert();                                                         // Clear alert, () → None
                                                                                    // reads CONVERSION to release the latch
    await adc.disableComparator();                                                  // Disable comparator, () → None
                                                                                    // COMP_QUE ← 11
    await adc.enableInterrupt(ADS1115Full.SOURCE_CONVERSION_READY);                 // Enable interrupt, (source) → None
                                                                                    // ALERT/RDY signals conversion-ready
    const status = await adc.pollInterrupt();                                       // Poll interrupt, () → number source
                                                                                    // enabled source if ALERT/RDY is asserted
    console.log(`poll ${status}`);
    await adc.onInterrupt((s) => console.log(`alert ${s}`));                        // Subscribe, (callback) → None
                                                                                    // pin edge, or a polling loop without an InputPin
    await sleep(1000);
    await adc.offInterrupt();                                                       // Unsubscribe, () → None
                                                                                    // stops delivery
    await adc.disableInterrupt(ADS1115Full.SOURCE_CONVERSION_READY);                // Disable interrupt, (source) → None
                                                                                    // releases ALERT/RDY
    await adc.reset();                                                              // Reset, () → None
                                                                                    // writes the reset values back

    await connection.close();
})();
