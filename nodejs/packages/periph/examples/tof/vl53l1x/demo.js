'use strict';

// Long-range doorway people counter with a split ROI. The sensor hangs
// overhead in a doorway (up to 2.5 m). Two narrow 8x16 ROIs (centre SPADs 167
// and 231, the left/right half-array centres used by ST's own people-counting
// code) form two virtual beams. Each zone learns its floor distance, then
// counts as occupied when something is more than 300 mm closer; the order in
// which the zones become occupied tells IN from OUT. Every 30 s a
// signal/ambient snapshot is printed and strong sunlight switches to short
// distance mode. After 60 s or 50 events the full ROI is restored.

const { I2CConnection } = require('periph/src/connection/i2c');
const { VL53L1XFull } = require('periph/src/chips/tof/vl53l1x');

const ZONE_CENTRES = [167, 231];   // left, right
const OCCUPIED_MM = 300;
const MAX_EVENTS = 50;
const MAX_MS = 60000;
const SNAPSHOT_MS = 30000;
const BRIGHT_MCPS = 5.0;

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, VL53L1XFull.I2C_ADDRESS);
    const sensor = new VL53L1XFull(connection);                                    // Create VL53L1X Full driver, (connection)
    await sensor.init();                                                           // Wait for init sequence, () → None

    // --- Two virtual beams ---
    // Long mode reaches the floor from a 2.5 m ceiling; a 33 ms budget keeps
    // the two zones fast enough to catch a walking person. An 8x16 ROI covers
    // one half of the SPAD array, so alternating the centre alternates beams.
    await sensor.setDistanceMode('long');                                          // Set distance mode, (mode) → None
    await sensor.setTimingBudget(33000);                                           // Set timing budget, (budgetUs µs) → None
    await sensor.setRoi(8, 16);                                                    // Set ROI size, (width SPADs, height SPADs) → None
    console.log('optical centre SPAD', await sensor.opticalCenter(), '- zone centres', ZONE_CENTRES);  // Read optical-centre SPAD, () → number

    const measure = async (zone) => {
        await sensor.setRoiCenter(ZONE_CENTRES[zone]);                             // Set ROI centre, (spad) → None
        const d = await sensor.distance();                                         // Measure distance, () → number mm
        return (await sensor.rangeValid()) ? d : null;                             // Check last measurement, () → boolean
    };

    // --- Learn the empty doorway ---
    // Twenty readings per zone give the floor distance each beam sees when
    // nobody is there; invalid readings are ignored.
    const baseline = [];
    for (const zone of [0, 1]) {
        const values = [];
        for (let i = 0; i < 20; i++) {
            const v = await measure(zone);
            if (v !== null) values.push(v);
        }
        baseline.push(values.length ? values.reduce((a, b) => a + b, 0) / values.length : 4000);
    }
    console.log(`baseline left ${baseline[0].toFixed(0)} mm, right ${baseline[1].toFixed(0)} mm`);

    // --- Count crossings ---
    // A person entering blocks the left beam first, then the right one (and
    // the reverse when leaving). Once both beams clear, the recorded order
    // decides the direction.
    let countIn = 0;
    let countOut = 0;
    let events = 0;
    let sequence = [];
    const start = Date.now();
    let lastSnapshot = start;
    while (events < MAX_EVENTS && Date.now() - start < MAX_MS) {
        const readings = [await measure(0), await measure(1)];
        const occupied = readings.map((r, z) => r !== null && r < baseline[z] - OCCUPIED_MM);
        for (const zone of [0, 1]) {
            if (occupied[zone] && !sequence.includes(zone)) sequence.push(zone);
        }
        if (!occupied[0] && !occupied[1] && sequence.length) {
            if (sequence[0] === 0 && sequence[1] === 1) countIn++;
            else if (sequence[0] === 1 && sequence[1] === 0) countOut++;
            events++;
            console.log(`IN ${countIn} OUT ${countOut} (left ${readings[0]}, right ${readings[1]})`);
            sequence = [];
        }

        // --- Watch the light ---
        // Sunlight through an open door raises the ambient rate and eats
        // long-mode range; short mode keeps working up to ~1.3 m.
        if (Date.now() - lastSnapshot >= SNAPSHOT_MS) {
            lastSnapshot = Date.now();
            await sensor.distance();                                               // Measure distance, () → number mm
            const m = await sensor.readMeasurement();                              // Read result block, () → {distanceMm, …}
            console.log(`signal ${m.signalRateMcps.toFixed(2)} MCPS, ambient ${m.ambientRateMcps.toFixed(2)} MCPS`);
            if (m.ambientRateMcps > BRIGHT_MCPS && (await sensor.distanceMode()) === 'long') {  // Read distance mode, () → string
                await sensor.setDistanceMode('short');                             // Set distance mode, (mode) → None
                console.log('bright ambient light: switched to short distance mode');
            }
        }
    }

    // --- Restore the full field of view ---
    await sensor.setRoi(16, 16);                                                   // Set ROI size, (width SPADs, height SPADs) → None
    console.log(`done: IN ${countIn} OUT ${countOut}`);
    await connection.close();
})();
