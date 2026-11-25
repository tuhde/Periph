#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x20
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
    PCA6408AMinimal chip(connection);

    CHK(chip._direction == 0xFF, "init_config");
    CHK(chip._shadow == 0xFF, "init_shadow");

    chip.write_port(0, 0x55);
    CHK(chip._shadow == 0x55, "write_port_shadow");
    chip.write_port(0, 0xFF);
    uint8_t port_val = chip.read_port();
    CHK(port_val <= 0xFF, "read_port_range");

    auto p7 = chip.pin(7);
    p7.mode(OUTPUT);
    p7.low();
    CHK((chip._shadow & 0x80) == 0x00, "pin7_off");
    p7.high();
    CHK((chip._shadow & 0x80) == 0x80, "pin7_on");
    p7.toggle();
    CHK((chip._shadow & 0x80) == 0x00, "pin7_toggle");
    CHK(!p7.mode(INPUT_PULLUP), "pull_up_rejected");

    PCA6408AFull full(connection);
    CHK(full._direction == 0xFF, "full_init_config");

    // Loopback: jumper P0-P3 to P4-P7 (Pn <-> Pn+4).
    full.set_direction(0xF0);
    CHK(full.read_register(0x03) == 0xF0, "config_readback");
    full.write_port(0, 0x05);
    CHK(((full.read_port() >> 4) & 0x0F) == 0x05, "loopback_0x5");
    full.write_port(0, 0x0A);
    CHK(((full.read_port() >> 4) & 0x0F) == 0x0A, "loopback_0xA");
    full.write_port(0, 0x00);
    CHK(((full.read_port() >> 4) & 0x0F) == 0x00, "loopback_0x0");

    full.configure_polarity(0xF0);
    CHK(full.read_register(0x02) == 0xF0, "polarity_readback");
    CHK(((full.read_port() >> 4) & 0x0F) == 0x0F, "polarity_inverted");
    full.configure_polarity(0x00);

    full.pollInterrupt();
    full.write_port(0, 0x01);
    CHK(full.pollInterrupt() == 0x10, "poll_interrupt_changed");
    full.write_port(0, 0x00);

    full.reset();
    CHK(full.read_register(0x03) == 0xFF, "reset_config");
    CHK(full.read_register(0x01) == 0xFF, "reset_output");

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }
