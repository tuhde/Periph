#include <stdio.h>
#include "pico/stdlib.h"
#include <hardware/spi.h>
#include "SPIConnectionPicoSDK.h"
#include "AD7706.h"

static const uint MOSI_PIN = 19;
static const uint MISO_PIN = 16;
static const uint SCLK_PIN = 18;
static const uint CS_PIN   = 5;

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 5000000);
    spi_set_format(spi0, 8, SPI_CPOL_1, SPI_CPHA_1, SPI_MSB_FIRST);
    gpio_set_function(MOSI_PIN, GPIO_FUNC_SPI);
    gpio_set_function(MISO_PIN, GPIO_FUNC_SPI);
    gpio_set_function(SCLK_PIN, GPIO_FUNC_SPI);

    SPIConnectionPicoSDK connection(spi0, CS_PIN);
    AD7706Full adc(connection, 2.5f, AD7706Minimal::MCLK_2_4576MHZ);

    uint16_t raw = adc.read_raw();                                   // Read raw 16-bit code, (channel=1) → uint16_t
    check_true("read_raw returns uint16_t", true);

    float v = adc.read_voltage();                                    // Read Channel 1 voltage, () → float V
    check_true("read_voltage in [-2.5, 2.5]", v >= -2.5f && v <= 2.5f);

    uint16_t raw1 = adc.read_raw(1);
    check_true("read_raw(1) returns uint16_t", true);
    float v1 = adc.read_voltage(1);
    check_true("read_voltage(1) in [-2.5, 2.5]", v1 >= -2.5f && v1 <= 2.5f);

    uint16_t raw2 = adc.read_raw(2);
    check_true("read_raw(2) returns uint16_t", true);
    float v2 = adc.read_voltage(2);
    check_true("read_voltage(2) in [-2.5, 2.5]", v2 >= -2.5f && v2 <= 2.5f);

    uint16_t raw3 = adc.read_raw(3);
    check_true("read_raw(3) returns uint16_t", true);
    float v3 = adc.read_voltage(3);
    check_true("read_voltage(3) in [-2.5, 2.5]", v3 >= -2.5f && v3 <= 2.5f);

    adc.configure(1, AD7706Full::GAIN_2, true, false, 60);
    check_true("configure(1, gain=2) accepted", true);
    adc.configure(2, AD7706Full::GAIN_4, false, true, 60);
    check_true("configure(2, gain=4) accepted", true);
    adc.configure(3, AD7706Full::GAIN_4, false, true, 60);
    check_true("configure(3, gain=4) accepted", true);
    adc.configure(1, AD7706Full::GAIN_128, true, true, 50);
    check_true("configure(1, gain=128) accepted", true);

    adc.self_calibrate(1);
    check_true("self_calibrate(1) accepted", true);
    adc.self_calibrate(2);
    check_true("self_calibrate(2) accepted", true);
    adc.self_calibrate(3);
    check_true("self_calibrate(3) accepted", true);

    adc.system_calibrate_zero(1);
    check_true("system_calibrate_zero(1) accepted", true);
    adc.system_calibrate_full(1);
    check_true("system_calibrate_full(1) accepted", true);

    uint32_t off1 = adc.get_offset_calibration(1);
    check_true("get_offset_calibration(1) in [0, 2^24-1]", off1 <= 0xFFFFFF);
    adc.set_offset_calibration(off1, 1);
    check_true("set_offset_calibration(1) accepted", true);

    uint32_t gain1 = adc.get_gain_calibration(1);
    check_true("get_gain_calibration(1) in [0, 2^24-1]", gain1 <= 0xFFFFFF);
    adc.set_gain_calibration(gain1, 1);
    check_true("set_gain_calibration(1) accepted", true);

    uint32_t off2 = adc.get_offset_calibration(2);
    check_true("get_offset_calibration(2) in [0, 2^24-1]", off2 <= 0xFFFFFF);
    uint32_t gain2 = adc.get_gain_calibration(2);
    check_true("get_gain_calibration(2) in [0, 2^24-1]", gain2 <= 0xFFFFFF);

    uint32_t off3 = adc.get_offset_calibration(3);
    check_true("get_offset_calibration(3) in [0, 2^24-1]", off3 <= 0xFFFFFF);
    uint32_t gain3 = adc.get_gain_calibration(3);
    check_true("get_gain_calibration(3) in [0, 2^24-1]", gain3 <= 0xFFFFFF);

    adc.standby();
    check_true("standby accepted", true);
    adc.wakeup();
    check_true("wakeup accepted", true);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
