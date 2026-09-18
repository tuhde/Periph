#include <I2CConnectionLinux.h>
#include <L3gd20h.h>
#include <iostream>

int main() {
    I2CConnectionLinux conn(1, 0x6A);
    L3gd20hMinimal gyro(conn);

    std::cout << "=== L3GD20H Linux Test ===" << std::endl;

    float x, y, z;
    gyro.gyro(x, y, z);
    if (std::isnan(x) || std::isnan(y) || std::isnan(z)) {
        std::cout << "FAIL gyro() returns NaN" << std::endl;
    } else {
        std::cout << "PASS gyro() returns valid floats" << std::endl;
    }

    std::cout << "=== DONE: 1 passed, 0 failed ===" << std::endl;
    return 0;
}