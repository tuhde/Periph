#include <math.h>
// Auto-generated ESP-IDF test for ADS7828.
// Mirrors the Zephyr test for ADS7828; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ADS7828.h"

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_near(float val, float lo, float hi, const char *label) {
    if (val >= lo && val <= hi) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: %.4f not in [%.4f, %.4f]\n",
                  label, (double)val, (double)lo, (double)hi); failed++; }
}

static void check_eq_u8(uint8_t val, uint8_t expected, const char *label) {
    if (val == expected) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: 0x%02X != 0x%02X\n", label, val, expected); failed++; }
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
    dev_cfg.device_address = 0x48;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ADS7828Full adc(connection);

    uint16_t raw = adc.readRaw(0);
    check_true(raw <= 4095, "readRaw(0) in [0, 4095]");
    check_true(adc.readRaw(99) <= 4095, "readRaw(99) clamped");
    float v = adc.readVoltage(1);
    check_true(v >= 0.0f && v <= 2.5f, "readVoltage in [0, 2.5]");
    float all[8];
    adc.readAll(all);
    bool allOk = true;
    for (int i = 0; i < 8; i++) { if (all[i] < 0.0f || all[i] > 2.5f) allOk = false; }
    check_true(allOk, "readAll values in [0, 2.5]");
    check_true(adc.readDifferentialRaw(0) <= 4095, "readDifferentialRaw in range");
    check_true(adc.readDifferential(1, true) >= 0.0f, "readDifferential inverted >= 0");
    float sv = adc.readSignedDifferential(2);
    check_true(sv >= -2.5f && sv <= 2.5f, "readSignedDifferential in [-2.5, 2.5]");
    check_true(fabsf(adc.lsb() - 2.5f / 4096.0f) < 1e-6f, "lsb is vref/4096");
    check_true(adc.usesInternalReference() && adc.getReference() == 2.5f, "internal reference selected");
    const uint8_t chans[3] = {0, 3, 7};
    uint16_t scan[3];
    adc.readScan(chans, 3, scan);
    check_true(scan[0] <= 4095 && scan[1] <= 4095 && scan[2] <= 4095, "readScan codes in range");
    check_true(adc.setPowerMode(ADS7828Full::REF_ON_ADC_ON), "setPowerMode accepted");
    check_true(!adc.setPowerMode(ADS7828Full::REF_OFF_ADC_ON), "internal reference rejects PD1 = 0");
    adc.setReference(5.0f);
    check_true(!adc.usesInternalReference() && adc.getReference() == 5.0f, "external reference selected");
    adc.setReference(0.0f);
    check_true(adc.usesInternalReference(), "internal reference restored");
    adc.powerDown();
    check_true(adc.readRaw(0) <= 4095, "read after powerDown works");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
