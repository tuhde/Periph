#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MPU9250.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .intr_priority = 0,
        .trans_queue_depth = 0,
        .flags = { .enable_internal_pullup = true, .allow_pd = false },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x68,
        .scl_speed_hz    = 400000,
        .scl_wait_us     = 0,
        .flags           = {},
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    i2c_device_config_t mag_dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x0C,  // AK8963, same bus, reached via I²C bypass
        .scl_speed_hz    = 400000,
        .scl_wait_us     = 0,
        .flags           = {},
    };
    i2c_master_dev_handle_t magDev;
    i2c_master_bus_add_device(bus, &mag_dev_cfg, &magDev);

    I2CConnectionESPIDF connection(dev);
    I2CConnectionESPIDF magConnection(magDev);
    MPU9250Full imu(connection, magConnection);

    float ax, ay, az, gx, gy, gz;
    imu.accel(ax, ay, az);                            // Read 3-axis acceleration, (float&, float&, float&) → void m/s²
                                                         // converts raw accel register to m/s² (16384 LSB/g at ±2g)
    imu.gyro(gx, gy, gz);                             // Read 3-axis angular rate, (float&, float&, float&) → void rad/s
                                                         // converts raw gyro register to rad/s (131.0 LSB/(°/s) at ±250dps)

    imu.configure_gyro(1);                            // Configure gyro range, (full_scale=0) → void
                                                         // sets GYRO_FS_SEL: 0=±250, 1=±500, 2=±1000, 3=±2000 dps
    imu.configure_accel(1);                           // Configure accel range, (full_scale=0) → void
                                                         // sets ACCEL_FS_SEL: 0=±2g, 1=±4g, 2=±8g, 3=±16g
    imu.configure_dlpf(3, 3);                         // Configure DLPF bandwidth, (gyro_dlpf=3, accel_dlpf=3) → void
                                                         // sets DLPF_CFG/A_DLPFCFG: 0=256/460Hz … 6=5/10Hz (gyro/accel BW)
    imu.configure_sample_rate(4);                     // Configure sample rate, (divider=4) → void
                                                         // sets SMPLRT_DIV: output rate = 1kHz / (1 + divider)

    float t = imu.temperature();                      // Read die temperature, () → float °C
                                                         // converts raw temp register: raw/333.87 + 21.0

    imu.enable_mag(16, 6);                            // Initialize magnetometer, (bits=16, mode=6) → void
                                                         // reads ASA calibration, sets 16-bit 100 Hz continuous mode
    float mx, my, mz;
    imu.mag(mx, my, mz);                              // Read 3-axis magnetic field, (float&, float&, float&) → void µT
                                                         // applies factory ASA calibration and sensitivity scaling

    int16_t rax, ray, raz, rgx, rgy, rgz, rmx, rmy, rmz;
    imu.accel_raw(rax, ray, raz);                     // Read raw accel values, (int16_t&, int16_t&, int16_t&) → void
                                                         // returns raw 16-bit signed accelerometer register values
    imu.gyro_raw(rgx, rgy, rgz);                      // Read raw gyro values, (int16_t&, int16_t&, int16_t&) → void
                                                         // returns raw 16-bit signed gyroscope register values
    imu.mag_raw(rmx, rmy, rmz);                       // Read raw mag values, (int16_t&, int16_t&, int16_t&) → void
                                                         // returns raw 16-bit signed magnetometer register values

    bool ready = imu.data_ready();                    // Check data ready flag, () → bool
                                                         // reads RAW_DATA_RDY_INT bit from INT_STATUS register

    imu.set_sleep(true);                              // Enter sleep mode, (sleep=true) → void
                                                         // sets SLEEP bit in PWR_MGMT_1
    vTaskDelay(pdMS_TO_TICKS(10));
    imu.set_sleep(false);                             // Wake from sleep, (sleep=true) → void
                                                         // clears SLEEP bit in PWR_MGMT_1

    imu.reset_fifo();                                 // Reset FIFO buffer, () → void
                                                         // sets FIFO_RST bit in USER_CTRL to clear the buffer
    imu.enable_fifo(true, true);                      // Enable FIFO sources, (gyro=true, accel=true, temp=false) → void
                                                         // configures FIFO_EN and sets FIFO_EN bit in USER_CTRL
    vTaskDelay(pdMS_TO_TICKS(50));
    uint16_t count = imu.fifo_count();                // Read FIFO byte count, () → uint16_t
                                                         // reads FIFO_COUNTH/L: number of bytes available
    uint8_t data[256];
    uint16_t read = imu.read_fifo(data, 256);         // Read FIFO data, (uint8_t*, uint16_t) → uint16_t
                                                         // reads all available bytes from FIFO_R_W register
    imu.reset_fifo();                                 // Reset FIFO buffer, () → void
                                                         // sets FIFO_RST bit in USER_CTRL to clear the buffer

    printf("Complete example done\n");
}