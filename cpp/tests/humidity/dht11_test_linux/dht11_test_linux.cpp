#ifndef TEST_CHIP_NUM
#define TEST_CHIP_NUM 0
#endif
#ifndef TEST_LINE_NUM
#define TEST_LINE_NUM 4
#endif

#include <stdio.h>
#include <stdlib.h>
#include "DHTxxTransportLinux.h"
#include "DHT11.h"

int passed = 0, failed = 0;

void check_true(bool cond, const char* label) {
    if (cond) {
        printf("PASS %s\n", label);
        passed++;
    } else {
        printf("FAIL %s\n", label);
        failed++;
    }
}

int main() {
    DHTxxTransportLinux transport(TEST_CHIP_NUM, TEST_LINE_NUM);
    DHT11Full dht(transport);

    uint8_t raw[5];
    dht.readRaw(raw, 5);

    uint8_t sum = (raw[0] + raw[1] + raw[2] + raw[3]) & 0xFF;
    check_true(sum == raw[4], "checksum");

    float temp, hum;
    dht.readRetry(temp, hum, 3);
    check_true(temp > -40.0f && temp < 80.0f, "temperature_range");
    check_true(hum >= 0.0f && hum <= 100.0f, "humidity_range");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
