#include <stdio.h>
#include <math.h>
#include "SPIConnectionMock.h"
#include "OutputPin.h"
#include "AD7705.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

class FakeOutputPin : public OutputPin {
public:
    std::vector<bool> calls;
    void set(bool high) override { calls.push_back(high); }
};

int main() {
    // --- AD7705Minimal ctor: Clock + Setup Register writes, self-calibrate ---
    // mclk_hz=4915200 -> CLKDIV=1, CLK=1, FS1:FS0=00 (50 Hz) -> Clock reg = 0x0C
    // (matches the spec's own worked example). Setup reg = MODE_SELF_CAL|GAIN_1|
    // BIPOLAR|UNBUFFERED|FSYNC_RUN = 0x40.
    SPIConnectionMock connection;
    AD7705Minimal sensor(connection, 2.5f, 4915200);
    check_true(connection.writes()[0] == std::vector<uint8_t>({0x20, 0x0C}), "init_clock_write");
    check_true(connection.writes()[1] == std::vector<uint8_t>({0x10, 0x40}), "init_setup_write");
    check_true(connection.writes()[2] == std::vector<uint8_t>({0x08}), "init_waits_drdy");

    // --- AD7705Minimal::read_raw / read_voltage: Channel 1, gain 1, bipolar ---
    // Data Register CH1 read comm byte = REG_DATA|RW_READ|CH1 = 0x38.
    // code=0xC000 (49152) -> bipolar: ((49152-32768)/32768)*(2.5/1) = 1.25 V
    connection.setRegister(0x38, {0xC0, 0x00});
    check_true(sensor.read_raw() == 0xC000, "read_raw");
    check_true(fabsf(sensor.read_voltage() - 1.25f) < 1e-6f, "read_voltage");

    // --- AD7705Full::configure + read_voltage: per-channel independence ---
    // Regression test for a driver bug found while writing this test: configure()
    // only updated the shared _gain/_bipolar when channel==1, so read_voltage(2)
    // silently converted using channel 1's gain/bipolar instead of channel 2's.
    SPIConnectionMock connection2;
    AD7705Full full(connection2, 2.5f, 4915200);

    // configure(channel=2, gain=4, bipolar=false, buffered=true, output_rate_hz=250):
    // Clock reg CH2 (comm=0x21): CLKDIV=1,CLK=1,FS=index(250)=2 -> 0x0E
    // Setup reg CH2 (comm=0x11): MODE_NORMAL|GAIN_4(0x10)|UNIPOLAR(0x04)|BUFFERED(0x02) = 0x16
    full.configure(2, 4, false, true, 250);
    check_true(connection2.writes()[3] == std::vector<uint8_t>({0x21, 0x0E}), "configure_ch2_clock");
    check_true(connection2.writes()[4] == std::vector<uint8_t>({0x11, 0x16}), "configure_ch2_setup");

    // Data Register CH2 read comm = REG_DATA|RW_READ|CH2 = 0x39.
    // code=0x8000 (32768), gain=4, unipolar -> (32768/65536)*(2.5/4) = 0.3125 V
    connection2.setRegister(0x39, {0x80, 0x00});
    check_true(fabsf(full.read_voltage(2) - 0.3125f) < 1e-6f, "read_voltage_ch2_uses_own_gain");

    // Channel 1 was never configured, so it must still use the ctor default
    // (gain 1, bipolar) -- unaffected by channel 2's configure() above.
    connection2.setRegister(0x38, {0xC0, 0x00});
    check_true(fabsf(full.read_voltage(1) - 1.25f) < 1e-6f, "read_voltage_ch1_unaffected_by_ch2_configure");

    // --- self_calibrate: also regression-checks per-channel gain/bipolar use ---
    // setup = MODE_SELF_CAL(0x40)|GAIN_4(0x10)|UNIPOLAR(0x04)|BUFFERED(0x02) = 0x56
    // (channel 2's configured state from above, not channel 1's defaults)
    size_t n = connection2.writes().size();
    full.self_calibrate(2);
    check_true(connection2.writes()[n] == std::vector<uint8_t>({0x11, 0x56}), "self_calibrate_ch2_uses_own_state");

    // --- system_calibrate_zero / system_calibrate_full: mode bits, channel 1 ---
    n = connection2.writes().size();
    full.system_calibrate_zero(1);
    check_true(connection2.writes()[n] == std::vector<uint8_t>({0x10, 0x80}), "system_calibrate_zero");  // MODE_ZERO_SYS|GAIN_1|BIPOLAR

    n = connection2.writes().size();
    full.system_calibrate_full(1);
    check_true(connection2.writes()[n] == std::vector<uint8_t>({0x10, 0xC0}), "system_calibrate_full");  // MODE_FULL_SYS|GAIN_1|BIPOLAR

    // Regression test for a second bug found while writing this test:
    // system_calibrate_full()'s gain switch was missing `case 2`, silently
    // no-op'ing (returning without writing) for gain=2.
    full.configure(1, 2, true, false, 50);
    n = connection2.writes().size();
    full.system_calibrate_full(1);
    // +2: the Setup Register write itself, plus _wait_drdy()'s poll.
    check_true(connection2.writes().size() == n + 2, "system_calibrate_full_gain2_not_silently_skipped");
    check_true(connection2.writes()[n] == std::vector<uint8_t>({0x10, 0xC8}), "system_calibrate_full_gain2_bits");  // MODE_FULL_SYS|GAIN_2(0x08)|BIPOLAR

    // --- offset / gain calibration: 24-bit read/write ---
    // Zero-Scale reg CH1 read comm = REG_OFFSET|RW_READ|CH1 = 0x68.
    connection2.setRegister(0x68, {0x12, 0x34, 0x56});
    check_true(full.get_offset_calibration(1) == 0x123456, "get_offset_calibration");

    n = connection2.writes().size();
    full.set_offset_calibration(0xABCDEF, 1);
    check_true(connection2.writes()[n] == std::vector<uint8_t>({0x60, 0xAB, 0xCD, 0xEF}), "set_offset_calibration");

    // Full-Scale reg CH1 read comm = REG_GAIN|RW_READ|CH1 = 0x78.
    connection2.setRegister(0x78, {0x01, 0x02, 0x03});
    check_true(full.get_gain_calibration(1) == 0x010203, "get_gain_calibration");

    n = connection2.writes().size();
    full.set_gain_calibration(0x040506, 1);
    check_true(connection2.writes()[n] == std::vector<uint8_t>({0x70, 0x04, 0x05, 0x06}), "set_gain_calibration");

    // --- standby / wakeup ---
    n = connection2.writes().size();
    full.standby();
    check_true(connection2.writes()[n] == std::vector<uint8_t>({0x04}), "standby");  // comm(COMM,WRITE,CH1)|STBY_SLEEP

    n = connection2.writes().size();
    full.wakeup();
    check_true(connection2.writes()[n] == std::vector<uint8_t>({0x00}), "wakeup_clears_stby");
    check_true(connection2.writes()[n + 1] == std::vector<uint8_t>({0x08}), "wakeup_waits_drdy");

    // --- reset: no-op without a reset_pin, pulses low then high with one ---
    full.reset();  // no reset_pin supplied -> must not crash
    check_true(true, "reset_without_pin_does_not_crash");

    FakeOutputPin resetPin;
    SPIConnectionMock connection3;
    AD7705Full withReset(connection3, 2.5f, 4915200, &resetPin);
    check_true(resetPin.calls.size() == 2 && resetPin.calls[0] == false && resetPin.calls[1] == true,
               "init_with_reset_pin_pulses");
    resetPin.calls.clear();
    withReset.reset();
    check_true(resetPin.calls.size() == 2 && resetPin.calls[0] == false && resetPin.calls[1] == true,
               "reset_pulses_pin");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
