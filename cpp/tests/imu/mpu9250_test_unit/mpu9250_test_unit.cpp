#include <stdio.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "MPU9250.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Access protected register constants for building expected values.
class MPU9250TestAccess : public MPU9250Full {
public:
    using MPU9250Full::MPU9250Full;
    using MPU9250Full::REG_SMPLRT_DIV;
    using MPU9250Full::REG_CONFIG;
    using MPU9250Full::REG_GYRO_CONFIG;
    using MPU9250Full::REG_ACCEL_CONFIG;
    using MPU9250Full::REG_ACCEL_CONFIG2;
    using MPU9250Full::REG_FIFO_EN;
    using MPU9250Full::REG_INT_PIN_CFG;
    using MPU9250Full::REG_INT_STATUS;
    using MPU9250Full::REG_ACCEL_XOUT_H;
    using MPU9250Full::REG_TEMP_OUT_H;
    using MPU9250Full::REG_GYRO_XOUT_H;
    using MPU9250Full::REG_USER_CTRL;
    using MPU9250Full::REG_PWR_MGMT_1;
    using MPU9250Full::REG_FIFO_COUNTH;
    using MPU9250Full::REG_FIFO_R_W;
    using MPU9250Full::REG_WHO_AM_I;
    using MPU9250Full::WHO_AM_I_VALUE;
    using MPU9250Full::AK8963_REG_CNTL1;
    using MPU9250Full::AK8963_REG_ASAX;
    using MPU9250Full::AK8963_REG_ASAY;
    using MPU9250Full::AK8963_REG_ASAZ;
    using MPU9250Full::AK8963_REG_HXL;
};

// Encode a signed 16-bit value as its two big-endian bytes.
static void s16(int16_t value, uint8_t& hi, uint8_t& lo) {
    uint16_t u = static_cast<uint16_t>(value);
    hi = static_cast<uint8_t>(u >> 8);
    lo = static_cast<uint8_t>(u & 0xFF);
}

// Encode a signed 16-bit value as its two little-endian bytes.
static void s16le(int16_t value, uint8_t& lo, uint8_t& hi) {
    s16(value, hi, lo);
}

static bool eq(const std::vector<uint8_t>& a, std::initializer_list<uint8_t> b) {
    return a == std::vector<uint8_t>(b);
}

int main() {
    // The AK8963 magnetometer sits behind I²C bypass as its own device at
    // 0x0C, so it needs its own connection - separate from the MPU-9250's
    // own, mirroring how the real driver is wired (see MPU9250Full.h).
    I2CConnectionMock connection;
    connection.setRegister(MPU9250TestAccess::REG_WHO_AM_I, {MPU9250TestAccess::WHO_AM_I_VALUE});
    I2CConnectionMock magConnection;

    MPU9250TestAccess sensor(connection, magConnection);
    check_true(true, "init");

    // init sequence: reset, wait, wake, WHO_AM_I check, default config writes
    // (MPU9250 additionally writes ACCEL_CONFIG2, unlike MPU6050).
    const auto& writes = connection.writes();
    check_true(writes.size() == 8, "init_write_count");
    check_true(eq(writes[0], {MPU9250TestAccess::REG_PWR_MGMT_1, 0x80}), "init_write_reset");
    check_true(eq(writes[1], {MPU9250TestAccess::REG_PWR_MGMT_1, 0x01}), "init_write_wake");
    check_true(eq(writes[2], {MPU9250TestAccess::REG_WHO_AM_I}), "init_who_am_i_read");
    check_true(eq(writes[3], {MPU9250TestAccess::REG_GYRO_CONFIG, 0x00}), "init_write_gyro_config");
    check_true(eq(writes[4], {MPU9250TestAccess::REG_ACCEL_CONFIG, 0x00}), "init_write_accel_config");
    check_true(eq(writes[5], {MPU9250TestAccess::REG_ACCEL_CONFIG2, 0x00}), "init_write_accel_config2");
    check_true(eq(writes[6], {MPU9250TestAccess::REG_CONFIG, 0x03}), "init_write_config");
    check_true(eq(writes[7], {MPU9250TestAccess::REG_SMPLRT_DIV, 0x04}), "init_write_smplrt_div");

    // accel(): raw (16384, -8192, 4096) at default ACCEL_FS_SEL=0 (16384 LSB/g).
    uint8_t hi, lo;
    s16(16384, hi, lo);
    connection.setRegister(MPU9250TestAccess::REG_ACCEL_XOUT_H, {hi, lo});
    s16(-8192, hi, lo);
    connection.setRegister(MPU9250TestAccess::REG_ACCEL_XOUT_H + 2, {hi, lo});
    s16(4096, hi, lo);
    connection.setRegister(MPU9250TestAccess::REG_ACCEL_XOUT_H + 4, {hi, lo});
    float ax, ay, az;
    sensor.accel(ax, ay, az);
    check_true(fabsf(ax - 9.80665f) < 1e-4f, "accel_x");
    check_true(fabsf(ay - (-4.903325f)) < 1e-4f, "accel_y");
    check_true(fabsf(az - 2.4516625f) < 1e-4f, "accel_z");

    // gyro(): raw (131, -131, 262) at default GYRO_FS_SEL=0 (131.0 LSB/(deg/s)) -> (1, -1, 2) dps.
    s16(131, hi, lo);
    connection.setRegister(MPU9250TestAccess::REG_GYRO_XOUT_H, {hi, lo});
    s16(-131, hi, lo);
    connection.setRegister(MPU9250TestAccess::REG_GYRO_XOUT_H + 2, {hi, lo});
    s16(262, hi, lo);
    connection.setRegister(MPU9250TestAccess::REG_GYRO_XOUT_H + 4, {hi, lo});
    float gx, gy, gz;
    sensor.gyro(gx, gy, gz);
    const float DEG2RAD = 3.141592653589793f / 180.0f;
    check_true(fabsf(gx - 1.0f * DEG2RAD) < 1e-4f, "gyro_x");
    check_true(fabsf(gy - (-1.0f) * DEG2RAD) < 1e-4f, "gyro_y");
    check_true(fabsf(gz - 2.0f * DEG2RAD) < 1e-4f, "gyro_z");

    sensor.configure_gyro(2);
    check_true(eq(connection.writes().back(), {MPU9250TestAccess::REG_GYRO_CONFIG, 2 << 3}), "configure_gyro_writes");
    // Sensitivity for FS_SEL=2 is 32.8 LSB/(deg/s); raw=328 -> 10 dps.
    s16(328, hi, lo);
    connection.setRegister(MPU9250TestAccess::REG_GYRO_XOUT_H, {hi, lo});
    s16(0, hi, lo);
    connection.setRegister(MPU9250TestAccess::REG_GYRO_XOUT_H + 2, {hi, lo});
    connection.setRegister(MPU9250TestAccess::REG_GYRO_XOUT_H + 4, {hi, lo});
    float gx2, gy2, gz2;
    sensor.gyro(gx2, gy2, gz2);
    check_true(fabsf(gx2 - 10.0f * DEG2RAD) < 1e-3f, "configure_gyro_changes_sensitivity");

    sensor.configure_accel(1);
    check_true(eq(connection.writes().back(), {MPU9250TestAccess::REG_ACCEL_CONFIG, 1 << 3}), "configure_accel_writes");
    // Sensitivity for AFS_SEL=1 is 8192 LSB/g; raw=8192 -> 1g.
    s16(8192, hi, lo);
    connection.setRegister(MPU9250TestAccess::REG_ACCEL_XOUT_H, {hi, lo});
    s16(0, hi, lo);
    connection.setRegister(MPU9250TestAccess::REG_ACCEL_XOUT_H + 2, {hi, lo});
    connection.setRegister(MPU9250TestAccess::REG_ACCEL_XOUT_H + 4, {hi, lo});
    float ax2, ay2, az2;
    sensor.accel(ax2, ay2, az2);
    check_true(fabsf(ax2 - 9.80665f) < 1e-4f, "configure_accel_changes_sensitivity");

    sensor.configure_dlpf(5, 2);
    check_true(eq(connection.writes()[connection.writes().size() - 2], {MPU9250TestAccess::REG_CONFIG, 5}), "configure_dlpf_gyro");
    check_true(eq(connection.writes().back(), {MPU9250TestAccess::REG_ACCEL_CONFIG2, 2}), "configure_dlpf_accel");

    sensor.configure_sample_rate(9);
    check_true(eq(connection.writes().back(), {MPU9250TestAccess::REG_SMPLRT_DIV, 9}), "configure_sample_rate");

    // temperature(): raw=340 -> 340/333.87 + 21.0.
    s16(340, hi, lo);
    connection.setRegister(MPU9250TestAccess::REG_TEMP_OUT_H, {hi, lo});
    check_true(fabsf(sensor.temperature() - (340.0f / 333.87f + 21.0f)) < 1e-3f, "temperature");

    // accel_raw() / gyro_raw()
    s16(100, hi, lo);
    connection.setRegister(MPU9250TestAccess::REG_ACCEL_XOUT_H, {hi, lo});
    s16(-200, hi, lo);
    connection.setRegister(MPU9250TestAccess::REG_ACCEL_XOUT_H + 2, {hi, lo});
    s16(300, hi, lo);
    connection.setRegister(MPU9250TestAccess::REG_ACCEL_XOUT_H + 4, {hi, lo});
    int16_t arx, ary, arz;
    sensor.accel_raw(arx, ary, arz);
    check_true(arx == 100 && ary == -200 && arz == 300, "accel_raw");

    s16(-50, hi, lo);
    connection.setRegister(MPU9250TestAccess::REG_GYRO_XOUT_H, {hi, lo});
    s16(60, hi, lo);
    connection.setRegister(MPU9250TestAccess::REG_GYRO_XOUT_H + 2, {hi, lo});
    s16(-70, hi, lo);
    connection.setRegister(MPU9250TestAccess::REG_GYRO_XOUT_H + 4, {hi, lo});
    int16_t grx, gry, grz;
    sensor.gyro_raw(grx, gry, grz);
    check_true(grx == -50 && gry == 60 && grz == -70, "gyro_raw");

    // data_ready()
    connection.setRegister(MPU9250TestAccess::REG_INT_STATUS, {0x01});
    check_true(sensor.data_ready() == true, "data_ready_true");
    connection.setRegister(MPU9250TestAccess::REG_INT_STATUS, {0x00});
    check_true(sensor.data_ready() == false, "data_ready_false");

    // set_sleep(): PWR_MGMT_1 is 0x01 in the register map after init.
    sensor.set_sleep(true);
    check_true(eq(connection.writes().back(), {MPU9250TestAccess::REG_PWR_MGMT_1, 0x41}), "set_sleep_true");
    sensor.set_sleep(false);
    check_true(eq(connection.writes().back(), {MPU9250TestAccess::REG_PWR_MGMT_1, 0x01}), "set_sleep_false");

    // fifo_count()
    connection.setRegister(MPU9250TestAccess::REG_FIFO_COUNTH, {0x03, 0x45});
    check_true(sensor.fifo_count() == (((0x03 & 0x1F) << 8) | 0x45), "fifo_count");

    // read_fifo()
    connection.setRegister(MPU9250TestAccess::REG_FIFO_COUNTH, {0x00, 0x02});
    connection.setRegister(MPU9250TestAccess::REG_FIFO_R_W, {0xAA, 0xBB});
    uint8_t fifoBuf[8] = {0};
    uint16_t n = sensor.read_fifo(fifoBuf, sizeof(fifoBuf));
    check_true(n == 2 && fifoBuf[0] == 0xAA && fifoBuf[1] == 0xBB, "read_fifo");

    connection.setRegister(MPU9250TestAccess::REG_FIFO_COUNTH, {0x00, 0x00});
    n = sensor.read_fifo(fifoBuf, sizeof(fifoBuf));
    check_true(n == 0, "read_fifo_empty");

    // enable_fifo(gyro=true, accel=true, temp=false): FIFO_EN write, then a
    // USER_CTRL read (whose write_read phase also appends a {reg} entry to
    // connection.writes()), then the USER_CTRL write.
    sensor.enable_fifo(true, true, false);
    const auto& writes2 = connection.writes();
    size_t n2 = writes2.size();
    check_true(eq(writes2[n2 - 3], {MPU9250TestAccess::REG_FIFO_EN, (1 << 3) | (1 << 4)}), "enable_fifo_fifo_en_write");
    check_true(eq(writes2[n2 - 2], {MPU9250TestAccess::REG_USER_CTRL}), "enable_fifo_user_ctrl_read");
    check_true(eq(writes2[n2 - 1], {MPU9250TestAccess::REG_USER_CTRL, 0x40}), "enable_fifo_user_ctrl_write");

    // reset_fifo(): USER_CTRL is 0x40 in the register map after enable_fifo().
    sensor.reset_fifo();
    check_true(eq(connection.writes().back(), {MPU9250TestAccess::REG_USER_CTRL, 0x44}), "reset_fifo");

    // enable_mag(): INT_PIN_CFG write (on the primary connection), AK8963
    // CNTL1 power-down, CNTL1 fuse ROM access, ASAX/ASAY/ASAZ reads, CNTL1
    // power-down, then CNTL1 mode write (bits=16 -> 0x10 | mode) - all on
    // magConnection.
    magConnection.setRegister(MPU9250TestAccess::AK8963_REG_ASAX, {200});
    magConnection.setRegister(MPU9250TestAccess::AK8963_REG_ASAY, {100});
    magConnection.setRegister(MPU9250TestAccess::AK8963_REG_ASAZ, {50});
    sensor.enable_mag();
    check_true(eq(connection.writes().back(), {MPU9250TestAccess::REG_INT_PIN_CFG, 0x22}), "enable_mag_int_pin_cfg_write");
    const auto& magWrites = magConnection.writes();
    check_true(magWrites.size() == 7, "enable_mag_write_count");
    check_true(eq(magWrites[0], {MPU9250TestAccess::AK8963_REG_CNTL1, 0x00}), "enable_mag_power_down_1");
    check_true(eq(magWrites[1], {MPU9250TestAccess::AK8963_REG_CNTL1, 0x0F}), "enable_mag_fuse_rom");
    check_true(eq(magWrites[2], {MPU9250TestAccess::AK8963_REG_ASAX}), "enable_mag_asax_read");
    check_true(eq(magWrites[3], {MPU9250TestAccess::AK8963_REG_ASAY}), "enable_mag_asay_read");
    check_true(eq(magWrites[4], {MPU9250TestAccess::AK8963_REG_ASAZ}), "enable_mag_asaz_read");
    check_true(eq(magWrites[5], {MPU9250TestAccess::AK8963_REG_CNTL1, 0x00}), "enable_mag_power_down_2");
    check_true(eq(magWrites[6], {MPU9250TestAccess::AK8963_REG_CNTL1, 0x16}), "enable_mag_mode_write");  // 16-bit | mode=6

    // mag(): raw (1000, -500, 250) with scale factors derived from ASAX/ASAY/ASAZ
    // above: (200-128)/256+1=1.28125, (100-128)/256+1=0.890625, (50-128)/256+1=0.6953125.
    uint8_t lo1, hi1, lo2, hi2, lo3, hi3;
    s16le(1000, lo1, hi1);
    s16le(-500, lo2, hi2);
    s16le(250, lo3, hi3);
    magConnection.setRegister(MPU9250TestAccess::AK8963_REG_HXL, {lo1, hi1, lo2, hi2, lo3, hi3, 0x00});
    float mx, my, mz;
    sensor.mag(mx, my, mz);
    check_true(fabsf(mx - (1000.0f * 0.15f * 1.28125f)) < 1e-3f, "mag_x");
    check_true(fabsf(my - (-500.0f * 0.15f * 0.890625f)) < 1e-3f, "mag_y");
    check_true(fabsf(mz - (250.0f * 0.15f * 0.6953125f)) < 1e-3f, "mag_z");

    // mag_raw()
    s16le(111, lo1, hi1);
    s16le(-222, lo2, hi2);
    s16le(333, lo3, hi3);
    magConnection.setRegister(MPU9250TestAccess::AK8963_REG_HXL, {lo1, hi1, lo2, hi2, lo3, hi3, 0x00});
    int16_t mrx, mry, mrz;
    sensor.mag_raw(mrx, mry, mrz);
    check_true(mrx == 111 && mry == -222 && mrz == 333, "mag_raw");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
