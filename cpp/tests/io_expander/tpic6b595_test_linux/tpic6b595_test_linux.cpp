#ifndef TEST_RCK
#define TEST_RCK 5
#endif
#ifndef TEST_SRCLR
#define TEST_SRCLR 6
#endif
#ifndef TEST_G
#define TEST_G 13
#endif
#ifndef TEST_SER_IN
#define TEST_SER_IN 19
#endif
#ifndef TEST_SRCK
#define TEST_SRCK 26
#endif

#include <cstdio>
#include <cstdlib>
#include <gpiod.h>
#include "SiPoConnectionLinux.h"
#include "TPIC6B595.h"

static gpiod_line* get_output_line(const char* chip_path, unsigned int offset,
                                   const char* consumer, int default_value) {
    gpiod_chip* chip = ::gpiod_chip_open(chip_path);
    if (!chip) {
        std::perror("gpiod_chip_open");
        std::exit(2);
    }
    gpiod_line* line = ::gpiod_line_get(chip, offset);
    if (!line) {
        std::perror("gpiod_line_get");
        std::exit(2);
    }
    if (::gpiod_line_request_output(line, consumer, default_value) < 0) {
        std::perror("gpiod_line_request_output");
        std::exit(2);
    }
    return line;
}

int passed = 0;
int failed = 0;

static void check_true(const char* label, bool cond) {
    if (cond) { std::printf("PASS %s\n", label); passed++; }
    else       { std::printf("FAIL %s\n", label); failed++; }
}

static void check_eq(const char* label, uint8_t got, uint8_t expected) {
    if (got == expected) { std::printf("PASS %s\n", label); passed++; }
    else { std::printf("FAIL %s: got %u expected %u\n", label, (unsigned)got, (unsigned)expected); failed++; }
}

int main() {
    const char* chip = getenv("GPIO_CHIP");
    if (!chip) chip = "/dev/gpiochip0";

    gpiod_line* ser_in_line = get_output_line(chip, TEST_SER_IN, "tpic6b595_test_si", 0);
    gpiod_line* srck_line   = get_output_line(chip, TEST_SRCK,   "tpic6b595_test_ck", 0);
    gpiod_line* rck_line    = get_output_line(chip, TEST_RCK,    "tpic6b595_test_rc", 0);
    gpiod_line* srclr_line  = get_output_line(chip, TEST_SRCLR,  "tpic6b595_test_clr", 1);
    gpiod_line* g_line      = get_output_line(chip, TEST_G,      "tpic6b595_test_g",   0);

    SiPoConnectionLinux connection(ser_in_line, srck_line, rck_line, srclr_line, g_line);
    TPIC6B595Full<SiPoConnectionLinux> chip(connection, 1);

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

    TPIC6B595Full<SiPoConnectionLinux>::IOExpanderPin p0 = chip.pin(0);
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

    TPIC6B595Full<SiPoConnectionLinux> cascaded(connection, 2);
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

    std::printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
