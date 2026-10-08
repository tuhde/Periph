#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "AS5600.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x36);
    AS5600Full as5600(connection);

    HAL_Delay(2000);

    // --- Motor feedback monitor: read angle 10 times per second ---
    // AGC monitoring detects magnet distance drift; status changes alert to magnet removal.
    // In 5 V mode, target AGC ≈ 128; in 3.3 V mode, target AGC ≈ 64.

    uint8_t prev_status = as5600.status_byte();

    for (int n = 0; n < 10; n++) {
        float a = as5600.angle();                    // Read absolute angle, () → float degrees
        uint16_t r = as5600.raw_angle();             // Read raw unscaled angle, () → int 0-4095
        uint8_t g = as5600.agc();                    // Read AGC value, () → int

        // --- Check for status changes (magnet inserted/removed) ---
        uint8_t status = as5600.status_byte();
        if (status != prev_status) {
            if (!as5600.is_magnet_detected()) {
                printf("[MAGNET REMOVED] MD=0\r\n");
            } else {
                printf("[MAGNET DETECTED] MD=1  MH=");
                printf("%d", as5600.is_magnet_too_strong());
                printf("  ML=");
                printf("%d\r\n", as5600.is_magnet_too_weak());
            }
            prev_status = status;
        }

        // --- AGC health check ---
        if (as5600.is_magnet_detected()) {
            const char* tag = "[OK]";
            if (g < 64 || g > 192) {
                tag = "[AGC low — magnet weak or too far]";
            }
            printf("angle="); printf("%.2f", a); printf("°  raw=");
            printf("%d", r); printf("  agc="); printf("%d", g);
            printf("%d\r\n", tag);
        }

        HAL_Delay(100);
    }
    while (true) {

    HAL_Delay(1000);
        HAL_Delay(10);
    }

}
