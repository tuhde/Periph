#include <cstdio>
#include <cstdlib>
#include "SPIConnectionLinux.h"
#include "AD7705.h"

#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS 0
#endif
#ifndef TEST_SPI_DEV
#define TEST_SPI_DEV 0
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    SPIConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEV, 3, 1000000);   // Mode 3 (CPOL=1 CPHA=1), 1 MHz
    AD7705Full adc(connection, 2.5f, AD7705Minimal::MCLK_2_4576MHZ);

    uint16_t raw = adc.read_raw();                                   // Read raw 16-bit code, (channel=1) → uint16_t
    check_true("read_raw in [0, 65535]", raw <= 65535);

    float v = adc.read_voltage();                                    // Read Channel 1 voltage, () → float V
    check_true("read_voltage in [-2.5, 2.5]", v >= -2.5f && v <= 2.5f);

    uint16_t raw1 = adc.read_raw(1);                                 // Read raw 16-bit code, (channel=1) → uint16_t
    check_true("read_raw(1) in [0, 65535]", raw1 <= 65535);
    float v1 = adc.read_voltage(1);                                  // Read voltage, (channel=1) → float V
    check_true("read_voltage(1) in [-2.5, 2.5]", v1 >= -2.5f && v1 <= 2.5f);

    uint16_t raw2 = adc.read_raw(2);                                 // Read raw 16-bit code, (channel=2) → uint16_t
    check_true("read_raw(2) in [0, 65535]", raw2 <= 65535);
    float v2 = adc.read_voltage(2);                                  // Read voltage, (channel=2) → float V
    check_true("read_voltage(2) in [-2.5, 2.5]", v2 >= -2.5f && v2 <= 2.5f);

    adc.configure(1, AD7705Full::GAIN_2, true, false, 60);           // Configure channel 1, (channel=1, gain=GAIN_2, bipolar=true, buffered=false, output_rate_hz=60) → None
    check_true("configure(1, gain=2) accepted", true);
    adc.configure(2, AD7705Full::GAIN_4, false, true, 60);           // Configure channel 2, (channel=2, gain=GAIN_4, bipolar=false, buffered=true, output_rate_hz=60) → None
    check_true("configure(2, gain=4) accepted", true);
    adc.configure(1, AD7705Full::GAIN_128, true, true, 50);          // Configure channel 1, (channel=1, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → None
    check_true("configure(1, gain=128) accepted", true);

    adc.self_calibrate(1);                                           // Self-calibrate channel 1, (channel=1) → None
    check_true("self_calibrate(1) accepted", true);
    adc.self_calibrate(2);                                           // Self-calibrate channel 2, (channel=2) → None
    check_true("self_calibrate(2) accepted", true);

    adc.system_calibrate_zero(1);                                    // System calibrate zero, (channel=1) → None
    check_true("system_calibrate_zero(1) accepted", true);
    adc.system_calibrate_full(1);                                    // System calibrate full, (channel=1) → None
    check_true("system_calibrate_full(1) accepted", true);

    uint32_t off1 = adc.get_offset_calibration(1);                   // Read offset calibration, (channel=1) → uint32_t 24-bit
    check_true("get_offset_calibration(1) in [0, 2^24-1]", off1 <= 0xFFFFFF);
    adc.set_offset_calibration(off1, 1);                             // Set offset calibration, (value, channel=1) → None
    check_true("set_offset_calibration(1) accepted", true);

    uint32_t gain1 = adc.get_gain_calibration(1);                    // Read gain calibration, (channel=1) → uint32_t 24-bit
    check_true("get_gain_calibration(1) in [0, 2^24-1]", gain1 <= 0xFFFFFF);
    adc.set_gain_calibration(gain1, 1);                              // Set gain calibration, (value, channel=1) → None
    check_true("set_gain_calibration(1) accepted", true);

    uint32_t off2 = adc.get_offset_calibration(2);                   // Read offset calibration, (channel=2) → uint32_t 24-bit
    check_true("get_offset_calibration(2) in [0, 2^24-1]", off2 <= 0xFFFFFF);
    uint32_t gain2 = adc.get_gain_calibration(2);                    // Read gain calibration, (channel=2) → uint32_t 24-bit
    check_true("get_gain_calibration(2) in [0, 2^24-1]", gain2 <= 0xFFFFFF);

    adc.standby();                                                   // Enter standby, () → None
    check_true("standby accepted", true);
    adc.wakeup();                                                    // Exit standby, () → None
    check_true("wakeup accepted", true);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
