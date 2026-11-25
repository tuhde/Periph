#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "BME280.h"

static UART_HandleTypeDef huart2;
static I2C_HandleTypeDef hi2c1;

// SysTick_Handler overrides the weak default in startup_stm32f411xe.s —
// without this, HAL_GetTick()/HAL_Delay() never advance.
extern "C" void SysTick_Handler(void) {
    HAL_IncTick();
}

// Retargets printf() to USART2 (PA2/PA3), which NUCLEO-F411RE's on-board
// ST-LINK exposes as a USB virtual COM port at 115200 baud.
extern "C" int _write(int file, char* ptr, int len) {
    (void)file;
    HAL_UART_Transmit(&huart2, reinterpret_cast<uint8_t*>(ptr), static_cast<uint16_t>(len), HAL_MAX_DELAY);
    return len;
}

static void gpio_clocks_init(void) {
    __HAL_RCC_GPIOA_CLK_ENABLE();
    __HAL_RCC_GPIOB_CLK_ENABLE();
    __HAL_RCC_GPIOC_CLK_ENABLE();
}

static void uart2_init(void) {
    __HAL_RCC_USART2_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_2 | GPIO_PIN_3;  // PA2=TX, PA3=RX
    gpioInit.Mode      = GPIO_MODE_AF_PP;
    gpioInit.Pull      = GPIO_NOPULL;
    gpioInit.Speed     = GPIO_SPEED_FREQ_LOW;
    gpioInit.Alternate = GPIO_AF7_USART2;
    HAL_GPIO_Init(GPIOA, &gpioInit);

    huart2.Instance          = USART2;
    huart2.Init.BaudRate     = 115200;
    huart2.Init.WordLength   = UART_WORDLENGTH_8B;
    huart2.Init.StopBits     = UART_STOPBITS_1;
    huart2.Init.Parity       = UART_PARITY_NONE;
    huart2.Init.Mode         = UART_MODE_TX_RX;
    huart2.Init.HwFlowCtl    = UART_HWCONTROL_NONE;
    huart2.Init.OverSampling = UART_OVERSAMPLING_16;
    HAL_UART_Init(&huart2);
}

static void i2c1_init(void) {
    __HAL_RCC_I2C1_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_8 | GPIO_PIN_9;  // PB8=SCL, PB9=SDA
    gpioInit.Mode      = GPIO_MODE_AF_OD;
    gpioInit.Pull      = GPIO_PULLUP;               // internal pull-up, in case the breakout has none
    gpioInit.Speed     = GPIO_SPEED_FREQ_HIGH;
    gpioInit.Alternate = GPIO_AF4_I2C1;
    HAL_GPIO_Init(GPIOB, &gpioInit);

    hi2c1.Instance             = I2C1;
    hi2c1.Init.ClockSpeed      = 100000;
    hi2c1.Init.DutyCycle       = I2C_DUTYCYCLE_2;
    hi2c1.Init.OwnAddress1     = 0;
    hi2c1.Init.AddressingMode  = I2C_ADDRESSINGMODE_7BIT;
    hi2c1.Init.DualAddressMode = I2C_DUALADDRESS_DISABLE;
    hi2c1.Init.OwnAddress2     = 0;
    hi2c1.Init.GeneralCallMode = I2C_GENERALCALL_DISABLE;
    hi2c1.Init.NoStretchMode   = I2C_NOSTRETCH_DISABLE;
    HAL_I2C_Init(&hi2c1);
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x76);
    BME280Full bme(connection);

    HAL_Delay(2000);

    // --- Weather monitoring preset: forced mode, ×1/×1/×1, filter off ---
    // BME280 datasheet "weather monitoring" preset: minimum power,
    // single-shot, 8 ms typ / 9.3 ms max per cycle. Sleep between samples
    // to demonstrate battery-friendly indoor monitoring.
    bme.configure(BME280Full::OSRS_X1, BME280Full::OSRS_X1, BME280Full::OSRS_X1, BME280Full::MODE_FORCED, BME280Full::FILTER_OFF, BME280Full::T_SB_0_5_MS);  // Configure chip, (osrs_t=×1, osrs_p=×1, osrs_h=×1, mode=forced, filter=off, t_sb=0) → void

    float t_min = 999, t_max = -999, t_sum = 0;
    float h_min = 999, h_max = -999, h_sum = 0;
    float p_min = 1e9, p_max = -1e9, p_sum = 0;
    int n_samples = 10;

    for (int n = 0; n < n_samples; n++) {
        float t = bme.temperature();                    // Read temperature, () → float °C
        float p = bme.pressure();                       // Read pressure, () → float hPa
        float h = bme.humidity();                       // Read humidity, () → float %RH
        float a = bme.altitude();                       // Compute altitude, (sea_level_hpa=1013.25) → float m
        float d = bme.dew_point();                      // Compute dew point, () → float °C
        if (t < t_min) t_min = t; if (t > t_max) t_max = t; t_sum += t;
        if (h < h_min) h_min = h; if (h > h_max) h_max = h; h_sum += h;
        if (p < p_min) p_min = p; if (p > p_max) p_max = p; p_sum += p;
        printf("%d", n);
        printf(": ");
        printf("%.1f", t); printf(" C, ");
        printf("%.1f", h); printf(" %%RH, ");
        printf("%.1f", p); printf(" hPa, dew=");
        printf("%.1f", d); printf(" C, alt=");
        printf("%.1f", a); printf(" m\r\n");
        HAL_Delay(1000);
    }

    // --- Half-way: breathe gently on the sensor for 3 seconds ---
    // User exposes the sensor to humid exhaled air; humidity climbs from
    // ~40 %RH toward ~80 %RH, dew point spikes toward ambient temperature,
    // pressure stays flat, temperature rises only slightly. Demonstrates
    // the humidity channel's response and the dew-point alarm use case.
    printf("--- Breathe gently on the sensor for 3 seconds ---\r\n");
    HAL_Delay(3000);
    {
        float t = bme.temperature();                    // Read temperature, () → float °C
        float p = bme.pressure();                       // Read pressure, () → float hPa
        float h = bme.humidity();                       // Read humidity, () → float %RH
        float d = bme.dew_point();                      // Compute dew point, () → float °C
        if (t < t_min) t_min = t; if (t > t_max) t_max = t; t_sum += t;
        if (h < h_min) h_min = h; if (h > h_max) h_max = h; h_sum += h;
        if (p < p_min) p_min = p; if (p > p_max) p_max = p; p_sum += p;
        printf("after breath: ");
        printf("%.1f", t); printf(" C, ");
        printf("%.1f", h); printf(" %%RH, ");
        printf("%.1f", p); printf(" hPa, dew=");
        printf("%.1f", d); printf(" C\r\n");
        n_samples++;
    }

    printf("T: "); printf("%.1f", t_min); printf("/");
    printf("%.1f", t_sum / n_samples); printf("/");
    printf("%.1f", t_max); printf(" C\r\n");
    printf("RH: "); printf("%.1f", h_min); printf("/");
    printf("%.1f", h_sum / n_samples); printf("/");
    printf("%.1f", h_max); printf(" %\r\n");
    printf("P: "); printf("%.1f", p_min); printf("/");
    printf("%.1f", p_sum / n_samples); printf("/");
    printf("%.1f", p_max); printf(" hPa\r\n");

    printf("===DONE: 0 passed, 0 failed===\r\n");
    while (true) {
    HAL_Delay(1000); 
        HAL_Delay(10);
    }

}
