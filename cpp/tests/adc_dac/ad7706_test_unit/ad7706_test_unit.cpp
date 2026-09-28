#include <stdio.h>
#include <math.h>
#include "SPIConnectionMock.h"
#include "OutputPin.h"
#include "AD7706.h"

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
    // --- AD7706Minimal ctor: Clock + Setup Register writes, self-calibrate ---
    SPIConnectionMock connection;
    AD7706Minimal sensor(connection, 2.5f, 4915200);
    check_true(connection.writes()[0] == std::vector<uint8_t>({0x20, 0x0C}), "init_clock_write");
    check_true(connection.writes()[1] == std::vector<uint8_t>({0x10, 0x40}), "init_setup_write");
    check_true(connection.writes()[2] == std::vector<uint8_t>({0x08}), "init_waits_drdy");

    // --- AD7706Minimal::read_raw / read_voltage: Channel 1, gain 1, bipolar ---
    connection.setRegister(0x38, {0xC0, 0x00});
    check_true(sensor.read_raw() == 0xC000, "read_raw");
    check_true(fabsf(sensor.read_voltage() - 1.25f) < 1e-6f, "read_voltage");

    // --- AD7706Full: three independent channels ---
    // Regression tests for driver bugs found while writing this test: (1)
    // configure() only updated the shared _gain/_bipolar/_buffered for
    // channel 1; (2) _configure_clock() was hardcoded to always write
    // Channel 1's Clock Register; (3) the bipolar voltage formula cast to
    // int16_t before subtracting 32768, wrapping any code > 32767 to the
    // wrong sign.
    SPIConnectionMock connection2;
    AD7706Full full(connection2, 2.5f, 4915200);

    // configure(2, gain=4, bipolar=false, buffered=true, 250 Hz):
    // Clock reg CH2 (comm=0x21) -> 0x0E, Setup reg CH2 (comm=0x11) -> 0x16.
    full.configure(2, 4, false, true, 250);
    check_true(connection2.writes()[3] == std::vector<uint8_t>({0x21, 0x0E}), "configure_ch2_clock");
    check_true(connection2.writes()[4] == std::vector<uint8_t>({0x11, 0x16}), "configure_ch2_setup");

    // configure(3, gain=8, bipolar=true, buffered=false, 500 Hz):
    // Channel 3 select = CH1:CH0=11 -> ch3=0x03.
    // Clock reg CH3 (comm=0x23): CLKDIV=1,CLK=1,FS=index(500)=3 -> 0x0F
    // Setup reg CH3 (comm=0x13): MODE_NORMAL|GAIN_8(0x18)|BIPOLAR|UNBUFFERED = 0x18
    full.configure(3, 8, true, false, 500);
    check_true(connection2.writes()[5] == std::vector<uint8_t>({0x23, 0x0F}), "configure_ch3_clock");
    check_true(connection2.writes()[6] == std::vector<uint8_t>({0x13, 0x18}), "configure_ch3_setup");

    // Data Register reads: CH2 comm=0x39, CH3 comm=0x3B.
    connection2.setRegister(0x39, {0x80, 0x00});  // code=0x8000, gain=4, unipolar -> 0.3125 V
    check_true(fabsf(full.read_voltage(2) - 0.3125f) < 1e-6f, "read_voltage_ch2_uses_own_gain");

    connection2.setRegister(0x3B, {0xE0, 0x00});  // code=0xE000, gain=8, bipolar -> 0.234375 V
    check_true(fabsf(full.read_voltage(3) - 0.234375f) < 1e-6f, "read_voltage_ch3_uses_own_gain");

    // Channel 1 was never configured -> still the ctor default (gain 1, bipolar).
    connection2.setRegister(0x38, {0xC0, 0x00});
    check_true(fabsf(full.read_voltage(1) - 1.25f) < 1e-6f, "read_voltage_ch1_unaffected");

    // --- self_calibrate: also regression-checks per-channel gain/bipolar use ---
    // setup = MODE_SELF_CAL(0x40)|GAIN_8(0x18)|BIPOLAR|UNBUFFERED = 0x58
    size_t n = connection2.writes().size();
    full.self_calibrate(3);
    check_true(connection2.writes()[n] == std::vector<uint8_t>({0x13, 0x58}), "self_calibrate_ch3_uses_own_state");

    // --- offset calibration: 24-bit read/write, channel 3 ---
    // Zero-Scale reg CH3 read comm = REG_OFFSET|RW_READ|CH3(0x03) = 0x6B.
    connection2.setRegister(0x6B, {0x12, 0x34, 0x56});
    check_true(full.get_offset_calibration(3) == 0x123456, "get_offset_calibration_ch3");

    n = connection2.writes().size();
    full.set_offset_calibration(0xABCDEF, 3);
    check_true(connection2.writes()[n] == std::vector<uint8_t>({0x63, 0xAB, 0xCD, 0xEF}), "set_offset_calibration_ch3");

    // --- standby / wakeup (channel-1-only) ---
    n = connection2.writes().size();
    full.standby();
    check_true(connection2.writes()[n] == std::vector<uint8_t>({0x04}), "standby");

    n = connection2.writes().size();
    full.wakeup();
    check_true(connection2.writes()[n] == std::vector<uint8_t>({0x00}), "wakeup_clears_stby");
    check_true(connection2.writes()[n + 1] == std::vector<uint8_t>({0x08}), "wakeup_waits_drdy");

    // --- reset: no-op without a reset_pin, pulses low then high with one ---
    full.reset();
    check_true(true, "reset_without_pin_does_not_crash");

    FakeOutputPin resetPin;
    SPIConnectionMock connection3;
    AD7706Full withReset(connection3, 2.5f, 4915200, &resetPin);
    check_true(resetPin.calls.size() == 2 && resetPin.calls[0] == false && resetPin.calls[1] == true,
               "init_with_reset_pin_pulses");
    resetPin.calls.clear();
    withReset.reset();
    check_true(resetPin.calls.size() == 2 && resetPin.calls[0] == false && resetPin.calls[1] == true,
               "reset_pulses_pin");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
