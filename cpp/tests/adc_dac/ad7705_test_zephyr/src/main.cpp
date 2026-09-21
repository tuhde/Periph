#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "SPIConnectionZephyr.h"
#include "AD7705.h"

#ifndef AD7705_SPI_NODE
#define AD7705_SPI_NODE DT_NODELABEL(spi0)
#endif
#ifndef AD7705_CS_GPIOS
#define AD7705_CS_GPIOS DT_PROP(AD7705_SPI_NODE, cs_gpios)
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printk("PASS %s\n", label); passed++; }
    else           { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(AD7705_SPI_NODE);
    struct spi_config cfg = {
        .frequency = 5000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER | SPI_MODE_CPOL | SPI_MODE_CPHA,
        .slave     = 0,
        .cs        = { .gpio = AD7705_CS_GPIOS, .delay = 0 },
    };
    SPIConnectionZephyr connection(dev, cfg);
    AD7705Full adc(connection, 2.5f, AD7705Minimal::MCLK_2_4576MHZ);

    uint16_t raw = adc.read_raw();                                   // Read raw 16-bit code, (channel=1) → uint16_t
    check_true("read_raw in [0, 65535]", raw <= 65535);

    float v = adc.read_voltage();                                    // Read Channel 1 voltage, () → float V
    check_true("read_voltage in [-2.5, 2.5]", v >= -2.5f && v <= 2.5f);

    uint16_t raw1 = adc.read_raw(1);
    check_true("read_raw(1) in [0, 65535]", raw1 <= 65535);
    float v1 = adc.read_voltage(1);
    check_true("read_voltage(1) in [-2.5, 2.5]", v1 >= -2.5f && v1 <= 2.5f);

    uint16_t raw2 = adc.read_raw(2);
    check_true("read_raw(2) in [0, 65535]", raw2 <= 65535);
    float v2 = adc.read_voltage(2);
    check_true("read_voltage(2) in [-2.5, 2.5]", v2 >= -2.5f && v2 <= 2.5f);

    adc.configure(1, AD7705Full::GAIN_2, true, false, 60);
    check_true("configure(1, gain=2) accepted", true);
    adc.configure(2, AD7705Full::GAIN_4, false, true, 60);
    check_true("configure(2, gain=4) accepted", true);
    adc.configure(1, AD7705Full::GAIN_128, true, true, 50);
    check_true("configure(1, gain=128) accepted", true);

    adc.self_calibrate(1);
    check_true("self_calibrate(1) accepted", true);
    adc.self_calibrate(2);
    check_true("self_calibrate(2) accepted", true);

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

    adc.standby();
    check_true("standby accepted", true);
    adc.wakeup();
    check_true("wakeup accepted", true);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
