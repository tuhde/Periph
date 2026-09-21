#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "SPIConnectionZephyr.h"
#include "AD7705.h"

#define TEMP_COEFF 0.05f
#define TEMP_REFERENCE 1.25f
#define CHANGE_THRESHOLD 0.001f

#ifndef AD7705_SPI_NODE
#define AD7705_SPI_NODE DT_NODELABEL(spi0)
#endif
#ifndef AD7705_CS_GPIOS
#define AD7705_CS_GPIOS DT_PROP(AD7705_SPI_NODE, cs_gpios)
#endif

static int passed = 0, failed = 0;
static float last_pressure = 0.0f;
static bool first = true;

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
    AD7705Full adc(connection, 2.5f, AD7705Minimal::MCLK_2_4576MHZ);       // Construct and initialise the AD7705, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → AD7705Full

    // --- Configure both channels for the bridge-pressure application ---
    adc.configure(1, AD7705Full::GAIN_128, true, true, 50);                // Configure channel 1, (channel=1, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → None
    adc.configure(2, AD7705Full::GAIN_2, true, false, 50);                 // Configure channel 2, (channel=2, gain=GAIN_2, bipolar=true, buffered=false, output_rate_hz=50) → None

    // --- Self-calibrate both channels before the measurement loop ---
    adc.self_calibrate(1);                                                  // Self-calibrate channel 1, (channel=1) → None
    adc.self_calibrate(2);                                                  // Self-calibrate channel 2, (channel=2) → None

    check_true(true, "init_and_configure");

    while (true) {
        // --- Sample continuously and compensate the pressure reading for temperature ---
        float pressure_raw = adc.read_voltage(1);                           // Read voltage on channel 1, (channel=1) → float V
        float temp = adc.read_voltage(2);                                   // Read voltage on channel 2, (channel=2) → float V
        float pressure = pressure_raw - TEMP_COEFF * (temp - TEMP_REFERENCE);
        if (first || fabsf(pressure - last_pressure) > CHANGE_THRESHOLD) {
            printk("→ pressure=%f V (raw %f V, temp %f V)\n",
                   (double)pressure, (double)pressure_raw, (double)temp);
            last_pressure = pressure;
            first = false;
        }
        k_msleep(200);
    }
}
