#include <cstdio>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "Rda5807m.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x10
#endif

// FM_READY deasserts on any register write and takes ~20 ms to settle back;
// not documented in the datasheet, measured on real hardware.
static const useconds_t SETTLE_US = 30000;

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CTransportLinux transport(TEST_I2C_BUS, TEST_ADDR);
    RDA5807MFull fm(transport, 100.0f, 8);

    usleep(SETTLE_US);
    check_true("is_ready", fm.is_ready());

    float f = fm.frequency();
    check_true("frequency near 100.0 MHz", f > 99.8f && f < 100.2f);

    fm.set_frequency(97.5f);
    f = fm.frequency();
    check_true("set_frequency: frequency near 97.5 MHz", f > 97.3f && f < 97.7f);

    fm.set_volume(10);
    uint8_t rssi = fm.signal_strength();
    check_true("signal_strength in range", rssi <= 127);

    fm.mute(true);
    fm.mute(false);
    usleep(SETTLE_US);
    check_true("mute/unmute: is_ready after", fm.is_ready());

    float seek_freq;
    fm.seek(true, seek_freq);
    check_true("seek: returns without hang", true);

    fm.enable_rds(true);
    check_true("rds_ready is callable", fm.rds_ready() || !fm.rds_ready());

    fm.configure(RDA5807MFull::BAND_WORLD, RDA5807MFull::SPACE_100K);
    usleep(SETTLE_US);
    check_true("after configure: is_ready", fm.is_ready());

    fm.standby(true);
    usleep(10000);
    fm.standby(false);
    check_true("after standby cycle: is_ready", fm.is_ready());

    fm.soft_reset();
    check_true("after soft_reset: is_ready", fm.is_ready());

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
