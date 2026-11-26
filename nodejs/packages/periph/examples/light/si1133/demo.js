'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { Si1133Full } = require('../../../src/chips/light/si1133');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x55', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x55) → Connection
const sensor = new Si1133Full(connection);                               // Create Si1133 driver, (connection) → void
const hex = (v) => '0x' + v.toString(16).padStart(2, '0');

async function main() {
    await sensor.init();                                                 // Check PART_ID, reset, UV + white channels, () → void

    // --- Identity ---
    // PART_ID 0x33 identifies the Si1133; HW_ID and REV_ID give silicon and
    // product revision. RESPONSE0 shows the sequencer state and command counter.
    const id = await sensor.readId();                                    // Identity registers, () → {partId, hwId, revId}
    console.log('PART_ID %s  HW_ID %s  REV_ID %s', hex(id.partId), hex(id.hwId), hex(id.revId));
    console.log('response', await sensor.readResponse());                // RESPONSE0 decoded, () → {state, error, counter}

    // --- UV and daylight monitor: 20 s at 1 Hz ---
    // The counts are raw ADC values; a UV index needs Silicon Labs' calibration
    // for the product's window, so watch the trend.
    for (let i = 0; i < 20; i++) {
        const m = await sensor.read();                                   // Forced measurement, () → {uv: number counts, white: number counts}
        console.log('%d s  UV %d  white %d', i, m.uv, m.white);
        await sleep(1000);
    }

    // --- Gain settings on a constant light level ---
    // HW_GAIN doubles the integration time per step, SW_GAIN accumulates 2^n
    // samples, HIGH_SIGNAL divides the sensitivity by 14.5 for direct sunlight.
    await sensor.enableChannels(0x04);                                   // CHAN_LIST, (mask) → void
    for (const [hwGain, swGain, highSignal] of [[2, 0, false], [6, 0, false], [6, 2, false], [6, 0, true]]) {
        await sensor.configureChannel(2, Si1133Full.MUX_WHITE, { hwGain, swGain, highSignal }); // Channel setup, (channel, adcmux, {hwGain, swGain, highSignal}) → void
        const [value] = await sensor.readChannels();                     // Forced read, () → number[]
        console.log('hwGain %d swGain %d highSignal %s -> %d', hwGain, swGain, highSignal, value);
    }

    // --- IR photodiodes ---
    for (const [name, mux] of [['small', Si1133Full.MUX_SMALL_IR], ['medium', Si1133Full.MUX_MEDIUM_IR],
        ['large', Si1133Full.MUX_LARGE_IR]]) {
        console.log('IR %s %d', name, await sensor.readIr(mux));         // IR photodiode once, (photodiode) → number counts
    }
    console.log('UV %d, white %d', await sensor.readUv(), await sensor.readWhite()); // Scratch-channel reads, () → number counts

    // --- Autonomous mode with a UV threshold ---
    // Channels 0 and 1 run every second; channel 0 only interrupts when crossing
    // THRESHOLD0, channel 1 on every result.
    await sensor.configureChannel(0, Si1133Full.MUX_UV, { hwGain: 9 });  // Channel setup, (channel, adcmux, {hwGain}) → void
    await sensor.enableChannels(0x03);                                   // CHAN_LIST, (mask) → void
    await sensor.setThreshold(0, 500);                                   // Threshold value, (index, value) → void
    await sensor.setChannelThreshold(0, 1);                              // Threshold select, (channel, index) → void
    await sensor.setChannelCounter(1, 1);                                // Counter for a channel, (channel, index) → void
    await sensor.disableInterrupt(Si1133Full.SOURCE_CHANNEL1);           // IRQ_ENABLE clear, (source) → void
    await sensor.enableInterrupt(Si1133Full.SOURCE_CHANNEL0);            // IRQ_ENABLE set, (source) → void
    await sensor.onInterrupt(async (status) => {                         // Subscribe, (callback, intPin=null) → void
        console.log('interrupt %s values %j', hex(status), await sensor.readChannels(false));
    });
    await sensor.startAutonomous(0.1, [10, 10, 10]);                     // Autonomous mode, (rate s, counters) → void
    await sleep(5000);
    await sensor.offInterrupt();                                         // Unsubscribe, () → void
    await sensor.pause();                                                // PAUSE, () → void
    await sensor.enableInterrupt(Si1133Full.SOURCE_CHANNEL1);            // IRQ_ENABLE set, (source) → void

    // --- Burst mode: four readings in a row ---
    await sensor.setBurst(4);                                            // Burst mode, (count) → void
    await sensor.force();                                                // FORCE without waiting, () → void
    await sleep(500);
    console.log('burst ready %s', hex(await sensor.dataReady()));        // IRQ_STATUS mask, () → number
    console.log('burst values %j', await sensor.readChannels(false));    // Decode current results, (force=false) → number[]
    await sensor.setBurst(0);

    // --- Parameter table ---
    await sensor.writeParameter(0x1E, 7);                                // PARAM_SET, (address, value) → void
    const chan = await sensor.readParameter(0x01);                       // PARAM_QUERY, (address) → number
    const rate = ((await sensor.readParameter(0x1A)) << 8) | (await sensor.readParameter(0x1B));
    console.log('CHAN_LIST %s  MEASRATE %d  MEASCOUNT2 %d', hex(chan), rate, await sensor.readParameter(0x1E));
    console.log('poll', await sensor.pollInterrupt());                   // IRQ_STATUS mask, () → number

    // --- Finish ---
    // setI2cAddress would move the chip to another address until the next
    // reset; the demo keeps 0x55 and only shows the call.
    // await sensor.setI2cAddress(0x56);                                 // Change address, (address) → void
    await sensor.reset();                                                // RESET_SW + defaults, () → void
    console.log('after reset', await sensor.read());                     // Forced measurement, () → {uv, white}
    await connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
