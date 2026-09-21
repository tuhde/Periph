#include <cstdio>
#include <cstdlib>
#include "SPIConnectionLinux.h"
#include "AD7706.h"

int main() {
    const char* bus_env    = getenv("SPI_BUS");
    const char* device_env = getenv("SPI_DEVICE");
    int bus    = bus_env    ? atoi(bus_env)    : 0;
    int device = device_env ? atoi(device_env) : 0;
    SPIConnectionLinux connection(bus, device, 3, 5000000);

    AD7706Minimal adc(connection, 2.5, AD7706Minimal::MCLK_2_4576MHZ);   // Create AD7706 driver, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → AD7706Minimal
                                                                          // default configuration: gain 1, bipolar, unbuffered, 50 Hz, self-calibrated Channel 1

    float v = adc.read_voltage();                                       // Read Channel 1 voltage, () → float V
    printf("%.4f\n", v);
    return 0;
}
