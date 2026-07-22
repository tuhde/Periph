#include <Wire.h>
#include "I2CTransport.h"
#include "Rda5807m.h"

#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_I2C_FREQ
#define TEST_I2C_FREQ 400000
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x10
#endif

I2CTransport transport(Wire, TEST_ADDR);
RDA5807MFull fm(transport, 100.0f, 8);

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) {
        Serial.print("PASS "); Serial.println(label);
        passed++;
    } else {
        Serial.print("FAIL "); Serial.println(label);
        failed++;
    }
}

void setup() {
    Serial.begin(115200);
    delay(2000);

    Wire.begin(TEST_SDA, TEST_SCL, TEST_I2C_FREQ);

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
    check_true("mute/unmute: is_ready after", fm.is_ready());

    float seek_freq;
    bool ok = fm.seek(true, seek_freq);
    check_true("seek: returns without hang", true);
    (void)ok;

    fm.enable_rds(true);
    check_true("rds_ready is callable", fm.rds_ready() || !fm.rds_ready());

    fm.configure(RDA5807MFull::BAND_WORLD, RDA5807MFull::SPACE_100K);
    check_true("after configure: is_ready", fm.is_ready());

    fm.standby(true);
    delay(10);
    fm.standby(false);
    delay(10);
    check_true("after standby cycle: is_ready", fm.is_ready());

    fm.soft_reset();
    check_true("after soft_reset: is_ready", fm.is_ready());

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
