'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { INA3221Full } = require('../../../src/chips/power/ina3221');

const connection = new I2CConnection(1, 0x40);
const ina = new INA3221Full(connection);                // Create INA3221 driver, (connection, r_shunt=0.1 Ω)

(async () => {
    console.log('0x' + (await ina.manufacturerId()).toString(16)); // Read Manufacturer ID, () → int 0x5449
                                                            // Texas Instruments ID
    console.log('0x' + (await ina.dieId()).toString(16)); // Read Die ID, () → int 0x3220
                                                            // INA3221 die revision

    for (const ch of [1, 2, 3]) {
        console.log(await ina.voltage(ch));                // Read bus voltage, (channel) → float V
                                                            // left-aligned 12-bit bus register, 8 mV LSB
        console.log(await ina.shuntVoltage(ch));           // Read shunt voltage, (channel) → float V
                                                            // left-aligned 13-bit signed shunt, 40 µV LSB
        console.log(await ina.current(ch));                // Read load current, (channel) → float A
                                                            // computed from shunt voltage / r_shunt
        console.log(await ina.power(ch));                  // Read power, (channel) → float W
                                                            // computed from voltage × current
    }

    console.log(await ina.conversionReady());              // Check conversion done, () → bool
                                                            // reads CVRF bit from Mask/Enable register

    await ina.configure(4, 4, 4, 7);                       // Configure ADC, (avg 0–7, vbus_ct 0–7, vsh_ct 0–7, mode 0–7) → None
                                                            // sets averaging count, conversion time, and operating mode

    await ina.enableChannel(1, true);                      // Enable channel, (channel, enabled) → None
                                                            // modifies CH1en bit in Configuration register
    const ena = await ina.channelEnabled(1);               // Read channel enabled, (channel) → bool
                                                            // reads CH1en bit

    await ina.setCriticalAlert(1, 0.1);                    // Set critical alert, (channel, limit_v, latch=False) → None
                                                            // per-conversion threshold on shunt voltage
    await ina.setWarningAlert(2, 0.05);                    // Set warning alert, (channel, limit_v, latch=False) → None
                                                            // per-average threshold on shunt voltage

    const flags = await ina.alertFlags();                  // Read alert flags, () → int
                                                            // reads Mask/Enable register, clears latched flags

    await ina.setSummationChannels([1, 2], 0.2);           // Set summation channels, (channels, limit_v) → None
                                                            // enables SCC bits and sets sum limit register
    const svSum = await ina.summationValue();              // Read summation value, () → float V
                                                            // reads Shunt-Voltage Sum register

    await ina.setPowerValidLimits(5.5, 4.5);               // Set PV limits, (upper_v, lower_v) → None
                                                            // sets PV Upper/Lower Limit registers
    const pv = await ina.powerValid();                     // Read power valid, () → bool
                                                            // reads PVF bit from Mask/Enable

    await ina.shutdown();                                  // Put chip into power-down mode, () → None
                                                            // saves current mode for wake()
    // 1 ms synchronous delay
    const end = Date.now() + 1;
    while (Date.now() < end) {}
    await ina.wake();                                     // Restore operating mode, () → None
                                                            // restores the mode saved by shutdown()

    await ina.reset();                                    // Reset all registers, () → None
                                                            // sets RST bit, chip re-initializes to defaults

    await connection.close();
})();
