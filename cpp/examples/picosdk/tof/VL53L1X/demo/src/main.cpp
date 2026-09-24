#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "VL53L1X.h"

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

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, VL53L1XMinimal::I2C_ADDRESS);
    VL53L1XFull sensor(connection);                         // Create VL53L1X Full driver, (connection)

    // --- Two virtual beams ---
    // Long mode reaches the floor from a 2.5 m ceiling; a 33 ms budget keeps
    // the two zones fast enough to catch a walking person. An 8x16 ROI covers
    // one half of the SPAD array, so alternating the centre alternates beams.
    sensor.setDistanceMode(VL53L1XFull::DistanceMode::Long);  // Set distance mode, (mode) → bool
    sensor.setTimingBudget(33000);                          // Set timing budget, (budgetUs µs) → bool
    sensor.setRoi(8, 16);                                   // Set ROI size, (width SPADs, height SPADs) → bool
    printf("optical centre SPAD %u, zone centres %u/%u\n",
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
    printf("baseline left %.0f mm, right %.0f mm\n", (double)baseline[0], (double)baseline[1]);

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
            printf("IN %d OUT %d (left %u, right %u)\n", countIn, countOut, (unsigned)r[0], (unsigned)r[1]);
            seqLen = 0;
        }

        // --- Watch the light ---
        // Sunlight through an open door raises the ambient rate and eats
        // long-mode range; short mode keeps working up to ~1.3 m.
        if (elapsedMs - lastSnapshot >= SNAPSHOT_MS) {
            lastSnapshot = elapsedMs;
            sensor.distance();                              // Measure distance, () → uint16_t mm
            VL53L1XFull::Measurement m = sensor.readMeasurement();  // Read result block, () → Measurement
            printf("signal %.2f MCPS, ambient %.2f MCPS\n", (double)m.signalRateMcps, (double)m.ambientRateMcps);
            if (m.ambientRateMcps > BRIGHT_MCPS &&
                sensor.distanceMode() == VL53L1XFull::DistanceMode::Long) {  // Read distance mode, () → DistanceMode
                sensor.setDistanceMode(VL53L1XFull::DistanceMode::Short);  // Set distance mode, (mode) → bool
                printf("bright ambient light: switched to short distance mode\n");
            }
        }
    }

    // --- Restore the full field of view ---
    sensor.setRoi(16, 16);                                  // Set ROI size, (width SPADs, height SPADs) → bool
    printf("done: IN %d OUT %d\n", countIn, countOut);
    return 0;
}
