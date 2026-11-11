#include <math.h>
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "ADS7828.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x48
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);
    ADS7828Full adc(connection);

    uint16_t raw = adc.readRaw(0);
    check_true("readRaw(0) in [0, 4095]", raw <= 4095);
    check_true("readRaw(99) clamped", adc.readRaw(99) <= 4095);
    float v = adc.readVoltage(1);
    check_true("readVoltage in [0, 2.5]", v >= 0.0f && v <= 2.5f);
    float all[8];
    adc.readAll(all);
    bool allOk = true;
    for (int i = 0; i < 8; i++) { if (all[i] < 0.0f || all[i] > 2.5f) allOk = false; }
    check_true("readAll values in [0, 2.5]", allOk);
    check_true("readDifferentialRaw in range", adc.readDifferentialRaw(0) <= 4095);
    check_true("readDifferential inverted >= 0", adc.readDifferential(1, true) >= 0.0f);
    float sv = adc.readSignedDifferential(2);
    check_true("readSignedDifferential in [-2.5, 2.5]", sv >= -2.5f && sv <= 2.5f);
    check_true("lsb is vref/4096", fabsf(adc.lsb() - 2.5f / 4096.0f) < 1e-6f);
    check_true("internal reference selected", adc.usesInternalReference() && adc.getReference() == 2.5f);
    const uint8_t chans[3] = {0, 3, 7};
    uint16_t scan[3];
    adc.readScan(chans, 3, scan);
    check_true("readScan codes in range", scan[0] <= 4095 && scan[1] <= 4095 && scan[2] <= 4095);
    check_true("setPowerMode accepted", adc.setPowerMode(ADS7828Full::REF_ON_ADC_ON));
    check_true("internal reference rejects PD1 = 0", !adc.setPowerMode(ADS7828Full::REF_OFF_ADC_ON));
    adc.setReference(5.0f);
    check_true("external reference selected", !adc.usesInternalReference() && adc.getReference() == 5.0f);
    adc.setReference(0.0f);
    check_true("internal reference restored", adc.usesInternalReference());
    adc.powerDown();
    check_true("read after powerDown works", adc.readRaw(0) <= 4095);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
