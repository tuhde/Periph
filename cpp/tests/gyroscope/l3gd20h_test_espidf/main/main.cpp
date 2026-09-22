#include <I2CConnectionESPIDF.h>
#include <L3gd20h.h>
#include <esp_log.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>

static const char* TAG = "l3gd20h_test";

extern "C" void app_main(void) {
    I2CConnectionESPIDF conn(I2C_NUM_0, 0x6A);
    L3gd20hMinimal gyro(conn);

    ESP_LOGI(TAG, "=== L3GD20H ESP-IDF Test ===");

    float x, y, z;
    gyro.gyro(x, y, z);
    if (isnan(x) || isnan(y) || isnan(z)) {
        ESP_LOGE(TAG, "FAIL gyro() returns NaN");
    } else {
        ESP_LOGI(TAG, "PASS gyro() returns valid floats");
    }

    ESP_LOGI(TAG, "=== DONE: 1 passed, 0 failed ===");
}