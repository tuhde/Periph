'use strict';

const { Default } = require('opengpio');
const { HX711Connection } = require('../../../src/connection/hx711');
const { HX710BFull } = require('../../../src/chips/adc_dac/hx710b');

// Battery-powered load cell demo: tare at startup, then print weight
// continuously, watching the DVDD−AVDD supply-difference reading for
// drift that signals a low battery. Replace SCALE_FACTOR with the value
// calibrated for your load cell and wiring topology. Calibration: (1)
// call tare() with nothing on the scale; (2) place a known 100 g reference
// weight; (3) SCALE_FACTOR = (readAverage() - getOffset()) / 100.
const SCALE_FACTOR = 420.0;
const LOW_BATT_DELTA = 50000;  // supply-diff drift threshold from baseline

const dout   = Default.input({ chip: 0, line: 5 });
const pd_sck = Default.output({ chip: 0, line: 6 });
const connection = new HX711Connection(dout, pd_sck);      // Create HX711 transport connection, (dout, pd_sck)
const chip = new HX710BFull(connection);                   // Create HX710B driver — discards first conversion, (connection)

// --- Tare the scale before use ---
// Averaging 10 readings with nothing on the scale suppresses noise in the
// zero-offset capture, so later weight readings aren't skewed by drift.
console.log('Taring — keep scale empty...');
chip.tare(10);                                             // Capture zero offset from 10-reading average, (times=10) → undefined
chip.setScale(SCALE_FACTOR);                               // Set calibration scale factor, (factor: number) → undefined
console.log('Tare done. Place weight on scale.');

// --- Capture the supply-difference baseline at full charge ---
// The datasheet gives no absolute LSB-to-volts conversion for this
// channel — it is only useful for relative drift tracking. Capture one
// reading at startup as a "known-good battery" baseline, then compare
// later readings against it to detect discharge.
const baselineSuppDiff = chip.readSupplyDiffRaw();         // Read raw DVDD−AVDD supply-difference code, () → number

let prevWeight = null;
let iteration = 0;

function poll() {
    const weight = chip.readWeight(3);                     // Return calibrated weight, (times=3) → number
    const rounded = Math.round(weight * 10) / 10;
    if (prevWeight === null || Math.abs(rounded - prevWeight) > 1.0) {
        console.log(`-> ${rounded.toFixed(1)} g`);
        prevWeight = rounded;
    }

    if (iteration % 20 === 0) {
        // --- Sample the DVDD−AVDD supply-difference channel every ~10 s ---
        // Uncalibrated ADC code, intended only for relative drift tracking
        // against a known-good baseline (the datasheet's stated purpose for
        // this channel in battery-powered weigh-scale applications) — not
        // an absolute voltage reading.
        const suppDiff = chip.readSupplyDiffRaw();         // Read raw DVDD−AVDD supply-difference code, () → number
        const drift = suppDiff - baselineSuppDiff;
        if (Math.abs(drift) > LOW_BATT_DELTA) {
            console.log(`LOW BATTERY (supply_diff=${suppDiff}, drift=${drift})`);
        }
    }

    iteration++;
    setTimeout(poll, 500);
}

poll();
