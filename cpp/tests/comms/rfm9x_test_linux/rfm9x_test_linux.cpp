#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "src/chips/comms/RFM9x.h"
#include "src/transport/SpiTransportLinux.h"

#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS 0
#endif
#ifndef TEST_SPI_DEVICE
#define TEST_SPI_DEVICE 0
#endif

int passed = 0;
int failed = 0;

void check_true(const char* label, bool cond) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

void check_eq(const char* label, uint8_t got, uint8_t expected) {
    if (got == expected) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: got 0x%02X, expected 0x%02X\n", label, got, expected); failed++; }
}

int main() {
    SpiTransportLinux transport(TEST_SPI_BUS, TEST_SPI_DEVICE);
    RFM95Full rfm(transport, 868000000);

    uint8_t ver = rfm.version();
    check_eq("version_reg", ver, 0x12);
    check_true("version_nonzero", ver != 0);
    check_true("rssi_sane", rfm.rssi() > -150.0f && rfm.rssi() < 0.0f);

    rfm.send(reinterpret_cast<const uint8_t*>("test"), 4);
    usleep(50000);

    rfm.standby();
    rfm.sleep();
    rfm.standby();

    rfm.set_tx_power(14, false);
    rfm.set_tx_power(17, true);
    rfm.set_frequency(868000000);
    rfm.configure(7, 125, 5, true);
    check_true("configure_valid", true);

    transport.close();
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}