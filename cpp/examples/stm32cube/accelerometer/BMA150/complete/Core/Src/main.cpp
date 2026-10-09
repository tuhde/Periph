#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "BMA150.h"

static I2C_HandleTypeDef hi2c1;
static UART_HandleTypeDef huart2;

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

    I2CConnectionSTM32Cube connection(&hi2c1, 0x38);
    BMA150Full accel(connection);                          // Create BMA150 Full driver, (connection)

    accel.set_range(8);                                     // Set measurement range, (range_g) → g
                                                             // selects ±8 g; LSB scale changes from 256 to 64 LSB/g
    accel.set_bandwidth(190);                               // Set bandwidth, (bandwidth_hz) → Hz
                                                             // picks nearest valid value (190 Hz)
    int16_t rx, ry, rz;
    accel.read_raw(rx, ry, rz);                             // Read raw 10-bit counts, (x, y, z) → int, int, int
                                                             // signed 10-bit two's-complement acceleration counts
    float temp = accel.read_temperature();                  // Read temperature, () → °C
                                                             // 0.5 °C/LSB, 0x00 maps to −30 °C
    accel.set_shadow(false);                                // Set shadow mode, (enabled) → None
                                                             // keep LSB-then-MSB ordering (shadow_dis=0)
    accel.set_low_g(0.4, 40);                               // Configure low-g, (threshold_g, duration_ms, hysteresis_g=0, counter=0) → g, ms
                                                             // 0.4 g threshold, 40 ms duration; enables SOURCE_LOW_G
    accel.set_high_g(4.0, 2);                               // Configure high-g, (threshold_g, duration_ms, hysteresis_g=0, counter=0) → g, ms
                                                             // 4.0 g threshold, 2 ms duration; enables SOURCE_HIGH_G
    accel.set_any_motion(0.5, 3);                           // Configure any-motion, (threshold_g, samples=1) → g, samples
                                                             // 0.5 g threshold, 3 consecutive samples; enables SOURCE_ANY_MOTION
    accel.set_alert(false);                                 // Toggle alert mode, (enabled) → None
                                                             // mutually exclusive with any-motion; not used here
    accel.set_latch(true);                                  // Set latched interrupts, (enabled) → None
                                                             // latched until clear_interrupt(); latch_INT=1
    uint8_t status = accel.poll_interrupt();                // Read STATUS, () → bitmask
                                                             // STATUS byte; does not clear latched bits
    accel.clear_interrupt();                                // Clear latched interrupts, () → None
                                                             // writes reset_INT to CTRL (cleared on next sample)
    accel.set_wake_up(true, 80);                            // Set self-wake-up, (enabled, pause_ms=20) → ms
                                                             // 80 ms sleep portion of the cycle

    float x, y, z;
    accel.read(x, y, z);                                    // Read 3-axis acceleration, (x, y, z) → g, g, g
                                                             // burst read of 0x02–0x07, scale 64 LSB/g
    uint8_t al, ml;
    accel.read_version(al, ml);                             // Read version, (al_version, ml_version) → version, version
    uint8_t c1 = accel.read_customer(0);                    // Read scratch byte, (index) → byte
    accel.write_customer(0, 0xA5);                          // Write scratch byte, (index, value) → None
    bool st = accel.self_test();                            // Run self-test, () → bool
    accel.soft_reset();                                     // Soft reset, () → None
                                                             // CTRL.soft_reset=1; 30 ms wait; range/bandwidth restored
    accel.sleep();                                          // Enter sleep mode, () → None
    accel.wake();                                           // Leave sleep mode, () → None

    printf("raw=(%d,%d,%d) temp=%.1f status=0x%02X al=%u ml=%u c1=0x%02X st=%s\r\n",
           (int)rx, (int)ry, (int)rz, (double)temp, status, al, ml, c1, st ? "PASS" : "FAIL");
    while (true) HAL_Delay(1000);
}
