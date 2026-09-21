'use strict';

const spi = require('spi-device');
const { SPIConnection } = require('../../../src/connection/spi');
const { AD7706Full } = require('../../../src/chips/adc_dac/ad7706');

const SPI_BUS  = parseInt(process.env.SPI_BUS  || '0', 10);
const SPI_DEV  = parseInt(process.env.SPI_DEV  || '0', 10);

const FILTER_DP_THRESHOLD = 0.001;

const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: spi.MODE3, maxSpeedHz: 5_000_000 });    // Create SPI connection, (bus=0, device=0, mode=3, maxSpeedHz=5_000_000) → SPIConnection
const adc = new AD7706Full(connection, 2.5, 2_457_600);                                                // Create AD7706 driver, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → AD7706Full

(async () => {
    // --- Configure all three channels for the HVAC manifold-pressure application ---
    // All three pressure transducers are bridge-type with small mV-level output
    // signals, so the AD7706's high-gain (128) pseudo-differential input is ideal.
    // The shared-COMMON architecture lets all three bridges share a single return
    // line instead of three fully-differential pairs.
    await adc.configure(1, 128, true, true, 50);                                                       // Configure channel 1, (channel=1, gain=128, bipolar=true, buffered=true, output_rate_hz=50) → None
    await adc.configure(2, 128, true, true, 50);                                                       // Configure channel 2, (channel=2, gain=128, bipolar=true, buffered=true, output_rate_hz=50) → None
    await adc.configure(3, 128, true, true, 50);                                                       // Configure channel 3, (channel=3, gain=128, bipolar=true, buffered=true, output_rate_hz=50) → None

    // --- Self-calibrate all three channels before the measurement loop ---
    await adc.selfCalibrate(1);                                                                       // Self-calibrate channel, (channel=1) → None
    await adc.selfCalibrate(2);                                                                       // Self-calibrate channel, (channel=2) → None
    await adc.selfCalibrate(3);                                                                       // Self-calibrate channel, (channel=3) → None

    // --- Sample continuously and compute filter differential pressure ---
    // Channel 1 = filter-inlet static, Channel 2 = filter-outlet static, Channel 3 = duct static.
    // filter_dp = ch1 - ch2 is the filter differential pressure (clog indicator).
    let lastFilterDp = null;
    setInterval(async () => {
        const inlet  = await adc.readVoltageChannel(1);                                                // Read voltage, (channel=1) → float V
        const outlet = await adc.readVoltageChannel(2);                                                // Read voltage, (channel=2) → float V
        const duct   = await adc.readVoltageChannel(3);                                                // Read voltage, (channel=3) → float V
        const filterDp = inlet - outlet;
        if (lastFilterDp === null || Math.abs(filterDp - lastFilterDp) > FILTER_DP_THRESHOLD) {
            console.log(`→ filter_dp=${filterDp.toFixed(4)} V, duct_pressure=${duct.toFixed(4)} V`);
            lastFilterDp = filterDp;
        }
    }, 200);
})();
