#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "LIS3DH.h"

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

static int passed = 0, failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { printf("PASS %s\r\n", label); passed++; }
    else       { printf("FAIL %s\r\n", label); failed++; }
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    HAL_Delay(100);

    I2CConnectionSTM32Cube connection(&hi2c1, 0x18);
    LIS3DHMinimal accel(connection);                       // Create LIS3DH driver, (connection)
    LIS3DHFull accel_full(connection);                     // Create LIS3DH Full driver, (connection)

    float x, y, z;
    accel.read(x, y, z);                                    // Read 3-axis acceleration, (x, y, z) → g, g, g
    check_true(x == x && y == y && z == z, "read_returns_floats");
    float mag = sqrtf(x * x + y * y + z * z);
    check_true(mag >= 0.5f && mag <= 1.5f, "magnitude_near_1g");

    accel_full.set_range(4);
    accel_full.read(x, y, z);
    check_true(x == x && y == y && z == z, "read_after_set_range_4g");
    accel_full.set_range(2);

    int16_t rx, ry, rz;
    accel_full.read_raw(rx, ry, rz);                        // Read raw counts, (x, y, z) → LSB, LSB, LSB
    check_true(rx >= -2048 && rx <= 2047 && ry >= -2048 && ry <= 2047 &&
               rz >= -2048 && rz <= 2047, "read_raw_in_12bit_range");

    int16_t temp = accel_full.read_temperature_delta();     // Read relative temperature, () → int16_t °C
    check_true(temp >= -128 && temp <= 127, "temperature_delta_in_range");

    accel_full.set_high_pass(0, 0);
    uint8_t ref = accel_full.reset_high_pass();             // Reset high-pass filter, () → uint8_t
    check_true(accel_full.reset_high_pass() == ref, "reset_high_pass_stable");

    LIS3DH_WAIT();                                         // wait for the next conversion (100 Hz ODR)
    uint8_t st = accel_full.read_status();                 // Read STATUS register, () → uint8_t
    check_true((st & 0x08) == 0x08, "read_status_zyxda_set");

    accel_full.set_fifo(LIS3DHFull::FIFO_FIFO, 8, false);   // Configure FIFO, (mode, watermark, trigger_int2)
    uint8_t level = accel_full.fifo_level();                // Read FIFO level, () → uint8_t
    check_true(level <= 32, "fifo_level_in_range");
    float xb[4], yb[4], zb[4];
    uint8_t n = accel_full.read_fifo(xb, yb, zb, 4);       // Drain FIFO, (x_buf, y_buf, z_buf, max_samples)
    check_true(n <= 4, "read_fifo_count");
    accel_full.set_fifo(LIS3DHFull::FIFO_BYPASS);

    accel_full.set_click(4, 1, 0.5f, 10, 20, 50, true);    // Configure click detection
    uint8_t click = accel_full.read_click_source();         // Read CLICK_SRC, () → uint8_t
    check_true(click != 0xFF, "read_click_source_byte");

    accel_full.set_motion_interrupt(1, 0x3F, 0.5f, 30, true);
    uint8_t int1 = accel_full.read_interrupt_source(1);    // Read INT1_SRC, () → uint8_t
    check_true(int1 != 0xFF, "read_interrupt_source_byte");

    accel_full.set_activity(0.5f, 100);
    accel_full.set_interrupt_polarity(false);
    accel_full.reboot();                                    // Reboot memory content, () → void
    accel_full.set_sdo_pullup(true);                        // Connect SDO pull-up, (connected) → void

    accel_full.read(x, y, z);
    check_true(x == x && y == y && z == z, "read_after_reboot");

    check_true(connection.error_count() == 0, "i2c_no_hal_errors");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
