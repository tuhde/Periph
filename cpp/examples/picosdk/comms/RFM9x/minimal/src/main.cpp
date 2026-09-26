#include <stdio.h>
#include "pico/stdlib.h"
#include <hardware/spi.h>
#include "SPIConnectionPicoSDK.h"
#include "RFM9x.h"

static const uint MOSI_PIN = 19;
static const uint MISO_PIN = 16;
static const uint SCLK_PIN = 18;
static const uint CS_PIN   = 5;

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 5000000);
    gpio_set_function(MOSI_PIN, GPIO_FUNC_SPI);
    gpio_set_function(MISO_PIN, GPIO_FUNC_SPI);
    gpio_set_function(SCLK_PIN, GPIO_FUNC_SPI);

    SPIConnectionPicoSDK connection(spi0, CS_PIN);       // Create SPI connection, (spi0, cs_pin=5) → SPIConnectionPicoSDK
    RFM95Minimal radio(connection, 868000000);           // Create RFM95W driver, (connection, frequency_hz=868e6 Hz) → RFM95Minimal

    const uint8_t msg[] = "hello";
    uint8_t buf[255];
    while (true) {
        radio.send(msg, sizeof(msg) - 1);                // Send packet, (data, len ≤ 255) → void
        size_t len = 0;
        bool ok = radio.receive(buf, len, 2000);         // Receive single packet, (buf, len, timeout_ms=2000 ms) → bool
        if (ok) printf("rx %u B\n", (unsigned)len);
        else    printf("rx timeout\n");
        sleep_ms(3000);
    }
}
