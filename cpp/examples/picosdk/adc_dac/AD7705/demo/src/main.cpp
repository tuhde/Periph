#include <stdio.h>
#include <math.h>
#include "pico/stdlib.h"
#include <hardware/spi.h>
#include "SPIConnectionPicoSDK.h"
#include "AD7705.h"

#define TEMP_COEFF 0.05f
#define TEMP_REFERENCE 1.25f
#define CHANGE_THRESHOLD 0.001f

static const uint MOSI_PIN = 19;
static const uint MISO_PIN = 16;
static const uint SCLK_PIN = 18;
static const uint CS_PIN   = 5;

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 5000000);
    spi_set_format(spi0, 8, SPI_CPOL_1, SPI_CPHA_1, SPI_MSB_FIRST);
    gpio_set_function(MOSI_PIN, GPIO_FUNC_SPI);
    gpio_set_function(MISO_PIN, GPIO_FUNC_SPI);
    gpio_set_function(SCLK_PIN, GPIO_FUNC_SPI);

    SPIConnectionPicoSDK connection(spi0, CS_PIN);                                  // Create SPI connection, (spi0, cs_pin=5) → SPIConnectionPicoSDK
    AD7705Full adc(connection, 2.5f, AD7705Minimal::MCLK_2_4576MHZ);               // Construct and initialise the AD7705, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → AD7705Full

    // --- Configure both channels for the bridge-pressure application ---
    adc.configure(1, AD7705Full::GAIN_128, true, true, 50);                         // Configure channel 1, (channel=1, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → None
    adc.configure(2, AD7705Full::GAIN_2, true, false, 50);                          // Configure channel 2, (channel=2, gain=GAIN_2, bipolar=true, buffered=false, output_rate_hz=50) → None

    // --- Self-calibrate both channels before the measurement loop ---
    adc.self_calibrate(1);                                                          // Self-calibrate channel 1, (channel=1) → None
    adc.self_calibrate(2);                                                          // Self-calibrate channel 2, (channel=2) → None

    float last_pressure = 0.0f;
    bool first = true;

    while (true) {
        // --- Sample continuously and compensate the pressure reading for temperature ---
        float pressure_raw = adc.read_voltage(1);                                   // Read voltage on channel 1, (channel=1) → float V
        float temp = adc.read_voltage(2);                                           // Read voltage on channel 2, (channel=2) → float V
        float pressure = pressure_raw - TEMP_COEFF * (temp - TEMP_REFERENCE);
        if (first || fabsf(pressure - last_pressure) > CHANGE_THRESHOLD) {
            printf("→ pressure=%.4f V (raw %.4f V, temp %.4f V)\n",
                   (double)pressure, (double)pressure_raw, (double)temp);
            last_pressure = pressure;
            first = false;
        }
        sleep_ms(200);
    }
}
