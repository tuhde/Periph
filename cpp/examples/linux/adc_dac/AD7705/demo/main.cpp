#include <cstdio>
#include <cstdlib>
#include <cmath>
#include <unistd.h>
#include "SPIConnectionLinux.h"
#include "AD7705.h"

#define TEMP_COEFF 0.05f
#define TEMP_REFERENCE 1.25f
#define CHANGE_THRESHOLD 0.001f

int main() {
    const char* bus_env    = getenv("SPI_BUS");
    const char* device_env = getenv("SPI_DEVICE");
    int bus    = bus_env    ? atoi(bus_env)    : 0;
    int device = device_env ? atoi(device_env) : 0;
    SPIConnectionLinux connection(bus, device, 3, 5000000);

    AD7705Full adc(connection, 2.5, AD7705Minimal::MCLK_2_4576MHZ);      // Create AD7705 driver, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → AD7705Full

    // --- Configure both channels for the bridge-pressure application ---
    // Channel 1 reads the pressure bridge at gain 128 (small mV-level signal);
    // Channel 2 reads an auxiliary temperature sensor at gain 2 for temperature
    // compensation of the pressure reading.
    adc.configure(1, AD7705Full::GAIN_128, true, true, 50);              // Configure channel 1, (channel=1, gain=GAIN_128, bipolar=true, buffered=true, output_rate_hz=50) → None
    adc.configure(2, AD7705Full::GAIN_2, true, false, 50);               // Configure channel 2, (channel=2, gain=GAIN_2, bipolar=true, buffered=false, output_rate_hz=50) → None

    // --- Self-calibrate both channels before the measurement loop ---
    adc.self_calibrate(1);                                               // Self-calibrate channel 1, (channel=1) → None
    adc.self_calibrate(2);                                               // Self-calibrate channel 2, (channel=2) → None

    float last_pressure = 0.0f;
    bool first = true;
    while (true) {
        // --- Sample continuously and compensate the pressure reading for temperature ---
        // pressure_compensated = pressure - TEMP_COEFF * (temp - TEMP_REFERENCE)
        // Print whenever the compensated reading changes by more than 1 mV.
        float pressure_raw = adc.read_voltage(1);                       // Read voltage on channel 1, (channel=1) → float V
        float temp = adc.read_voltage(2);                               // Read voltage on channel 2, (channel=2) → float V
        float pressure = pressure_raw - TEMP_COEFF * (temp - TEMP_REFERENCE);
        if (first || fabs(pressure - last_pressure) > CHANGE_THRESHOLD) {
            printf("pressure=%.4f V (raw %.4f V, temp %.4f V)\n", pressure, pressure_raw, temp);
            last_pressure = pressure;
            first = false;
        }
        usleep(200000);
    }
    return 0;
}
