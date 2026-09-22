#include <I2CConnectionESPIDF.h>
#include <L3gd20h.h>
#include <esp_log.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>

static const char* TAG = "l3gd20h_minimal";

extern "C" void app_main(void) {
    I2CConnectionESPIDF conn(I2C_NUM_0, 0x6A);
    L3gd20hMinimal gyro(conn);

    while (true) {
        float x, y, z;
        gyro.gyro(x, y, z);  // Read angular rate, () -> (float, float, float) rad/s
        ESP_LOGI(TAG, "x=%.3f y=%.3f z=%.3f rad/s", x, y, z);
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}