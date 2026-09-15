'use strict';

const { Default } = require('opengpio');
const { HX711Connection } = require('../../../src/connection/hx711');
const { HX710AFull } = require('../../../src/chips/adc_dac/hx710a');

const dout   = Default.input({ chip: 0, line: 5 });        // Configure DOUT as input, ({chip, line}) → Input
const pd_sck = Default.output({ chip: 0, line: 6 });       // Configure PD_SCK as output, ({chip, line}) → Output
const connection = new HX711Connection(dout, pd_sck);      // Create HX711 transport connection, (dout, pd_sck)
const chip = new HX710AFull(connection);                   // Create HX710A driver — discards first conversion, (connection)

const ready = chip.isReady();                              // Check if conversion is ready (non-blocking), () → boolean
                                                            // returns true when DOUT is LOW
const raw = chip.readRaw();                                // Read signed 24-bit differential-input value, () → number
                                                            // blocks until DOUT goes LOW, then clocks out 24 bits

chip.setRate(40);                                          // Select differential-input output rate, (rate: 10|40) → undefined
                                                            // takes effect after next read; issues dummy read to apply
chip.setRate(10);                                          // (restores default 10 SPS)

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

const tempRaw = chip.readTemperatureRaw();                 // Read raw on-chip temperature code, () → number
                                                            // uncalibrated ADC code (~20.4 LSB/°C), not a °C value
console.log('temp raw:', tempRaw);

chip.powerDown();                                          // Enter power-down mode, () → undefined
                                                            // holds PD_SCK HIGH for >60 µs
chip.powerUp();                                            // Exit power-down, reset chip, discard settling conversion, () → undefined
                                                            // resets to differential input, gain 128, 10 SPS

connection.close();
