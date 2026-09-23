#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "TMP117.h"

// PT100-replacement cold-chain container thermometer: maximum averaging gives
// the lowest-noise reading, and the ALERT output fires when the cargo leaves
// the -25 C to 8 C safe transport range. Each event is reported with the
// boundary that tripped. Set REFERENCE_C to a reference thermometer reading to
// calibrate once and persist the offset to EEPROM.

static const int MAX_ALERTS = 10;
static const bool CALIBRATE = false;
static const float REFERENCE_C = 4.0f;

static volatile uint8_t pendingStatus = 0;
static volatile bool pending = false;

static void onAlert(uint8_t status) {
    pendingStatus = status;
    pending = true;
}

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
        .device_address  = TMP117Minimal::I2C_ADDRESS,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    TMP117Full sensor(connection);                          // Create TMP117 Full driver, (connection)

    // --- Lowest-noise continuous conversion ---
    // 64-conversion averaging with a 1 s cycle gives the quietest result the
    // chip can deliver — a cold-chain log needs stability, not speed.
    sensor.configure(TMP117Full::Mode::Continuous, 64, 1.0f);  // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool

    // --- One-time calibration against a reference thermometer ---
    // The observed error is written to TEMP_OFFSET with the EEPROM unlocked, so
    // the correction survives power cycles. EEPROM endurance is limited — this
    // is a once-per-deployment step, not a loop.
    if (CALIBRATE) {
        vTaskDelay(pdMS_TO_TICKS(1100));
        float offset = REFERENCE_C - sensor.readTemperature()  // Read temperature, () → °C
                       + sensor.getTemperatureOffset();     // Read calibration offset, () → °C
        sensor.unlockEeprom();                              // Unlock EEPROM, () → void
        sensor.setTemperatureOffset(offset);                // Set calibration offset, (celsius °C) → void
        while (sensor.isEepromBusy()) {                     // Check EEPROM busy, () → bool
            vTaskDelay(pdMS_TO_TICKS(1));
        }
        sensor.lockEeprom();                                // Lock EEPROM, () → void
        printf("calibrated, offset %.4f C\n", (double)offset);
    }

    // --- Program the safe transport range ---
    // Alert mode flags either side of the window independently; ALERT is
    // active-low open-drain, pulled up on the board.
    sensor.setHighLimit(8.0f);                              // Set THIGH_LIMIT, (celsius °C) → void
    sensor.setLowLimit(-25.0f);                             // Set TLOW_LIMIT, (celsius °C) → void
    sensor.configureAlert(TMP117Full::AlertMode::Alert,
                          TMP117Full::AlertPolarity::ActiveLow);  // Configure ALERT, (mode=Alert, polarity=ActiveLow, pinFunction=Alert) → void
    sensor.onInterrupt(onAlert);                            // Subscribe to ALERT, (callback, intPin=nullptr) → void
    printf("monitoring, %.2f C now\n", (double)sensor.readTemperature());  // Read temperature, () → °C

    // --- Report which boundary tripped ---
    // Without an ALERT pin on the connection, poll the alert flags instead.
    // Reading them clears them in Alert mode, re-arming for the next excursion.
    int alerts = 0;
    while (alerts < MAX_ALERTS) {
        if (!connection.intPin()) {
            uint8_t status = sensor.pollInterrupt();        // Read alert flags, () → uint8_t mask
            if (status) {
                pendingStatus = status;
                pending = true;
            }
        }
        if (pending) {
            pending = false;
            uint8_t status = pendingStatus;
            float t = sensor.readTemperature();             // Read temperature, () → °C
            if (status & TMP117Full::SOURCE_HIGH)     printf("%.2f C  too warm - cargo above 8 C\n", (double)t);
            else if (status & TMP117Full::SOURCE_LOW) printf("%.2f C  too cold - cargo below -25 C\n", (double)t);
            alerts++;
        }
        vTaskDelay(pdMS_TO_TICKS(1000));
    }

    // --- Stop monitoring after the demo run ---
    sensor.offInterrupt();                                  // Unsubscribe, () → void
}
