#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "MCP3422.h"

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

    I2CConnectionPicoSDK connection(i2c0, MCP3422Minimal::I2C_ADDRESS);
    MCP3422Minimal adc(connection);                         // Create MCP3422 driver, (connection)
    int32_t raw = adc.readRaw(0);                           // Read a signed code, (channel 0–1) → int32_t
    check_true(adc.lastReadOk() && raw >= -32768 && raw <= 32767, "raw_in_16_bit_range");
    float v = adc.readVoltage(1);                           // Read the differential voltage, (channel 0–1) → float V
    check_true(adc.lastReadOk() && v >= -2.048f && v <= 2.048f, "voltage_in_range");
    float both[2];
    check_true(adc.readAll(both), "read_all_two_values");   // Read both channels, (float out[2]) → bool

    MCP3422Full full(connection);                           // Create MCP3422 Full driver, (connection)
    check_true(full.setResolution(12) && full.setGain(2), "selection_accepted"); // Select resolution / gain, (bits|gain) → bool
    check_true(full.getResolution() == 12 && full.getGain() == 2, "selection_roundtrip");
    check_true(fabsf(full.fullScale() - 1.024f) < 1e-6f, "full_scale"); // Full-scale range, () → float V
    int32_t r12 = full.readRaw(0);
    check_true(full.lastReadOk() && r12 >= -2048 && r12 <= 2047, "one_shot_12_bit_range");
    full.setGain(1);
    full.startContinuous(0);                                // Start continuous conversion, (channel=-1) → void
    check_true(full.isContinuous(), "continuous_flag");     // Report the mode, () → bool
    int32_t rc = full.readContinuousRaw();                  // Next fresh code, () → int32_t
    check_true(full.lastReadOk() && rc >= -2048 && rc <= 2047, "continuous_sample_in_range");
    check_true((full.readConfig() & 0x10) != 0, "read_config_continuous_bit"); // Read the config byte, () → uint8_t
    full.stopContinuous();                                  // Back to one-shot / standby, () → void
    check_true(!full.isContinuous(), "stopped");
    const uint8_t ch[2] = {0, 1};
    float scanned[2];
    check_true(full.readScan(ch, 2, scanned), "read_scan_two_values"); // Scan channels, (channels, count, float out[]) → bool

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
