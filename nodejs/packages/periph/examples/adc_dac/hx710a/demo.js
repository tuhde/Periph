'use strict';

const { Default } = require('opengpio');
const { HX711Connection } = require('../../../src/connection/hx711');
const { HX710AFull } = require('../../../src/chips/adc_dac/hx710a');

// Temperature-monitored load cell demo: tare at startup, then print weight
// continuously, sampling the on-chip temperature sensor periodically.
// Replace SCALE_FACTOR with the value calibrated for your load cell and V_DD.
// Calibration: (1) call tare() with nothing on the scale; (2) place a known
// 100 g reference weight; (3) SCALE_FACTOR = (readAverage() - getOffset()) / 100.
const SCALE_FACTOR = 420.0;

const dout   = Default.input({ chip: 0, line: 5 });
const pd_sck = Default.output({ chip: 0, line: 6 });
const connection = new HX711Connection(dout, pd_sck);      // Create HX711 transport connection, (dout, pd_sck)
const chip = new HX710AFull(connection);                   // Create HX710A driver — discards first conversion, (connection)

// --- Tare the scale before use ---
// Averaging 10 readings with nothing on the scale suppresses noise in the
// zero-offset capture, so later weight readings aren't skewed by drift.
console.log('Taring — keep scale empty...');
chip.tare(10);                                             // Capture zero offset from 10-reading average, (times=10) → undefined
chip.setScale(SCALE_FACTOR);                               // Set calibration scale factor, (factor: number) → undefined
console.log('Tare done. Place weight on scale.');

let prevWeight = null;
let iteration = 0;

function poll() {
    const weight = chip.readWeight(3);                     // Return calibrated weight, (times=3) → number
    const rounded = Math.round(weight * 10) / 10;
    if (prevWeight === null || Math.abs(rounded - prevWeight) > 1.0) {
        console.log(`-> ${rounded.toFixed(1)} g`);
        prevWeight = rounded;
    }

    if (iteration % 10 === 0) {
        // --- Sample the on-chip temperature sensor every ~5 s ---
        // This is an uncalibrated raw ADC code (~20.4 LSB/°C, chip-to-chip
        // offset/gain vary per the datasheet), intended only for the
        // datasheet's stated purpose of relative drift compensation of the
        // weight reading — not as an absolute °C measurement.
        const tempRaw = chip.readTemperatureRaw();         // Read raw on-chip temperature code, () → number
        console.log(`-> ${rounded.toFixed(1)} g (temp raw=${tempRaw})`);
    }

    iteration++;
    setTimeout(poll, 500);
}

poll();
