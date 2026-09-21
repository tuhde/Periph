#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "SPIConnectionLinux.h"
#include "AD7706.h"

int main() {
    const char* bus_env    = getenv("SPI_BUS");
    const char* device_env = getenv("SPI_DEVICE");
    int bus    = bus_env    ? atoi(bus_env)    : 0;
    int device = device_env ? atoi(device_env) : 0;
    SPIConnectionLinux connection(bus, device, 3, 5000000);

    AD7706Full adc(connection, 2.5, AD7706Minimal::MCLK_2_4576MHZ);      // Create AD7706 driver, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → AD7706Full

    adc.configure(2, AD7706Full::GAIN_8, true, true, 60);                // Configure channel 2, (channel=2, gain=GAIN_8, bipolar=true, buffered=true, output_rate_hz=60) → None
                                                                          // writes Setup and Clock Registers for channel 2; does not calibrate
    adc.self_calibrate(2);                                               // Self-calibrate channel 2, (channel=2) → None
                                                                          // runs internal self-calibration, blocks until DRDY
    adc.configure(3, AD7706Full::GAIN_8, true, true, 60);                // Configure channel 3, (channel=3, gain=GAIN_8, bipolar=true, buffered=true, output_rate_hz=60) → None
    adc.self_calibrate(3);                                               // Self-calibrate channel 3, (channel=3) → None

    uint32_t off2 = adc.get_offset_calibration(2);                       // Read offset calibration, (channel=2) → uint32_t 24-bit
    uint32_t gain2 = adc.get_gain_calibration(2);                        // Read gain calibration, (channel=2) → uint32_t 24-bit
    printf("ch2 offset=%lu gain=%lu\n", (unsigned long)off2, (unsigned long)gain2);

    uint16_t raw1 = adc.read_raw(1);                                     // Read raw 16-bit code, (channel=1) → uint16_t
                                                                          // blocks until DRDY, returns raw Data Register code
    float v1 = adc.read_voltage(1);                                      // Read voltage, (channel=1) → float V
                                                                          // converts raw code to volts using channel's current gain/bipolar
    float v2 = adc.read_voltage(2);                                      // Read voltage, (channel=2) → float V
    float v3 = adc.read_voltage(3);                                      // Read voltage, (channel=3) → float V
    printf("ch1 raw=%u ch1 v=%.4f ch2 v=%.4f ch3 v=%.4f\n", raw1, v1, v2, v3);

    adc.standby();                                                       // Enter standby, () → None
                                                                          // sets STBY=1 (~10 µA, registers retained)
    usleep(100000);
    adc.wakeup();                                                        // Exit standby, () → None
                                                                          // clears STBY; blocks until a fresh conversion is available
    return 0;
}
