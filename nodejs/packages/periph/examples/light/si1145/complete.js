'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { Si1145Full } = require('../../../src/chips/light/si1145');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x60', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);                 // Create I²C connection, (bus=1, addr=0x60) → Connection
// The Si1145 has the fixed address 0x60 (older silicon: 0x5A).
const sensor = new Si1145Full(connection);                               // Create Si1145 driver, (connection) → void
// Constructor stores the connection only; no bus traffic yet.

async function main() {
    await sensor.init();                                                 // Check PART_ID, HW_KEY, UV + ALS + PS1, () → void
    // Waits 25 ms, verifies PART_ID 0x45/0x46/0x47, writes HW_KEY 0x17, UCOEF, CHLIST 0xB1, LED1 level 3.

    console.log(await sensor.readId());                                  // Identity registers, () → {partId, revId, seqId}
    // PART_ID 0x45 for the Si1145; SEQ_ID 0x01 parts swap the MEAS_RATE bytes.
    console.log(await sensor.readResponse());                            // RESPONSE decoded, () → {counter, error}
    // 4-bit command counter and the error code (0 = none, 0x8x = ADC overflow / invalid setting).

    console.log(await sensor.read());                                    // Forced PS + ALS, () → {proximity, uv_index, visible, infrared}
    // PSALS_FORCE, waits for ALS_INT and PS1_INT; raw counts and the UV index (AUX_DATA / 100).
    console.log(await sensor.readProximity(1));                          // Forced PS, (channel=1) → number counts
    // PS1 only; PS2/PS3 exist on the Si1146/47.
    console.log(await sensor.readUvIndex());                             // Forced ALS, () → number UV index
    // UV index with the default UV coefficients 7B 6B 01 00.
    console.log(await sensor.readVisible());                             // Forced ALS, () → number counts
    // Raw visible ambient light.
    console.log(await sensor.readInfrared());                            // Forced ALS, () → number counts
    // Raw infrared ambient light.
    console.log(await sensor.readRaw());                                 // Data registers, () → {visible, infrared, ps1, ps2, ps3, aux}
    // No new measurement; the latest values of 0x22–0x2D.
    console.log(await sensor.readTemperatureRaw());                      // AUX temperature, () → number counts
    // Relative die temperature; CHLIST is switched to EN_AUX and restored.
    console.log(await sensor.readVddRaw());                              // AUX VDD, () → number counts
    // Supply voltage on the auxiliary channel.

    await sensor.setChannels(true, true, true, true);                    // CHLIST, (uv, visible, infrared, ps1, ps2=false, ps3=false, aux=false) → void
    // UV and AUX share AUX_DATA, so only one of them can be enabled.
    await sensor.setLedCurrent(1, 8);                                    // LED drive, (led, level) → void
    // Level 0 = off … 15 = maximum (~359 mA); keep the gain low at high currents.
    await sensor.setPsLed(1, 0x01);                                      // PS LED select, (channel, leds) → void
    // PS1 drives LED1; the Si1145 has no LED2/LED3.
    await sensor.setPsPhotodiode(1, true);                               // PS photodiode, (channel, large) → void
    // Large IR photodiode (0x03); false selects the small one.
    await sensor.setGain(0, 0, 0);                                       // ADC gains, (visible=0, infrared=0, proximity=0) → void
    // Integration time × 2^n, recovery counts at the one's complement of the gain.
    await sensor.setHighRange(false, false, false);                      // Signal range, (visible, infrared, proximity) → void
    // true divides the sensitivity by 14.5 (direct sunlight).
    await sensor.setAlignment();                                         // Result alignment, ({visible, infrared, ps1, ps2, ps3}) → void
    // true reports the 16 LSBs instead of the 16 MSBs of the ADC result.
    await sensor.setUvCoefficients(Si1145Full.UCOEF_DEFAULT);            // UCOEF0..3, (ucoef) → void
    // 7B 6B 01 00 make AUX_DATA the UV index × 100.
    console.log(await sensor.readCalibration());                         // GET_CAL, () → number[12]
    // Factory calibration bytes; overwrites the data registers.

    await sensor.pollInterrupt();                                        // IRQ_STATUS, () → number sources
    // Reads and clears pending flags.
    await sensor.startAutonomous(0.1);                                   // Autonomous mode, (interval s, proximity=true, als=true) → void
    // MEAS_RATE = 0.1 s / 31.25 µs = 3200, then PSALS_AUTO.
    await sleep(500);
    console.log(await sensor.pollInterrupt());                           // IRQ_STATUS, () → number sources
    // SOURCE_ALS | SOURCE_PS1 when both finished at least once.
    await sensor.pause();                                                // PSALS_PAUSE, () → void
    // Stops the autonomous loop.
    await sensor.force(true, false);                                     // PS_FORCE without waiting, (proximity=true, als=true) → void
    // The result arrives when IRQ_STATUS flags PS1.
    await sleep(50);
    console.log((await sensor.readRaw()).ps1);

    const events = [];
    await sensor.enableInterrupt(Si1145Full.SOURCE_ALS | Si1145Full.SOURCE_PS1); // INT_CFG + IRQ_ENABLE, (source) → void
    // INT pin (active low) driven for ALS and PS1 completions.
    await sensor.onInterrupt((s) => events.push(s));                     // Subscribe, (callback, intPin=null) → void
    // Falling edge on the connection's intPin, otherwise a 10 ms polling timer.
    await sensor.force();                                                // PSALS_FORCE without waiting, () → void
    await sleep(200);
    await sensor.offInterrupt();                                         // Unsubscribe, () → void
    console.log(events);
    await sensor.disableInterrupt(Si1145Full.SOURCE_CMD);                // IRQ_ENABLE clear, (source) → void
    // CMD_INT is not used by default.

    await sensor.setI2cAddress(0x60);                                    // I2C_ADDR + BUSADDR, (address) → void
    // Volatile; here the address stays 0x60, otherwise reconnect on the new address.
    await sensor.reset();                                                // RESET + HW_KEY + defaults, () → void
    // Software reset, 10 ms wait, then the Minimal configuration again.
    await connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
