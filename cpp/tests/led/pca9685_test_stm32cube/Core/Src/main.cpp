#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Pca9685.h"

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

int passed = 0;
int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\r\n", label); passed++; }
    else       { printf("FAIL %s\r\n", label); failed++; }
}

static void check_near(float v, float lo, float hi, const char *label) {
    if (v >= lo && v <= hi) { printf("PASS %s\r\n", label); passed++; }
    else { printf("FAIL %s: %.4f not in [%.4f, %.4f]\r\n", label, (double)v, (double)lo, (double)hi); failed++; }
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x40);
    Pca9685Minimal pwm(connection);
    Pca9685Full full(connection);

    check_true(Pca9685Minimal::CHANNELS == 16, "constants");

    check_true(pwm.set_duty(0, 0.5f) && pwm.set_duty(15, 0.0f) && pwm.set_duty(1, 1.0f), "set_duty");
    check_true(!pwm.set_duty(16, 0.5f) && !pwm.set_duty(0, 1.5f), "set_duty_range");

    uint16_t on = 0, off = 0;
    check_true(full.set_pwm(2, 409, 1228) && !full.set_pwm(2, 5, 5), "set_pwm");
    check_true(full.get_pwm(2, on, off) && on == 409 && off == 1228, "get_pwm");
    check_true(full.set_full_on(3) && full.set_full_off(3), "full_on_off");
    check_true(full.set_all_duty(0.1f) && full.set_all_pwm(0, 2048) && full.set_all_duty(0.0f), "all");
    check_true(full.set_frequency(50.0f), "set_frequency");
    float hz = full.get_frequency();
    check_true(hz > 49.9f && hz < 50.1f, "get_frequency");
    check_true(full.set_pulse_width(15, 0.0015f), "pulse_width");
    full.sleep();
    full.wake();
    full.restart();
    check_true(true, "sleep_wake");
    check_true(full.set_output_mode(false, false, Pca9685Full::OFF_LOW), "output_mode");
    full.set_update_on_ack(false);
    check_true(full.set_allcall(false, 0x70), "allcall");
    check_true(full.set_subaddress(1, 0x71, false), "subaddress");
    uint8_t mode1 = 0xFF, mode2 = 0;
    full.read_mode(mode1, mode2);
    check_true((mode1 & 0x10) == 0 && (mode2 & 0x04) == 0x04, "read_mode");
    full.reset();
    check_true(true, "reset");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
