#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "APDS9960.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x39);
    APDS9960Full apds(connection);

    printf("0x%X\r\n", (unsigned)apds.chip_id());                   // Read device ID, () → uint8_t

    uint16_t c, r, g, b;
    apds.color(c, r, g, b);                                // Read all RGBC channels, (clear, red, green, blue) → void
                                                           // burst read 0x94-0x9B latches all channels atomically
    printf("%d\r\n", apds.color_clear());                    // Read clear channel, () → uint16_t
    printf("%d\r\n", apds.color_red());                      // Read red channel, () → uint16_t
    printf("%d\r\n", apds.color_green());                    // Read green channel, () → uint16_t
    printf("%d\r\n", apds.color_blue());                     // Read blue channel, () → uint16_t

    apds.configure_als(0xB6, 1);                           // Configure ALS, (atime 0-255, again 0-3) → void
                                                           // sets integration time and gain for the ALS/color engine
    apds.configure_wait(0xFF, false);                      // Configure wait, (wtime 0-255, wlong=false) → void
                                                           // sets idle period between measurement cycles
    apds.enable_wait(true);                                // Enable wait engine, (enabled) → void

    apds.enable_proximity(true);                           // Enable proximity engine, (enabled) → void
    apds.configure_proximity_led(0, 0, 0, 1);              // Configure proximity LED, (ldrive 0-3, pgain 0-3, ppulse 0-63, pplen 0-3) → void
                                                           // sets LED drive strength, gain, pulse count and length
    apds.set_led_boost(0);                                 // Set LED boost, (boost 0-3) → void
                                                           // multiplies LED current: 0=100%, 1=150%, 2=200%, 3=300%
    printf("%d\r\n", apds.proximity());                      // Read proximity count, () → uint8_t

    apds.als_threshold(100, 60000);                        // Set ALS thresholds, (low 0-65535, high 0-65535) → void
    apds.proximity_threshold(10, 200);                     // Set proximity thresholds, (low 0-255, high 0-255) → void
    apds.set_persistence(0, 1);                            // Set persistence, (ppers 0-15, apers 0-15) → void

    apds.enable_als_interrupt(true);                       // Enable ALS interrupt, (enabled) → void
    apds.enable_proximity_interrupt(true);                 // Enable proximity interrupt, (enabled) → void
    apds.clear_als_interrupt();                            // Clear ALS interrupt, () → void
    apds.clear_proximity_interrupt();                      // Clear proximity interrupt, () → void
    apds.clear_all_interrupts();                           // Clear all interrupts, () → void

    apds.set_proximity_offset(10, -5);                     // Set proximity offset, (ur -127..127, dl -127..127) → void
                                                           // sign-magnitude encoding compensates for optical crosstalk
    apds.set_proximity_mask(false, false, false, false);   // Set proximity mask, (u, d, l, r) → void

    apds.enable_gesture(true);                             // Enable gesture engine, (enabled) → void
    apds.configure_gesture(1, 0, 0, 1, 1, 50, 20);        // Configure gesture, (ggain, gldrive, gpulse, gplen, gwtime, gpenth, gexth) → void
                                                           // sets gain, LED drive, pulse, wait time, entry/exit thresholds
    printf("%d\r\n", apds.gesture_available());              // Check gesture data, () → bool
    printf("%d\r\n", apds.gesture_fifo_level());             // Read FIFO level, () → uint8_t
    uint8_t fifo_buf[128];
    uint8_t n = apds.read_gesture_fifo(fifo_buf, 32);     // Read gesture FIFO, (buf, max_len) → uint8_t
    apds.clear_gesture_fifo();                             // Clear gesture FIFO, () → void
    apds.enable_gesture_interrupt(false);                  // Enable gesture interrupt, (enabled) → void
    apds.enable_gesture(false);                            // Disable gesture engine, (enabled) → void

    printf("%d\r\n", apds.status());                         // Read STATUS register, () → uint8_t
    printf("%d\r\n", apds.is_als_valid());                   // Check ALS data valid, () → bool
    printf("%d\r\n", apds.is_proximity_valid());             // Check proximity valid, () → bool
    printf("%d\r\n", apds.is_als_saturated());               // Check ALS saturated, () → bool
    printf("%d\r\n", apds.is_proximity_saturated());         // Check proximity saturated, () → bool

    apds.enable_proximity(false);
    while (true) HAL_Delay(1000);
}
