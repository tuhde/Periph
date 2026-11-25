#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/spi_master.h"
#include "SPIConnectionESPIDF.h"
#include "HY3131.h"

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
    dev_cfg.mode            = 0;
    dev_cfg.clock_speed_hz  = 1000000;
    dev_cfg.spics_io_num    = CS_PIN;
    dev_cfg.queue_size      = 1;
    spi_device_handle_t dev;
    spi_bus_add_device(SPI2_HOST, &dev_cfg, &dev);

    SPIConnectionESPIDF connection(dev);
    HY3131Full adc(connection, 1.2f);

    int32_t raw = adc.read_raw();                                    // Read AD1 code, () → int32_t 24-bit
    check_true("read_raw in 24-bit range", raw != HY3131Minimal::TIMEOUT && raw >= -8388608 && raw <= 8388607);
    float v = adc.read_voltage();                                    // Read AD1 voltage, () → float V
    check_true("read_voltage finite", v == v);
    adc.data_ready();                                                // Report AD1F, () → bool
    check_true("data_ready accepted", true);

    adc.configure_ad1(4, 2, 0, 0, 0, 0);                             // Configure AD1, (osr, chop, input_gain, ref_gain, offset, buffers) → void
    check_true("read_raw after configure_ad1", adc.read_raw() != HY3131Minimal::TIMEOUT);
    adc.set_ad1_input(10, 3, 2);                                     // Select AD1 inputs, (pos, neg, filter_r) → void
    adc.set_ad1_reference(1, 1);                                     // Select AD1 reference, (high, low) → void
    check_true("set_ad1_input/reference accepted", true);

    int32_t tcode = adc.read_temperature_code();                     // Read temperature code, () → int32_t
    float t = adc.read_temperature(tcode);                           // Read die temperature, (tcode_25) → float °C
    check_true("read_temperature finite", t == t);

    adc.enable_ad2(5, 1, 0, false, false, 0, false);                 // Enable AD2, (osr, input_gain, ref_gain, chop, clk_div4, source, use_ad3) → void
    int32_t ad2 = adc.read_ad2_raw();                                // Read AD2 code, () → int32_t 19-bit
    check_true("read_ad2_raw in 19-bit range", ad2 >= -262144 && ad2 <= 262143);
    adc.enable_lpf(0);                                               // Enable low-pass filter, (bw) → void
    check_true("read_lpf_raw accepted", adc.read_lpf_raw() != HY3131Minimal::TIMEOUT);
    adc.enable_rms();                                                // Enable RMS converter, () → void
    float rms = adc.read_rms();                                      // Read RMS, () → float LSB
    check_true("read_rms >= 0", rms >= 0.0f);
    adc.enable_peak_hold(0);                                         // Enable peak hold, (source) → void
    int32_t pk_min, pk_max;
    adc.read_peak(pk_min, pk_max);                                   // Read peak hold, (min&, max&) → void
    check_true("read_peak min <= max", pk_min <= pk_max);
    adc.enable_interrupts(0x04);                                     // Enable interrupts, (mask) → void
    adc.read_flags();                                                // Read and clear INTF, () → uint8_t
    check_true("read_flags accepted", true);
    adc.reset();                                                     // Software reset, () → void
    check_true("reset accepted", true);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
