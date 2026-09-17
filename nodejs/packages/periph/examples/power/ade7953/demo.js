'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { ADE7953Full } = require('../../../src/chips/power/ade7953');

const connection = new I2CConnection(1, 0x38);
const ade = new ADE7953Full(connection, 251.0, 30.0);             // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V, bus_type='i2c')

// --- Prepare the chip: enable overcurrent interrupt and pin it to IRQ ---
// The ADE7953 exposes power-quality events via the IRQ pin. Driving
// OIA through the chip's own alert output lets the host react without
// polling every reading every cycle.
ade.configureOvercurrent(40.0);                                     // Configure overcurrent, (threshold) → None

// --- Sample at 1 Hz and emit one structured line per cycle ---
// The energy accumulator resets on read by default (RSTREAD = 1), so
// activeEnergy() returns watt-hours accumulated since the previous
// call. Callers wanting a running total accumulate the returned deltas
// themselves (or call setReadWithReset(false) and track the 24-bit
// register's own rollovers instead).
console.log('%-10s %-10s %-10s %-12s'.replace(/%-?\d+s/g, (m) => m.padEnd(12)));
setInterval(async () => {
    const v = await ade.voltage();                                  // Read bus voltage, () → float V
    const i = await ade.current();                                  // Read load current, () → float A
    const p = await ade.activePower();                              // Read active power, () → float W
    const e = await ade.activeEnergy();                             // Read active energy, () → float Wh
    console.log(`${v.toFixed(2).padEnd(10)} ${i.toFixed(3).padEnd(10)} ${p.toFixed(2).padEnd(10)} ${e.toFixed(5).padEnd(12)}`);
}, 1000);