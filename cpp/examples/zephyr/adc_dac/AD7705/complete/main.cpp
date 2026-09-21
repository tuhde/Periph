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

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(AD7705_SPI_NODE);
    struct spi_config cfg = {
        .frequency = 5000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER | SPI_MODE_CPOL | SPI_MODE_CPHA,
        .slave     = 0,
        .cs        = { .gpio = AD7705_CS_GPIOS, .delay = 0 },
    };
    SPIConnectionZephyr connection(dev, cfg);                              // Create SPI connection, (dev, cfg) → SPIConnectionZephyr
    AD7705Full adc(connection, 2.5f, AD7705Minimal::MCLK_2_4576MHZ);       // Construct and initialise the AD7705, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz, reset_pin=nullptr) → AD7705Full

    adc.configure(2, AD7705Full::GAIN_8, true, true, 60);                  // Configure channel 2, (channel=2, gain=GAIN_8, bipolar=true, buffered=true, output_rate_hz=60) → None
                                                                            // writes Setup and Clock Registers; does not calibrate
    adc.self_calibrate(2);                                                  // Self-calibrate channel 2, (channel=2) → None

    uint32_t off2 = adc.get_offset_calibration(2);                          // Read offset calibration, (channel=2) → uint32_t 24-bit
    uint32_t gain2 = adc.get_gain_calibration(2);                           // Read gain calibration, (channel=2) → uint32_t 24-bit
    printk("ch2 offset=%lu gain=%lu\n", (unsigned long)off2, (unsigned long)gain2);

    uint16_t raw1 = adc.read_raw(1);                                        // Read raw 16-bit code, (channel=1) → uint16_t
    float v1 = adc.read_voltage(1);                                         // Read voltage, (channel=1) → float V
    float v2 = adc.read_voltage(2);                                         // Read voltage, (channel=2) → float V
    printk("ch1 raw=%u ch1 v=%f ch2 v=%f\n", raw1, (double)v1, (double)v2);

    adc.standby();                                                          // Enter standby, () → None
    k_msleep(100);
    adc.wakeup();                                                           // Exit standby, () → None

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
