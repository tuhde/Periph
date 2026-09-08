#include <stdio.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "INA226.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Access protected register constants for building expected values.
class INA226TestAccess : public INA226Full {
public:
    using INA226Full::INA226Full;
    using INA226Full::REG_CONFIG;
    using INA226Full::REG_SHUNT;
    using INA226Full::REG_BUS;
    using INA226Full::REG_POWER;
    using INA226Full::REG_CURRENT;
    using INA226Full::REG_CAL;
    using INA226Full::CONFIG_DEFAULT;
};

int main() {
    I2CConnectionMock connection;

    // Construction: r_shunt=0.1, max_current=2.0 (defaults) -> current_lsb=6.103515625e-5,
    // cal=(uint16_t)(0.00512/(current_lsb*0.1))=838 (0x0346). Constructor writes CONFIG then CAL.
    INA226TestAccess sensor(connection);
    check_true(true, "init");

    bool sawConfigDefault = false, sawCalDefault = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 3 && w[0] == INA226TestAccess::REG_CONFIG &&
            w[1] == 0x41 && w[2] == 0x27) sawConfigDefault = true;
        if (w.size() == 3 && w[0] == INA226TestAccess::REG_CAL &&
            w[1] == 0x03 && w[2] == 0x46) sawCalDefault = true;
    }
    check_true(sawConfigDefault, "init_writes_config_default");
    check_true(sawCalDefault, "init_writes_calibration");

    // Bus voltage: raw=6400 (0x1900) -> 6400 * 1.25e-3 = 8.0 V
    connection.setRegister(INA226TestAccess::REG_BUS, {0x19, 0x00});
    check_true(sensor.voltage() == 8.0f, "voltage");

    // Shunt voltage: raw signed = -100 (0xFF9C) -> -100 * 2.5e-6 V
    connection.setRegister(INA226TestAccess::REG_SHUNT, {0xFF, 0x9C});
    check_true(fabsf(sensor.shunt_voltage() - (-2.5e-4f)) < 1e-9f, "shunt_voltage");

    // Current: raw signed = 1000 (0x03E8) -> 1000 * current_lsb
    connection.setRegister(INA226TestAccess::REG_CURRENT, {0x03, 0xE8});
    float currentLsb = 2.0f / 32768.0f;
    check_true(fabsf(sensor.current() - (1000 * currentLsb)) < 1e-9f, "current");

    // Power: raw = 500 (0x01F4) -> 500 * 25 * current_lsb
    connection.setRegister(INA226TestAccess::REG_POWER, {0x01, 0xF4});
    check_true(fabsf(sensor.power() - (500 * 25.0f * currentLsb)) < 1e-6f, "power");

    // configure(avg=2, vbus_ct=3, vsh_ct=5, mode=6) -> config = 0x04EE
    sensor.configure(2, 3, 5, 6);
    bool sawConfigureWrite = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 3 && w[0] == INA226TestAccess::REG_CONFIG && w[1] == 0x04 && w[2] == 0xEE)
            sawConfigureWrite = true;
    }
    check_true(sawConfigureWrite, "configure");

    // conversion_ready(): Mask/Enable CVRF bit (0x0008)
    connection.setRegister(0x06, {0x00, 0x08});
    check_true(sensor.conversion_ready() == true, "conversion_ready_true");
    connection.setRegister(0x06, {0x00, 0x00});
    check_true(sensor.conversion_ready() == false, "conversion_ready_false");

    // overflow(): Mask/Enable OVF bit (0x0004)
    connection.setRegister(0x06, {0x00, 0x04});
    check_true(sensor.overflow() == true, "overflow_true");

    // set_alert(POL, limit=1.5, polarity=true, latch=true):
    // raw = (uint16_t)(1.5 / (25*current_lsb)) = 983 (0x03D7); mask = POL|0x0002|0x0001 = 0x0803
    sensor.set_alert(INA226Full::POL, 1.5f, true, true);
    bool sawMaskWrite = false, sawAlertWrite = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 3 && w[0] == 0x06 && w[1] == 0x08 && w[2] == 0x03) sawMaskWrite = true;
        if (w.size() == 3 && w[0] == 0x07 && w[1] == 0x03 && w[2] == 0xD7) sawAlertWrite = true;
    }
    check_true(sawMaskWrite, "set_alert_mask");
    check_true(sawAlertWrite, "set_alert_limit");

    // alert_flags(): raw Mask/Enable register
    connection.setRegister(0x06, {0x08, 0x03});
    check_true(sensor.alert_flags() == 0x0803, "alert_flags");

    // reset(): writes CONFIG=0x8000, then re-writes CAL
    sensor.reset();
    const auto& writesAfterReset = connection.writes();
    const auto& resetConfigWrite = writesAfterReset[writesAfterReset.size() - 2];
    const auto& resetCalWrite = writesAfterReset[writesAfterReset.size() - 1];
    check_true(resetConfigWrite.size() == 3 && resetConfigWrite[0] == INA226TestAccess::REG_CONFIG &&
               resetConfigWrite[1] == 0x80 && resetConfigWrite[2] == 0x00, "reset_config");
    check_true(resetCalWrite.size() == 3 && resetCalWrite[0] == INA226TestAccess::REG_CAL &&
               resetCalWrite[1] == 0x03 && resetCalWrite[2] == 0x46, "reset_cal");

    // shutdown(): reads CONFIG, saves mode, writes CONFIG & 0xFFF8
    connection.setRegister(INA226TestAccess::REG_CONFIG, {0x41, 0x27});
    sensor.shutdown();
    const auto& shutdownWrite = connection.writes().back();
    check_true(shutdownWrite.size() == 3 && shutdownWrite[0] == INA226TestAccess::REG_CONFIG &&
               shutdownWrite[1] == 0x41 && shutdownWrite[2] == 0x20, "shutdown");

    // wake(): reads CONFIG, writes back with saved mode restored
    connection.setRegister(INA226TestAccess::REG_CONFIG, {0x41, 0x20});
    sensor.wake();
    const auto& wakeWrite = connection.writes().back();
    check_true(wakeWrite.size() == 3 && wakeWrite[0] == INA226TestAccess::REG_CONFIG &&
               wakeWrite[1] == 0x41 && wakeWrite[2] == 0x27, "wake");

    // manufacturer_id() / die_id()
    connection.setRegister(0xFE, {0x54, 0x49});
    check_true(sensor.manufacturer_id() == 0x5449, "manufacturer_id");
    connection.setRegister(0xFF, {0x22, 0x60});
    check_true(sensor.die_id() == 0x2260, "die_id");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
