#include <stdio.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "INA260.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Access protected register constants for building expected values.
class INA260TestAccess : public INA260Full {
public:
    using INA260Full::INA260Full;
    using INA260Full::REG_CONFIG;
    using INA260Full::REG_BUS;
    using INA260Full::REG_POWER;
    using INA260Full::REG_CURRENT;
};

static bool wrote(const std::vector<uint8_t>& w, uint8_t reg, uint8_t hi, uint8_t lo) {
    return w.size() == 3 && w[0] == reg && w[1] == hi && w[2] == lo;
}

int main() {
    I2CConnectionMock connection;

    // Construction makes no bus access: no calibration, chip power-on state is the default.
    INA260TestAccess sensor(connection);
    check_true(connection.writes().empty(), "init_no_writes");

    // Bus voltage: raw=6400 (0x1900) -> 6400 * 1.25e-3 = 8.0 V
    connection.setRegister(INA260TestAccess::REG_BUS, {0x19, 0x00});
    check_true(sensor.voltage() == 8.0f, "voltage");

    // Current: raw signed = -100 (0xFF9C) -> -0.125 A
    connection.setRegister(INA260TestAccess::REG_CURRENT, {0xFF, 0x9C});
    check_true(fabsf(sensor.current() - (-0.125f)) < 1e-6f, "current_negative");

    // Current: datasheet example 0x2710 = 10000 -> 12.5 A
    connection.setRegister(INA260TestAccess::REG_CURRENT, {0x27, 0x10});
    check_true(fabsf(sensor.current() - 12.5f) < 1e-4f, "current");

    // Power: datasheet example 0x3A7F = 14975 -> 149.75 W
    connection.setRegister(INA260TestAccess::REG_POWER, {0x3A, 0x7F});
    check_true(fabsf(sensor.power() - 149.75f) < 1e-3f, "power");

    // configure(avg=2, vbus_ct=3, ish_ct=5, mode=6) -> 0x6000|(2<<9)|(3<<6)|(5<<3)|6 = 0x64EE
    connection.clearWrites();
    sensor.configure(2, 3, 5, 6);
    check_true(wrote(connection.writes().back(), INA260TestAccess::REG_CONFIG, 0x64, 0xEE), "configure");

    // trigger(): same settings, MODE = 3 -> 0x64EB
    sensor.trigger();
    check_true(wrote(connection.writes().back(), INA260TestAccess::REG_CONFIG, 0x64, 0xEB), "trigger");

    // conversion_ready(): Mask/Enable CVRF bit (0x0008)
    connection.setRegister(0x06, {0x00, 0x08});
    check_true(sensor.conversion_ready() == true, "conversion_ready_true");
    connection.setRegister(0x06, {0x00, 0x00});
    check_true(sensor.conversion_ready() == false, "conversion_ready_false");

    // overflow(): Mask/Enable OVF bit (0x0004)
    connection.setRegister(0x06, {0x00, 0x04});
    check_true(sensor.overflow() == true, "overflow_true");

    // set_alert(POL, 1.5 W, polarity, latch): limit 150 (0x0096) written first, then mask 0x0803
    connection.clearWrites();
    sensor.set_alert(INA260Full::POL, 1.5f, true, true);
    check_true(connection.writes().size() == 2 &&
               wrote(connection.writes()[0], 0x07, 0x00, 0x96) &&
               wrote(connection.writes()[1], 0x06, 0x08, 0x03), "set_alert_pol_limit_first");

    // set_alert(OCL, 5.0 A): 4000 (0x0FA0), mask 0x8000
    connection.clearWrites();
    sensor.set_alert(INA260Full::OCL, 5.0f);
    check_true(connection.writes().size() == 2 &&
               wrote(connection.writes()[0], 0x07, 0x0F, 0xA0) &&
               wrote(connection.writes()[1], 0x06, 0x80, 0x00), "set_alert_ocl");

    // set_alert(UCL, -1.0 A): -800 -> 0xFCE0
    connection.clearWrites();
    sensor.set_alert(INA260Full::UCL, -1.0f);
    check_true(wrote(connection.writes()[0], 0x07, 0xFC, 0xE0), "set_alert_ucl_negative");

    // set_alert(CNVR): limit register not written
    connection.clearWrites();
    sensor.set_alert(INA260Full::CNVR);
    check_true(connection.writes().size() == 1 &&
               wrote(connection.writes()[0], 0x06, 0x04, 0x00), "set_alert_cnvr");

    // clear_alert(): Mask/Enable = 0
    sensor.clear_alert();
    check_true(wrote(connection.writes().back(), 0x06, 0x00, 0x00), "clear_alert");

    // alert_flags(): raw Mask/Enable register
    connection.setRegister(0x06, {0x08, 0x03});
    check_true(sensor.alert_flags() == 0x0803, "alert_flags");

    // reset(): writes CONFIG = 0x8000
    sensor.reset();
    check_true(wrote(connection.writes().back(), INA260TestAccess::REG_CONFIG, 0x80, 0x00), "reset");

    // shutdown(): MODE = 000 with default settings -> 0x6120
    sensor.shutdown();
    check_true(wrote(connection.writes().back(), INA260TestAccess::REG_CONFIG, 0x61, 0x20), "shutdown");

    // wake(): restore MODE = 111 -> 0x6127
    sensor.wake();
    check_true(wrote(connection.writes().back(), INA260TestAccess::REG_CONFIG, 0x61, 0x27), "wake");

    // manufacturer_id() / die_id()
    connection.setRegister(0xFE, {0x54, 0x49});
    check_true(sensor.manufacturer_id() == 0x5449, "manufacturer_id");
    connection.setRegister(0xFF, {0x22, 0x70});
    check_true(sensor.die_id() == 0x2270, "die_id");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
