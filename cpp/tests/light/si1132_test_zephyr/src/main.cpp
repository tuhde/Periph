#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include <math.h>
#include "Si1132.h"

#define I2C_NODE DT_NODELABEL(i2c0)

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    if (!device_is_ready(i2c_dev)) {
        printk("FAIL device_ready\n");
        return 1;
    }
    I2CConnectionZephyr connection(i2c_dev, Si1132Minimal::I2C_ADDRESS);
    Si1132Minimal sensor(connection);                    // Create Si1132 driver, (connection)
    Si1132Full full(connection);                          // Create Si1132 Full driver, (connection)

    check_true(full.init(), "init");                        // Initialize the chip, () → bool
    float uv = 0; uint16_t vis = 0, ir = 0, aux = 0;
    check_true(full.read(uv, vis, ir), "read");             // Read UV index and ALS counts, (uv&, visible&, infrared&) → bool
    check_true(uv >= 0.0f && uv < 20.0f, "read_uv_range");
    check_true(full.read_uv_index() < 20.0f, "read_uv_index");
    check_true(full.read_visible(vis) && full.read_infrared(ir), "read_single");
    check_true(full.read_raw(vis, ir, aux), "read_raw");
    check_true(full.read_temperature_raw(aux), "temperature_raw");
    check_true(full.read_vdd_raw(aux), "vdd_raw");
    check_true(full.set_gain(2, 2) && full.read_visible(vis), "gain_read");
    check_true(full.set_gain(0, 0), "gain_default");
    check_true(full.set_high_range(true, true) && full.read_visible(vis), "high_range_read");
    check_true(full.set_high_range(false, false), "high_range_off");
    check_true(full.set_channels(true, false, false) && full.read(uv, vis, ir), "uv_only_read");
    check_true(full.set_channels(true, true, true), "channels_all");
    check_true(!full.set_channels(false, false, false), "channels_invalid");
    uint8_t cal[12];
    check_true(full.read_calibration(cal), "read_calibration");
    check_true(full.force(), "force");
    k_sleep(K_MSEC(50));
    check_true(full.measurement_ready(), "measurement_ready");
    uint8_t counter = 0, error = 0;
    check_true(full.read_response(counter, error) && error == 0, "response_no_error");
    full.poll_interrupt();
    check_true(full.start_autonomous(0.1f), "autonomous");
    k_sleep(K_MSEC(500));
    check_true(full.measurement_ready(), "autonomous_ready");
    check_true(full.pause() && full.start_autonomous(0.0f), "pause");
    check_true(full.enable_interrupt() && full.disable_interrupt(), "interrupt_pin");
    check_true(full.reset(), "reset");
    check_true(full.read(uv, vis, ir), "read_after_reset");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
