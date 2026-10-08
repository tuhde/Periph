#include <I2CConnectionSTM32Cube.h>
#include <L3gd20h.h>
#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"

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
int main() {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();

    I2CConnectionSTM32Cube conn(&hi2c1, 0x6A);
    L3gd20hFull gyro(conn);

    gyro.configure(L3gd20hFull::ODR_190_HZ, 0, 1);  // Configure, (odr 0-3, bw 0-3, full_scale 0-2) -> None

    gyro.configure_hp_filter(L3gd20hFull::HPM_NORMAL, 0);  // Configure HPF, (mode 0-3, cutoff 0-15) -> None
    gyro.enable_hp_filter(true);                            // Enable HPF, (enable=true) -> None

    gyro.configure_fifo(L3gd20hFull::FIFO_FIFO, 10);  // Configure FIFO, (mode 0/1/2/3/7, watermark 0-31) -> None
    gyro.enable_fifo(true);                            // Enable FIFO, (enable=true) -> None

    gyro.set_power_mode(L3gd20hFull::POWER_NORMAL);    // Set power mode, (mode='normal'/'sleep'/'power_down') -> None

    int8_t temp = gyro.temperature();                  // Read temperature, () -> int8_t
    printf("Temperature: %d\r\n", temp);

    while (true) {
        if (gyro.data_ready()) {                       // Check data ready, () -> bool
            float x, y, z;
            gyro.gyro(x, y, z);                        // Read angular rate, () -> (float, float, float) rad/s
            printf("x=%.3f y=%.3f z=%.3f rad/s\r\n", x, y, z);

            int16_t rx, ry, rz;
            gyro.gyro_raw(rx, ry, rz);                 // Read raw, () -> (int16_t, int16_t, int16_t)

            uint8_t level = gyro.fifo_level();         // FIFO level, () -> uint8_t
            if (level > 0) {
                float fx[32], fy[32], fz[32];
                uint8_t n = gyro.read_fifo(fx, fy, fz, level); // Read FIFO, (out_x, out_y, out_z, max) -> uint8_t
                printf("FIFO: %d samples\r\n", n);
            }
        }
        HAL_Delay(10);
    }
}
