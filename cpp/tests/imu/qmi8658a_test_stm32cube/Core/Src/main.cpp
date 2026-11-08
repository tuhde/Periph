#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "QMI8658A.h"

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

#define CHECK(l, c) check_true((l), (c))
#define DELAY_MS_T(ms) HAL_Delay(ms)

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\r\n", label); passed++; }
    else           { printf("FAIL %s\r\n", label); failed++; }
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();

    I2CConnectionSTM32Cube connection(&hi2c1, 0x6B);
    QMI8658AFull imu(connection);

    // The constructor already verified WHO_AM_I (it aborts on a mismatch).
    float ax, ay, az, gx, gy, gz;
    imu.accel(ax, ay, az);
    float mag = sqrtf(ax * ax + ay * ay + az * az);
    CHECK("gravity magnitude", mag > 7.0f && mag < 12.6f);
    imu.gyro(gx, gy, gz);
    CHECK("gyro finite", gx > -10.0f && gx < 10.0f && gy > -10.0f && gy < 10.0f && gz > -10.0f && gz < 10.0f);

    float a[3], g[3], tc;
    imu.read_all(a, g, tc);
    CHECK("read_all temp range", tc > -40.0f && tc < 85.0f);
    CHECK("temperature matches", fabsf(imu.temperature() - tc) < 5.0f);

    int16_t rax, ray, raz, rgx, rgy, rgz;
    imu.accel_raw(rax, ray, raz);
    imu.gyro_raw(rgx, rgy, rgz);
    float rmag = sqrtf((float)rax * rax + (float)ray * ray + (float)raz * raz);
    CHECK("accel_raw gravity", rmag > 8000.0f && rmag < 24000.0f);
    (void)rgx; (void)rgy; (void)rgz;

    uint32_t ts1 = imu.timestamp();
    DELAY_MS_T(100);
    CHECK("timestamp advances", imu.timestamp() != ts1);

    imu.configure_accel(1, 5);
    imu.configure_gyro(5, 5);
    DELAY_MS_T(200);
    imu.accel(ax, ay, az);
    mag = sqrtf(ax * ax + ay * ay + az * az);
    CHECK("accel after reconfig", mag > 7.0f && mag < 12.6f);
    imu.configure_accel();
    imu.configure_gyro();
    imu.configure_filters(true, 3, true, 3);
    DELAY_MS_T(200);

    imu.configure_fifo(2, 1, 8);
    DELAY_MS_T(300);
    uint16_t count = imu.fifo_count();
    CHECK("fifo_count non-zero", count > 0);
    uint8_t fifo[96];
    uint16_t got = imu.read_fifo(fifo, sizeof(fifo));
    CHECK("read_fifo returns whole frames", got > 0 && got % 12 == 0);
    imu.reset_fifo();
    imu.configure_fifo(0);
    DELAY_MS_T(100);

    uint8_t revision, firmware[3], usid[6];
    imu.device_info(revision, firmware, usid);
    CHECK("device_info callable", true);
    imu.set_sensors(true, true);

    uint16_t status = imu.poll_interrupt();
    CHECK("poll_interrupt callable", status <= 0x1FF);
    (void)revision; (void)firmware; (void)usid; (void)status; (void)count;

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
