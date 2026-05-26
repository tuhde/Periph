'use strict';

const Gpio = require('onoff').Gpio;
const { HX711Transport } = require('../../../src/transport/hx711');
const { HX711Minimal } = require('../../../src/chips/adc_dac/hx711');

const dout   = new Gpio(5, 'in');                          // DOUT input pin from HX711
const pd_sck = new Gpio(6, 'out');                         // PD_SCK clock / power-down output pin
const transport = new HX711Transport(dout, pd_sck);        // Create HX711 transport, (dout, pd_sck)
const chip = new HX711Minimal(transport);                  // Create HX711 driver — discards first conversion, (transport)

const ready = chip.isReady();                              // Check if conversion is ready (non-blocking), () → boolean
const raw = chip.readRaw();                                // Read signed 24-bit ADC value, () → number
console.log(raw);

transport.close();
