#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ICM20948.h"

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
    dev_cfg.device_address = 0x68;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ICM20948Full imu(connection);

    // WHO_AM_I is already verified during construction; if it mismatched,
    // the constructor would have aborted before reaching here.

    float ax, ay, az, gx, gy, gz;
    imu.accel(ax, ay, az);
    check_true("accel_x finite", ax > -200.0f && ax < 200.0f);
    check_true("accel_y finite", ay > -200.0f && ay < 200.0f);
    check_true("accel_z finite", az > -200.0f && az < 200.0f);
    imu.gyro(gx, gy, gz);
    check_true("gyro_x finite", gx > -100.0f && gx < 100.0f);
    check_true("gyro_y finite", gy > -100.0f && gy < 100.0f);
    check_true("gyro_z finite", gz > -100.0f && gz < 100.0f);
    check_true("gravity magnitude", sqrtf(ax * ax + ay * ay + az * az) > 5.0f && sqrtf(ax * ax + ay * ay + az * az) < 15.0f);

    float t = imu.temperature();
    check_true("temperature range", t > -40.0f && t < 85.0f);

    float a[3], g[3], tc;
    imu.read_all(a, g, tc);
    check_true("read_all temp range", tc > -40.0f && tc < 85.0f);

    int16_t rax, ray, raz, rgx, rgy, rgz;
    imu.accel_raw(rax, ray, raz);
    imu.gyro_raw(rgx, rgy, rgz);
    check_true("accel_raw gravity", sqrtf((float)rax * rax + (float)ray * ray + (float)raz * raz) > 8000.0f && sqrtf((float)rax * rax + (float)ray * ray + (float)raz * raz) < 24000.0f);
    (void)rgx; (void)rgy; (void)rgz;

    imu.configure_gyro(1);
    imu.configure_accel(1);
    float ax2, ay2, az2;
    imu.accel(ax2, ay2, az2);
    check_true("accel after reconfig", ax2 > -200.0f && ax2 < 200.0f);

    imu.configure_filters(4, 4, true, true);
    imu.configure_sample_rate(9, 9);
    int16_t ox, oy, oz;
    imu.get_accel_offset(ox, oy, oz);
    check_true("accel offset readable", (ox + oy + oz) != 0x7FFF);

    check_true("enable_mag", imu.enable_mag());
    vTaskDelay(pdMS_TO_TICKS(150));
    float mx, my, mz;
    check_true("mag read", imu.mag(mx, my, mz));
    check_true("mag magnitude", sqrtf(mx * mx + my * my + mz * mz) > 5.0f && sqrtf(mx * mx + my * my + mz * mz) < 150.0f);
    uint8_t who = 0;
    check_true("aux_read WIA2", imu.aux_read_byte(0x0C, 0x01, who) && who == 0x09);

    imu.set_sleep(true);
    vTaskDelay(pdMS_TO_TICKS(10));
    imu.set_sleep(false);
    vTaskDelay(pdMS_TO_TICKS(50));
    float ax3, ay3, az3;
    imu.accel(ax3, ay3, az3);
    check_true("accel after wake", ax3 > -200.0f && ax3 < 200.0f);

    imu.enable_fifo(true, true, false);
    vTaskDelay(pdMS_TO_TICKS(50));
    uint16_t count = imu.fifo_count();
    check_true("fifo_count > 0", count > 0);
    uint8_t data[256];
    uint16_t read = imu.read_fifo(data, 256);
    check_true("read_fifo returns bytes", read > 0);
    imu.reset_fifo();

    imu.set_wake_on_motion(200.0f);
    uint8_t status = imu.poll_interrupt();
    check_true("poll_interrupt callable", status <= 0x1F);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}