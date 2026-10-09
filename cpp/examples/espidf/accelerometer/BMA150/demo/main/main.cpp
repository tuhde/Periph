#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "esp_timer.h"
#include "I2CConnectionESPIDF.h"
#include "BMA150.h"

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
    dev_cfg.device_address = 0x38;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    BMA150Full accel(connection);                       // Create BMA150 driver, (connection)

    // --- Configure ±8 g / 190 Hz and arm LG + HG latched interrupts ---
    // ±8 g gives 64 LSB/g, plenty of headroom for shock detection. 190 Hz
    // bandwidth is wide enough to capture a 2 ms high-g spike without
    // aliasing. Latched interrupts free the polling loop from having to
    // catch a transient.
    accel.set_range(8);                                  // Set measurement range, (range_g=2) → g
    accel.set_bandwidth(190);                            // Set bandwidth, (bandwidth_hz=25) → Hz
    accel.set_latch(true);                               // Set latched interrupts, (enabled=False) → None
    accel.set_low_g(0.4, 40);                            // Configure low-g, (threshold_g, duration_ms, hysteresis_g=0, counter=0) → g, ms
    accel.set_high_g(4.0, 2);                            // Configure high-g, (threshold_g, duration_ms, hysteresis_g=0, counter=0) → g, ms

    int64_t start = esp_timer_get_time() / 1000;
    int64_t last_heartbeat = 0;
    int64_t last_poll = 0;

    // --- 60-second free-fall / shock logger ---
    // User is expected to drop or shake the board at some point during
    // the 60 s window. Between events the magnitude sits at ≈1.00 g
    // (gravity). Each latched interrupt is reported with a timestamp,
    // the latest (x, y, z), temperature, and a free-fall or shock tag.
    while ((esp_timer_get_time() / 1000) - start < 60000) {
        int64_t now = esp_timer_get_time() / 1000;

        if (now - last_heartbeat >= 1000) {
            float x, y, z;
            accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
            float mag = sqrtf(x * x + y * y + z * z);
            float temp = accel.read_temperature();               // Read temperature, () → °C
            printf("%5lld  x=%+.3f  y=%+.3f  z=%+.3f  |a|=%.3f g  T=%.1f C\n",
                   (long long)((now - start) / 1000), (double)x, (double)y, (double)z, (double)mag, (double)temp);
            last_heartbeat = now;
        }

        if (now - last_poll >= 50) {
            uint8_t status = accel.poll_interrupt();             // Read STATUS, () → bitmask
            if (status & 0x08) {                                // STATUS_LG_LATCHED (bit 3)
                float x, y, z;
                accel.read(x, y, z);                            // Read 3-axis acceleration, (x, y, z) → g, g, g
                float temp = accel.read_temperature();           // Read temperature, () → °C
                printf("%5lld  FREE FALL detected  x=%+.3f  y=%+.3f  z=%+.3f  T=%.1f C\n",
                       (long long)(now - start), (double)x, (double)y, (double)z, (double)temp);
                accel.clear_interrupt();                         // Clear latched interrupts, () → None
            }
            if (status & 0x04) {                                // STATUS_HG_LATCHED (bit 2)
                float x, y, z;
                accel.read(x, y, z);                            // Read 3-axis acceleration, (x, y, z) → g, g, g
                float temp = accel.read_temperature();           // Read temperature, () → °C
                printf("%5lld  SHOCK detected     x=%+.3f  y=%+.3f  z=%+.3f  T=%.1f C\n",
                       (long long)(now - start), (double)x, (double)y, (double)z, (double)temp);
                accel.clear_interrupt();                         // Clear latched interrupts, () → None
            }
            last_poll = now;
        }

        vTaskDelay(pdMS_TO_TICKS(10));
    }
    printf("done\n");
}
