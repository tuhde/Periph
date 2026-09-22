#include <I2CConnectionESPIDF.h>
#include <L3gd20h.h>
#include <esp_log.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <math.h>

static const char* TAG = "l3gd20h_demo";

extern "C" void app_main(void) {
    I2CConnectionESPIDF conn(I2C_NUM_0, 0x6A);
    L3gd20hFull gyro(conn);

    // --- Configure for shake detection at 190 Hz, ±500 dps ---
    // 190 Hz ODR provides good temporal resolution for shake detection;
    // ±500 dps full scale gives 17.5 mdps/digit sensitivity, suitable for
    // detecting moderate to strong motion without clipping.
    gyro.configure(L3gd20hFull::ODR_190_HZ, 0, 1);  // Configure, (odr 0-3, bw 0-3, full_scale 0-2) -> None

    ESP_LOGI(TAG, "L3GD20H shake detector running. Shake the device...");

    while (true) {
        if (gyro.data_ready()) {                       // Check data ready, () -> bool
            float x, y, z;
            gyro.gyro(x, y, z);                        // Read angular rate, () -> (float, float, float) rad/s
            float magnitude = sqrtf(x*x + y*y + z*z);
            if (magnitude > 1.0) {
                ESP_LOGI(TAG, "SHAKE DETECTED: mag=%.3f (x=%.3f y=%.3f z=%.3f)", magnitude, x, y, z);
            } else {
                ESP_LOGI(TAG, "x=%.3f y=%.3f z=%.3f mag=%.3f", x, y, z, magnitude);
            }
        }
    }
}