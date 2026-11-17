#include <stdio.h>
#include "I2CConnectionMock.h"
#include "AHT10.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// 6-byte measurement response encoding humidity=50.0%, temperature=50.0 degC:
// raw_rh = buf[1]<<12 | buf[2]<<4 | buf[3]>>4      = 0x80000 (524288 / 2**20 * 100 = 50.0)
// raw_t  = (buf[3]&0x0F)<<16 | buf[4]<<8 | buf[5]  = 0x80000 (524288 / 2**20 * 200 - 50 = 50.0)
static const uint8_t MEASUREMENT[6] = {0x08, 0x80, 0x00, 0x08, 0x00, 0x00};

static bool writesContain(const I2CConnectionMock& c, const std::vector<uint8_t>& expected) {
    for (const auto& w : c.writes()) if (w == expected) return true;
    return false;
}

static void queue(I2CConnectionMock& c, const uint8_t* m) {
    c.queueRead({m[0], m[1], m[2], m[3], m[4], m[5]});
}

int main() {
    I2CConnectionMock connection;

    // CAL bit set after the init command: no soft-reset retry.
    connection.queueRead({0x08});

    AHT10Full sensor(connection);
    check_true(writesContain(connection, {0xE1, 0x08, 0x00}), "init_sends_e1");
    check_true(!writesContain(connection, {(uint8_t)0xBA}), "init_no_reset");

    queue(connection, MEASUREMENT);
    float temperature_c, humidity_pct;
    bool ok = sensor.read(temperature_c, humidity_pct);
    check_true(ok && writesContain(connection, {0xAC, 0x33, 0x00}), "read_triggers");
    check_true(humidity_pct == 50.0f, "read_humidity_value");
    check_true(temperature_c == 50.0f, "read_temperature_value");

    queue(connection, MEASUREMENT);
    check_true(sensor.temperature() == 50.0f, "temperature");
    queue(connection, MEASUREMENT);
    check_true(sensor.humidity() == 50.0f, "humidity");
    queue(connection, MEASUREMENT);
    check_true(sensor.read_temperature() == 50.0f, "read_temperature");
    queue(connection, MEASUREMENT);
    check_true(sensor.read_humidity() == 50.0f, "read_humidity");

    queue(connection, MEASUREMENT);
    uint32_t rh, rt;
    check_true(sensor.read_raw(rh, rt) && rh == 0x80000 && rt == 0x80000, "read_raw");

    // BUSY on the first read, cleared on the second.
    const uint8_t BUSY[6] = {0x88, 0x80, 0x00, 0x08, 0x00, 0x00};
    queue(connection, BUSY);
    queue(connection, MEASUREMENT);
    check_true(sensor.read(temperature_c, humidity_pct) && temperature_c == 50.0f, "read_polls_busy");

    // Spec example: raw_t = 0x6B300 -> 33.74 degC
    const uint8_t EX[6] = {0x08, 0x80, 0x00, 0x06, 0xB3, 0x00};
    queue(connection, EX);
    float te = sensor.read_temperature();
    check_true(te > 33.73f && te < 33.75f, "read_temperature_example");

    queue(connection, MEASUREMENT);
    float dp = sensor.dew_point();
    check_true(dp > 36.6f && dp < 36.8f, "dew_point");

    connection.queueRead({0x08});
    check_true(sensor.read_status() == 0x08, "read_status");

    sensor.soft_reset();
    check_true(writesContain(connection, {(uint8_t)0xBA}), "soft_reset");

    connection.queueRead({0x08});
    check_true(sensor.is_calibrated() == true, "is_calibrated_true");
    connection.queueRead({0x00});
    check_true(sensor.is_calibrated() == false, "is_calibrated_false");
    connection.queueRead({0x80});
    check_true(sensor.is_busy() == true, "is_busy_true");
    connection.queueRead({0x00});
    check_true(sensor.is_busy() == false, "is_busy_false");

    // Uncalibrated first: soft reset + second init.
    I2CConnectionMock c2;
    c2.queueRead({0x00});
    AHT10Full s2(c2);
    check_true(writesContain(c2, {(uint8_t)0xBA}), "init_retry_reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
