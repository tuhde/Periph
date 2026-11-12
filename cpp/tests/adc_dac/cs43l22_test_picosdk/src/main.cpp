#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "CS43L22.h"

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

    I2CConnectionPicoSDK connection(i2c0, CS43L22Minimal::I2C_ADDRESS);
    CS43L22Minimal dac(connection);                     // Create CS43L22 driver, (connection, resPin=nullptr)
    check_true(dac.init(), "init");                     // Reset, verify ID, load defaults, () → bool
    dac.setVolume(-20.0f);                              // Set master volume, (db -102–12 dB) → void
    check_true(dac.getVolume() == -20.0f, "volume_roundtrip"); // Read master volume, () → float dB
    dac.mute(true);                                     // Mute headphones, (on=true) → void
    dac.mute(false);

    CS43L22Full full(connection);                       // Create CS43L22 Full driver, (connection, resPin=nullptr)
    check_true(full.init(), "full_init");
    uint8_t id, rev;
    full.readId(id, rev);                               // Read chip ID and revision, (&chipId, &revision) → void
    check_true(id == 0x1C, "chip_id");
    full.setVolumeChannels(-10.0f, -12.0f);             // Set per-channel master volume, (leftDb, rightDb) → void
    uint8_t v0 = 0, v1 = 0;
    full.readRegister(0x20, v0);                        // Read a raw register, (reg, &value) → bool
    full.readRegister(0x21, v1);
    check_true(v0 == 0xEC && v1 == 0xE8, "volume_channels");
    full.setHeadphoneGain(CS43L22Full::GAIN_1_000);     // Select headphone analog gain, (index 0–7) → bool
    check_true(full.headphoneGain() == 1.0f, "headphone_gain");
    full.setTone(6.0f, 6.0f);                           // Configure treble / bass shelves, (trebleDb, bassDb) → bool
    uint8_t tone = 0;
    full.readRegister(0x1F, tone);
    check_true(tone == 0x44, "tone");
    full.setLimiter(true, -6, -12);                     // Configure the peak limiter, (enabled, maxDb, cushionDb) → bool
    uint8_t lim = 0;
    full.readRegister(0x28, lim);
    check_true((lim & 0x80) == 0x80, "limiter");
    full.setLimiter(false);
    CS43L22SpeakerStatus sp = full.readSpeakerStatus(); // Read speaker status, () → CS43L22SpeakerStatus
    (void)sp;
    CS43L22Clocking clk;
    check_true(CS43L22Full::clockingFor(12288000, 48000.0f, clk) && clk.speed == 1, "clocking_for");
    full.stop();                                        // Power down the DAC, () → void
    uint8_t pwr = 0;
    full.readRegister(0x02, pwr);
    check_true(pwr == 0x9F, "stop_powers_down");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
