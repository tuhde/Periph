#include <stdio.h>
#include "I2CConnectionMock.h"
#include "AHT21.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// 6-byte measurement response encoding humidity=50.0%, temperature=50.0 degC:
// raw_rh = buf[1]<<12 | buf[2]<<4 | buf[3]>>4      = 0x80000 (524288 / 2**20 * 100 = 50.0)
// raw_t  = (buf[3]&0x0F)<<16 | buf[4]<<8 | buf[5]  = 0x80000 (524288 / 2**20 * 200 - 50 = 50.0)
static const uint8_t MEASUREMENT[6] = {0x00, 0x80, 0x00, 0x08, 0x00, 0x00};

static bool writesContain(const I2CConnectionMock& c, const std::vector<uint8_t>& expected) {
    for (const auto& w : c.writes()) if (w == expected) return true;
    return false;
}

int main() {
    I2CConnectionMock connection;

    // Status byte with both the CAL (0x08) and paired 0x10 bit set, so the
    // (status & 0x18) != 0x18 check in the constructor passes and no
    // soft-reset/recalibration path runs.
    connection.queueRead({0x18});

    AHT21Full sensor(connection);
    check_true(!writesContain(connection, {(uint8_t)0xBA}), "init_no_reset");

    connection.queueRead({MEASUREMENT[0], MEASUREMENT[1], MEASUREMENT[2],
                          MEASUREMENT[3], MEASUREMENT[4], MEASUREMENT[5]});
    float temperature_c, humidity_pct;
    sensor.read(temperature_c, humidity_pct);
    check_true(writesContain(connection, {0xAC, 0x33, 0x00}), "read_triggers");
    check_true(humidity_pct == 50.0f, "read_humidity_value");
    check_true(temperature_c == 50.0f, "read_temperature_value");

    connection.queueRead({MEASUREMENT[0], MEASUREMENT[1], MEASUREMENT[2],
                          MEASUREMENT[3], MEASUREMENT[4], MEASUREMENT[5]});
    check_true(sensor.temperature() == 50.0f, "temperature");

    connection.queueRead({MEASUREMENT[0], MEASUREMENT[1], MEASUREMENT[2],
                          MEASUREMENT[3], MEASUREMENT[4], MEASUREMENT[5]});
    check_true(sensor.humidity() == 50.0f, "humidity");

    // read_with_crc: same 6 bytes plus a correct CRC-8 (poly 0x31, init 0xFF) trailer.
    connection.queueRead({MEASUREMENT[0], MEASUREMENT[1], MEASUREMENT[2],
                          MEASUREMENT[3], MEASUREMENT[4], MEASUREMENT[5], 0x8B});
    float t2, h2;
    bool crcOk = sensor.read_with_crc(t2, h2);
    check_true(t2 == 50.0f && h2 == 50.0f, "read_with_crc_values");
    check_true(crcOk, "read_with_crc_ok");

    connection.queueRead({MEASUREMENT[0], MEASUREMENT[1], MEASUREMENT[2],
                          MEASUREMENT[3], MEASUREMENT[4], MEASUREMENT[5], 0x00});
    check_true(!sensor.read_with_crc(t2, h2), "read_with_crc_detects_bad_crc");

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

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
