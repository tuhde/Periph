#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "LPS28DFW.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x5C);
    LPS28DFWFull lps(connection);                          // Create LPS28DFW driver, (connection)

    HAL_Delay(2000);

    uint8_t cid = lps.chip_id();                           // Read chip ID, () → uint8_t
                                                           // returns 0xB4 for LPS28DFW
    lps.configure(LPS28DFWFull::ODR_25_HZ, LPS28DFWFull::AVG_64,
                  LPS28DFWFull::FS_MODE_1, 1, LPS28DFWFull::LFPF_ODR_OVER_4);  // Configure chip, (odr 0–8, avg 0–7, fs_mode 0/1, lpf_en 0/1, lpf_cfg 0/1) → void
                                                           // sets output data rate, averaging, full-scale, IIR filter
    lps.set_threshold(1050.0, 1, 1);                       // Set pressure threshold, (threshold_hpa, high, low) → void
                                                           // arms PH/PL when pressure crosses threshold_hPa
    lps.set_offset(0.5);                                   // Set one-point calibration, (offset_hpa) → void
                                                           // subtracts 0.5 hPa from subsequent readings
    uint8_t ready = lps.is_data_ready();                   // Check data ready, () → uint8_t
                                                           // reads STATUS.P_DA
    float p = 0.0f, t = 0.0f;
    lps.read(p, t);                                        // Read both values, (pressure, temperature) → void
                                                           // burst-reads pressure+temperature
    lps.softreset();                                       // Soft reset, () → void
                                                           // waits ~2 ms for reboot
    lps.fifo_configure(LPS28DFWFull::FIFO_FIFO, 16, 1);    // Configure FIFO, (mode 0–6, wtm 0–127, stop_on_wtm 0/1) → void
                                                           // enables 16-sample watermark FIFO
    uint8_t level = lps.fifo_level();                      // FIFO unread count, () → uint8_t
    float samples[128] = {0};
    lps.fifo_read(level, samples);                         // Drain FIFO, (count, buf) → void
    lps.read_oneshot(p, t);                                // One-shot read, (pressure, temperature) → void
                                                           // triggers a single measurement with ODR=0
    float alt = lps.altitude();                            // Compute altitude, (sea_level_hpa=1013.25) → float m
    printf("chip=0x%02X ready=%u p=%.2f t=%.2f alt=%.1f level=%u\r\n",
           cid, ready, (double)p, (double)t, (double)alt, level);
    printf("===DONE: 0 passed, 0 failed===\r\n");
    while (true) { HAL_Delay(1000); }
}
