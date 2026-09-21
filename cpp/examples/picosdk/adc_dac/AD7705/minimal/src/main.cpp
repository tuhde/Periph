#include <stdio.h>
#include "pico/stdlib.h"
#include <hardware/spi.h>
#include "SPIConnectionPicoSDK.h"
#include "AD7705.h"

static const uint MOSI_PIN = 19;
static const uint MISO_PIN = 16;
static const uint SCLK_PIN = 18;
static const uint CS_PIN   = 5;

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 5000000);
    spi_set_format(spi0, 8, 1, 1, SPI_MSB_FIRST);
    gpio_set_function(MOSI_PIN, GPIO_FUNC_SPI);
    gpio_set_function(MISO_PIN, GPIO_FUNC_SPI);
    gpio_set_function(SCLK_PIN, GPIO_FUNC_SPI);

    SPIConnectionPicoSDK connection(spi0, CS_PIN);                            // Create SPI connection, (spi0, cs_pin=5) → SPIConnectionPicoSDK
    AD7705Minimal adc(connection, 2.5f, AD7705Minimal::MCLK_2_4576MHZ);       // Construct and initialise the AD7705, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz, reset_pin=nullptr) → AD7705Minimal

    while (true) {
        float v = adc.read_voltage();                                         // Read Channel 1 voltage, () → float V
        printf("%.4f\n", (double)v);
        sleep_ms(1000);
    }
}
