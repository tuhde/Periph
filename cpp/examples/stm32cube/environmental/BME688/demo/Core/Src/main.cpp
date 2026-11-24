#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "BME688.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x77);
    BME688Full bme(connection);

    HAL_Delay(2000);

    // --- Room air probe with parallel mode ---
    // Part 1: ten forced-mode readings (T, RH, hPa, Ohm, dew point), one per 5 s.
    // Part 2: parallel mode with a 3-step heater profile (320, 250 and 180 degC;
    // repeats 1, 2, 1; shared wait 100 ms) for one minute, printing the gas
    // resistance of every heater step, then back to sleep.
    bme.configure(BME688Full::OSRS_X2, BME688Full::OSRS_X16, BME688Full::OSRS_X1, BME688Full::MODE_SLEEP, BME688Full::FILTER_15);  // Configure chip, (osrs_t=×2, osrs_p=×16, osrs_h=×1, mode=sleep, filter=15) → void
    bme.set_heater(320, 150);                           // Configure heater profile 0, (temp_c=320, duration_ms=150) → void

    printf("--- forced mode ---\r\n");
    for (int n = 0; n < 10; n++) {
        float t, p, h, g;
        bme.read_all(t, p, h, g);                       // Read all sensors in one cycle, (t, p, h, g) → void
        float gm = logf((h > 0.01f ? h : 0.01f) / 100.0f) + 17.62f * t / (243.12f + t);
        float dew = 243.12f * gm / (17.62f - gm);       // Magnus dew point, °C
        printf("%d: %.1f C, %.1f %%RH, %.1f hPa, %.0f Ohm, dew %.1f C\r\n", n, (double)t, (double)h, (double)p, (double)g, (double)dew);
        HAL_Delay(5000);
    }

    printf("--- parallel mode ---\r\n");
    const int16_t temps[3] = {320, 250, 180};
    const uint8_t repeats[3] = {1, 2, 1};
    bme.start_parallel(temps, repeats, 3, 100.0f);      // Start parallel mode, (temps, repeats, steps=3, shared_wait_ms=100) → void
    for (int n = 0; n < 60; n++) {
        BME688Field f[3];
        uint8_t cnt = bme.read_parallel(f, 3);          // Read the new data fields, (out, max) → uint8_t count
        for (uint8_t i = 0; i < cnt; i++) {
            printf("step %d #%d: %.1f C, %.1f %%RH, %.0f Ohm (valid=%d, stable=%d)\r\n", f[i].step, f[i].sub_index, (double)f[i].temperature, (double)f[i].humidity, (double)f[i].gas_resistance, f[i].gas_valid, f[i].heater_stable);
        }
        HAL_Delay(1000);
    }
    bme.stop_parallel();                                // Back to sleep, () → void

    printf("===DONE: 0 passed, 0 failed===\r\n");
    while (true) {
    HAL_Delay(1000); 
        HAL_Delay(10);
    }

}
