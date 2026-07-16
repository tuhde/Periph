'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { PCF8591Full } = require('../../../src/chips/adc_dac/pcf8591');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x48', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const adc = new PCF8591Full(transport);

const ch0_raw = adc.read_channel(0);                        // Read single channel, (channel=0–3) → number
                                                             // discards the stale first conversion byte; returns 0–255
const ch1_raw = adc.read_channel(1);                        // Read single channel, (channel=0–3) → number
                                                             // selects channel 1 via the control byte, returns 0–255
const all_raw = adc.read_all();                             // Read all four channels, () → number[]
                                                             // sets AI=1 and reads 5 bytes; discards stale byte 0

const v0 = adc.read_channel_voltage(0, 3.3, 0.0);           // Read channel as voltage, (channel, vref=3.3 V, vagnd=0.0 V) → number V
                                                             // converts raw to voltage using V_AGND + raw × (V_REF−V_AGND) / 256
const v_all = adc.read_all_voltage(3.3, 0.0);               // Read all channels as voltages, (vref=3.3 V, vagnd=0.0 V) → number[] V
                                                             // returns four voltages using the same conversion

adc.configure(PCF8591Full.MODE_3_DIFFERENTIAL, false, false);  // Configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → None
                                                                // sets AIP=01 (3 differential channels vs AIN3) and clears AOE/AI
const diff = adc.read_differential(0);                       // Read differential channel, (channel=0–2) → number
                                                             // returns signed 8-bit two's complement (-128 to 127)
adc.configure(PCF8591Full.MODE_4_SINGLE_ENDED, false, true);   // Configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → None
                                                                // restores 4 single-ended mode and enables the DAC output
adc.set_dac(128);                                            // Enable DAC and set raw value, (value=0–255) → None
                                                             // sets AOE=1 and writes 128 to the DAC register; V_AOUT ≈ V_REF/2
adc.set_dac_voltage(0.25);                                   // Set DAC as fraction of (VREF−VAGND), (fraction=0.0–1.0) → None
                                                             // maps fraction to 0–255 and writes the DAC; AOUT follows
adc.disable_dac();                                           // Disable DAC output, () → None
                                                             // clears AOE; AOUT returns to high-impedance
console.log('ch0_raw=' + ch0_raw);
console.log('v0=' + v0.toFixed(3) + 'V');
console.log('diff=' + diff);
console.log('PCF8591 complete running');
