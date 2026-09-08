#include <stdio.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "MPU6050.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Access protected register constants for building expected values.
class MPU6050TestAccess : public MPU6050Full {
public:
    using MPU6050Full::MPU6050Full;
    using MPU6050Full::REG_SMPLRT_DIV;
    using MPU6050Full::REG_CONFIG;
    using MPU6050Full::REG_GYRO_CONFIG;
    using MPU6050Full::REG_ACCEL_CONFIG;
    using MPU6050Full::REG_FIFO_EN;
    using MPU6050Full::REG_INT_STATUS;
    using MPU6050Full::REG_ACCEL_XOUT_H;
    using MPU6050Full::REG_TEMP_OUT_H;
    using MPU6050Full::REG_GYRO_XOUT_H;
    using MPU6050Full::REG_USER_CTRL;
    using MPU6050Full::REG_PWR_MGMT_1;
    using MPU6050Full::REG_PWR_MGMT_2;
    using MPU6050Full::REG_FIFO_COUNTH;
    using MPU6050Full::REG_FIFO_R_W;
    using MPU6050Full::REG_WHO_AM_I;
    using MPU6050Full::WHO_AM_I_VALUE;
};

// Encode a signed 16-bit value as its two big-endian bytes.
static void s16(int16_t value, uint8_t& hi, uint8_t& lo) {
    uint16_t u = static_cast<uint16_t>(value);
    hi = static_cast<uint8_t>(u >> 8);
    lo = static_cast<uint8_t>(u & 0xFF);
}

static bool eq(const std::vector<uint8_t>& a, std::initializer_list<uint8_t> b) {
    return a == std::vector<uint8_t>(b);
}

int main() {
    I2CConnectionMock connection;
    connection.setRegister(MPU6050TestAccess::REG_WHO_AM_I, {MPU6050TestAccess::WHO_AM_I_VALUE});

    MPU6050TestAccess sensor(connection);
    check_true(true, "init");

    const auto& writes = connection.writes();
    check_true(writes.size() == 7, "init_write_count");
    check_true(eq(writes[0], {MPU6050TestAccess::REG_PWR_MGMT_1, 0x80}), "init_write_reset");
    check_true(eq(writes[1], {MPU6050TestAccess::REG_PWR_MGMT_1, 0x01}), "init_write_wake");
    check_true(eq(writes[2], {MPU6050TestAccess::REG_WHO_AM_I}), "init_who_am_i_read");
    check_true(eq(writes[3], {MPU6050TestAccess::REG_GYRO_CONFIG, 0x00}), "init_write_gyro_config");
    check_true(eq(writes[4], {MPU6050TestAccess::REG_ACCEL_CONFIG, 0x00}), "init_write_accel_config");
    check_true(eq(writes[5], {MPU6050TestAccess::REG_CONFIG, 0x03}), "init_write_config");
    check_true(eq(writes[6], {MPU6050TestAccess::REG_SMPLRT_DIV, 0x04}), "init_write_smplrt_div");

    // accel(): raw (16384, -8192, 4096) at default AFS_SEL=0 (16384 LSB/g).
    uint8_t hi, lo;
    s16(16384, hi, lo);
    connection.setRegister(MPU6050TestAccess::REG_ACCEL_XOUT_H, {hi, lo});
    s16(-8192, hi, lo);
    connection.setRegister(MPU6050TestAccess::REG_ACCEL_XOUT_H + 2, {hi, lo});
    s16(4096, hi, lo);
    connection.setRegister(MPU6050TestAccess::REG_ACCEL_XOUT_H + 4, {hi, lo});
    float ax, ay, az;
    sensor.accel(ax, ay, az);
    check_true(fabsf(ax - 9.80665f) < 1e-4f, "accel_x");
    check_true(fabsf(ay - (-4.903325f)) < 1e-4f, "accel_y");
    check_true(fabsf(az - 2.4516625f) < 1e-4f, "accel_z");

    // gyro(): raw (131, -131, 262) at default FS_SEL=0 (131.0 LSB/(deg/s)) -> (1, -1, 2) dps.
    s16(131, hi, lo);
    connection.setRegister(MPU6050TestAccess::REG_GYRO_XOUT_H, {hi, lo});
    s16(-131, hi, lo);
    connection.setRegister(MPU6050TestAccess::REG_GYRO_XOUT_H + 2, {hi, lo});
    s16(262, hi, lo);
    connection.setRegister(MPU6050TestAccess::REG_GYRO_XOUT_H + 4, {hi, lo});
    float gx, gy, gz;
    sensor.gyro(gx, gy, gz);
    const float DEG2RAD = 3.141592653589793f / 180.0f;
    check_true(fabsf(gx - 1.0f * DEG2RAD) < 1e-4f, "gyro_x");
    check_true(fabsf(gy - (-1.0f) * DEG2RAD) < 1e-4f, "gyro_y");
    check_true(fabsf(gz - 2.0f * DEG2RAD) < 1e-4f, "gyro_z");

    sensor.configure_gyro(2);
    check_true(eq(connection.writes().back(), {MPU6050TestAccess::REG_GYRO_CONFIG, 2 << 3}), "configure_gyro_writes");
    // Sensitivity for FS_SEL=2 is 32.8 LSB/(deg/s); raw=328 -> 10 dps.
    s16(328, hi, lo);
    connection.setRegister(MPU6050TestAccess::REG_GYRO_XOUT_H, {hi, lo});
    s16(0, hi, lo);
    connection.setRegister(MPU6050TestAccess::REG_GYRO_XOUT_H + 2, {hi, lo});
    connection.setRegister(MPU6050TestAccess::REG_GYRO_XOUT_H + 4, {hi, lo});
    float gx2, gy2, gz2;
    sensor.gyro(gx2, gy2, gz2);
    check_true(fabsf(gx2 - 10.0f * DEG2RAD) < 1e-3f, "configure_gyro_changes_sensitivity");

    sensor.configure_accel(1);
    check_true(eq(connection.writes().back(), {MPU6050TestAccess::REG_ACCEL_CONFIG, 1 << 3}), "configure_accel_writes");
    // Sensitivity for AFS_SEL=1 is 8192 LSB/g; raw=8192 -> 1g.
    s16(8192, hi, lo);
    connection.setRegister(MPU6050TestAccess::REG_ACCEL_XOUT_H, {hi, lo});
    s16(0, hi, lo);
    connection.setRegister(MPU6050TestAccess::REG_ACCEL_XOUT_H + 2, {hi, lo});
    connection.setRegister(MPU6050TestAccess::REG_ACCEL_XOUT_H + 4, {hi, lo});
    float ax2, ay2, az2;
    sensor.accel(ax2, ay2, az2);
    check_true(fabsf(ax2 - 9.80665f) < 1e-4f, "configure_accel_changes_sensitivity");

    sensor.configure_dlpf(5);
    check_true(eq(connection.writes().back(), {MPU6050TestAccess::REG_CONFIG, 5}), "configure_dlpf");

    sensor.configure_sample_rate(9);
    check_true(eq(connection.writes().back(), {MPU6050TestAccess::REG_SMPLRT_DIV, 9}), "configure_sample_rate");

    // temperature(): raw=340 -> 340/340 + 36.53 = 37.53 degC.
    s16(340, hi, lo);
    connection.setRegister(MPU6050TestAccess::REG_TEMP_OUT_H, {hi, lo});
    check_true(fabsf(sensor.temperature() - 37.53f) < 1e-3f, "temperature");

    // accel_raw() / gyro_raw()
    s16(100, hi, lo);
    connection.setRegister(MPU6050TestAccess::REG_ACCEL_XOUT_H, {hi, lo});
    s16(-200, hi, lo);
    connection.setRegister(MPU6050TestAccess::REG_ACCEL_XOUT_H + 2, {hi, lo});
    s16(300, hi, lo);
    connection.setRegister(MPU6050TestAccess::REG_ACCEL_XOUT_H + 4, {hi, lo});
    int16_t arx, ary, arz;
    sensor.accel_raw(arx, ary, arz);
    check_true(arx == 100 && ary == -200 && arz == 300, "accel_raw");

    s16(-50, hi, lo);
    connection.setRegister(MPU6050TestAccess::REG_GYRO_XOUT_H, {hi, lo});
    s16(60, hi, lo);
    connection.setRegister(MPU6050TestAccess::REG_GYRO_XOUT_H + 2, {hi, lo});
    s16(-70, hi, lo);
    connection.setRegister(MPU6050TestAccess::REG_GYRO_XOUT_H + 4, {hi, lo});
    int16_t grx, gry, grz;
    sensor.gyro_raw(grx, gry, grz);
    check_true(grx == -50 && gry == 60 && grz == -70, "gyro_raw");

    // data_ready()
    connection.setRegister(MPU6050TestAccess::REG_INT_STATUS, {0x01});
    check_true(sensor.data_ready() == true, "data_ready_true");
    connection.setRegister(MPU6050TestAccess::REG_INT_STATUS, {0x00});
    check_true(sensor.data_ready() == false, "data_ready_false");

    // set_sleep(): PWR_MGMT_1 is 0x01 in the register map after init.
    sensor.set_sleep(true);
    check_true(eq(connection.writes().back(), {MPU6050TestAccess::REG_PWR_MGMT_1, 0x41}), "set_sleep_true");
    sensor.set_sleep(false);
    check_true(eq(connection.writes().back(), {MPU6050TestAccess::REG_PWR_MGMT_1, 0x01}), "set_sleep_false");

    // set_standby(xa=true, zg=true)
    sensor.set_standby(true, false, false, false, false, true);
    check_true(eq(connection.writes().back(), {MPU6050TestAccess::REG_PWR_MGMT_2, 0x21}), "set_standby");

    // fifo_count()
    connection.setRegister(MPU6050TestAccess::REG_FIFO_COUNTH, {0x03, 0x45});
    check_true(sensor.fifo_count() == (((0x03 & 0x1F) << 8) | 0x45), "fifo_count");

    // read_fifo()
    connection.setRegister(MPU6050TestAccess::REG_FIFO_COUNTH, {0x00, 0x02});
    connection.setRegister(MPU6050TestAccess::REG_FIFO_R_W, {0xAA, 0xBB});
    uint8_t fifoBuf[8] = {0};
    uint16_t n = sensor.read_fifo(fifoBuf, sizeof(fifoBuf));
    check_true(n == 2 && fifoBuf[0] == 0xAA && fifoBuf[1] == 0xBB, "read_fifo");

    connection.setRegister(MPU6050TestAccess::REG_FIFO_COUNTH, {0x00, 0x00});
    n = sensor.read_fifo(fifoBuf, sizeof(fifoBuf));
    check_true(n == 0, "read_fifo_empty");

    // enable_fifo(gyro=true, accel=true, temp=false): FIFO_EN write, then a
    // USER_CTRL read (whose write_read phase also appends a bytes([reg])
    // entry to connection.writes()), then the USER_CTRL write.
    sensor.enable_fifo(true, true, false);
    const auto& writes2 = connection.writes();
    size_t n2 = writes2.size();
    check_true(eq(writes2[n2 - 3], {MPU6050TestAccess::REG_FIFO_EN, (1 << 3) | (1 << 4)}), "enable_fifo_fifo_en_write");
    check_true(eq(writes2[n2 - 2], {MPU6050TestAccess::REG_USER_CTRL}), "enable_fifo_user_ctrl_read");
    check_true(eq(writes2[n2 - 1], {MPU6050TestAccess::REG_USER_CTRL, 0x40}), "enable_fifo_user_ctrl_write");

    // reset_fifo(): USER_CTRL is 0x40 in the register map after enable_fifo().
    sensor.reset_fifo();
    check_true(eq(connection.writes().back(), {MPU6050TestAccess::REG_USER_CTRL, 0x44}), "reset_fifo");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
