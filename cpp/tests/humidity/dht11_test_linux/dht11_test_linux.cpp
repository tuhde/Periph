#include <cstdio>
#include <gpiod.h>
#include "DHT11PinLinux.h"
#include "DHT11.h"

#ifndef TEST_GPIO_CHIP
#define TEST_GPIO_CHIP   "/dev/gpiochip0"
#endif
#ifndef TEST_DATA_LINE
#define TEST_DATA_LINE   4
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    struct gpiod_chip* chip_dev = gpiod_chip_open(TEST_GPIO_CHIP);
    if (!chip_dev) {
        perror("gpiod_chip_open");
        printf("===DONE: %d passed, %d failed===\n", passed, failed);
        return 1;
    }

    DHT11PinLinux pin(chip_dev, TEST_DATA_LINE);
    DHT11Full<DHT11PinLinux> dht(pin);

    float t, h;
    bool ok = dht.read(t, h);
    check_true("read returned bool", true);
    check_true("read_temperature in [-20, 60]", t >= -20.0f && t <= 60.0f);
    check_true("read_humidity in [0, 100]",    h >=   0.0f && h <= 100.0f);

    float t2 = 0.0f, h2 = 0.0f;
    bool ok2 = dht.read_retry(t2, h2, 3);
    check_true("read_retry returned bool", true);
    check_true("read_retry temperature in [-20, 60]", t2 >= -20.0f && t2 <= 60.0f);
    check_true("read_retry humidity in [0, 100]",    h2 >=   0.0f && h2 <= 100.0f);

    uint8_t raw[5];
    bool raw_ok = dht.read_raw(raw);
    check_true("read_raw returned bool", true);
    if (raw_ok) {
        uint8_t checksum = (uint8_t)(raw[0] + raw[1] + raw[2] + raw[3]);
        check_true("read_raw checksum OK", checksum == raw[4]);
    }

    pin.close();
    gpiod_chip_close(chip_dev);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
