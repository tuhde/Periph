#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "LSM303.h"

static I2C_HandleTypeDef hi2c1;
static UART_HandleTypeDef huart2;
static int passed = 0;
static int failed = 0;

extern "C" void SysTick_Handler(void) {
    HAL_IncTick();
}

extern "C" int _write(int file, char* ptr, int len) {
    (void)file;
    HAL_UART_Transmit(&huart2, reinterpret_cast<uint8_t*>(ptr), static_cast<uint16_t>(len), HAL_MAX_DELAY);
    return len;
}

static void check_true(bool cond, const char* label) {
    if (cond) { printf("PASS %s\r\n", label); passed++; }
    else       { printf("FAIL %s\r\n", label); failed++; }
}

static bool finite(float v) { return v == v && v > -1e30f && v < 1e30f; }

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
    gpioInit.Pull      = GPIO_PULLUP;
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
    HAL_Delay(100);

    // Accelerometer at 0x19 and magnetometer at 0x1E, both on I2C1.
    I2CConnectionSTM32Cube accelConn(&hi2c1, 0x19);
    I2CConnectionSTM32Cube magConn(&hi2c1, 0x1E);

    LSM303Full chip(accelConn, magConn);

    check_true(chip.valid(), "identity valid");
    check_true(magConn.error_count() == 0, "i2c_no_hal_errors_on_identity_read");

    uint8_t id[3] = {0, 0, 0};
    chip.readMagIdentity(id);
    check_true(id[0] == 0x48 && id[1] == 0x34 && id[2] == 0x33, "mag identity 0x48 0x34 0x33");

    // Wait for the first accelerometer sample (7/ODR after init) and one magnetometer period.
    HAL_Delay(200);

    float ax, ay, az;
    chip.readAcceleration(ax, ay, az);
    check_true(finite(ax), "accel_x finite");
    check_true(finite(ay), "accel_y finite");
    check_true(finite(az), "accel_z finite");
    float amag = sqrtf(ax * ax + ay * ay + az * az);
    check_true(amag > 0.5f && amag < 1.5f, "accel magnitude 0.5..1.5 g (stationary)");

    float mx, my, mz;
    chip.readMagnetic(mx, my, mz);
    check_true(finite(mx), "mag_x finite");
    check_true(finite(my), "mag_y finite");
    check_true(finite(mz), "mag_z finite");
    float mmag = sqrtf(mx * mx + my * my + mz * mz);
    check_true(mmag < 1000.0f, "mag magnitude below 1000 uT");

    float h = chip.heading(false);
    check_true(h >= 0.0f && h < 360.0f, "heading flat in 0..360");
    float ht = chip.heading(true);
    check_true(ht >= 0.0f && ht < 360.0f, "heading tilt in 0..360");

    float t = chip.readTemperatureDelta();
    check_true(finite(t), "temperature delta finite");
    check_true(t > -60.0f && t < 60.0f, "temperature delta within ±60 C");

    int16_t rx, ry, rz;
    chip.readAccelerationRaw(rx, ry, rz);
    check_true(rx >= -2048 && rx <= 2047 && ry >= -2048 && ry <= 2047, "accel raw in range");

    // Range and rate changes round-trip.
    check_true(chip.setRange(4), "set_range 4");
    check_true(chip.setRange(2), "set_range 2");
    check_true(!chip.setRange(3), "set_range 3 rejected");
    check_true(chip.setDataRate(50), "set_data_rate 50");
    check_true(chip.setDataRate(100), "set_data_rate 100");
    check_true(chip.setMagRange(810), "set_mag_range 810");
    check_true(chip.setMagRange(130), "set_mag_range 130");
    check_true(chip.setMagDataRate(15.0f), "set_mag_data_rate 15");

    // FIFO bypass drain returns no more than requested.
    float xs[32], ys[32], zs[32];
    uint8_t n = chip.readFifo(xs, ys, zs, 32);
    check_true(n <= 32, "fifo drain bounded");

    // Interrupt routing and polling (no INT line is wired in this test).
    check_true(chip.enableInterrupt(LSM303Source::DATA_READY, 1), "enable DATA_READY on INT1");
    check_true(chip.disableInterrupt(LSM303Source::DATA_READY, 1), "disable DATA_READY on INT1");
    check_true(!chip.enableInterrupt(LSM303Source::MAG_DATA_READY, 1), "mag DRDY not routable");
    uint8_t status = chip.pollInterrupt();
    // No accelerometer source is routed now, so only the magnetometer DRDY bit (0x80) may be set.
    check_true(status == 0x00 || status == 0x80, "poll status only carries mag DRDY");

    check_true(accelConn.error_count() == 0 && magConn.error_count() == 0, "i2c_no_hal_errors_overall");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
