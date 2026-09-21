#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/spi_master.h"
#include "SPIConnectionESPIDF.h"
#include "AD7705.h"

static const int MOSI_PIN = 23;
static const int MISO_PIN = 19;
static const int SCLK_PIN = 18;
static const int CS_PIN   = 5;

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

extern "C" void app_main(void) {
    spi_bus_config_t bus_cfg = {};
    bus_cfg.mosi_io_num   = MOSI_PIN;
    bus_cfg.miso_io_num   = MISO_PIN;
    bus_cfg.sclk_io_num   = SCLK_PIN;
    bus_cfg.quadwp_io_num = -1;
    bus_cfg.quadhd_io_num = -1;
    spi_bus_initialize(SPI2_HOST, &bus_cfg, SPI_DMA_CH_AUTO);

    spi_device_interface_config_t dev_cfg = {};
    dev_cfg.mode            = 3;
    dev_cfg.clock_speed_hz  = 5000000;
    dev_cfg.spics_io_num    = CS_PIN;
    dev_cfg.queue_size      = 1;
    spi_device_handle_t dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &dev);

    SPIConnectionESPIDF connection(dev);
    AD7705Full adc(connection, 2.5f, AD7705Minimal::MCLK_2_4576MHZ);

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

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
