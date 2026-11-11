'use strict';

// Complete example: exercises every method of ADS7828Full.

const { I2CConnection } = require('periph/src/connection/i2c');
const { ADS7828Full } = require('periph/src/chips/adc_dac/ads7828');

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, ADS7828Full.I2C_ADDRESS);    // Create I2C connection, (bus, address)
    const adc = new ADS7828Full(connection);                                // Create ADS7828 Full driver, (connection, vref=null → internal 2.5 V)
    await adc.init();                                                       // Enable internal reference, () → None
                                                                            // sends command 0x08 and waits 1 ms for the reference to settle

    console.log(await adc.readRaw(3));                                      // Read CH3 raw code, (channel 0–7) → int
                                                                            // single-ended, interleaved channel encoding; returns 0–4095
    console.log(await adc.readVoltage(3));                                  // Read CH3 voltage, (channel 0–7) → float V
                                                                            // raw / 4096 × vref
    console.log(await adc.readAll());                                       // Read all eight channels, () → float[8] V
                                                                            // one conversion per channel, CH0…CH7

    console.log(await adc.readDifferentialRaw(0));                          // Read pair 0 raw code, (pair 0–3, inverted=false) → int
                                                                            // CH0 (+) / CH1 (−); a negative difference reads 0
    console.log(await adc.readDifferential(1, true));                       // Read pair 1 inverted, (pair 0–3, inverted=false) → float V
                                                                            // CH3 (+) / CH2 (−)
    console.log(await adc.readSignedDifferential(0));                       // Read pair 0 signed, (pair 0–3) → float V
                                                                            // two conversions (normal and inverted), range −vref … +vref

    console.log(adc.lsb());                                                 // Get code step, () → float V
                                                                            // vref / 4096 (610 µV at 2.5 V)
    console.log(await adc.readScan([0, 2, 4, 6]));                          // Scan channels, (channels int[]) → int[]
                                                                            // one raw conversion per channel in the given order

    await adc.setReference(5.0);                                            // Use external reference, (vref null|float V) → None
                                                                            // PD1 = 0 from now on; the reference must already be stable
    console.log(adc.getReference());                                        // Get reference voltage, () → float V
    console.log(adc.usesInternalReference());                               // Internal reference selected?, () → bool
    await adc.setReference(null);                                           // Use internal reference, (vref null|float V) → None
                                                                            // re-runs the reference turn-on and 1 ms settle wait

    adc.setPowerMode(ADS7828Full.REF_ON_ADC_ON);                            // Set PD bits, (pd 0–3) → None
                                                                            // internal reference requires PD1 = 1 (values 2 or 3)
    await adc.powerDown();                                                  // Power down completely, () → None
                                                                            // lone command with PD=00; next read re-enables the reference
    await connection.close();
})();
