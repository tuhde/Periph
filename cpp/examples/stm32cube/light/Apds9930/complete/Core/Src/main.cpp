#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Apds9930.h"

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

    I2CConnectionSTM32Cube connection(&hi2c1, 0x39);                                   // Create I2C connection, (&hi2c1, addr=0x39) → I2CConnectionSTM32Cube
    APDS9930Full apds(connection);                                                  // Create APDS-9930 Full, (connection) → APDS9930Full
                                                                                   // exposes ALS and proximity configuration methods

    HAL_Delay(110);
    apds.configure_als(0xDB, 0, false);                                            // Configure ALS, (atime=0xDB, again=0, agl=false) → void
    apds.configure_proximity(8, 0, 0, false, 0xFF);                               // Configure proximity, (ppulse=8, pgain=0, pdrive=0, pdl=false, ptime=0xFF) → void
    apds.disable_wait();                                                           // Disable wait timer, () → void
    apds.set_als_thresholds(100, 60000, 1);                                        // Set ALS thresholds, (low=100, high=60000, persistence=1) → void
    apds.set_proximity_thresholds(10, 200, 1);                                     // Set proximity thresholds, (low=10, high=200, persistence=1) → void
    apds.set_proximity_offset(0);                                                 // Set proximity offset, (offset=0) → void
    apds.sleep_after_interrupt(false);                                            // Configure SAI, (enable=false) → void

    for (int i = 0; i < 10; i++) {
        HAL_Delay(110);
        float lx = apds.lux();                                                      // Read ambient illuminance, () → float lx
        uint16_t p = apds.proximity();                                              // Read proximity count, () → uint16_t count
        uint16_t c0 = apds.ch0();                                                   // Read Ch0 raw, () → uint16_t count
        uint16_t c1 = apds.ch1();                                                   // Read Ch1 raw, () → uint16_t count
        bool avalid, pvalid, psat, aint, pint;
        apds.status(avalid, pvalid, psat, aint, pint);                              // Read STATUS decoded, (avalid, pvalid, psat, aint, pint) → void
        printf("lux=%.1f lx  prox=%u  ch0=%u  ch1=%u  AVALID=%d  PVALID=%d\r\n",
               lx, p, c0, c1, avalid, pvalid);
    }
    apds.clear_interrupt(0);                                                        // Clear interrupts, (channel=0) → void

    while (true) HAL_Delay(1000);
}
