#include <Wire.h>
#include "I2CTransport.h"
#include "PCF8574.h"

#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_I2C_FREQ
#define TEST_I2C_FREQ 100000
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x20
#endif

I2CTransport  transport(Wire, TEST_ADDR);
PCF8574Full   chip(transport);

static int passed = 0;
static int failed = 0;

static void check_eq(const char* label, uint8_t got, uint8_t expected) {
    if (got == expected) {
        Serial.print("PASS "); Serial.println(label);
        passed++;
    } else {
        Serial.print("FAIL "); Serial.print(label);
        Serial.print(": got 0x"); Serial.print(got, HEX);
        Serial.print(", expected 0x"); Serial.println(expected, HEX);
        failed++;
    }
}

static void check_true(const char* label, bool condition) {
    if (condition) { Serial.print("PASS "); Serial.println(label); passed++; }
    else           { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, TEST_I2C_FREQ);

    // shadow initialised to 0xFF
    check_eq("init_shadow", chip._shadow, 0xFF);

    // read_port returns a byte
    uint8_t port = chip.read_port();
    check_true("read_port_range", port <= 0xFF);

    // write_port updates shadow
    chip.write_port(0, 0xAA);
    check_eq("write_port_shadow", chip._shadow, 0xAA);
    chip.write_port(0, 0xFF);

    // pin proxy: drive P0 low
    PCF8574Full::IOExpanderPin p0 = chip.pin(0);
    p0.mode(OUTPUT);
    p0.low();
    check_eq("pin_low_shadow",  chip._shadow & 0x01, 0x00);
    p0.high();
    check_eq("pin_high_shadow", chip._shadow & 0x01, 0x01);
    p0.toggle();
    check_eq("pin_toggle_shadow", chip._shadow & 0x01, 0x00);
    p0.write(HIGH);
    check_eq("pin_write_high_shadow", chip._shadow & 0x01, 0x01);
    uint8_t v = p0.read();
    check_true("pin_read_range", v <= 1);

    // input pin
    PCF8574Full::IOExpanderPin p4 = chip.pin(4);
    p4.mode(INPUT);
    check_eq("input_shadow_bit4", (chip._shadow >> 4) & 1, 0x01);

    // clear_interrupt
    uint8_t changed = chip.clear_interrupt();
    check_true("clear_interrupt_range", changed <= 0xFF);

    Serial.print("===DONE: "); Serial.print(passed);
    Serial.print(" passed, "); Serial.print(failed); Serial.println(" failed===");
}

void loop() {}
