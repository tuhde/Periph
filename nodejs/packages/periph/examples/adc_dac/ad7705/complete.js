'use strict';

const spi = require('spi-device');
const { SPIConnection } = require('../../../src/connection/spi');
const { AD7705Full } = require('../../../src/chips/adc_dac/ad7705');

const SPI_BUS  = parseInt(process.env.SPI_BUS  || '0', 10);
const SPI_DEV  = parseInt(process.env.SPI_DEV  || '0', 10);

const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: spi.MODE3, maxSpeedHz: 5_000_000 });    // Create SPI connection, (bus=0, device=0, mode=3, maxSpeedHz=5_000_000) → SPIConnection
const adc = new AD7705Full(connection, 2.5, 2_457_600);                                                // Create AD7705 driver, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → AD7705Full

(async () => {
    await adc.configure(2, 8, true, true, 60);                                                        // Configure channel 2, (channel=2, gain=8, bipolar=true, buffered=true, output_rate_hz=60) → None
                                                                                                      // sets gain/bipolar/buffered/output_rate for the given channel; does not calibrate
    await adc.selfCalibrate(2);                                                                       // Self-calibrate channel, (channel=2) → None
                                                                                                      // runs internal self-calibration, blocking until DRDY

    const off2 = await adc.getOffsetCalibration(2);                                                    // Read offset calibration, (channel=2) → int 24-bit
    const gain2 = await adc.getGainCalibration(2);                                                     // Read gain calibration, (channel=2) → int 24-bit
    console.log('ch2 offset=' + off2 + ' gain=' + gain2);

    const raw1 = await adc.readRawChannel(1);                                                          // Read raw 16-bit code, (channel=1) → int 16-bit
    const v1 = await adc.readVoltageChannel(1);                                                        // Read voltage, (channel=1) → float V
    const v2 = await adc.readVoltageChannel(2);                                                        // Read voltage, (channel=2) → float V
    console.log('ch1 raw=' + raw1 + ' ch1 v=' + v1.toFixed(4) + ' ch2 v=' + v2.toFixed(4));

    await adc.standby();                                                                              // Enter standby, () → None
                                                                                                      // sets STBY=1 (~10 µA, registers retained)
    await adc.wakeup();                                                                               // Exit standby, () → None
                                                                                                      // clears STBY; blocks until a fresh conversion is available
})();
