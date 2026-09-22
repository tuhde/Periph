#include <I2CConnectionLinux.h>
#include <L3gd20h.h>
#include <iostream>
#include <unistd.h>

int main() {
    I2CConnectionLinux conn(1, 0x6A);
    L3gd20hFull gyro(conn);

    gyro.configure(L3gd20hFull::ODR_190_HZ, 0, 1);  // Configure, (odr 0-3, bw 0-3, full_scale 0-2) -> None

    gyro.configure_hp_filter(L3gd20hFull::HPM_NORMAL, 0);  // Configure HPF, (mode 0-3, cutoff 0-15) -> None
    gyro.enable_hp_filter(true);                            // Enable HPF, (enable=true) -> None

    gyro.configure_fifo(L3gd20hFull::FIFO_FIFO, 10);  // Configure FIFO, (mode 0/1/2/3/7, watermark 0-31) -> None
    gyro.enable_fifo(true);                            // Enable FIFO, (enable=true) -> None

    gyro.set_power_mode(L3gd20hFull::POWER_NORMAL);    // Set power mode, (mode='normal'/'sleep'/'power_down') -> None

    uint8_t who = gyro.who_am_i();                     // Read WHO_AM_I, () -> uint8_t
    std::cout << "WHO_AM_I: 0x" << std::hex << (int)who << std::dec << std::endl;

    int8_t temp = gyro.temperature();                  // Read temperature, () -> int8_t
    std::cout << "Temperature: " << (int)temp << std::endl;

    while (true) {
        if (gyro.data_ready()) {                       // Check data ready, () -> bool
            float x, y, z;
            gyro.gyro(x, y, z);                        // Read angular rate, () -> (float, float, float) rad/s
            std::cout << "x=" << x << " y=" << y << " z=" << z << " rad/s" << std::endl;

            int16_t rx, ry, rz;
            gyro.gyro_raw(rx, ry, rz);                 // Read raw, () -> (int16_t, int16_t, int16_t)

            uint8_t level = gyro.fifo_level();         // FIFO level, () -> uint8_t
            if (level > 0) {
                float fx[32], fy[32], fz[32];
                uint8_t n = gyro.read_fifo(fx, fy, fz, level); // Read FIFO, (out_x, out_y, out_z, max) -> uint8_t
                std::cout << "FIFO: " << (int)n << " samples" << std::endl;
            }
        }
        usleep(10000);
    }
    return 0;
}