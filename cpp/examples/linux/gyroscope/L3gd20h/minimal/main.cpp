#include <I2CConnectionLinux.h>
#include <L3gd20h.h>
#include <iostream>
#include <unistd.h>

int main() {
    I2CConnectionLinux conn(1, 0x6A);
    L3gd20hMinimal gyro(conn);

    while (true) {
        float x, y, z;
        gyro.gyro(x, y, z);  // Read angular rate, () -> (float, float, float) rad/s
        std::cout << "x=" << x << " y=" << y << " z=" << z << " rad/s" << std::endl;
        usleep(100000);
    }
    return 0;
}