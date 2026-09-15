'use strict';

const { Default } = require('opengpio');
const { HX711Connection } = require('../../../src/connection/hx711');
const { HX710AMinimal } = require('../../../src/chips/adc_dac/hx710a');

const dout   = Default.input({ chip: 0, line: 5 });        // Configure DOUT as input, ({chip, line}) → Input
const pd_sck = Default.output({ chip: 0, line: 6 });       // Configure PD_SCK as output, ({chip, line}) → Output
const connection = new HX711Connection(dout, pd_sck);      // Create HX711 transport connection, (dout, pd_sck)
const chip = new HX710AMinimal(connection);                // Create HX710A driver — discards first conversion, (connection)

const ready = chip.isReady();                              // Check if conversion is ready (non-blocking), () → boolean
const raw = chip.readRaw();                                // Read signed 24-bit differential-input value, () → number
console.log(raw);

connection.close();
