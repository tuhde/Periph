#include <stdio.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "AS5600.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Access protected register constants for building expected values.
class AS5600TestAccess : public AS5600Full {
public:
    using AS5600Full::AS5600Full;
    using AS5600Full::REG_ZMCO;
    using AS5600Full::REG_ZPOS_H;
    using AS5600Full::REG_ZPOS_L;
    using AS5600Full::REG_MPOS_H;
    using AS5600Full::REG_MPOS_L;
    using AS5600Full::REG_MANG_H;
    using AS5600Full::REG_MANG_L;
    using AS5600Full::REG_CONF_H;
    using AS5600Full::REG_CONF_L;
    using AS5600Full::REG_STATUS;
    using AS5600Full::REG_RAW_ANGLE_H;
    using AS5600Full::REG_ANGLE_H;
    using AS5600Full::REG_AGC;
    using AS5600Full::REG_MAGNITUDE_H;
    using AS5600Full::REG_BURN;
    using AS5600Full::STATUS_MD;
    using AS5600Full::STATUS_ML;
    using AS5600Full::STATUS_MH;
};

int main() {
    I2CConnectionMock connection;
    // STATUS: MD=1 (magnet detected), MH=0, ML=0.
    connection.setRegister(AS5600TestAccess::REG_STATUS, {0x08});

    AS5600TestAccess sensor(connection);
    check_true(true, "init");

    check_true(sensor.is_magnet_detected(), "is_magnet_detected");
    check_true(!sensor.is_magnet_too_strong(), "is_magnet_too_strong_false");
    check_true(!sensor.is_magnet_too_weak(), "is_magnet_too_weak_false");

    // ANGLE burst (0x0E-0x0F): H=0x01, L=0x23 -> raw = 0x0123 = 291.
    connection.setRegister(AS5600TestAccess::REG_ANGLE_H, {0x01, 0x23});
    check_true(sensor.angle_raw() == 291, "angle_raw");
    check_true(fabsf(sensor.angle() - (291.0f * 360.0f / 4096.0f)) < 1e-4f, "angle");

    // RAW_ANGLE burst (0x0C-0x0D): H=0x02, L=0x00 -> raw = 512 -> 45.0 degrees.
    connection.setRegister(AS5600TestAccess::REG_RAW_ANGLE_H, {0x02, 0x00});
    check_true(sensor.raw_angle() == 512, "raw_angle");
    check_true(fabsf(sensor.raw_angle_degrees() - 45.0f) < 1e-4f, "raw_angle_degrees");

    connection.setRegister(AS5600TestAccess::REG_AGC, {128});
    check_true(sensor.agc() == 128, "agc");

    // MAGNITUDE burst (0x1B-0x1C): H=0x00, L=0x64 -> raw = 100.
    connection.setRegister(AS5600TestAccess::REG_MAGNITUDE_H, {0x00, 0x64});
    check_true(sensor.magnitude() == 100, "magnitude");

    // STATUS: MD=1, MH=1 (magnet too strong).
    connection.setRegister(AS5600TestAccess::REG_STATUS, {0x28});
    check_true(sensor.is_magnet_too_strong(), "is_magnet_too_strong_true");
    check_true(sensor.status_byte() == 0x28, "status_byte");

    // configure() must preserve CONF_H[7:6] reserved bits (preloaded as 0xC5).
    connection.setRegister(AS5600TestAccess::REG_CONF_H, {0xC5, 0x00});
    sensor.configure(1, 2, 1, 3, 2, 5, true);
    const auto& regs = connection.registers();
    check_true(regs.at(AS5600TestAccess::REG_CONF_H) == 0xF6 &&
               regs.at(AS5600TestAccess::REG_CONF_L) == 0xD9, "configure");

    sensor.set_zero_position(1000);
    check_true(sensor.zero_position() == 1000, "zero_position");

    sensor.set_max_position(2000);
    check_true(sensor.max_position() == 2000, "max_position");

    sensor.set_max_angle(2048);
    check_true(sensor.max_angle() == 2048, "max_angle");

    connection.setRegister(AS5600TestAccess::REG_ZMCO, {0x02});
    check_true(sensor.burn_count() == 2, "burn_count");

    // burn_angle(): MD=1 (STATUS=0x28), ZMCO=2 < 3 -> succeeds, writes BURN=0x80.
    sensor.burn_angle();
    const auto& lastWrite1 = connection.writes().back();
    check_true(lastWrite1.size() == 2 && lastWrite1[0] == AS5600TestAccess::REG_BURN &&
               lastWrite1[1] == 0x80, "burn_angle_writes");

    // burn_setting(): requires ZMCO=0.
    connection.setRegister(AS5600TestAccess::REG_ZMCO, {0x00});
    sensor.burn_setting();
    const auto& lastWrite2 = connection.writes().back();
    check_true(lastWrite2.size() == 2 && lastWrite2[0] == AS5600TestAccess::REG_BURN &&
               lastWrite2[1] == 0x40, "burn_setting_writes");

    // Precondition failures are silent no-ops in the C++ driver: reads still
    // issue a register-address write phase (logged in writes()), but no
    // 2-byte BURN data write is ever appended.
    auto burnWriteCount = [&]() {
        int n = 0;
        for (const auto& w : connection.writes()) {
            if (w.size() == 2 && w[0] == AS5600TestAccess::REG_BURN) n++;
        }
        return n;
    };

    int burnWritesBefore = burnWriteCount();
    connection.setRegister(AS5600TestAccess::REG_STATUS, {0x00}); // MD=0
    sensor.burn_angle();
    check_true(burnWriteCount() == burnWritesBefore, "burn_angle_no_magnet_is_noop");

    connection.setRegister(AS5600TestAccess::REG_STATUS, {0x08}); // MD=1
    connection.setRegister(AS5600TestAccess::REG_ZMCO, {0x03});   // ZMCO limit reached
    burnWritesBefore = burnWriteCount();
    sensor.burn_angle();
    check_true(burnWriteCount() == burnWritesBefore, "burn_angle_zmco_limit_is_noop");

    connection.setRegister(AS5600TestAccess::REG_ZMCO, {0x01});
    burnWritesBefore = burnWriteCount();
    sensor.burn_setting();
    check_true(burnWriteCount() == burnWritesBefore, "burn_setting_zmco_nonzero_is_noop");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
