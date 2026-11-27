#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Rrh46410.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x38
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}
#define CHECK(c, l) check_true((l), (c))
#define NEAR(v, lo, hi, l) check_true((l), ((v) >= (lo) && (v) <= (hi)))
#define TEST_DELAY_MS(ms) usleep((ms) * 1000)

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);
    RRH46410Full sensor(connection);

    CHECK(sensor.init(), "init");                          // Check product ID, start IAQ 2nd Gen mode, () → bool
    uint16_t pid = 0;
    uint8_t fw[3];
    CHECK(sensor.product_id(pid, fw) && pid == 0x2310, "product_id");
    uint8_t tn[6];
    CHECK(sensor.tracking_number(tn), "tracking_number");
    CHECK(sensor.get_mode() == RRH46410Full::MODE_IAQ_2ND_GEN, "mode_iaq");
    CHECK(sensor.set_humidity(50.0f) && sensor.status().raw == 0, "set_humidity");
    RRH46410Result r;
    CHECK(sensor.read(r), "read");
    CHECK(r.iaq >= 0.0f && r.iaq <= 25.5f, "read_iaq_range");
    CHECK(!r.warmed_up, "read_not_warmed_up");
    CHECK(sensor.wait_for_sample(5000), "wait_for_sample");
    CHECK(sensor.read_results(r), "read_results");
    CHECK(!sensor.poll_interrupt(), "poll_interrupt_false");
    CHECK(sensor.set_mode(RRH46410Full::MODE_PBAQ) && sensor.get_mode() == RRH46410Full::MODE_PBAQ, "mode_pbaq");
    CHECK(sensor.read(r) && r.pbaq, "read_pbaq");
    CHECK(sensor.config_gpio(0x03) && sensor.set_gpio(0x01) && (sensor.get_gpio() & 0x01), "set_gpio");
    CHECK(sensor.clear_gpio(0x01) && !(sensor.get_gpio() & 0x01), "clear_gpio");
    uint8_t mode = 0xFF;
    CHECK(sensor.send_command(0x10, nullptr, 0, &mode, 1) == 1 && mode == RRH46410Full::MODE_PBAQ, "send_command");
    CHECK(sensor.suspend() && sensor.get_mode() == RRH46410Full::MODE_SUSPEND, "suspend");
    CHECK(sensor.reset() && sensor.get_mode() == RRH46410Full::MODE_SUSPEND, "reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
