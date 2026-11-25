#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "QMI8658A.h"

#define CHECK(l, c) check_true((l), (c))
#define DELAY_MS_T(ms) vTaskDelay(pdMS_TO_TICKS(ms))

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = 0x6B;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    QMI8658AFull imu(connection);

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

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}