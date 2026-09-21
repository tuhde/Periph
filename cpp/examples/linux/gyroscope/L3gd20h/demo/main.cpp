#include <I2CConnectionLinux.h>
#include <L3gd20h.h>
#include <iostream>
#include <cmath>
#include <unistd.h>

int main() {
    I2CConnectionLinux conn(1, 0x6A);
    L3gd20hFull gyro(conn);

    // --- Configure for shake detection at 190 Hz, ±500 dps ---
    // 190 Hz ODR provides good temporal resolution for shake detection;
    // ±500 dps full scale gives 17.5 mdps/digit sensitivity, suitable for
    // detecting moderate to strong motion without clipping.
    gyro.configure(L3gd20hFull::ODR_190_HZ, 0, 1);  // Configure, (odr 0-3, bw 0-3, full_scale 0-2) -> None

    std::cout << "L3GD20H shake detector running. Shake the device..." << std::endl;

    while (true) {
        if (gyro.data_ready()) {                       // Check data ready, () -> bool
            float x, y, z;
            gyro.gyro(x, y, z);                        // Read angular rate, () -> (float, float, float) rad/s
            float magnitude = std::sqrt(x*x + y*y + z*z);
            if (magnitude > 1.0) {
                std::cout << "SHAKE DETECTED: mag=" << magnitude
                          << " (x=" << x << " y=" << y << " z=" << z << ")" << std::endl;
            } else {
                std::cout << "x=" << x << " y=" << y << " z=" << z
                          << " mag=" << magnitude << std::endl;
            }
        }
    }
    return 0;
}