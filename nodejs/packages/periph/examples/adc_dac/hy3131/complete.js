'use strict';

const spi = require('spi-device');
const { SPIConnection } = require('../../../src/connection/spi');
const { HY3131Full } = require('../../../src/chips/adc_dac/hy3131');

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);

(async () => {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: spi.MODE0, maxSpeedHz: 1_000_000 });
    const adc = await HY3131Full.create(connection);                                                       // Create and initialise HY3131 driver, (connection, vref=1.2 V) → HY3131Full
                                                                                                           // resets the chip and applies the Minimal defaults (AD1 on PB2/AGND, 5 Hz)

    await adc.configureAd1({ osr: 4, chop: 2, inputGain: 0, refGain: 0, offset: 0, buffers: 0 });         // Configure AD1, ({osr=7, chop=0, inputGain=0, refGain=0, offset=0, buffers=0}) → None
                                                                                                           // OSR 4096 (~40 Hz), chopper on (offset cancelled, rate halved), gain 0.9
    await adc.setAd1Input(10, 3, 2);                                                                       // Select AD1 inputs, (pos=SAD1FP 0–15, neg=SAD1FN 0–7, filterR=2) → None
                                                                                                           // PB2 vs AGND with the filter resistor bypassed
    await adc.setAd1Reference(1, 1);                                                                       // Select AD1 reference, (high=0–6, low=0–6) → None
                                                                                                           // REFO (+) / AGND (−)

    const raw = await adc.readRaw();                                                                       // Read AD1 code, () → int 24-bit
                                                                                                           // waits for AD1F in INTF, returns the sign-extended code
    const v = await adc.readVoltage();                                                                     // Read AD1 voltage, () → float V
                                                                                                           // code / 2^23 · RG − DCOS, scaled by vref / input gain
    console.log('raw=', raw, 'v=', v);

    const tcode = await adc.readTemperatureCode();                                                         // Read temperature code, () → int
                                                                                                           // (AD1[TS pair 2] − AD1[TS pair 1]) / 2; note this value at 25 °C
    const t = await adc.readTemperature(tcode);                                                            // Read die temperature, (tcode25) → float °C
                                                                                                           // one-point calibrated against tcode25
    console.log('temperature=', t);

    await adc.enableAd2({ osr: 5, inputGain: 1, refGain: 0, chop: false, clkDiv4: false, source: 0, useAd3: false }); // Enable AD2, ({osr=5, inputGain=1, refGain=0, chop=false, clkDiv4=false, source=0, useAd3=false}) → None
                                                                                                           // AD2 samples OP1O at gain 1.0, OSR 1024
    const ad2 = await adc.readAd2Raw();                                                                    // Read AD2 code, () → int 19-bit
                                                                                                           // waits for AD2F
    await adc.enableLpf(0);                                                                                // Enable low-pass filter, (bw=0) → None
                                                                                                           // OSR4 = 128
    const lpf = await adc.readLpfRaw();                                                                    // Read LPF code, () → int 19-bit
                                                                                                           // waits for LPFF
    await adc.enableRms();                                                                                 // Enable RMS converter, () → None
                                                                                                           // accumulates the squared LPF input
    const rms = await adc.readRms();                                                                       // Read RMS, () → float LSB
                                                                                                           // square root of the accumulator
    await adc.enablePeakHold(0);                                                                           // Enable peak hold, (source=0) → None
                                                                                                           // tracks AD2 min/max; re-enabling resets
    const pk = await adc.readPeak();                                                                       // Read peak hold, () → {min, max} 19-bit
    console.log('ad2=', ad2, 'lpf=', lpf, 'rms=', rms, 'peak=', pk.min, pk.max);

    await adc.startCounter(0);                                                                             // Start frequency counter, (gatePreload=0) → None
                                                                                                           // enables the CNT buffer and the counter
    const ct = await adc.readCounter();                                                                    // Read counter, () → {cta, ctb, ctc, overflow}
                                                                                                           // waits for CTF; overflow=true means the measurement is invalid
    console.log('counter=', ct);

    await adc.enableInterrupts(0x04);                                                                      // Enable interrupts, (mask) → None
                                                                                                           // AD1IE: DO goes high while CS is high and AD1F is set
    const flags = await adc.readFlags();                                                                   // Read and clear INTF, () → int
                                                                                                           // bit 2 = AD1F
    console.log('flags=', flags);

    await adc.reset();                                                                                     // Software reset, () → None
                                                                                                           // writes 0x60 to R37 and re-applies the Minimal defaults
    await connection.close();
})();
