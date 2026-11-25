#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Mpr121.h"

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

    I2CConnectionSTM32Cube connection(&hi2c1, 0x5A);                                   // Create I2C connection, (&hi2c1, addr=0x5A) → I2CConnectionSTM32Cube
    MPR121Full mpr(connection);                                                   // Create MPR121 Full, (connection) → MPR121Full

    mpr.stop();                                                                   // Enter Stop Mode, () → void
    mpr.configure_thresholds(0, 15, 8);                                           // Set thresholds, (electrode=0, touch=15, release=8) → void
    mpr.configure_all_thresholds(12, 6);                                          // Apply thresholds to all, (touch=12, release=6) → void
    mpr.configure_proximity_thresholds(8, 4);                                     // Set ELEPROX thresholds, (touch=8, release=4) → void
    mpr.configure_baseline_filter(1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0);               // Set baseline filter, (mhdr, nhdr, nclr, fdlr, mhdf, nhdf, nclf, fdlf, nhdt, nclt, fdlt) → void
    mpr.configure_sampling(16, 1, 0, 0, 4);                                       // Set AFE config, (cdc=16, cdt=1, ffi=0, sfi=0, esi=4) → void
    mpr.configure_debounce(1, 1);                                                 // Set debounce, (touch=1, release=1) → void
    mpr.configure_autoconfig(3300, 0, false, true, true);                         // Configure autoconfig, (vdd_mv=3300, retry=0, scts=false, are=true, ace=true) → void
    mpr.start(12, 2, 0);                                                          // Enter Run Mode, (n_electrodes=12, cl=2, eleprox_en=0) → void

    for (int i = 0; i < 10; i++) {
        HAL_Delay(200);
        uint16_t t = mpr.touched();                                               // Read 12-bit touch bitmask, () → uint16_t bitmask
        uint16_t f0 = mpr.filtered(0);                                            // Read ELE0 filtered, (electrode=0) → uint16_t 0..1023
        uint16_t b0 = mpr.baseline(0);                                            // Read ELE0 baseline, (electrode=0) → uint16_t 0..1023
        uint16_t oor = mpr.oor_status();                                          // Read OOR bitmask, () → uint16_t bitmask
        bool pt = mpr.proximity_touched();                                        // Read proximity touched, () → bool
        printf("t=0x%03X f0=%u b0=%u oor=0x%03X pt=%d\r\n", t, f0, b0, oor, pt);
    }
    mpr.enable_interrupt(MPR121Full::SOURCE_OOR);                                 // Enable interrupt source, (source=SOURCE_OOR) → void
    mpr.disable_interrupt(MPR121Full::SOURCE_OOR);                                // Disable interrupt source, (source=SOURCE_OOR) → void
    mpr.clear_overcurrent();                                                      // Clear OVCF, () → void
    mpr.reset();                                                                  // Soft reset, () → void
    while (true) HAL_Delay(1000);
}
