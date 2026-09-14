#include <stdio.h>
#include "I2CConnectionMock.h"
#include "Mpr121.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void test_minimal_construction() {
    I2CConnectionMock mock;
    MPR121Minimal chip(mock);
    bool wrote_srst = false;
    bool wrote_ecr = false;
    bool wrote_t0 = false;
    bool wrote_r0 = false;
    for (const auto& w : mock.writes()) {
        if (w.size() == 2 && w[0] == 0x80 && w[1] == 0x63) wrote_srst = true;
        if (w.size() == 2 && w[0] == 0x5E && w[1] == 0x8C) wrote_ecr = true;
        if (w.size() == 2 && w[0] == 0x41 && w[1] == 12)   wrote_t0 = true;
        if (w.size() == 2 && w[0] == 0x42 && w[1] == 6)    wrote_r0 = true;
    }
    check_true(wrote_srst, "Minimal init issues soft reset");
    check_true(wrote_ecr, "Minimal init writes ECR=0x8C");
    check_true(wrote_t0, "Minimal init writes ELE0_TTH=12");
    check_true(wrote_r0, "Minimal init writes ELE0_RTH=6");
}

static void test_touched_decodes_bitmask() {
    I2CConnectionMock mock;
    mock.setRegister(0x00, {0x5A});
    mock.setRegister(0x01, {0x05});
    MPR121Minimal chip(mock);
    check_true(chip.touched() == 0x5A, "touched returns 0x55A");
}

static void test_is_touched_per_electrode() {
    I2CConnectionMock mock;
    mock.setRegister(0x00, {0x28});
    mock.setRegister(0x01, {0x08});
    MPR121Minimal chip(mock);
    check_true(chip.is_touched(5) == true, "is_touched(5) True");
    check_true(chip.is_touched(11) == true, "is_touched(11) True (byte 1 bit 3)");
    check_true(chip.is_touched(0) == false, "is_touched(0) False");
}

static void test_filtered_decodes_10bit() {
    I2CConnectionMock mock;
    mock.setRegister(0x04, {0x80});
    mock.setRegister(0x05, {0x02});
    MPR121Full chip(mock);
    check_true(chip.filtered(0) == 0x280, "filtered(0) = 0x280");
}

static void test_baseline_shifts_left_2() {
    I2CConnectionMock mock;
    mock.setRegister(0x1E, {0x80});
    MPR121Full chip(mock);
    check_true(chip.baseline(0) == 0x200, "baseline(0) = 0x200");
}

static void test_set_baseline_shifts_right_2() {
    I2CConnectionMock mock;
    MPR121Full chip(mock);
    chip.set_baseline(0, 0x300);
    bool wrote = false;
    for (const auto& w : mock.writes()) {
        if (w.size() == 2 && w[0] == 0x1E && w[1] == 0xC0) wrote = true;
    }
    check_true(wrote, "set_baseline(0, 0x300) writes 0xC0 to 0x1E");
}

static void test_proximity_touched_bit4() {
    I2CConnectionMock mock;
    mock.setRegister(0x01, {0x10});
    MPR121Full chip(mock);
    check_true(chip.proximity_touched() == true, "proximity_touched True at 0x01=0x10");
    mock.setRegister(0x01, {0x00});
    check_true(chip.proximity_touched() == false, "proximity_touched False at 0x01=0x00");
}

static void test_clear_overcurrent_clears_bit7() {
    I2CConnectionMock mock;
    mock.setRegister(0x01, {0x80});
    MPR121Full chip(mock);
    chip.clear_overcurrent();
    bool wrote = false;
    for (const auto& w : mock.writes()) {
        if (w.size() == 2 && w[0] == 0x01 && (w[1] & 0x80) == 0) wrote = true;
    }
    check_true(wrote, "clear_overcurrent writes 0x01 with bit 7 cleared");
}

static void test_configure_sampling_packs_bits() {
    I2CConnectionMock mock;
    MPR121Full chip(mock);
    chip.configure_sampling(10, 2, 1, 2, 5);
    bool wrote_cdc = false, wrote_cdt = false;
    for (const auto& w : mock.writes()) {
        if (w.size() == 2 && w[0] == 0x5C && w[1] == 0x4A) wrote_cdc = true;
        if (w.size() == 2 && w[0] == 0x5D && w[1] == 0x4D) wrote_cdt = true;
    }
    check_true(wrote_cdc, "configure_sampling writes CDC_CONFIG=0x4A (ffi=1, cdc=10)");
    check_true(wrote_cdt, "configure_sampling writes CDT_CONFIG=0x4D (cdt=2, sfi=2, esi=5)");
}

static void test_configure_debounce_packs_bits() {
    I2CConnectionMock mock;
    MPR121Full chip(mock);
    chip.configure_debounce(3, 5);
    bool wrote = false;
    for (const auto& w : mock.writes()) {
        if (w.size() == 2 && w[0] == 0x5B && w[1] == 0x53) wrote = true;
    }
    check_true(wrote, "configure_debounce writes 0x5B=0x53 (release=5,touch=3)");
}

static void test_enable_disable_interrupt() {
    I2CConnectionMock mock;
    mock.setRegister(0x7C, {0x00});
    MPR121Full chip(mock);
    chip.enable_interrupt(MPR121Full::SOURCE_OOR);
    bool wrote_enable = false;
    for (const auto& w : mock.writes()) {
        if (w.size() == 2 && w[0] == 0x7C && w[1] == 0x04) wrote_enable = true;
    }
    check_true(wrote_enable, "enable_interrupt(SOURCE_OOR) writes 0x7C=0x04");
    mock.setRegister(0x7C, {0x04});
    chip.disable_interrupt(MPR121Full::SOURCE_OOR);
    bool wrote_disable = false;
    for (const auto& w : mock.writes()) {
        if (w.size() == 2 && w[0] == 0x7C && w[1] == 0x00) wrote_disable = true;
    }
    check_true(wrote_disable, "disable_interrupt(SOURCE_OOR) writes 0x7C=0x00");
}

int main() {
    test_minimal_construction();
    test_touched_decodes_bitmask();
    test_is_touched_per_electrode();
    test_filtered_decodes_10bit();
    test_baseline_shifts_left_2();
    test_set_baseline_shifts_right_2();
    test_proximity_touched_bit4();
    test_clear_overcurrent_clears_bit7();
    test_configure_sampling_packs_bits();
    test_configure_debounce_packs_bits();
    test_enable_disable_interrupt();
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
