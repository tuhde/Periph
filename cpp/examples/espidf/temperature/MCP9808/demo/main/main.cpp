#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MCP9808.h"

// Industrial freezer monitor: the healthy range is -25 C to -15 C, and
// -5 C means the door has been left open too long. The Alert output fires
// in interrupt mode each time the temperature leaves or re-enters the
// window; each event is reported with the boundary that tripped.

static const int MAX_ALERTS = 10;

static volatile uint8_t pendingStatus = 0;
static volatile bool pending = false;

static void onAlert(uint8_t status) {
    pendingStatus = status;
    pending = true;
}

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = MCP9808Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    MCP9808Full sensor(connection);                         // Create MCP9808 Full driver, (connection)

    // --- Trade resolution for faster sampling ---
    // 0.25 C is plenty for a freezer and converts in ~65 ms instead of 250 ms,
    // so a door opening shows up in the next reading almost immediately.
    sensor.setResolution(0.25f);                            // Set resolution, (celsius 0.5|0.25|0.125|0.0625) → bool

    // --- Program the healthy window and the door-open threshold ---
    // TLOWER/TUPPER bracket normal operation; TCRIT flags a door left open.
    // 3 C of hysteresis stops the Alert chattering while the compressor cycles.
    sensor.setLowerLimit(-25.0f);                           // Set TLOWER, (celsius °C) → void
    sensor.setUpperLimit(-15.0f);                           // Set TUPPER, (celsius °C) → void
    sensor.setCriticalLimit(-5.0f);                         // Set TCRIT, (celsius °C) → void
    sensor.setHysteresis(3.0f);                             // Set hysteresis, (celsius 0|1.5|3.0|6.0) → bool

    // --- Route every boundary to the Alert pin as a latched interrupt ---
    // Interrupt mode latches each crossing until clearInterrupt(), so a short
    // excursion is never missed between two reads.
    sensor.configureAlert(MCP9808Full::AlertMode::All,
                          MCP9808Full::AlertOutput::Interrupt,
                          MCP9808Full::AlertPolarity::ActiveLow);  // Configure Alert, (mode=All, output=Comparator, polarity=ActiveLow) → bool
    sensor.enableAlert();                                   // Enable Alert output, () → void
    sensor.onInterrupt(onAlert);                            // Subscribe to Alert, (callback, intPin=nullptr) → void
    printf("monitoring, %.2f C now\n", (double)sensor.readTemperature());  // Read ambient temperature, () → °C

    // --- Report which boundary tripped, then re-arm ---
    // Without an Alert pin on the connection, poll ALERT_STAT instead. An empty
    // status mask means the temperature has come back inside the healthy window.
    int alerts = 0;
    while (alerts < MAX_ALERTS) {
        if (!connection.intPin() && sensor.isAlertAsserted()) {  // Check Alert output, () → bool
            pendingStatus = sensor.pollInterrupt();         // Read boundary status, () → uint8_t mask
            pending = true;
        }
        if (pending) {
            pending = false;
            uint8_t status = pendingStatus;
            float t = sensor.readTemperature();             // Read ambient temperature, () → °C
            if (status & MCP9808Full::SOURCE_CRITICAL)   printf("%.2f C  CRITICAL - door open?\n", (double)t);
            else if (status & MCP9808Full::SOURCE_UPPER) printf("%.2f C  too warm\n", (double)t);
            else if (status & MCP9808Full::SOURCE_LOWER) printf("%.2f C  too cold\n", (double)t);
            else                                         printf("%.2f C  back in range\n", (double)t);
            sensor.clearInterrupt();                        // Clear interrupt-mode Alert, () → void
            alerts++;
        }
        vTaskDelay(pdMS_TO_TICKS(1000));
    }

    // --- Shut down cleanly after the demo run ---
    sensor.disableAlert();                                  // Disable Alert output, () → void
    sensor.offInterrupt();                                  // Unsubscribe, () → void
}
