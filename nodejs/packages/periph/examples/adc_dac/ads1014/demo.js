'use strict';

// Shunt-current monitor with alert. A 0.1 ohm shunt in the load's supply line
// is read on the ±0.256 V range at 250 SPS; the current is printed every
// second, and the traditional comparator (high 0.1 V, low 0.08 V, latching,
// queue of 4) reports when the load current exceeds 1 A.

const { I2CConnection } = require('periph/src/connection/i2c');
const { ADS1014Full } = require('periph/src/chips/adc_dac/ads1014');

const SHUNT_OHMS = 0.1;
const SAMPLES = 20;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, ADS1014Full.I2C_ADDRESS);
    const adc = new ADS1014Full(connection);                                       // Create ADS1014 Full driver, (connection)

    // --- Pick the range and speed for a small shunt voltage ---
    // ±0.256 V gives 0.125 mV per code; 250 SPS keeps noise low and is plenty
    // for a once-per-second report.
    await adc.setGain(ADS1014Full.PGA_0_256);                                      // Set PGA, (pga 0–5) → None
    await adc.setDataRate(ADS1014Full.DR_250);                                     // Set data rate, (dr 0–6) → None

    // --- Arm the over-current comparator ---
    // 0.1 V across 0.1 ohm is 1 A. Traditional comparator with hysteresis
    // (clears again below 0.08 V), latched so a brief spike is not missed, and
    // 4 consecutive conversions are needed to assert.
    await adc.setThresholds(0.08, 0.1);                                            // Set both thresholds, (low V, high V) → None
    await adc.configureComparator(ADS1014Full.COMP_TRADITIONAL, ADS1014Full.ACTIVE_LOW, true, ADS1014Full.QUEUE_4);  // Configure comparator, (mode, polarity, latch, queue) → None
    await adc.enableInterrupt(ADS1014Full.SOURCE_COMPARATOR);                      // Route comparator to ALERT/RDY, (source) → None

    // --- Monitor the current ---
    // The pin level is not wired in this demo, so the alert state is inferred
    // from the measured current; clearAlert() releases the latch afterwards.
    for (let i = 0; i < SAMPLES; i++) {
        const v = await adc.readVoltage();                                         // Read voltage, () → number V
        const amps = v / SHUNT_OHMS;
        console.log(`${v.toFixed(3)} V -> ${amps.toFixed(2)} A`);
        if (amps > 1.0) {
            console.log('over-current!');
            await adc.clearAlert();                                                // Release latched alert, () → None
        }
        await sleep(1000);
    }

    // --- Release the alert pin when finished ---
    await adc.disableInterrupt(ADS1014Full.SOURCE_COMPARATOR);                     // Release ALERT/RDY, (source) → None

    await connection.close();
})();
