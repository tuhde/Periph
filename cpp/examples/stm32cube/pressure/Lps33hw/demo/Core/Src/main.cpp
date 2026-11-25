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

    // --- Initialization and configuration for altimeter preset ---
    // ODR=10 Hz gives ~10 Hz pressure output; BDU=1 latches the output
    // registers so a coherent 24-bit pressure can be read without tearing;
    // EN_LPFP=1 with LPFP_BW_ODR_20 (LPFP_CFG=1) gives an additional
    // ODR/20 low-pass filter that suppresses the kind of cabin-air
    // pressure bursts that would otherwise read as bogus altitude steps.
    lps.configure(LPS33HWFull::ODR_10_HZ, true, true, LPS33HWFull::LPFP_BW_ODR_20, false, false);  // Configure chip, (odr=10Hz, bdu=true, en_lpfp=true, lpfp_cfg=ODR/20, lc_en=false, sim=false) → None
    lps.reset_lpf();                                      // Flush transitory LPF state after enabling EN_LPFP, () → None

    // --- Main loop: poll P_DA rather than fixed delay ---
    // The chip updates pressure asynchronously at 10 Hz; spinning on the
    // STATUS register's P_DA bit lets us sample fresh data immediately
    // rather than racing the ODR clock with HAL_Delay().
    const float sea_level_Pa = 101325.0f;
    uint32_t last_print = 0;
    uint32_t last_autozero = 0;
    uint32_t t0 = HAL_GetTick();

    while (HAL_GetTick() - t0 < 60000) {
        float p_Pa = lps.pressure();                      // Read pressure, () → float Pa
                                                        // waits for STATUS.P_DA before reading PRESS_XL..PRESS_H
        float t_C = lps.temperature();                    // Read temperature, () → float °C
        uint32_t now = HAL_GetTick();

        if (now - last_print >= 1000) {
            last_print = now;
            // --- Altitude via the barometric formula ---
            // The 44330 × (1 − (p/p0)^(1/5.255)) approximation is valid up
            // to ~11000 m and troposphere temperatures; for higher
            // altitudes use the full hypsometric equation.
            float altitude_m = 44330.0f * (1.0f - powf(p_Pa / sea_level_Pa, 1.0f / 5.255f));  // Barometric altitude, () → float m
            printf("alt=%.2f m, T=%.2f C\r\n", altitude_m, t_C);
        }

        // --- AUTOZERO removes atmospheric drift every 10 s ---
        // Weather fronts shift sea-level pressure by ~1 hPa/hour, which
        // would otherwise show up as bogus altitude drift in a relative
        // (uncalibrated) altimeter; re-zeroing REF_P every 10 s cancels
        // that slow DC bias without throwing away the 10 Hz rate.
        if (now - last_autozero >= 10000) {
            last_autozero = now;
            lps.set_autozero();                           // Set AUTOZERO, () → None
                                                        // current pressure is stored in REF_P
            printf("Reference updated.\r\n");
        }
    }

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
