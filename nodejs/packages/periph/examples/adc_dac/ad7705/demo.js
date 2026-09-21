'use strict';

const spi = require('spi-device');
const { SPIConnection } = require('../../../src/connection/spi');
const { AD7705Full } = require('../../../src/chips/adc_dac/ad7705');

const SPI_BUS  = parseInt(process.env.SPI_BUS  || '0', 10);
const SPI_DEV  = parseInt(process.env.SPI_DEV  || '0', 10);

const TEMP_COEFF = 0.05;
const TEMP_REFERENCE = 1.25;
const CHANGE_THRESHOLD = 0.001;

const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: spi.MODE3, maxSpeedHz: 5_000_000 });    // Create SPI connection, (bus=0, device=0, mode=3, maxSpeedHz=5_000_000) → SPIConnection
const adc = new AD7705Full(connection, 2.5, 2_457_600);                                                // Create AD7705 driver, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → AD7705Full

(async () => {
    // --- Configure both channels for the bridge-pressure application ---
    // Channel 1 reads the pressure bridge at gain 128 (small mV-level signal);
    // Channel 2 reads an auxiliary temperature sensor at gain 2 for temperature
    // compensation of the pressure reading.
    await adc.configure(1, 128, true, true, 50);                                                       // Configure channel 1, (channel=1, gain=128, bipolar=true, buffered=true, output_rate_hz=50) → None
    await adc.configure(2, 2, true, false, 50);                                                        // Configure channel 2, (channel=2, gain=2, bipolar=true, buffered=false, output_rate_hz=50) → None

    // --- Self-calibrate both channels before the measurement loop ---
    await adc.selfCalibrate(1);                                                                       // Self-calibrate channel, (channel=1) → None
    await adc.selfCalibrate(2);                                                                       // Self-calibrate channel, (channel=2) → None

    // --- Sample continuously and compensate the pressure reading for temperature ---
    // pressure_compensated = pressure - TEMP_COEFF * (temp - TEMP_REFERENCE)
    // Print whenever the compensated reading changes by more than 1 mV.
    let lastPressure = null;
    setInterval(async () => {
        const pressureRaw = await adc.readVoltageChannel(1);                                          // Read voltage, (channel=1) → float V
        const temp = await adc.readVoltageChannel(2);                                                  // Read voltage, (channel=2) → float V
        const pressure = pressureRaw - TEMP_COEFF * (temp - TEMP_REFERENCE);
        if (lastPressure === null || Math.abs(pressure - lastPressure) > CHANGE_THRESHOLD) {
            console.log(`→ pressure=${pressure.toFixed(4)} V (raw ${pressureRaw.toFixed(4)} V, temp ${temp.toFixed(4)} V)`);
            lastPressure = pressure;
        }
    }, 200);
})();
