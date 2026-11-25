#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "MPU6050.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x68);
    MPU6050Full imu(connection);

    float ax, ay, az;
    float gx, gy, gz;
    imu.accel(ax, ay, az);                               // Read 3-axis acceleration, (x, y, z) → m/s²
                                                         // converts raw accel register to m/s² (16384 LSB/g at ±2g)
    imu.gyro(gx, gy, gz);                                // Read 3-axis angular rate, (x, y, z) → rad/s
                                                         // converts raw gyro register to rad/s (131.0 LSB/(°/s) at ±250dps)

    imu.configure_gyro(1);                                // Configure gyro range, (full_scale=0) → None
                                                         // sets FS_SEL: 0=±250, 1=±500, 2=±1000, 3=±2000 dps
    imu.configure_accel(1);                               // Configure accel range, (full_scale=0) → None
                                                         // sets AFS_SEL: 0=±2g, 1=±4g, 2=±8g, 3=±16g
    imu.configure_dlpf(3);                                // Configure DLPF bandwidth, (dlpf=3) → None
                                                         // sets DLPF_CFG: 0=260Hz … 6=5Hz (gyro/accel BW)
    imu.configure_sample_rate(4);                         // Configure sample rate, (divider=4) → None
                                                         // sets SMPLRT_DIV: output rate = 1kHz / (1 + divider)

    printf("%d\r\n", imu.temperature());                    // Read die temperature, () → °C
                                                         // converts raw temp register: raw/340 + 36.53

    int16_t rax, ray, raz;
    imu.accel_raw(rax, ray, raz);                         // Read raw accel values, (x, y, z) → int16_t
                                                         // returns raw 16-bit signed accelerometer register values
    int16_t rgx, rgy, rgz;
    imu.gyro_raw(rgx, rgy, rgz);                          // Read raw gyro values, (x, y, z) → int16_t
                                                         // returns raw 16-bit signed gyroscope register values

    printf("%d\r\n", imu.data_ready());                     // Check data ready flag, () → bool
                                                         // reads DATA_RDY_INT bit from INT_STATUS register

    imu.set_sleep(true);                                  // Enter sleep mode, (sleep=true) → None
                                                         // sets SLEEP bit in PWR_MGMT_1
    HAL_Delay(10);
    imu.set_sleep(false);                                 // Wake from sleep, (sleep=true) → None
                                                         // clears SLEEP bit in PWR_MGMT_1

    imu.set_standby(true, false, false, true, false, false); // Set axes standby, (xa, ya, za, xg, yg, zg) → None
                                                         // puts individual axes into low-power standby mode
    imu.set_standby();                                    // Clear all standby, (xa=false, ...) → None
                                                         // restores all axes from standby

    imu.enable_fifo(true, true, false);                   // Enable FIFO sources, (gyro=true, accel=true, temp=false) → None
                                                         // configures FIFO_EN and sets FIFO_EN bit in USER_CTRL
    imu.reset_fifo();                                     // Reset FIFO buffer, () → None
                                                         // sets FIFO_RST bit in USER_CTRL to clear the buffer
    printf("%d\r\n", imu.fifo_count());                     // Read FIFO byte count, () → uint16_t
                                                         // reads FIFO_COUNTH/L: number of bytes available
    uint8_t buf[128];
    uint16_t n = imu.read_fifo(buf, sizeof(buf));         // Read FIFO data, (buf, len) → uint16_t
                                                         // reads all available bytes from FIFO_R_W register
    printf("FIFO read: "); printf("%d\r\n", n);
    while (true) HAL_Delay(1000);
}
