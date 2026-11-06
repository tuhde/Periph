'use strict';

// Haptic notification showcase: checks the actuator with diagnostics,
// calibrates it, plays a strong, double and triple click, a "heartbeat"
// sequence, then a 2-second amplitude ramp while printing the supply voltage
// (and LRA frequency). Finishes by reading the status flags.

const { I2CConnection } = require('periph/src/connection/i2c');
const { DRV2605Full } = require('periph/src/chips/motor/drv2605');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const ACTUATOR = 'ERM'; // set to 'LRA' for a linear resonant actuator
const LRA = ACTUATOR === 'LRA';

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, DRV2605Full.I2C_ADDRESS);
    const haptic = new DRV2605Full(connection, LRA);                               // Create DRV2605 Full driver, (connection, lra=false)
    await haptic.init();                                                           // Reset, verify and configure the chip, () → None

    // --- Verify and calibrate the actuator ---
    // diagnose() reports a missing or shorted actuator; calibrate() measures the
    // back-EMF so the closed-loop engine matches this particular motor.
    console.log('actuator ok:', await haptic.diagnose());                          // Run actuator diagnostics, () → boolean
    const cal = await haptic.calibrate(LRA, 3.0, 3.0, LRA ? 175 : undefined);      // Run auto-calibration, (lra, ratedVoltage V, overdriveVoltage V, lraFreqHz Hz) → {ok, comp, bemf, bemfGain}
    console.log('calibration:', cal);

    // --- Play the three click effects ---
    for (const effect of [DRV2605Full.EFFECT_STRONG_CLICK, DRV2605Full.EFFECT_DOUBLE_CLICK, DRV2605Full.EFFECT_TRIPLE_CLICK]) {
        await haptic.playEffect(effect);                                           // Play a ROM effect, (effectId 1–123) → None
        await haptic.waitDone();                                                   // Wait for playback, (timeoutMs=5000) → boolean
        await sleep(500);
    }

    // --- Heartbeat: two strong clicks 100 ms apart ---
    // The sequencer plays both effects and the delay without host involvement.
    await haptic.playSequence([DRV2605Full.EFFECT_STRONG_CLICK, ['wait', 100], DRV2605Full.EFFECT_STRONG_CLICK]); // Play a sequence, (items ≤ 8) → None
    await haptic.waitDone();                                                       // Wait for playback, (timeoutMs=5000) → boolean
    await sleep(500);

    // --- Two-second amplitude ramp ---
    // Real-time playback streams the amplitude; VBAT and LRA_PERIOD are valid
    // while the actuator is driven.
    for (let step = 0; step <= 20; step++) {
        await haptic.rtpLevel(step / 20);                                          // Stream an RTP amplitude, (level 0.0–1.0) → None
        await sleep(100);
        console.log('vdd', (await haptic.readSupplyVoltage()).toFixed(2), 'V');   // Read supply voltage, () → number V
        if (LRA) console.log('lra', await haptic.readLraFrequency());              // Read LRA frequency, () → number Hz | null
    }
    await haptic.stop();                                                           // Cancel playback, () → None

    // --- Report latched faults ---
    console.log('status:', await haptic.readStatus());                             // Read status flags, () → {diagFailed, fbTimeout, overTemp, overCurrent}
    await connection.close();
})();
