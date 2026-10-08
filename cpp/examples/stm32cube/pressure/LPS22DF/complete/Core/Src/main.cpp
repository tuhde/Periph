#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "LPS22DF.h"

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
    LPS22DFFull lps(connection);            // Create LPS22DF driver, (connection)
    lps.configure(3, 0, false, 0, true);                     // Configure chip, (odr=10 Hz, avg=4, en_lpfp=false, lfpf_cfg=0, bdu=true) → None
    lps.oneshot();                                              // Trigger one-shot conversion, () → None
    float p = lps.pressure();                                  // Read pressure, () → float Pa
    float t = lps.temperature();                               // Read temperature, () → float °C
    float alt = lps.altitude(101325.0);                        // Compute altitude, (sea_level_pa=101325.0) → float m
    lps.software_reset();                                      // Reset chip, () → None
    lps.set_pressure_offset(-50.0);                            // Set pressure offset, (offset_pa=-50.0) → None
    lps.set_pressure_threshold(102000.0);                      // Set pressure threshold, (threshold_pa=102000.0) → None
    lps.configure_interrupt(false, false, true, false, true, false, false, false);  // Configure interrupt, (int_h_l, pp_od, drdy, drdy_pls, int_en, int_f_wtm, int_f_full, int_f_ovr) → None
    lps.configure_pressure_event(true, false, false);         // Configure pressure event, (phe=true, ple=false, lir=false) → None
    lps.autozero();                                            // Capture AUTOZERO reference, () → None
    lps.reset_reference();                                     // Reset reference, () → None
    float ref = lps.reference_pressure();                      // Read reference pressure, () → float Pa
    lps.set_fifo_mode(LPS22DFFull::FIFO_FIFO);                 // Set FIFO mode, (mode 0–5) → None
    lps.set_fifo_watermark(64);                                // Set FIFO watermark, (level 0–127) → None
    uint8_t count = lps.fifo_sample_count();                   // Read FIFO sample count, () → int
    float samples[128];
    uint8_t n_read = lps.read_fifo(samples, 128);              // Read FIFO samples, (out_buf, max_samples) → int
    uint8_t src = lps.interrupt_source();                      // Read interrupt source, () → int

    HAL_Delay(2000);
    printf("T=%.2f C, P=%.0f Pa, alt=%.1f m, ref=%.0f Pa, fifo=%u/%u, src=0x%02X\r\n",
           t, p, alt, ref, n_read, count, src);
    printf("===DONE: 0 passed, 0 failed===\r\n");
    while (true) HAL_Delay(1000);
}
