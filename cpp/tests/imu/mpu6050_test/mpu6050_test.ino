#include <Wire.h>
#include "I2CTransport.h"
#include "Mpu6050.h"

#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_I2C_FREQ
#define TEST_I2C_FREQ 400000
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x68
#endif

I2CTransport transport(Wire, TEST_ADDR);
MPU6050Full  imu(transport);

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

    Wire.begin(TEST_SDA, TEST_SCL, TEST_I2C_FREQ);

    check_true("who_am_i", imu.who_am_i() == 0x68);

    float ax, ay, az;
    imu.accel(ax, ay, az);
    check_true("accel_x finite", ax > -200.0f && ax < 200.0f);
    check_true("accel_y finite", ay > -200.0f && ay < 200.0f);
    check_true("accel_z finite", az > -200.0f && az < 200.0f);

    float gx, gy, gz;
    imu.gyro(gx, gy, gz);
    check_true("gyro_x finite", gx > -100.0f && gx < 100.0f);
    check_true("gyro_y finite", gy > -100.0f && gy < 100.0f);
    check_true("gyro_z finite", gz > -100.0f && gz < 100.0f);

    float t = imu.temperature();
    check_true("temperature range", t > -40.0f && t < 85.0f);

    int16_t rax, ray, raz;
    imu.accel_raw(rax, ray, raz);
    check_true("accel_raw_x range", rax >= -32768 && rax <= 32767);

    int16_t rgx, rgy, rgz;
    imu.gyro_raw(rgx, rgy, rgz);
    check_true("gyro_raw_x range", rgx >= -32768 && rgx <= 32767);

    imu.configure_gyro(1);
    imu.configure_accel(1);
    imu.accel(ax, ay, az);
    check_true("accel after reconfig", ax > -200.0f && ax < 200.0f);

    imu.configure_dlpf(4);
    imu.configure_sample_rate(9);

    imu.set_sleep(true);
    delay(10);
    imu.set_sleep(false);
    delay(50);
    imu.accel(ax, ay, az);
    check_true("accel after wake", ax > -200.0f && ax < 200.0f);

    imu.set_standby(true, false, false, false, false, false);
    imu.set_standby();

    imu.reset_fifo();
    imu.enable_fifo(true, true, false);
    delay(50);
    uint16_t count = imu.fifo_count();
    check_true("fifo_count > 0", count > 0);
    uint8_t buf[256];
    uint16_t n = imu.read_fifo(buf, sizeof(buf));
    check_true("read_fifo matches count", n == count);

    imu.reset_fifo();

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
