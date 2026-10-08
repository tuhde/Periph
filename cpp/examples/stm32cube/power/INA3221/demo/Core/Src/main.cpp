#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "INA3221.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x40);
    INA3221Full ina(connection, /*r_shunt=*/0.1f);

    // --- Monitor three rails simultaneously ---
    // User wires CH1 to 5V rail, CH2 to 3.3V rail, CH3 to 12V rail.
    // The demo prints a one-line tabular update each second for 30 seconds.
    printf("V1       I1       P1       V2       I2       P2       V3       I3       P3\r\n");
    for (int t = 0; t < 30; t++) {
        for (uint8_t ch = 1; ch <= 3; ch++) {
            float v = ina.voltage(ch);                // Read bus voltage, (channel) → float V
            float i = ina.current(ch);                // Read load current, (channel) → float A
            float p = ina.power(ch);                  // Read power, (channel) → float W
            printf("%.3f", v); printf(" ");
            printf("%.4f", i); printf(" ");
            printf("%.4f", p); printf("   ");
        }
        printf("\r\n");

        if (t == 9) {
            // --- Arm critical-alert limits at 1.5x current draw ---
            for (uint8_t ch = 1; ch <= 3; ch++) {
                float i = ina.current(ch);
                ina.set_critical_alert(ch, i * 1.5f);
            }
            printf("alerts armed\r\n");
        }

        if (t == 19) {
            // --- Arm shunt-voltage summation across all three channels ---
            uint8_t channels[] = {1, 2, 3};
            ina.set_summation_channels(channels, 3, 0.3f);  // Set summation channels, (channels, n, limit_v) → None
                                                             // configures SCC bits and sum limit register
            printf("summation armed\r\n");
        }

        HAL_Delay(1000);
    }

    // --- Dump alert flags and decode any that fired ---
    uint16_t flags = ina.alert_flags();               // Read alert flags, () → int
                                                       // reads Mask/Enable register, clears latched flags
    printf("Mask/Enable: 0x");
    printf("0x%X\r\n", (unsigned)flags);
    while (true) HAL_Delay(1000);
}
