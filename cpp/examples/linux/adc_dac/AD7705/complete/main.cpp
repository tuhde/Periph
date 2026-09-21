#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "SPIConnectionLinux.h"
#include "AD7705.h"

int main() {
    const char* bus_env    = getenv("SPI_BUS");
    const char* device_env = getenv("SPI_DEVICE");
    int bus    = bus_env    ? atoi(bus_env)    : 0;
    int device = device_env ? atoi(device_env) : 0;
    SPIConnectionLinux connection(bus, device, 3, 5000000);

    AD7705Full adc(connection, 2.5, AD7705Minimal::MCLK_2_4576MHZ);      // Create AD7705 driver, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → AD7705Full

    adc.configure(2, AD7705Full::GAIN_8, true, true, 60);                // Configure channel 2, (channel=2, gain=GAIN_8, bipolar=true, buffered=true, output_rate_hz=60) → None
                                                                          // writes Setup and Clock Registers for channel 2; does not calibrate
    adc.self_calibrate(2);                                               // Self-calibrate channel 2, (channel=2) → None
                                                                          // runs internal self-calibration, blocks until DRDY

    uint32_t off2 = adc.get_offset_calibration(2);                       // Read offset calibration, (channel=2) → uint32_t 24-bit
    uint32_t gain2 = adc.get_gain_calibration(2);                        // Read gain calibration, (channel=2) → uint32_t 24-bit
    printf("ch2 offset=%lu gain=%lu\n", (unsigned long)off2, (unsigned long)gain2);

    uint16_t raw1 = adc.read_raw(1);                                     // Read raw 16-bit code, (channel=1) → uint16_t
                                                                          // blocks until DRDY, returns raw Data Register code
    float v1 = adc.read_voltage(1);                                      // Read voltage, (channel=1) → float V
                                                                          // converts raw code to volts using channel's current gain/bipolar
    float v2 = adc.read_voltage(2);                                      // Read voltage, (channel=2) → float V
    printf("ch1 raw=%u ch1 v=%.4f ch2 v=%.4f\n", raw1, v1, v2);

    adc.standby();                                                       // Enter standby, () → None
                                                                          // sets STBY=1 (~10 µA, registers retained)
    usleep(100000);
    adc.wakeup();                                                        // Exit standby, () → None
                                                                          // clears STBY; blocks until a fresh conversion is available
    return 0;
}
