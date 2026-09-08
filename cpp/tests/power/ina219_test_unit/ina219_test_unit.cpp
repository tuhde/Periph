#include <stdio.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "INA219.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Access protected register constants for building expected values.
class INA219TestAccess : public INA219Full {
public:
    using INA219Full::INA219Full;
    using INA219Full::REG_CONFIG;
    using INA219Full::REG_SHUNT;
    using INA219Full::REG_BUS;
    using INA219Full::REG_POWER;
    using INA219Full::REG_CURRENT;
    using INA219Full::REG_CAL;
};

int main() {
    I2CConnectionMock connection;

    // r_shunt=0.1, max_current=2.0 -> current_lsb=2.0/32768,
    // cal=(uint16_t)(0.04096/(current_lsb*r_shunt)) & 0xFFFE = 0x1A36.
    INA219TestAccess sensor(connection, 0.1f, 2.0f);
    const auto& lastInit = connection.writes().back();
    check_true(lastInit.size() == 3 && lastInit[0] == INA219TestAccess::REG_CAL &&
               lastInit[1] == 0x1A && lastInit[2] == 0x36, "init_writes_calibration");

    // Bus Voltage: raw=(1000<<3)|0b010 = 0x1F42 -> voltage=4.0V, CNVR=1, OVF=0.
    connection.setRegister(INA219TestAccess::REG_BUS, {0x1F, 0x42});
    check_true(fabsf(sensor.voltage() - 4.0f) < 1e-6f, "voltage");
    check_true(sensor.conversion_ready() == true, "conversion_ready_true");
    check_true(sensor.overflow() == false, "overflow_false");

    // Bus Voltage: raw=(1000<<3)|0b001 = 0x1F41 -> CNVR=0, OVF=1.
    connection.setRegister(INA219TestAccess::REG_BUS, {0x1F, 0x41});
    check_true(sensor.overflow() == true, "overflow_true");

    // Shunt Voltage: raw=-500 (0xFE0C) -> -0.005 V.
    connection.setRegister(INA219TestAccess::REG_SHUNT, {0xFE, 0x0C});
    check_true(fabsf(sensor.shunt_voltage() - (-0.005f)) < 1e-6f, "shunt_voltage");

    // Current: raw=1000 (0x03E8) -> 1000 * current_lsb.
    connection.setRegister(INA219TestAccess::REG_CURRENT, {0x03, 0xE8});
    float expectedCurrent = 1000.0f * (2.0f / 32768.0f);
    check_true(fabsf(sensor.current() - expectedCurrent) < 1e-6f, "current");

    // Power: raw=2000 (0x07D0) -> 2000 * 20 * current_lsb.
    connection.setRegister(INA219TestAccess::REG_POWER, {0x07, 0xD0});
    float expectedPower = 2000.0f * 20.0f * (2.0f / 32768.0f);
    check_true(fabsf(sensor.power() - expectedPower) < 1e-6f, "power");

    // configure(brng=0, pga=1, badc=0x0B, sadc=0x02, mode=5) -> config = 0x0D95;
    // re-writes Calibration afterward.
    sensor.configure(0, 1, 0x0B, 0x02, 5);
    const auto& writes = connection.writes();
    const auto& configWrite = writes[writes.size() - 2];
    const auto& calWrite = writes[writes.size() - 1];
    check_true(configWrite.size() == 3 && configWrite[0] == INA219TestAccess::REG_CONFIG &&
               configWrite[1] == 0x0D && configWrite[2] == 0x95, "configure_writes_config");
    check_true(calWrite.size() == 3 && calWrite[0] == INA219TestAccess::REG_CAL &&
               calWrite[1] == 0x1A && calWrite[2] == 0x36, "configure_rewrites_cal");

    // shutdown(): MODE forced to 0, other CONFIG bits preserved (0x0D95 -> 0x0D90).
    sensor.shutdown();
    const auto& shutdownWrite = connection.writes().back();
    check_true(shutdownWrite.size() == 3 && shutdownWrite[0] == INA219TestAccess::REG_CONFIG &&
               shutdownWrite[1] == 0x0D && shutdownWrite[2] == 0x90, "shutdown");

    // wake(): restores the previously configured mode (5) -> 0x0D95.
    sensor.wake();
    const auto& wakeWrite = connection.writes().back();
    check_true(wakeWrite.size() == 3 && wakeWrite[0] == INA219TestAccess::REG_CONFIG &&
               wakeWrite[1] == 0x0D && wakeWrite[2] == 0x95, "wake");

    // trigger(): re-writes the current config unchanged.
    sensor.trigger();
    const auto& triggerWrite = connection.writes().back();
    check_true(triggerWrite.size() == 3 && triggerWrite[0] == INA219TestAccess::REG_CONFIG &&
               triggerWrite[1] == 0x0D && triggerWrite[2] == 0x95, "trigger");

    // reset(): sets RST, re-writes Calibration. (This driver does not restore
    // the last configure()'d Configuration afterward.)
    sensor.reset();
    const auto& writes2 = connection.writes();
    const auto& rstWrite = writes2[writes2.size() - 2];
    const auto& resetCalWrite = writes2[writes2.size() - 1];
    check_true(rstWrite.size() == 3 && rstWrite[0] == INA219TestAccess::REG_CONFIG &&
               rstWrite[1] == 0x80 && rstWrite[2] == 0x00, "reset_writes_rst");
    check_true(resetCalWrite.size() == 3 && resetCalWrite[0] == INA219TestAccess::REG_CAL &&
               resetCalWrite[1] == 0x1A && resetCalWrite[2] == 0x36, "reset_rewrites_cal");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
