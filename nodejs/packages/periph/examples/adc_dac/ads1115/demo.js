'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { ADS1115Full } = require('periph/src/chips/adc_dac/ads1115');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, ADS1115Full.I2C_ADDRESS);
    const adc = new ADS1115Full(connection);                                        // Create ADS1115 Full driver, (connection)

    // --- Configure the converter ---
    // ±4.096 V covers a 3.3 V rail with headroom; 250 SPS keeps single-shot
    // reads short while still averaging out most noise.
    await adc.setGain(ADS1115Full.PGA_4_096);                                       // Set gain, (pga 0–5) → None
    await adc.setDataRate(ADS1115Full.DR_250);                                      // Set data rate, (dr 0–7) → None

    // --- Read the inputs ---
    // Single-shot mode powers the chip down between conversions.
    for (let i = 0; i < 3; i++) {
        for (let ch = 0; ch < 4; ch++) {
            const raw = await adc.readRaw(ch);                                      // Read raw code, (channel=0) → number
            console.log(`AIN${ch} raw ${raw} ${(raw * adc.lsb()).toFixed(5)} V`);
        }
        await sleep(1000);
    }

    // --- Arm a window comparator ---
    // ALERT/RDY asserts when the input leaves 0.5 V ... 3.0 V for two
    // consecutive conversions; the latch holds it until clearAlert().
    await adc.setThresholds(0.5, 3.0);                                              // Set thresholds, (low V, high V) → None
    await adc.configureComparator(ADS1115Full.COMP_WINDOW, ADS1115Full.ACTIVE_LOW, true, ADS1115Full.QUEUE_2);// Configure comparator, (mode, polarity, latch=false, queue=QUEUE_1) → None
    await adc.startContinuous(ADS1115Full.MUX_AIN0_GND);                            // Start continuous, (mux=MUX_AIN0_GND 0–7) → None

    // --- Report out-of-range events ---
    // With an InputPin on ALERT/RDY the callback fires on the edge; without
    // one the driver polls the register every 5 ms.
    await adc.onInterrupt(async () => {                                             // Subscribe, (callback) → None
        console.log('OUT OF RANGE');
        await adc.clearAlert();                                                     // Clear alert, () → None
    });
    await sleep(10000);
    await adc.offInterrupt();                                                       // Unsubscribe, () → None
    await adc.disableComparator();                                                  // Disable comparator, () → None
    await adc.stopContinuous();                                                     // Stop continuous, () → None

    // --- Bridge-sensor style differential read ---
    // The smallest range gives 7.8 uV per code for millivolt-level signals.
    await adc.setGain(ADS1115Full.PGA_0_256);                                       // Set gain, (pga 0–5) → None
    const d = await adc.readDifferential(ADS1115Full.DIFF_2_3);                     // Read differential, (pair 0–3) → number V
    console.log(`differential ${d.toFixed(6)} V`);

    await connection.close();
})();
