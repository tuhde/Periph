#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif

#include <Arduino.h>
#include <Wire.h>
#include <Periph.h>
#include <math.h>

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
#if defined(ARDUINO_ARCH_ESP32)
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
#else
    Wire.begin();
    Wire.setClock(400000);
#endif
    I2CConnection connection(Wire, Si1132Minimal::I2C_ADDRESS);

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
    delay(50);
    check_true(full.measurement_ready(), "measurement_ready");
    uint8_t counter = 0, error = 0;
    check_true(full.read_response(counter, error) && error == 0, "response_no_error");
    full.poll_interrupt();
    check_true(full.start_autonomous(0.1f), "autonomous");
    delay(500);
    check_true(full.measurement_ready(), "autonomous_ready");
    check_true(full.pause() && full.start_autonomous(0.0f), "pause");
    check_true(full.enable_interrupt() && full.disable_interrupt(), "interrupt_pin");
    check_true(full.reset(), "reset");
    check_true(full.read(uv, vis, ir), "read_after_reset");

    Serial.print("===DONE: "); Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {}
