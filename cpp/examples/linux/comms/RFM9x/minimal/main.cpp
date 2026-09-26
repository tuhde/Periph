#include <cstdio>
#include <ctime>
#include <unistd.h>
#include "SPIConnectionLinux.h"
#include "RFM9x.h"

#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS 0
#endif
#ifndef TEST_SPI_DEVICE
#define TEST_SPI_DEVICE 0
#endif

int main() {
    SPIConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEVICE, 0, 5000000); // Create SPI connection, (bus=0, device=0, mode=0, max_speed_hz=5e6 Hz) → SPIConnectionLinux
    RFM95Minimal radio(connection, 868000000);           // Create RFM95W driver, (connection, frequency_hz=868e6 Hz) → RFM95Minimal

    const uint8_t msg[] = "hello";
    uint8_t buf[255];
    while (true) {
        radio.send(msg, sizeof(msg) - 1);                // Send packet, (data, len ≤ 255) → void
        size_t len = 0;
        bool ok = radio.receive(buf, len, 2000);         // Receive single packet, (buf, len, timeout_ms=2000 ms) → bool
        if (ok) printf("rx %u B\n", (unsigned)len);
        else    printf("rx timeout\n");
        usleep(3000 * 1000);
    }
}
