#include <string.h>
#include <math.h>
#include <stdio.h>
#include "pico/stdlib.h"
#include <hardware/spi.h>
#include "SPIConnectionPicoSDK.h"
#include "23X1024.h"

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
    SRAM23X1024Minimal sram(connection);                                     // Create 23X1024 driver, (connection) → SRAM23X1024Minimal

    uint8_t counter = 0;
    while (true) {
        sram.write_byte(0x00000, counter);                                   // Write one byte, (address 0–131071, value 0–255)
        uint8_t value = sram.read_byte(0x00000);                             // Read one byte, (address 0–131071) → byte
        printf("wrote 0x%02X, read 0x%02X\n", counter, value);
        counter++;
        sleep_ms(500);
    }
    return 0;
}
