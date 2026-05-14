'use strict';

const Gpio = require('onoff').Gpio;
const { HX711Transport } = require('../../../src/transport/hx711');
const { HX711Full } = require('../../../src/chips/adc_dac/hx711');

const dout   = new Gpio(5, 'in');                          // DOUT input pin from HX711
const pd_sck = new Gpio(6, 'out');                         // PD_SCK clock / power-down output pin
const transport = new HX711Transport(dout, pd_sck);        // Create HX711 transport, (dout, pd_sck)
const chip = new HX711Full(transport);                     // Create HX711 driver — discards first conversion, (transport)

const ready = chip.isReady();                              // Check if conversion is ready (non-blocking), () → boolean
                                                            // returns true when DOUT is LOW
const raw = chip.readRaw();                                // Read signed 24-bit ADC value at current gain, () → number
                                                            // blocks until DOUT goes LOW, then clocks out 24 bits

chip.setGain(64);                                          // Select channel and gain, (gain: 128|64|32) → undefined
                                                            // 128 → Channel A, 64 → Channel A, 32 → Channel B; issues dummy read to apply
chip.setGain(32);
chip.setGain(128);

const avg = chip.readAverage(10);                          // Average multiple raw readings, (times=10) → number
                                                            // blocks for `times` complete conversions

chip.tare(10);                                             // Capture zero offset from 10-reading average, (times=10) → undefined
                                                            // stores result in internal _offset; call with nothing on the scale
const offset = chip.getOffset();                           // Return stored tare offset, () → number

chip.setScale(420.0);                                      // Set calibration scale factor, (factor: number) → undefined
                                                            // factor = (readAverage() - offset) / known_weight_in_target_unit
const scale = chip.getScale();                             // Return current scale factor, () → number

const weight = chip.readWeight(5);                         // Return calibrated weight, (times=1) → number
                                                            // computes (readAverage(times) - offset) / scale
console.log('weight:', weight);

chip.powerDown();                                          // Enter power-down mode, () → undefined
                                                            // holds PD_SCK HIGH for >60 µs
chip.powerUp();                                            // Exit power-down, reset chip, discard settling conversion, () → undefined
                                                            // resets to Channel A Gain 128; first post-reset conversion discarded internally

transport.close();
