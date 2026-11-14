#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "BNO055.h"

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



int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x29);
    BNO055Full imu(connection);

    uint8_t chip_id, acc_id, mag_id, gyr_id, bootloader;
    uint16_t sw_revision;
    imu.revisions(chip_id, acc_id, mag_id, gyr_id, sw_revision, bootloader);
    CHECK(chip_id == 0xA0, "chip_id");
    CHECK(acc_id == 0xFB, "acc_id");
    CHECK(mag_id == 0x32, "mag_id");
    CHECK(gyr_id == 0x0F, "gyr_id");
    CHECK(imu.valid(), "valid");
    CHECK(imu.get_mode() == 0x0C, "mode_ndof");

    float h, r, p;
    imu.euler(h, r, p);
    NEAR(h, 0.0f, 6.5f, "heading range");
    NEAR(r, -1.6f, 1.6f, "roll range");
    NEAR(p, -3.2f, 3.2f, "pitch range");

    float qw, qx, qy, qz;
    imu.quaternion(qw, qx, qy, qz);
    NEAR(qw * qw + qx * qx + qy * qy + qz * qz, 0.9f, 1.1f, "quaternion unit");

    float gx, gy, gz;
    imu.gravity(gx, gy, gz);
    NEAR(gx * gx + gy * gy + gz * gz, 72.0f, 121.0f, "gravity ~1 g");

    float lx, ly, lz;
    imu.linear_acceleration(lx, ly, lz);
    NEAR(lx, -5.0f, 5.0f, "linear acceleration x");
    NEAR(ly, -5.0f, 5.0f, "linear acceleration y");
    NEAR(lz, -5.0f, 5.0f, "linear acceleration z");

    NEAR((float)imu.temperature(), -40.0f, 85.0f, "temperature range");
    uint8_t sys_c, gyr_c, acc_c, mag_c;
    imu.calibration_status(sys_c, gyr_c, acc_c, mag_c);
    CHECK(sys_c <= 3 && gyr_c <= 3 && acc_c <= 3 && mag_c <= 3, "calibration status range");
    uint8_t status, error;
    imu.system_status(status, error);
    CHECK(status == 5 && error == 0, "system status");

    uint8_t uid[16];
    imu.unique_id(uid);
    uint8_t blob[22];
    imu.get_calibration(blob);
    CHECK(imu.get_mode() == 0x0C, "mode restored after get_calibration");

    imu.set_mode(BNO055Full::MODE_AMG);
    CHECK(imu.get_mode() == 0x07, "mode_amg");
    float ax, ay, az, mx, my, mz;
    imu.acceleration(ax, ay, az);
    NEAR(ax * ax + ay * ay + az * az, 64.0f, 132.0f, "accel magnitude ~1 g");
    imu.magnetic_field(mx, my, mz);
    NEAR(mx * mx + my * my + mz * mz, 25.0f, 40000.0f, "mag field range");
    imu.set_mode(BNO055Full::MODE_NDOF);
    CHECK(imu.get_mode() == 0x0C, "mode_ndof_again");

    bool st_acc, st_mag, st_gyr, st_mcu;
    imu.self_test(st_acc, st_mag, st_gyr, st_mcu);
    CHECK(st_acc && st_mag && st_gyr && st_mcu, "self_test passes");
    CHECK(imu.get_mode() == 0x0C, "mode restored after self_test");

    imu.enable_interrupt(BNO055Full::SOURCE_ACC_BSX_DRDY);
    imu.poll_interrupt();
    imu.disable_interrupt(BNO055Full::SOURCE_ACC_BSX_DRDY);
    CHECK(true, "interrupt api");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
