#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x6B
#endif

#include <Wire.h>
#include <math.h>
#include <Periph.h>

I2CConnection connection(Wire, TEST_ADDR);
QMI8658AFull imu(connection);

#define CHECK(l, c) check_true((l), (c))
#define DELAY_MS_T(ms) delay(ms)

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) {
        Serial.print("PASS "); Serial.println(label);
        passed++;
    } else {
        Serial.print("FAIL "); Serial.println(label);
        failed++;
    }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);

    // The constructor already verified WHO_AM_I (it aborts on a mismatch).
    float ax, ay, az, gx, gy, gz;
    imu.accel(ax, ay, az);
    float mag = sqrtf(ax * ax + ay * ay + az * az);
    CHECK("gravity magnitude", mag > 7.0f && mag < 12.6f);
    imu.gyro(gx, gy, gz);
    CHECK("gyro finite", gx > -10.0f && gx < 10.0f && gy > -10.0f && gy < 10.0f && gz > -10.0f && gz < 10.0f);

    float a[3], g[3], tc;
    imu.read_all(a, g, tc);
    CHECK("read_all temp range", tc > -40.0f && tc < 85.0f);
    CHECK("temperature matches", fabsf(imu.temperature() - tc) < 5.0f);

    int16_t rax, ray, raz, rgx, rgy, rgz;
    imu.accel_raw(rax, ray, raz);
    imu.gyro_raw(rgx, rgy, rgz);
    float rmag = sqrtf((float)rax * rax + (float)ray * ray + (float)raz * raz);
    CHECK("accel_raw gravity", rmag > 8000.0f && rmag < 24000.0f);
    (void)rgx; (void)rgy; (void)rgz;

    uint32_t ts1 = imu.timestamp();
    DELAY_MS_T(100);
    CHECK("timestamp advances", imu.timestamp() != ts1);

    imu.configure_accel(1, 5);
    imu.configure_gyro(5, 5);
    DELAY_MS_T(200);
    imu.accel(ax, ay, az);
    mag = sqrtf(ax * ax + ay * ay + az * az);
    CHECK("accel after reconfig", mag > 7.0f && mag < 12.6f);
    imu.configure_accel();
    imu.configure_gyro();
    imu.configure_filters(true, 3, true, 3);
    DELAY_MS_T(200);

    imu.configure_fifo(2, 1, 8);
    DELAY_MS_T(300);
    uint16_t count = imu.fifo_count();
    CHECK("fifo_count non-zero", count > 0);
    uint8_t fifo[96];
    uint16_t got = imu.read_fifo(fifo, sizeof(fifo));
    CHECK("read_fifo returns whole frames", got > 0 && got % 12 == 0);
    imu.reset_fifo();
    imu.configure_fifo(0);
    DELAY_MS_T(100);

    uint8_t revision, firmware[3], usid[6];
    imu.device_info(revision, firmware, usid);
    CHECK("device_info callable", true);
    imu.set_sensors(true, true);

    uint16_t status = imu.poll_interrupt();
    CHECK("poll_interrupt callable", status <= 0x1FF);
    (void)revision; (void)firmware; (void)usid; (void)status; (void)count;

    Serial.print("===DONE: "); Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
