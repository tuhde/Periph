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
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, DS1881Minimal::I2C_ADDRESS);

    DS1881Minimal pot(connection);                      // Create DS1881 driver, (connection)
    pot.init();                                         // Apply defaults, () → void
    pot.setAttenuation(0, 20.0f);                       // Set attenuation, (channel 0–1, db dB) → void
    pot.setAttenuation(1, 33.0f);
    check_true(pot.getPosition(0) == 20 && pot.getPosition(1) == 33, "position_roundtrip"); // Read wiper position, (channel 0–1) → uint8_t
    check_true(pot.getAttenuation(0) == 20.0f && pot.getAttenuation(1) == 33.0f, "attenuation_roundtrip"); // Read attenuation, (channel 0–1) → float dB
    pot.mute(0);                                        // Mute channel, (channel 0–1) → void
    check_true(pot.getPosition(0) == 63 && pot.getAttenuation(0) == 80.0f, "mute");
    pot.setPosition(1, 5);                              // Set raw wiper position, (channel 0–1, position 0–63) → void
    check_true(pot.getPosition(1) == 5, "set_position");

    DS1881Full full(connection);                        // Create DS1881 Full driver, (connection)
    full.init();
    DS1881Config cfg = full.getConfig();                // Read configuration, () → DS1881Config
    check_true(cfg.option == 1 && cfg.zeroCrossing && !cfg.nonvolatile, "config_default");
    uint8_t p0, p1, c;
    full.setBoth(10, 40);                               // Set both wipers, (position0 0–63, position1 0–63) → void
    full.readAll(p0, p1, c);                            // Read everything, (&position0, &position1, &config) → void
    check_true(p0 == 10 && p1 == 40, "set_both");
    full.setAttenuationBoth(15.0f);                     // Set both attenuations, (db0 dB, db1=db0 dB) → void
    full.readAll(p0, p1, c);
    check_true(p0 == 15 && p1 == 15, "set_attenuation_both");
    full.setZeroCrossing(false);                        // Set zero-crossing, (enabled bool) → void
    check_true(!full.getConfig().zeroCrossing, "zero_crossing_off");
    full.setZeroCrossing(true);
    full.setOption(2);                                  // Select option, (option 1–2) → void
    check_true(full.getConfig().option == 2 && full.maxPosition() == 32, "option2");
    full.setBoth(13, 33);
    full.readAll(p0, p1, c);
    check_true(p0 == 13 && p1 == 33 && full.positionToDb(13) == 14.0f, "option2_positions");
    full.setOption(1);
    check_true(full.getConfig().option == 1, "option1_again");
    full.reset();                                       // Reset, () → void
    full.readAll(p0, p1, c);
    check_true(p0 == 63 && p1 == 63, "reset_mutes");

    Serial.print("===DONE: "); Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {}
