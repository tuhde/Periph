'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { PCF8591Full } = require('../../../src/chips/adc_dac/pcf8591');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x48', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const adc = new PCF8591Full(transport);

const VREF  = 3.3;
const VAGND = 0.0;

function delay(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

async function run_feedback() {
    // --- Wire a potentiometer across VAGND–VREF with the wiper to AIN0 ---
    // Connect an LED (with series resistor) to AOUT. In a loop, read AIN0, map
    // the 0–255 value to a DAC output value, and write it to AOUT — the LED
    // brightness tracks the potentiometer. This demonstrates the ADC→DAC
    // feedback path inside a single chip.
    adc.configure(PCF8591Full.MODE_4_SINGLE_ENDED, false, true);   // Configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → None
                                                                    // single-ended mode with DAC output enabled
    for (let n = 0; n < 20; n++) {
        const raw = adc.read_channel(0);                            // Read single channel, (channel=0–3) → number
        const vin  = VAGND + raw * (VREF - VAGND) / 256.0;
        adc.set_dac(raw);                                           // Enable DAC and set raw value, (value=0–255) → None
        const vout = VAGND + raw * (VREF - VAGND) / 256.0;
        console.log(`n=${String(n).padStart(2)} raw=${String(raw).padStart(3)} vin=${vin.toFixed(3)}V  vout=${vout.toFixed(3)}V`);
        await delay(200);
    }
    console.log('Demo complete');
    process.exit(0);
}

run_feedback();
