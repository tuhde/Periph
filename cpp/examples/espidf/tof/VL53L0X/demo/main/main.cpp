#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "VL53L0X.h"

// Touchless presence gate with multi-rate ranging: a first measurement picks
// the profile (long range in a dark room, default otherwise), then timed
// continuous ranging at 100 ms feeds an out-of-window interrupt — closer than
// 10 cm is an ENTER event, the scene clearing beyond 80 cm a LEAVE event.
// After 20 events or 60 s, it prints statistics over 10 fresh samples, stops
// ranging and recalibrates.

static const int MAX_EVENTS = 20;
static const int MAX_MS = 60000;

static volatile bool pending = false;

static void onEvent(uint8_t) { pending = true; }

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags = { .enable_internal_pullup = true },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = VL53L0XMinimal::I2C_ADDRESS,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    VL53L0XFull sensor(connection);                         // Create VL53L0X Full driver, (connection)

    // --- Pick a profile from the ambient light level ---
    // The long-range profile (0.1 MCPS limit, 18/14 PCLK VCSEL periods) reaches
    // ~2 m, but only without IR background; in daylight it mostly adds invalid
    // readings. One single-shot measurement tells us how bright the scene is.
    sensor.distance();                                      // Measure distance, () → uint16_t mm
    VL53L0XFull::Measurement first = sensor.readMeasurement();  // Read result block, () → Measurement
    if (first.ambientRateMcps < 0.5f) {
        sensor.setProfile(VL53L0XFull::Profile::LongRange);  // Apply ranging profile, (profile) → bool
        printf("dark scene (%.2f MCPS ambient): long range profile\n", (double)first.ambientRateMcps);
    } else {
        sensor.setProfile(VL53L0XFull::Profile::Default);   // Apply ranging profile, (profile) → bool
        printf("bright scene (%.2f MCPS ambient): default profile\n", (double)first.ambientRateMcps);
    }

    // --- Arm the presence gate ---
    // Timed ranging every 100 ms keeps the laser mostly idle. The firmware
    // compares each result with the 100 mm / 800 mm window itself and only
    // raises GPIO1 when a reading falls outside it.
    sensor.setInterruptThresholds(100, 800);                // Set distance thresholds, (lowMm mm, highMm mm) → bool
    sensor.enableInterrupt(VL53L0XFull::SOURCE_OUT_OF_WINDOW);  // Select interrupt source, (source) → bool
    sensor.startContinuous(100);                            // Start continuous ranging, (periodMs=0 ms) → void
    sensor.onInterrupt(onEvent);                            // Subscribe to GPIO1, (callback, intPin=nullptr) → void

    // --- Classify each event ---
    // Without a GPIO1 pin on the connection, poll the status instead. The
    // result block still holds the measurement that triggered the event.
    int events = 0;
    for (int elapsed = 0; events < MAX_EVENTS && elapsed < MAX_MS; elapsed += 50) {
        if (!connection.intPin() && sensor.pollInterrupt()) pending = true;  // Read and clear status, () → uint8_t
        if (pending) {
            pending = false;
            uint16_t d = sensor.readMeasurement().distanceMm;  // Read result block, () → Measurement
            printf("%s %u mm\n", d < 100 ? "ENTER" : "LEAVE", (unsigned)d);
            events++;
        }
        vTaskDelay(pdMS_TO_TICKS(50));
    }

    // --- Statistics over fresh samples ---
    // Threshold sources hide ordinary samples from dataReady(), so switch back
    // to new-sample-ready before using the blocking continuous reads.
    sensor.offInterrupt();                                  // Unsubscribe, () → void
    sensor.enableInterrupt(VL53L0XFull::SOURCE_NEW_SAMPLE_READY);  // Select interrupt source, (source) → bool
    sensor.pollInterrupt();                                 // Read and clear status, () → uint8_t
    uint32_t sum = 0;
    uint16_t lo = 0xFFFF, hi = 0;
    float rate = 0.0f;
    for (int i = 0; i < 10; i++) {
        uint16_t d = sensor.readContinuous();               // Read next continuous result, () → uint16_t mm
        rate += sensor.readMeasurement().signalRateMcps;    // Read result block, () → Measurement
        sum += d;
        if (d < lo) lo = d;
        if (d > hi) hi = d;
    }
    printf("mean %lu mm, min %u mm, max %u mm, signal %.2f MCPS\n",
           (unsigned long)(sum / 10), (unsigned)lo, (unsigned)hi, (double)(rate / 10.0f));

    // --- Shut down and recalibrate ---
    // Reference calibration must run in software standby. Repeat it whenever
    // the sensor's temperature has drifted more than 8 °C.
    sensor.stopContinuous();                                // Stop continuous ranging, () → void
    vTaskDelay(pdMS_TO_TICKS(200));
    sensor.recalibrate();                                   // Rerun reference calibration, () → bool
    printf("done\n");
}
