#include <math.h>
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "ADS7828.h"

#ifndef ADS7828_I2C_NODE
#define ADS7828_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef ADS7828_ADDR
#define ADS7828_ADDR 0x48
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(ADS7828_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, ADS7828_ADDR);
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

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
