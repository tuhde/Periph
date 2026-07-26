#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "ENS160.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CTransportPicoSDK transport(i2c0, 0x53);
    ENS160Full sensor(transport);

    static int passed = 0, failed = 0;

    stdio_init_all();
    sleep_ms(2000);

    uint8_t major, minor, release;
    sensor.get_firmware_version(major, minor, release);  // Get firmware version, (major&, minor&, release&) → void
                                                          // switches to IDLE, issues GET_APPVER, returns to STANDARD
    printf("Firmware: ");
    printf("%d", major);
    printf(".");
    printf("%d", minor);
    printf(".");
    printf("%d\n", release);

    sensor.set_compensation(25.0f, 50.0f);               // Set compensation, (temp_celsius, rh_percent) → void
                                                          // improves accuracy with external T/RH readings

    sensor.configure_interrupt(true, false, false, true, false);  // Configure interrupt, (enabled, active_high, push_pull, on_data, on_gpr) → void
                                                          // sets INTn pin behavior for new data notification

    printf("Waiting for warm-up...\n");
    {
        uint8_t _aqi; float _tvoc, _eco2;
        while (!sensor.read_air_quality(_aqi, _tvoc, _eco2)) {  // Wait for valid data, () → blocks until warm
            sleep_ms(1000);
        }
    }

    float tvoc = sensor.read_tvoc();                     // Read TVOC, () → float ppb
    float eco2 = sensor.read_eco2();                     // Read eCO2, () → float ppm
    uint8_t aqi = sensor.read_aqi();                     // Read AQI, () → uint8_t 1–5
    float ethanol = sensor.read_ethanol();               // Read ethanol, () → float ppb
                                                          // alias of DATA_TVOC at 0x22
    float r1 = sensor.read_raw_resistance(1);            // Read raw resistance, (sensor=1 or 4) → float Ohms
    float r4 = sensor.read_raw_resistance(4);            // Read raw resistance, (sensor=1 or 4) → float Ohms
    float temp_actual, rh_actual;
    sensor.read_compensation_actuals(temp_actual, rh_actual);  // Read compensation actuals, (temp_celsius&, rh_percent&) → void
                                                          // returns T/RH values used by sensor

    printf("TVOC=");
    printf("%.0f", tvoc);
    printf(" ppb, eCO2=");
    printf("%.0f", eco2);
    printf(" ppm, AQI=");
    printf("%d\n", aqi);
    printf("Ethanol=");
    printf("%.0f", ethanol);
    printf(" ppb, R1=");
    printf("%.0f", r1);
    printf(" Ohm, R4=");
    printf("%.0f", r4);
    printf(" Ohm\n");
    printf("Actual T=");
    printf("%.1f", temp_actual);
    printf(" C, RH=");
    printf("%.1f", rh_actual);
    printf(" %\n");

    sensor.sleep();                                      // Enter deep sleep, () → void
                                                          // reduces current to ~10 uA
    sleep_ms(1000);
    sensor.wake();                                       // Wake and resume sensing, () → void
                                                          // transitions IDLE then STANDARD

    printf("===DONE: ");
    printf("%d", passed);
    printf(" passed, ");
    printf("%d", failed);
    printf(" failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
