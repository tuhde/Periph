#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Lps33hw.h"

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
    LPS33HWFull lps(connection);

    static int passed = 0, failed = 0;

    HAL_Delay(2000);

    lps.configure(LPS33HWFull::ODR_10_HZ, true, true, LPS33HWFull::LPFP_BW_ODR_20, false, false);  // Configure chip, (odr 0–5, bdu, en_lpfp, lpfp_cfg 0/1, lc_en, sim) → None
                                                        // sets CTRL_REG1 (ODR/BDU/LPF), RES_CONF (LPFP_CFG), CTRL_REG2 (SIM)
    float p_lp, t_lp;
    bool ok = lps.one_shot(p_lp, t_lp);                   // Trigger single measurement, () → (bool, &pressure_Pa, &temperature_C)
                                                        // requires ODR=0; returns false on timeout
    uint8_t st = lps.status();                            // Read STATUS register, () → uint8_t
                                                        // bit 0=P_DA, bit 1=T_DA, bit 4=P_OR, bit 5=T_OR
    lps.reset();                                          // Software reset via SWRESET, () → None
                                                        // restores CTRL_REG1 / CTRL_REG2 to defaults
    lps.reboot();                                         // Reload factory trimming via BOOT, () → None
                                                        // takes one ODR cycle to self-clear
    lps.set_pressure_offset(0.5f);                        // Apply one-point calibration, (offset_hPa) → None
                                                        // 1 RPDS LSB = 1/16 hPa
    lps.set_autozero();                                   // Set AUTOZERO, () → None
                                                        // current pressure is stored in REF_P
    lps.clear_autozero();                                 // Clear AUTOZERO and reset REF_P, () → None
    lps.set_autorifp();                                   // Set AUTORIFP, () → None
                                                        // next measurement value stored in RPDS
    lps.clear_autorifp();                                 // Clear AUTORIFP and reset RPDS, () → None

    lps.configure_interrupt(true, false, false, false, LPS33HWFull::INT_S_DATA_SIGNALS, false, false);  // Route events to INT_DRDY, (drdy, f_fth, f_ovr, f_fss5, int_s 0–3, active_low, open_drain) → None
                                                        // writes CTRL_REG3 (signal routing) and INTERRUPT_CFG (INT_S, active level, output mode)
    lps.configure_pressure_interrupt(true, false, 1050.0f, true);  // Configure pressure threshold interrupt, (high_en, low_en, threshold_hPa, latch) → None
                                                        // arms INT_DRDY when pressure exceeds threshold; latched until INT_SOURCE read
    uint8_t isrc = lps.interrupt_status();                // Read INT_SOURCE, () → uint8_t
                                                        // clears latched pressure interrupts

    lps.enable_fifo(LPS33HWFull::FIFO_MODE_FIFO, 16);     // Enable FIFO, (mode 0–7 excl. 5, watermark 0–31) → None
                                                        // writes FIFO_CTRL and sets F_EN in CTRL_REG2
    uint8_t fsts = lps.fifo_status();                     // Read FIFO_STATUS, () → uint8_t
                                                        // bit 7=FTH, bit 6=OVR, bits 5:0=FSS
    lps.disable_fifo();                                   // Disable FIFO and reset to Bypass, () → None
    lps.reset_lpf();                                      // Read LPFP_RES to flush transitory LPF state, () → None

    float p = lps.pressure();                             // Read pressure, () → float Pa
    float t = lps.temperature();                          // Read temperature, () → float °C

    printf("P=");
    printf("%.1f", p);
    printf(" Pa, T=");
    printf("%.2f", t);
    printf(" C\r\n");

    printf("===DONE: ");
    printf("%d", passed);
    printf(" passed, ");
    printf("%d", failed);
    printf(" failed===\r\n");
    while (true) {
    HAL_Delay(1000);
        HAL_Delay(10);
    }

}
