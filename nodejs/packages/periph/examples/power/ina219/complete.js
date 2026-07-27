'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { INA219Full }   = require('../../../src/chips/power/ina219');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x40', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const ina = new INA219Full(connection);

(async () => {
    console.log(await ina.voltage());       // Read bus voltage, () → float V
    console.log(await ina.shuntVoltage()); // Read shunt voltage, () → float V
    console.log(await ina.current());      // Read load current, () → float A
    console.log(await ina.power());        // Read power, () → float W
    console.log(await ina.conversionReady()); // Check conversion done, () → bool
    console.log(await ina.overflow());    // Check math overflow, () → bool

    await ina.configure(1, 3, 0x03, 0x03, 7);
                                                    // Configure ADC, (brng 0–1, pga 0–3, badc 0x0F, sadc 0x0F, mode 0–7) → None
                                                    // sets bus range, PGA gain, ADC resolution, and operating mode

    await ina.shutdown();          // Put chip into power-down mode, () → None
    const end = Date.now() + 1;
    while (Date.now() < end) {}
    await ina.wake();              // Restore previous operating mode, () → None

    await ina.reset();             // Reset all registers and re-write calibration, () → None

    await connection.close();
})();
