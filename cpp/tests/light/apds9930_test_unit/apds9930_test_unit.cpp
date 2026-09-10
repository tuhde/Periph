#include <stdio.h>
#include "I2CConnectionMock.h"
#include "Apds9930.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Test access shim - exposes the command-byte helpers for assertions.
class Apds9930TestAccess : public APDS9930Full {
public:
    using APDS9930Full::APDS9930Full;
    using APDS9930Full::cmd_write;
    using APDS9930Full::cmd_read;
    using APDS9930Full::cmd_special;
};

// APDS-9930 uses a command-register protocol: every bus transaction
// starts with a command byte whose high bits are the type (0x80=write,
// 0xA0=auto-increment read, 0xE0=special) and whose low 5 bits are
// the register address. The mock is addressed by raw byte index, so
// preload registers at the command-byte addresses the driver writes.
static uint8_t CW(uint8_t reg) { return 0x80 | (reg & 0x1F); }
static uint8_t CR(uint8_t reg) { return 0xA0 | (reg & 0x1F); }

static void test_minimal_construction() {
    I2CConnectionMock mock;
    mock.setRegister(CR(0x12), {0x39});  // ID
    mock.setRegister(CR(0x0F), {0x00});  // CONTROL
    mock.setRegister(CR(0x01), {0xDB});  // ATIME
    mock.setRegister(CR(0x02), {0xFF});  // PTIME
    mock.setRegister(CR(0x0E), {0x08});  // PPULSE
    mock.setRegister(CR(0x0D), {0x00});  // CONFIG
    mock.setRegister(CR(0x14), {0x00, 0x00});
    mock.setRegister(CR(0x16), {0x00, 0x00});
    mock.setRegister(CR(0x18), {0x00, 0x00});
    APDS9930Minimal chip(mock);

    bool wrote_enable_default = false;
    bool wrote_ppulse = false;
    for (const auto& w : mock.writes()) {
        if (w.size() == 2 && w[0] == CW(0x00) && w[1] == 0x07) wrote_enable_default = true;
        if (w.size() == 2 && w[0] == CW(0x0E) && w[1] == 0x08) wrote_ppulse = true;
    }
    check_true(wrote_enable_default, "Minimal init enables PON|AEN|PEN");
    check_true(wrote_ppulse, "Minimal init writes PPULSE=0x08");
}

static void test_proximity_returns_16bit() {
    I2CConnectionMock mock;
    mock.setRegister(CR(0x12), {0x39});
    mock.setRegister(CR(0x0F), {0x00});
    mock.setRegister(CR(0x01), {0xDB});
    mock.setRegister(CR(0x02), {0xFF});
    mock.setRegister(CR(0x0E), {0x08});
    mock.setRegister(CR(0x0D), {0x00});
    mock.setRegister(CR(0x14), {0x00, 0x00});
    mock.setRegister(CR(0x16), {0x00, 0x00});
    mock.setRegister(CR(0x18), {0x34, 0x12});  // PDATA = 0x1234 little-endian
    APDS9930Minimal chip(mock);
    check_true(chip.proximity() == 0x1234, "proximity is 0x1234");
}

static void test_lux_zero_when_dark() {
    I2CConnectionMock mock;
    mock.setRegister(CR(0x12), {0x39});
    mock.setRegister(CR(0x0F), {0x00});
    mock.setRegister(CR(0x01), {0xDB});
    mock.setRegister(CR(0x02), {0xFF});
    mock.setRegister(CR(0x0E), {0x08});
    mock.setRegister(CR(0x0D), {0x00});
    mock.setRegister(CR(0x14), {0x00, 0x00});
    mock.setRegister(CR(0x16), {0x00, 0x00});
    mock.setRegister(CR(0x18), {0x00, 0x00});
    APDS9930Minimal chip(mock);
    check_true(chip.lux() == 0.0f, "lux=0 when dark");
}

static void test_lux_positive_when_visible_only() {
    I2CConnectionMock mock;
    mock.setRegister(CR(0x12), {0x39});
    mock.setRegister(CR(0x0F), {0x00});
    mock.setRegister(CR(0x01), {0xDB});
    mock.setRegister(CR(0x02), {0xFF});
    mock.setRegister(CR(0x0E), {0x08});
    mock.setRegister(CR(0x0D), {0x00});
    mock.setRegister(CR(0x14), {0x00, 0x10});  // Ch0 = 4096
    mock.setRegister(CR(0x16), {0x00, 0x00});  // Ch1 = 0
    mock.setRegister(CR(0x18), {0x00, 0x00});
    APDS9930Minimal chip(mock);
    check_true(chip.lux() > 0.0f, "lux>0 with visible-only light");
}

static void test_full_configure_als() {
    I2CConnectionMock mock;
    mock.setRegister(CR(0x12), {0x39});
    mock.setRegister(CR(0x0F), {0x00});
    mock.setRegister(CR(0x01), {0xDB});
    mock.setRegister(CR(0x02), {0xFF});
    mock.setRegister(CR(0x0E), {0x08});
    mock.setRegister(CR(0x0D), {0x00});
    mock.setRegister(CR(0x14), {0x00, 0x00});
    mock.setRegister(CR(0x16), {0x00, 0x00});
    mock.setRegister(CR(0x18), {0x00, 0x00});
    APDS9930Full chip(mock);
    chip.configure_als(0xF6, 2, false);

    bool wrote_atime = false;
    bool wrote_again = false;
    for (const auto& w : mock.writes()) {
        if (w.size() == 2 && w[0] == CW(0x01) && w[1] == 0xF6) wrote_atime = true;
        if (w.size() == 2 && w[0] == CW(0x0F) && (w[1] & 0x03) == 0x02) wrote_again = true;
    }
    check_true(wrote_atime, "configure_als writes ATIME");
    check_true(wrote_again, "configure_als writes AGAIN");
}

static void test_full_status_decoded() {
    I2CConnectionMock mock;
    mock.setRegister(CR(0x12), {0x39});
    mock.setRegister(CR(0x0F), {0x00});
    mock.setRegister(CR(0x01), {0xDB});
    mock.setRegister(CR(0x02), {0xFF});
    mock.setRegister(CR(0x0E), {0x08});
    mock.setRegister(CR(0x0D), {0x00});
    mock.setRegister(CR(0x13), {0x01});  // STATUS: AVALID
    mock.setRegister(CR(0x14), {0x00, 0x00});
    mock.setRegister(CR(0x16), {0x00, 0x00});
    mock.setRegister(CR(0x18), {0x00, 0x00});
    APDS9930Full chip(mock);

    bool avalid, pvalid, psat, aint, pint;
    chip.status(avalid, pvalid, psat, aint, pint);
    check_true(avalid, "status avalid=True with STATUS=0x01");
    check_true(!pvalid, "status pvalid=False with STATUS=0x01");
}

static void test_clear_interrupt_writes_command() {
    I2CConnectionMock mock;
    mock.setRegister(CR(0x12), {0x39});
    mock.setRegister(CR(0x0F), {0x00});
    mock.setRegister(CR(0x01), {0xDB});
    mock.setRegister(CR(0x02), {0xFF});
    mock.setRegister(CR(0x0E), {0x08});
    mock.setRegister(CR(0x0D), {0x00});
    mock.setRegister(CR(0x14), {0x00, 0x00});
    mock.setRegister(CR(0x16), {0x00, 0x00});
    mock.setRegister(CR(0x18), {0x00, 0x00});
    APDS9930Full chip(mock);
    const auto& writes_before = mock.writes();
    size_t before = writes_before.size();
    chip.clear_interrupt(2);  // proximity
    const auto& writes_after = mock.writes();
    bool prox_written = false;
    for (size_t i = before; i < writes_after.size(); i++) {
        if (writes_after[i].size() == 1 && writes_after[i][0] == 0xE5) prox_written = true;
    }
    check_true(prox_written, "clear_interrupt(proximity) writes 0xE5");

    before = mock.writes().size();
    chip.clear_interrupt(1);  // ALS
    bool als_written = false;
    for (size_t i = before; i < mock.writes().size(); i++) {
        if (mock.writes()[i].size() == 1 && mock.writes()[i][0] == 0xE6) als_written = true;
    }
    check_true(als_written, "clear_interrupt(als) writes 0xE6");

    before = mock.writes().size();
    chip.clear_interrupt(0);  // both
    bool both_written = false;
    for (size_t i = before; i < mock.writes().size(); i++) {
        if (mock.writes()[i].size() == 1 && mock.writes()[i][0] == 0xE7) both_written = true;
    }
    check_true(both_written, "clear_interrupt(both) writes 0xE7");
}

int main() {
    test_minimal_construction();
    test_proximity_returns_16bit();
    test_lux_zero_when_dark();
    test_lux_positive_when_visible_only();
    test_full_configure_als();
    test_full_status_decoded();
    test_clear_interrupt_writes_command();
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}