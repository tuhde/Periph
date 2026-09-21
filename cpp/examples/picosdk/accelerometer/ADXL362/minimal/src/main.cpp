#include <stdio.h>
#include "pico/stdlib.h"
#include <hardware/spi.h>
#include "SPIConnectionPicoSDK.h"
#include "ADXL362.h"

static const uint MOSI_PIN = 19;
static const uint MISO_PIN = 16;
static const uint SCLK_PIN = 18;
static const uint CS_PIN   = 5;

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 8000000);
    gpio_set_function(MOSI_PIN, GPIO_FUNC_SPI);
    gpio_set_function(MISO_PIN, GPIO_FUNC_SPI);
    gpio_set_function(SCLK_PIN, GPIO_FUNC_SPI);

    SPIConnectionPicoSDK connection(spi0, CS_PIN);                         // Create SPI connection, (spi0, cs_pin=5) → SPIConnectionPicoSDK
    ADXL362Minimal accel(connection);                                       // Create ADXL362 driver, (connection) → ADXL362Minimal

    while (true) {
        float x, y, z;
        accel.read(x, y, z);                                                // Read 3-axis acceleration, (x, y, z) → g, g, g
        printf("x=%+.3f  y=%+.3f  z=%+.3f g\n", x, y, z);
        sleep_ms(100);
    }
    return 0;
}