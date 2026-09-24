#include <stdarg.h>
#include <Wire.h>
#include <Periph.h>

static void logPrintf(const char* fmt, ...) {
    char buf[128];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    Serial.print(buf);
}

// Long-range doorway people counter with a split ROI: the sensor hangs
// overhead (up to 2.5 m); two 8x16 ROIs (SPADs 167 and 231, the half-array
// centres used by ST's people-counting code) form two virtual beams, and the
// order in which they are blocked tells IN from OUT.

static const uint8_t ZONE_CENTRES[2] = { 167, 231 };   // left, right
static const uint16_t OCCUPIED_MM = 300;
static const int MAX_EVENTS = 50;
static const uint32_t MAX_MS = 60000;
static const uint32_t SNAPSHOT_MS = 30000;
static const float BRIGHT_MCPS = 5.0f;

static uint32_t elapsedMs = 0;

static bool measure(VL53L1XFull& sensor, int zone, uint16_t& mm) {
    sensor.setRoiCenter(ZONE_CENTRES[zone]);                // Set ROI centre, (spad) → void
    mm = sensor.distance();                                 // Measure distance, () → uint16_t mm
    elapsedMs += 33;
    return sensor.rangeValid();                             // Check last measurement, () → bool
}

void setup() {
    Serial.begin(115200);
    Wire.begin();
    I2CConnection connection(Wire, VL53L1XMinimal::I2C_ADDRESS);
    VL53L1XFull sensor(connection);                         // Create VL53L1X Full driver, (connection)

    // --- Two virtual beams ---
    // Long mode reaches the floor from a 2.5 m ceiling; a 33 ms budget keeps
    // the two zones fast enough to catch a walking person. An 8x16 ROI covers
    // one half of the SPAD array, so alternating the centre alternates beams.
    sensor.setDistanceMode(VL53L1XFull::DistanceMode::Long);  // Set distance mode, (mode) → bool
    sensor.setTimingBudget(33000);                          // Set timing budget, (budgetUs µs) → bool
    sensor.setRoi(8, 16);                                   // Set ROI size, (width SPADs, height SPADs) → bool
    logPrintf("optical centre SPAD %u, zone centres %u/%u\n",
           (unsigned)sensor.opticalCenter(), (unsigned)ZONE_CENTRES[0], (unsigned)ZONE_CENTRES[1]);  // Read optical-centre SPAD, () → uint8_t

    // --- Learn the empty doorway ---
    // Twenty readings per zone give the floor distance each beam sees when
    // nobody is there; invalid readings are ignored.
    float baseline[2];
    for (int zone = 0; zone < 2; zone++) {
        uint32_t sum = 0;
        int n = 0;
        for (int i = 0; i < 20; i++) {
            uint16_t mm;
            if (measure(sensor, zone, mm)) { sum += mm; n++; }
        }
        baseline[zone] = n ? (float)sum / n : 4000.0f;
    }
    logPrintf("baseline left %.0f mm, right %.0f mm\n", (double)baseline[0], (double)baseline[1]);

    // --- Count crossings ---
    // A person entering blocks the left beam first, then the right one (and
    // the reverse when leaving). Once both beams clear, the recorded order
    // decides the direction.
    int countIn = 0, countOut = 0, events = 0;
    int sequence[2];
    int seqLen = 0;
    uint32_t lastSnapshot = 0;
    while (events < MAX_EVENTS && elapsedMs < MAX_MS) {
        uint16_t r[2];
        bool occupied[2];
        for (int zone = 0; zone < 2; zone++) {
            bool valid = measure(sensor, zone, r[zone]);
            occupied[zone] = valid && r[zone] < baseline[zone] - OCCUPIED_MM;
            if (occupied[zone] && seqLen < 2 && (seqLen == 0 || sequence[0] != zone)) {
                sequence[seqLen++] = zone;
            }
        }
        if (!occupied[0] && !occupied[1] && seqLen > 0) {
            if (seqLen == 2 && sequence[0] == 0) countIn++;
            else if (seqLen == 2 && sequence[0] == 1) countOut++;
            events++;
            logPrintf("IN %d OUT %d (left %u, right %u)\n", countIn, countOut, (unsigned)r[0], (unsigned)r[1]);
            seqLen = 0;
        }

        // --- Watch the light ---
        // Sunlight through an open door raises the ambient rate and eats
        // long-mode range; short mode keeps working up to ~1.3 m.
        if (elapsedMs - lastSnapshot >= SNAPSHOT_MS) {
            lastSnapshot = elapsedMs;
            sensor.distance();                              // Measure distance, () → uint16_t mm
            VL53L1XFull::Measurement m = sensor.readMeasurement();  // Read result block, () → Measurement
            logPrintf("signal %.2f MCPS, ambient %.2f MCPS\n", (double)m.signalRateMcps, (double)m.ambientRateMcps);
            if (m.ambientRateMcps > BRIGHT_MCPS &&
                sensor.distanceMode() == VL53L1XFull::DistanceMode::Long) {  // Read distance mode, () → DistanceMode
                sensor.setDistanceMode(VL53L1XFull::DistanceMode::Short);  // Set distance mode, (mode) → bool
                logPrintf("bright ambient light: switched to short distance mode\n");
            }
        }
    }

    // --- Restore the full field of view ---
    sensor.setRoi(16, 16);                                  // Set ROI size, (width SPADs, height SPADs) → bool
    logPrintf("done: IN %d OUT %d\n", countIn, countOut);
}

void loop() {}
