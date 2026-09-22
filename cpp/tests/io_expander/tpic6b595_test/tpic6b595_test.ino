#ifndef TEST_SCK
#define TEST_SCK 18
#endif
#ifndef TEST_MOSI
#define TEST_MOSI 19
#endif
#ifndef TEST_RCK
#define TEST_RCK 17
#endif
#ifndef TEST_SRCLR
#define TEST_SRCLR 16
#endif
#ifndef TEST_G
#define TEST_G 15
#endif

#include <SPI.h>
#include "SiPoConnection.h"
#include "TPIC6B595.h"

SiPoConnection connection(SPI, TEST_RCK, TEST_SRCLR, TEST_G);          // Create SiPo connection, (spi, rck, srclr, g)
TPIC6B595Full<SiPoConnection> chip(connection, 1);                     // Create TPIC6B595 full driver, (connection, num_devices=1)

int passed = 0;
int failed = 0;

void check_true(const char* label, bool cond) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

void check_eq(const char* label, uint8_t got, uint8_t expected) {
    if (got == expected) { Serial.print("PASS "); Serial.println(label); passed++; }
    else { Serial.print("FAIL "); Serial.print(label); Serial.print(": got "); Serial.print(got); Serial.print(" expected "); Serial.println(expected); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin(TEST_SCK, -1, TEST_MOSI, -1);

    check_eq("init_shadow_0", chip._shadow[0], 0x00);

    chip.fill(true);
    check_eq("fill_true_shadow", chip._shadow[0], 0xFF);
    chip.fill(false);
    check_eq("fill_false_shadow", chip._shadow[0], 0x00);
    chip.off();
    check_eq("off_shadow", chip._shadow[0], 0x00);

    chip.write_port(0, 0xA5);
    check_eq("write_port_0xa5_shadow", chip._shadow[0], 0xA5);
    chip.write_port(0, 0x00);

    TPIC6B595Full<SiPoConnection>::IOExpanderPin p0 = chip.pin(0);
    p0.high();
    check_eq("pin_on_shadow_bit", chip._shadow[0] & 0x01, 1);

    p0.low();
    check_eq("pin_off_shadow_bit", chip._shadow[0] & 0x01, 0);

    p0.toggle();
    check_eq("pin_toggle_shadow_bit", chip._shadow[0] & 0x01, 1);

    p0.write(0);
    check_eq("pin_write_0_shadow", chip._shadow[0] & 0x01, 0);
    check_eq("pin_read_after_write_0", p0.read(), 0);
    p0.write(1);
    check_eq("pin_write_1_shadow", chip._shadow[0] & 0x01, 1);

    p0.set(true);
    check_eq("pin_set_true", chip._shadow[0] & 0x01, 1);
    p0.set(false);
    check_eq("pin_set_false", chip._shadow[0] & 0x01, 0);

    TPIC6B595Full<SiPoConnection> cascaded(connection, 2);
    check_eq("cascaded_init_shadow_0", cascaded._shadow[0], 0x00);
    check_eq("cascaded_init_shadow_1", cascaded._shadow[1], 0x00);
    cascaded.write_port(0, 0x01);
    cascaded.write_port(1, 0x80);
    check_eq("cascaded_write_port_0", cascaded._shadow[0], 0x01);
    check_eq("cascaded_write_port_1", cascaded._shadow[1], 0x80);

    chip.clear();
    check_true("clear_accepted", true);
    chip.set_output_enable(false);
    check_true("set_output_enable_false_accepted", true);
    chip.set_output_enable(true);
    check_true("set_output_enable_true_accepted", true);

    uint8_t bytes_[2] = { 0xA5, 0x5A };
    cascaded.write_all(bytes_, 2);
    check_eq("write_all_shadow_0", cascaded._shadow[0], 0xA5);
    check_eq("write_all_shadow_1", cascaded._shadow[1], 0x5A);

    uint8_t single[1] = { 0xFF };
    cascaded.write_all(single, 1);
    check_eq("write_all_pad_shadow_0", cascaded._shadow[0], 0xFF);
    check_eq("write_all_pad_shadow_1", cascaded._shadow[1], 0x00);

    uint8_t three[3] = { 0x12, 0x34, 0x56 };
    cascaded.write_all(three, 3);
    check_eq("write_all_truncate_shadow_0", cascaded._shadow[0], 0x12);
    check_eq("write_all_truncate_shadow_1", cascaded._shadow[1], 0x34);

    Serial.print("===DONE: "); Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() { delay(1000); }
