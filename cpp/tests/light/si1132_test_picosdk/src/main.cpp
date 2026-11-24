#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Si1132.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate

    I2CConnectionPicoSDK connection(i2c0, Si1132Minimal::I2C_ADDRESS);
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
    sleep_ms(50);
    check_true(full.measurement_ready(), "measurement_ready");
    uint8_t counter = 0, error = 0;
    check_true(full.read_response(counter, error) && error == 0, "response_no_error");
    full.poll_interrupt();
    check_true(full.start_autonomous(0.1f), "autonomous");
    sleep_ms(500);
    check_true(full.measurement_ready(), "autonomous_ready");
    check_true(full.pause() && full.start_autonomous(0.0f), "pause");
    check_true(full.enable_interrupt() && full.disable_interrupt(), "interrupt_pin");
    check_true(full.reset(), "reset");
    check_true(full.read(uv, vis, ir), "read_after_reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
