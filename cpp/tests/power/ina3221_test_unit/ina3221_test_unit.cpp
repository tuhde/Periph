#include <stdio.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "INA3221.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Access protected register constants for building expected values.
class INA3221TestAccess : public INA3221Full {
public:
    using INA3221Full::INA3221Full;
    using INA3221Full::REG_CONFIG;
    using INA3221Full::REG_SHUNT1;
    using INA3221Full::REG_BUS1;
    using INA3221Full::REG_SHUNT2;
    using INA3221Full::REG_BUS2;
};

int main() {
    I2CConnectionMock connection;

    // Construction (default r_shunt=0.1 for all 3 channels) writes nothing.
    INA3221TestAccess sensor(connection);
    check_true(connection.writes().empty(), "init_writes_nothing");

    // --- Channel 1 ---
    // Bus1 raw=10000 (0x2710) -> (10000>>3)*8e-3 = 10.0 V
    connection.setRegister(INA3221TestAccess::REG_BUS1, {0x27, 0x10});
    check_true(sensor.voltage(1) == 10.0f, "voltage_ch1");

    // Shunt1 raw signed = -400 (0xFE70) -> -400 * 5e-6 = -0.002 V
    connection.setRegister(INA3221TestAccess::REG_SHUNT1, {0xFE, 0x70});
    check_true(fabsf(sensor.shunt_voltage(1) - (-0.002f)) < 1e-6f, "shunt_voltage_ch1");
    check_true(fabsf(sensor.current(1) - (-0.02f)) < 1e-6f, "current_ch1");

    // power(1): SHUNT1 (0x01) and BUS1 (0x02) are adjacent registers, and the
    // mock's byte-slot model can't hold two independent 16-bit values across
    // adjacent addresses at once (writing one clobbers the shared byte slot) -
    // so the SHUNT1 low byte and BUS1 high byte are chosen equal (0x10) to
    // survive either write order. SHUNT1=0xFF10 (-240 signed) -> -0.0012 V;
    // BUS1=0x1000 (4096) -> 4.096 V.
    connection.setRegister(INA3221TestAccess::REG_SHUNT1, {0xFF, 0x10});
    connection.setRegister(INA3221TestAccess::REG_BUS1, {0x10, 0x00});
    check_true(fabsf(sensor.power(1) - (4.096f * -0.012f)) < 1e-6f, "power_ch1");

    // --- Channel 2 ---
    // Bus2 raw=4096 (0x1000) -> (4096>>3)*8e-3 = 4.096 V
    connection.setRegister(INA3221TestAccess::REG_BUS2, {0x10, 0x00});
    check_true(sensor.voltage(2) == 4.096f, "voltage_ch2");

    // Shunt2 raw=800 (0x0320) -> 800 * 5e-6 = 0.004 V
    connection.setRegister(INA3221TestAccess::REG_SHUNT2, {0x03, 0x20});
    check_true(fabsf(sensor.shunt_voltage(2) - 0.004f) < 1e-6f, "shunt_voltage_ch2");
    check_true(fabsf(sensor.current(2) - 0.04f) < 1e-6f, "current_ch2");

    // power(2): same adjacent-register overlap as power(1); SHUNT2 low byte
    // and BUS2 high byte chosen equal (0x08). SHUNT2=0x0108 (264) -> 0.00132 V;
    // BUS2=0x0800 (2048) -> 2.048 V.
    connection.setRegister(INA3221TestAccess::REG_SHUNT2, {0x01, 0x08});
    connection.setRegister(INA3221TestAccess::REG_BUS2, {0x08, 0x00});
    check_true(fabsf(sensor.power(2) - (2.048f * 0.0132f)) < 1e-6f, "power_ch2");

    // Invalid channel silently clamps to channel 1 (C++ driver has no
    // exceptions) - verify it reads channel 1's Bus register.
    connection.setRegister(INA3221TestAccess::REG_BUS1, {0x27, 0x10});
    check_true(sensor.voltage(9) == 10.0f, "invalid_channel_clamps_to_1");

    // configure(avg=3, vbus_ct=2, vsh_ct=1, mode=5) preserves channel-enable
    // bits (0x7000) from the current Configuration Register.
    connection.setRegister(INA3221TestAccess::REG_CONFIG, {0x71, 0x27});
    sensor.configure(3, 2, 1, 5);
    bool sawConfigure = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 3 && w[0] == INA3221TestAccess::REG_CONFIG && w[1] == 0x76 && w[2] == 0x8D)
            sawConfigure = true;
    }
    check_true(sawConfigure, "configure");

    // enable_channel(2, true): CH2en is bit 13.
    connection.setRegister(INA3221TestAccess::REG_CONFIG, {0x01, 0x27});
    sensor.enable_channel(2, true);
    bool sawEnableChannel = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 3 && w[0] == INA3221TestAccess::REG_CONFIG && w[1] == 0x21 && w[2] == 0x27)
            sawEnableChannel = true;
    }
    check_true(sawEnableChannel, "enable_channel");

    // channel_enabled(1): CH1en is bit 14.
    connection.setRegister(INA3221TestAccess::REG_CONFIG, {0x41, 0x27});
    check_true(sensor.channel_enabled(1) == true, "channel_enabled");

    // conversion_ready(): CVRF is bit 0.
    connection.setRegister(0x0F, {0x00, 0x01});
    check_true(sensor.conversion_ready() == true, "conversion_ready");

    // set_critical_alert(channel=2, limit_v=0.048, latch=true):
    // raw = ((int)(0.048/40e-6) << 3) & 0xFFF8 = (1200 << 3) & 0xFFF8 = 0x2580.
    connection.setRegister(0x0F, {0x00, 0x00});
    sensor.set_critical_alert(2, 0.048f, true);
    bool sawCritLimit = false, sawCritLatch = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 3 && w[0] == 0x09 && w[1] == 0x25 && w[2] == 0x80) sawCritLimit = true;
        if (w.size() == 3 && w[0] == 0x0F && w[1] == 0x04 && w[2] == 0x00) sawCritLatch = true;
    }
    check_true(sawCritLimit, "set_critical_alert_limit");
    check_true(sawCritLatch, "set_critical_alert_latch");

    // set_warning_alert(channel=1, limit_v=0.024, latch=false):
    // raw = ((int)(0.024/40e-6) << 3) & 0xFFF8 = (600 << 3) & 0xFFF8 = 0x12C0.
    connection.setRegister(0x0F, {0x04, 0x00});
    sensor.set_warning_alert(1, 0.024f, false);
    bool sawWarnLimit = false, sawWarnLatch = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 3 && w[0] == 0x08 && w[1] == 0x12 && w[2] == 0xC0) sawWarnLimit = true;
        if (w.size() == 3 && w[0] == 0x0F && w[1] == 0x04 && w[2] == 0x00) sawWarnLatch = true;
    }
    check_true(sawWarnLimit, "set_warning_alert_limit");
    check_true(sawWarnLatch, "set_warning_alert_latch");

    // alert_flags(): raw Mask/Enable register.
    connection.setRegister(0x0F, {0x02, 0x41});
    check_true(sensor.alert_flags() == 0x0241, "alert_flags");

    // set_summation_channels([1], limit_v=0.1) with a stale SCC3 bit (0x1000)
    // already set: the fix must clear bits 14:12 (0x7000), not just 15:13
    // (0xE000), or SCC3 would incorrectly survive; and channel 1 must map to
    // bit 14 (SCC1), not the reserved bit 15.
    connection.setRegister(0x0F, {0x10, 0x00});
    uint8_t channels1[] = {1};
    sensor.set_summation_channels(channels1, 1, 0.1f);
    bool sawSummationMask = false, sawSummationLimit = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 3 && w[0] == 0x0F && w[1] == 0x40 && w[2] == 0x00) sawSummationMask = true;
        if (w.size() == 3 && w[0] == 0x0E && w[1] == 0x13 && w[2] == 0x88) sawSummationLimit = true;
    }
    check_true(sawSummationMask, "set_summation_channels_clears_stale_scc3");
    check_true(sawSummationLimit, "set_summation_channels_limit");

    // summation_value(): raw=0x2328 (9000) -> 9000 * 20e-6 = 0.18 V.
    connection.setRegister(0x0D, {0x23, 0x28});
    check_true(fabsf(sensor.summation_value() - 0.18f) < 1e-6f, "summation_value");

    // set_power_valid_limits(upper_v=8.112, lower_v=4.096):
    // raw_upper = ((int)(8.112/8e-3) << 3) & 0xFFF8 = (1014 << 3) & 0xFFF8 = 0x1FB0
    // raw_lower = ((int)(4.096/8e-3) << 3) & 0xFFF8 = (512 << 3) & 0xFFF8 = 0x1000
    sensor.set_power_valid_limits(8.112f, 4.096f);
    bool sawPVUpper = false, sawPVLower = false;
    for (const auto& w : connection.writes()) {
        if (w.size() == 3 && w[0] == 0x10 && w[1] == 0x1F && w[2] == 0xB0) sawPVUpper = true;
        if (w.size() == 3 && w[0] == 0x11 && w[1] == 0x10 && w[2] == 0x00) sawPVLower = true;
    }
    check_true(sawPVUpper, "set_power_valid_upper");
    check_true(sawPVLower, "set_power_valid_lower");

    // power_valid(): PVF is bit 2.
    connection.setRegister(0x0F, {0x00, 0x04});
    check_true(sensor.power_valid() == true, "power_valid");

    // shutdown(): reads CONFIG, saves MODE bits, writes CONFIG & 0xFFF8.
    connection.setRegister(INA3221TestAccess::REG_CONFIG, {0x71, 0x27});
    sensor.shutdown();
    const auto& shutdownWrite = connection.writes().back();
    check_true(shutdownWrite.size() == 3 && shutdownWrite[0] == INA3221TestAccess::REG_CONFIG &&
               shutdownWrite[1] == 0x71 && shutdownWrite[2] == 0x20, "shutdown");

    // wake(): reads CONFIG, restores saved MODE bits.
    connection.setRegister(INA3221TestAccess::REG_CONFIG, {0x71, 0x20});
    sensor.wake();
    const auto& wakeWrite = connection.writes().back();
    check_true(wakeWrite.size() == 3 && wakeWrite[0] == INA3221TestAccess::REG_CONFIG &&
               wakeWrite[1] == 0x71 && wakeWrite[2] == 0x27, "wake");

    // reset(): writes CONFIG = 0x8000 (RST bit) only.
    sensor.reset();
    const auto& resetWrite = connection.writes().back();
    check_true(resetWrite.size() == 3 && resetWrite[0] == INA3221TestAccess::REG_CONFIG &&
               resetWrite[1] == 0x80 && resetWrite[2] == 0x00, "reset");

    // manufacturer_id() / die_id()
    connection.setRegister(0xFE, {0x54, 0x49});
    check_true(sensor.manufacturer_id() == 0x5449, "manufacturer_id");
    connection.setRegister(0xFF, {0x32, 0x20});
    check_true(sensor.die_id() == 0x3220, "die_id");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
