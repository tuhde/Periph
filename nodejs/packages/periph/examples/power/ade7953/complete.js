'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { ADE7953Full } = require('../../../src/chips/power/ade7953');

const connection = new I2CConnection(1, 0x38);
const ade = new ADE7953Full(connection, 251.0, 30.0);          // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V, bus_type='i2c')

(async () => {
    console.log('version:', (await ade.version()).toString(16));    // Read silicon version, () → int
    console.log('V=', await ade.voltage());                         // Read bus voltage, () → float V
                                                                     // converts raw VRMS to volts using voltage_gain
    console.log('I_a=', await ade.current());                       // Read load current, () → float A
                                                                     // converts raw IRMSA to amperes using current_gain
    console.log('P_a=', await ade.activePower());                   // Read active power, () → float W
                                                                     // converts raw AWATT (instantaneous, 6.99 kHz) to watts
    console.log('E_a=', await ade.activeEnergy());                  // Read active energy, () → float Wh
                                                                     // converts raw AENERGYA accumulated LSBs to watt-hours
    console.log('PF=', await ade.powerFactor());                    // Read power factor, () → float
                                                                     // converts raw PFA (1 LSB = 2^-15) to a −1.0..+1.0 ratio
    console.log('f=', await ade.lineFrequency());                   // Read line frequency, () → float Hz

    await ade.configureChannelB(30.0);                              // Set Channel B calibration, (current_gain_b) → None
    console.log('I_b=', await ade.currentB());                      // Read Current Channel B, () → float A

    await ade.setPga('a', 1);                                       // Write PGA Channel A, (channel, gain) → None
                                                                     // gain 1..16 (+22 valid for Channel A only)
    await ade.setPhaseCalibration('a', 0.0);                        // Write phase calibration A, (channel, delay_s) → None
                                                                     // negative delay_s advances; range ±383 × 1.117 µs
    await ade.setGainCalibration(0x282, 0x400000);                  // Write active-power gain A, (register, value) → None
                                                                     // 0x400000 = unity; valid range 0x200000..0x600000
    await ade.setOffsetCalibration(0x289, 0);                       // Write active-power offset A, (register, value) → None
                                                                     // signed 24-bit offset
    console.log('checksum:', (await ade.checksum()).toString(16)); // Read CRC/checksum, () → int
    await ade.enableChecksum(true);                                 // Enable CRC/checksum, (enabled) → None

    await ade.configureOvervoltage(260.0);                          // Configure overvoltage, (threshold) → None
                                                                     // threshold in volts (same scale as voltage())
    await ade.configureOvercurrent(40.0);                           // Configure overcurrent, (threshold) → None
                                                                     // threshold in amperes; applies to BOTH current channels

    await ade.reset();                                              // Software reset, () → None
                                                                     // waits 110 ms then re-runs the mandatory power-up sequence
})();