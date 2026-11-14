#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "AM2315.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x5C);
    AM2315Full sensor(connection);                                           // Create AM2315 driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t rh_raw = 0, t_raw = 0, model = 0, user1 = 0, user2 = 0;
    uint8_t version = 0, regs[7] = {0};
    uint32_t device_id = 0;

    sensor.read(t, rh);                                                      // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                             // previous measurement; reads are kept >= 2 s apart, CRC-16 checked
    printf("%.1f C, %.1f %%RH\r\n", t, rh);
    printf("humidity %.1f %%RH\r\n", sensor.read_humidity());
    // sensor.read_humidity() → float %RH (NAN on failure)
    printf("temperature %.1f C\r\n", sensor.read_temperature());
    // sensor.read_temperature() → float °C (NAN on failure)
    sensor.read_raw(rh_raw, t_raw);                                          // Read raw words, (humidity_raw, temperature_raw) → bool
                                                                             // humidity x10; temperature x10 as sign + magnitude
    printf("raw RH %u raw T %u\r\n", (unsigned)(rh_raw), (unsigned)(t_raw));
    sensor.read_fresh(t, rh);                                                // Real-time value, (t, rh) → bool
                                                                             // two reads >= 2 s apart; returns the second
    printf("fresh %.1f C, %.1f %%RH\r\n", t, rh);
    sensor.read_registers(0x08, 7, regs);                                    // Read registers, (start, count, out) → bool
                                                                             // function 0x03, 1-10 registers, CRC verified
    printf("register 0x08 = 0x%02X\r\n", (unsigned)(regs[0]));
    sensor.read_device_info(model, version, device_id);                      // Read device information, (model, version, device_id) → bool
    printf("model %u version %u device id 0x%08lX\r\n", (unsigned)(model), (unsigned)(version), (unsigned long)(device_id));
    sensor.write_user_registers(0x1234, 0xABCD);                             // Write user registers, (user1, user2) → bool
                                                                             // registers 0x10-0x13
    sensor.read_user_registers(user1, user2);                                // Read user registers, (user1, user2) → bool
    printf("user registers 0x%02X 0x%02X\r\n", (unsigned)(user1), (unsigned)(user2));
    sensor.write_registers(0x10, regs, 2);                                   // Write registers, (start, data, len) → bool
                                                                             // only the user registers 0x10-0x13 are allowed
    sensor.wake();                                                           // Wake the sensor, () → void
                                                                             // dummy write, the NACK is ignored
    printf("last error %u\r\n", (unsigned)(sensor.last_error()));
    // sensor.last_error() → uint8_t, 0x80-0x84 after a sensor error response, else 0
    printf("done\r\n");
    while (true) HAL_Delay(1000);
}
