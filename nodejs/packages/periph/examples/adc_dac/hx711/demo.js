'use strict';

const { Default } = require('opengpio');
const { HX711Connection } = require('../../../src/connection/hx711');
const { HX711Full } = require('../../../src/chips/adc_dac/hx711');

// Kitchen scale demo: tare at startup, then print weight continuously.
// Replace SCALE_FACTOR with the value calibrated for your load cell and V_DD.
// Calibration: (1) call tare() with nothing on the scale; (2) place a known
// weight W grams; (3) SCALE_FACTOR = (readAverage() - getOffset()) / W.
const SCALE_FACTOR = 420.0;

const dout   = Default.input({ chip: 0, line: 5 });
const pd_sck = Default.output({ chip: 0, line: 6 });
const connection = new HX711Connection(dout, pd_sck);      // Create HX711 connection, (dout, pd_sck)
const chip = new HX711Full(connection);                    // Create HX711 driver — discards first conversion, (connection)

console.log('Taring — keep scale empty...');
chip.tare(10);                                             // Capture zero offset from 10-reading average, (times=10) → undefined
chip.setScale(SCALE_FACTOR);                               // Set calibration scale factor, (factor: number) → undefined
console.log('Tare done. Place weight on scale.');

let prevWeight = null;

function poll() {
    const weight = chip.readWeight(3);                     // Return calibrated weight, (times=3) → number
    const rounded = Math.round(weight * 10) / 10;
    if (prevWeight === null || Math.abs(rounded - prevWeight) > 1.0) {
        console.log(`-> ${rounded.toFixed(1)} g`);
        prevWeight = rounded;
    }
    setTimeout(poll, 500);
}

poll();
