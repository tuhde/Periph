#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x41
#endif

#include <Arduino.h>
#include <Wire.h>
#include <Periph.h>

static int passed = 0, failed = 0;

static void check_true(const char *label, bool cond) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

static void check_eq(const char* label, int got, int expected) {
    if (got == expected) { Serial.print("PASS "); Serial.println(label); passed++; }
    else { Serial.print("FAIL "); Serial.print(label); Serial.print(" got "); Serial.print(got, HEX); Serial.print(" want "); Serial.println(expected, HEX); failed++; }
}

#define CHK(c, l) check_true(l, (c))

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CConnection connection(Wire, TEST_ADDR);
    PCA9536Minimal chip(connection);

    CHK(chip._direction == 0xFF, "init_config");
    CHK(chip._shadow == 0xFF, "init_shadow");

    chip.write_port(0, 0x05);
    CHK((chip._shadow & 0x0F) == 0x05, "write_port_shadow");
    chip.write_port(0, 0x0F);
    uint8_t port_val = chip.read_port();
    CHK(port_val <= 0x0F, "read_port_range");

    auto p3 = chip.pin(3);
    p3.mode(OUTPUT);
    p3.low();
    CHK((chip._shadow & 0x08) == 0x00, "pin3_off");
    p3.high();
    CHK((chip._shadow & 0x08) == 0x08, "pin3_on");
    p3.toggle();
    CHK((chip._shadow & 0x08) == 0x00, "pin3_toggle");
    CHK(!p3.mode(INPUT_PULLUP), "pull_up_rejected");

    PCA9536Full full(connection);
    CHK(full._direction == 0xFF, "full_init_config");

    // Loopback: jumper P0-P1 to P2-P3 (Pn <-> Pn+2).
    full.set_direction(0x0C);
    CHK((full.read_register(0x03) & 0x0F) == 0x0C, "config_readback");
    full.write_port(0, 0x01);
    CHK(((full.read_port() >> 2) & 0x03) == 0x01, "loopback_0x1");
    full.write_port(0, 0x02);
    CHK(((full.read_port() >> 2) & 0x03) == 0x02, "loopback_0x2");
    full.write_port(0, 0x00);
    CHK(((full.read_port() >> 2) & 0x03) == 0x00, "loopback_0x0");

    full.configure_polarity(0x0C);
    CHK((full.read_register(0x02) & 0x0F) == 0x0C, "polarity_readback");
    CHK(((full.read_port() >> 2) & 0x03) == 0x03, "polarity_inverted");
    full.configure_polarity(0x00);

    full.pollChanges();
    full.write_port(0, 0x01);
    CHK(full.pollChanges() == 0x04, "poll_changes_changed");
    full.write_port(0, 0x00);

    full.reset();
    CHK((full.read_register(0x03) & 0x0F) == 0x0F, "reset_config");
    CHK((full.read_register(0x01) & 0x0F) == 0x0F, "reset_output");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
