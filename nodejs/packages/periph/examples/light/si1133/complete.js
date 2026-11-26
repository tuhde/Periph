'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { Si1133Full } = require('../../../src/chips/light/si1133');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x55', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x55) → Connection
// Default address 0x55 (AD pulled up); 0x52 with AD pulled down.
const sensor = new Si1133Full(connection);                               // Create Si1133 driver, (connection) → void
// Constructor stores the connection only; no bus traffic yet.

async function main() {
    await sensor.init();                                                 // Check PART_ID, reset, UV + white channels, () → void
    // Waits 25 ms, verifies PART_ID 0x33, RESET_SW, channel 0 = UV, channel 1 = white, 24-bit outputs.

    console.log(await sensor.readId());                                  // Identity registers, () → {partId, hwId, revId}
    // PART_ID is 0x33; HW_ID bits 4:0 are 0x03 for the Si1133-AA00.
    console.log(await sensor.readResponse());                            // RESPONSE0 decoded, () → {state, error, counter}
    // State bits RUNNING/SUSPEND/SLEEP, the CMD_ERR flag and the 4-bit command counter.

    console.log(await sensor.read());                                    // Forced measurement, () → {uv: number counts, white: number counts}
    // FORCE, wait for both channels in IRQ_STATUS, decode two signed 24-bit counts.
    console.log(await sensor.readUv());                                  // UV photodiode once, () → number counts
    // Uses channel 5 as a scratch channel and restores CHAN_LIST afterwards.
    console.log(await sensor.readWhite());                               // White photodiode once, () → number counts
    // Same scratch channel, HW_GAIN 7.
    console.log(await sensor.readIr(Si1133Full.MUX_LARGE_IR));           // IR photodiode once, (photodiode) → number counts
    // Small, medium or large IR photodiode; the small one is the default.

    await sensor.configureChannel(2, Si1133Full.MUX_LARGE_WHITE, { hwGain: 4, swGain: 1, decimRate: 0,
        highSignal: false, bits24: false, postshift: 0 });               // Channel setup, (channel, adcmux, {hwGain, swGain, decimRate, highSignal, bits24, postshift}) → void
    // Writes ADCCONFIG2, ADCSENS2 and ADCPOST2; this channel reports 16-bit unsigned counts.
    await sensor.enableChannels(0x07);                                   // CHAN_LIST, (mask) → void
    // Channels 0, 1 and 2 now run on every FORCE.
    console.log(await sensor.readChannels());                            // Forced read of all channels, (force=true) → number[]
    // Results in channel order: 24-bit signed for channels 0/1, 16-bit for channel 2.
    await sensor.force();                                                // FORCE without waiting, () → void
    // Starts a measurement; the results arrive in HOSTOUT when IRQ_STATUS flags the channels.
    await sleep(100);
    console.log(await sensor.dataReady());                               // IRQ_STATUS mask, () → number
    // Bit c set when channel c finished; this read clears the flags.
    console.log(await sensor.readChannels(false));                       // Decode current results, (force=false) → number[]
    // No new measurement, only HOSTOUT decoded.

    await sensor.setThreshold(0, 2000);                                  // Threshold value, (index, value) → void
    // THRESHOLD0 = 2000 counts (16-bit).
    await sensor.setChannelThreshold(0, 1);                              // Threshold select, (channel, index) → void
    // Channel 0 interrupts only when crossing THRESHOLD0 (index 1); 0 turns it off.
    await sensor.setChannelThreshold(0, 0);
    await sensor.setChannelCounter(2, 2);                                // Counter for a channel, (channel, index) → void
    // Channel 2 uses MEASCOUNT1 in autonomous mode; 0 keeps a channel forced-only.
    await sensor.startAutonomous(0.1, [10, 5, 1]);                       // Autonomous mode, (rate s, counters) → void
    // MEASRATE = 0.1 s; channels 0/1 run every 1 s (MEASCOUNT0 = 10), channel 2 every 0.5 s.
    await sleep(1500);
    console.log(await sensor.pollInterrupt());                           // IRQ_STATUS mask, () → number
    // Same as dataReady; also releases the INT pin.
    await sensor.pause();                                                // PAUSE, () → void
    // Stops the autonomous measurements.
    await sensor.setBurst(4);                                            // Burst mode, (count) → void
    // Four readings per burst at MEASRATE; 0 disables burst mode.
    await sensor.setBurst(0);

    await sensor.writeParameter(0x1E, 3);                                // PARAM_SET, (address, value) → void
    // Direct parameter-table access: MEASCOUNT2 = 3.
    console.log(await sensor.readParameter(0x1E));                       // PARAM_QUERY, (address) → number
    // Reads it back through RESPONSE1.

    await sensor.disableInterrupt(Si1133Full.SOURCE_CHANNEL2);           // IRQ_ENABLE clear, (source) → void
    // Channel 2 no longer drives INT.
    await sensor.enableInterrupt(Si1133Full.SOURCE_CHANNEL2);            // IRQ_ENABLE set, (source) → void
    // Channel 2 drives INT again.
    await sensor.onInterrupt((status) => console.log('irq 0x%s', status.toString(16))); // Subscribe, (callback, intPin=null) → void
    // INT falling edge when a pin is wired, otherwise a 10 ms IRQ_STATUS poll.
    await sensor.readChannels();
    await sleep(100);
    await sensor.offInterrupt();                                         // Unsubscribe, () → void
    // Stops the edge handler or polling timer.

    await sensor.reset();                                                // RESET_SW + defaults, () → void
    // Parameter table back to power-up values, then channel 0 = UV, channel 1 = white again.
    // await sensor.setI2cAddress(0x56);                                 // Change address, (address) → void
    // Volatile; the chip answers on the new address at once, so a new connection is needed.
    await connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
