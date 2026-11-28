#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Rrh62000.h"

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

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\r\n", label); passed++; }
    else       { printf("FAIL %s\r\n", label); failed++; }
}
#define CHECK(c, l) check_true((c), (l))
#define NEAR(v, lo, hi, l) check_true(((v) >= (lo) && (v) <= (hi)), (l))
#define TEST_DELAY_MS(ms) HAL_Delay(ms)



int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x69);
    RRH62000Full sensor(connection);

    CHECK(sensor.init(), "init");                          // Wait for start-up, check firmware version, () → bool
    uint8_t fw_major = 0, fw_minor = 0, ver_patch = 0;
    CHECK(sensor.firmware_version(fw_major, fw_minor), "firmware_version");
    CHECK(sensor.algorithm_version(fw_major, fw_minor, ver_patch), "algorithm_version");
    uint8_t uid[6];
    CHECK(sensor.unique_id(uid), "unique_id");
    RRH62000Measurement m;
    CHECK(sensor.read(m), "read");
    NEAR(m.pm2_5_ug_m3, 0.0f, 1000.0f, "read_pm");
    NEAR(m.temperature_c, -20.0f, 60.0f, "read_temperature");
    NEAR(m.humidity_rh, 0.0f, 100.0f, "read_humidity");
    NEAR(m.iaq, 0.0f, 5.0f, "read_iaq");
    RRH62000Data d;
    CHECK(sensor.read_all(d) && d.particles.nc_0_3 >= d.particles.nc_0_5, "read_all");
    RRH62000Status st;
    CHECK(sensor.read_status(st) && !st.fan, "read_status");
    RRH62000Particles p;
    CHECK(sensor.read_particles(p), "read_particles");
    CHECK(sensor.data_ready() >= 0, "data_ready");
    int avg = sensor.get_moving_average();
    CHECK(sensor.set_moving_average(20) && sensor.get_moving_average() == 20, "moving_average");
    if (avg > 0) sensor.set_moving_average((uint8_t)avg);
    int fan = sensor.get_fan_speed();
    CHECK(sensor.set_fan_speed(70) && sensor.get_fan_speed() == 70, "fan_speed");
    if (fan > 0) sensor.set_fan_speed((uint8_t)fan);
    int32_t interval = sensor.get_cleaning_interval();
    CHECK(sensor.set_cleaning_interval(86400) && sensor.get_cleaning_interval() == 86400, "cleaning_interval");
    if (interval >= 0) sensor.set_cleaning_interval((uint32_t)interval);
    int ctime = sensor.get_cleaning_time();
    CHECK(sensor.set_cleaning_time(12) && sensor.get_cleaning_time() == 12, "cleaning_time");
    if (ctime >= 0) sensor.set_cleaning_time((uint8_t)ctime);
    uint32_t ohms = 0;
    CHECK(sensor.read_mox_resistance(ohms) && ohms > 0, "mox_resistance");
    CHECK(sensor.cleaning_done() >= 0, "cleaning_done");
    CHECK(sensor.sleep(), "sleep");
    TEST_DELAY_MS(3000);
    CHECK(sensor.wake() && sensor.read(m), "wake_read");
    CHECK(sensor.soft_reset() && sensor.read(m), "soft_reset_read");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
