#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "BMP581.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x46);
    BMP581Full bmp(connection);

    HAL_Delay(2000);

    uint8_t cid = bmp.chip_id();                          // Read chip ID, () → int
    (void)cid;
    bmp.configure(0x1C, BMP581Full::OSR_1X, BMP581Full::OSR_1X, true);  // Configure chip, (odr 0x00–0x1F, osr_p 0–7, osr_t 0–7, press_en) → None
    bmp.set_mode(BMP581Full::MODE_NORMAL);                // Set power mode, (mode 0/1/2/3) → None
    bmp.set_iir_filter(BMP581Full::IIR_COEFF_3, BMP581Full::IIR_BYPASS);  // Set IIR filter, (coeff_p 0–7, coeff_t 0–7) → None
    bmp.configure_fifo(BMP581Full::FIFO_BOTH, BMP581Full::FIFO_STREAM, 8);  // Configure FIFO, (frame_sel 0–3, mode 0/1, threshold 0–31) → None
    uint8_t n = bmp.fifo_count();                         // Read FIFO frame count, () → int
    bmp.enable_drdy_interrupt(true);                      // Enable data-ready interrupt, (enable bool) → None
    bmp.data_ready();                                     // Check data ready, () → bool
    float p_f, t_f;
    bmp.forced(p_f, t_f);                                 // Trigger FORCED measurement, (out p_pa, out t_c) → None
    float p_pa, t_c;
    bmp.both(p_pa, t_c);                                  // Read both atomically, (out p_pa, out t_c) → None
    float alt = bmp.altitude();                           // Compute altitude, (sea_level_pa=101325.0) → float m
    uint8_t op, ot;
    bmp.effective_osr(op, ot);                            // Read effective OSR, (out osr_p 0–7, out osr_t 0–7) → None
    bmp.set_oor_threshold(110000.0f, 200.0f, 1);          // Set OOR threshold, (threshold_pa, range_pa, count_limit 0–3) → None
    bmp.software_reset();                                 // Soft reset chip, () → None

    printf("P=%.1f Pa, T=%.2f C, alt=%.1f m, frames=%d\r\n", p_pa, t_c, alt, n);
    printf("===DONE: 0 passed, 0 failed===\r\n");
    while (true) HAL_Delay(1000);

}
