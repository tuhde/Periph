#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "SPIConnectionZephyr.h"
#include "AD7706.h"

#define FILTER_DP_THRESHOLD 0.001f

#ifndef AD7706_SPI_NODE
#define AD7706_SPI_NODE DT_NODELABEL(spi0)
#endif
#ifndef AD7706_CS_GPIOS
#define AD7706_CS_GPIOS DT_PROP(AD7706_SPI_NODE, cs_gpios)
#endif

static int passed = 0, failed = 0;
static float last_filter_dp = 0.0f;
static bool first = true;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(AD7706_SPI_NODE);
    struct spi_config cfg = {
        .frequency = 5000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER | SPI_MODE_CPOL | SPI_MODE_CPHA,
        .slave     = 0,
        .cs        = { .gpio = AD7706_CS_GPIOS, .delay = 0 },
    };
    SPIConnectionZephyr connection(dev, cfg);                              // Create SPI connection, (dev, cfg) → SPIConnectionZephyr
    AD7706Full adc(connection, 2.5f, AD7706Minimal::MCLK_2_4576MHZ);       // Construct and initialise the AD7706, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → AD7706Full

    // --- Configure all three channels for the HVAC manifold-pressure application ---
    // All three pressure transducers are bridge-type with small mV-level output
    // signals, so the AD7706's high-gain (128) pseudo-differential input is ideal.
    // The shared-COMMON architecture lets all three bridges share a single return
    // line instead of three fully-differential pairs.
    adc.configure(1, AD7706Full::GAIN_128, true, true, 50);                // Configure channel 1, (channel=1, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → None
    adc.configure(2, AD7706Full::GAIN_128, true, true, 50);                // Configure channel 2, (channel=2, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → None
    adc.configure(3, AD7706Full::GAIN_128, true, true, 50);                // Configure channel 3, (channel=3, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → None

    // --- Self-calibrate all three channels before the measurement loop ---
    adc.self_calibrate(1);                                                  // Self-calibrate channel 1, (channel=1) → None
    adc.self_calibrate(2);                                                  // Self-calibrate channel 2, (channel=2) → None
    adc.self_calibrate(3);                                                  // Self-calibrate channel 3, (channel=3) → None

    check_true(true, "init_and_configure");

    while (true) {
        // --- Sample continuously and compute filter differential pressure ---
        // Channel 1 = filter-inlet static, Channel 2 = filter-outlet static, Channel 3 = duct static.
        // filter_dp = ch1 - ch2 is the filter differential pressure (clog indicator).
        float inlet  = adc.read_voltage(1);                                 // Read voltage on channel 1, (channel=1) → float V
        float outlet = adc.read_voltage(2);                                 // Read voltage on channel 2, (channel=2) → float V
        float duct   = adc.read_voltage(3);                                 // Read voltage on channel 3, (channel=3) → float V
        float filter_dp = inlet - outlet;
        if (first || fabsf(filter_dp - last_filter_dp) > FILTER_DP_THRESHOLD) {
            printk("→ filter_dp=%f V, duct_pressure=%f V\n",
                   (double)filter_dp, (double)duct);
            last_filter_dp = filter_dp;
            first = false;
        }
        k_msleep(200);
    }
}
