#include <cstdio>
#include <cstdlib>
#include "SPIConnectionLinux.h"
#include "HY3131.h"

int main() {
    const char* bus_env    = getenv("SPI_BUS");
    const char* device_env = getenv("SPI_DEVICE");
    int bus    = bus_env    ? atoi(bus_env)    : 0;
    int device = device_env ? atoi(device_env) : 0;
    SPIConnectionLinux connection(bus, device, 0, 1000000);

    HY3131Minimal adc(connection, 1.2f);                                    // Construct and initialise the HY3131, (connection, vref=1.2 V) → HY3131Minimal

    float v = adc.read_voltage();                                           // Read AD1 differential voltage, () → float V
    printf("%.5f\n", (double)v);
    return 0;
}
