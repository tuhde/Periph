#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MAX44009.h"

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
    dev_cfg.device_address = MAX44009Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    MAX44009Full sensor(connection);                    // Create MAX44009 Full driver, (connection)
    float lx = sensor.lux();                            // Read illuminance, () → float lux
    // 2^E × M × 0.045; 188006.4 on overrange
    printf("lux %.2f\n", (double)lx);
    uint8_t e = 0, m = 0;
    bool over = sensor.readRaw(e, m);                   // Read exponent and mantissa, (exponent&, mantissa&) → bool overrange
    // one consistent sample: LUX_HIGH, LUX_LOW, LUX_HIGH
    printf("E=%d M=%d overrange=%d\n", (int)e, (int)m, (int)over);
    bool last = sensor.isOverrange();                   // Query the last reading, () → bool
    // true if the last exponent field was 15
    float res = sensor.resolution();                    // Lux per LSB, () → float lux
    // 2^E × 0.045 for the last reading
    printf("overrange %d resolution %.3f\n", (int)last, (double)res);
    sensor.setContinuous(true);                         // Select measurement mode, (continuous bool) → void
    // true: a new measurement as soon as one finishes
    sensor.setManual(100.0f, false);                    // Select manual range, (integrationMs, divideBy8=false) → void
    // nearest of 800/400/200/100/50/25/12.5/6.25 ms
    MAX44009Full::Config cfg = sensor.getConfig();      // Read CONFIG, () → Config
    // {continuous, manual, divideBy8, integrationMs}
    printf("manual %d integration %.2f ms\n", (int)cfg.manual, (double)cfg.integrationMs);
    sensor.setAutomatic();                              // Select automatic range, () → void
    // the chip chooses the current divider and integration time
    sensor.setContinuous(false);                        // Back to one measurement per 800 ms, (continuous bool) → void
    bool ok = sensor.setThresholds(50.0f, 500.0f);      // Set the interrupt window, (lowLux, highLux) → bool
    // 4 mantissa MSBs: about 1/16 to 1/8 of the value
    float lo = 0, hi = 0;
    sensor.getThresholds(lo, hi);                       // Read the window, (lowLux&, highLux&) → void
    printf("window %.2f .. %.2f lx (set %d)\n", (double)lo, (double)hi, (int)ok);
    sensor.setThresholdDelay(2.0f);                     // Set the threshold delay, (seconds) → void
    // 0–25.5 s in 0.1 s steps
    float delay = sensor.getThresholdDelay();           // Read the delay, () → float s
    printf("delay %.1f s\n", (double)delay);
    sensor.enableInterrupt();                           // Enable the interrupt, () → void
    // INT_ENABLE = 1
    sensor.onInterrupt([](uint8_t status) { (void)status; });  // Subscribe, (callback(status), intPin=nullptr) → void
    // falling edge on INT; call service() periodically without a pin
    sensor.service();                                   // Poll once and dispatch the callback, () → void
    uint8_t status = sensor.pollInterrupt();            // Read and clear INT_STATUS, () → uint8_t
    // bit 0 = SOURCE_THRESHOLD
    printf("status %d\n", (int)status);
    sensor.offInterrupt();                              // Unsubscribe, () → void
    sensor.disableInterrupt();                          // Disable the interrupt, () → void
    // INT_ENABLE = 0; also clears a pending status
}
