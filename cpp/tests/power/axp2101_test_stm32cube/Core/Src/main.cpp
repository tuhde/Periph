#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "AXP2101.h"

static I2C_HandleTypeDef hi2c1;
static UART_HandleTypeDef huart2;

extern "C" void SysTick_Handler(void) { HAL_IncTick(); }

extern "C" int _write(int file, char* ptr, int len) {
    (void)file;
    HAL_UART_Transmit(&huart2, reinterpret_cast<uint8_t*>(ptr), static_cast<uint16_t>(len), HAL_MAX_DELAY);
    return len;
}

static int passed = 0, failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { printf("PASS %s\r\n", label); passed++; }
    else       { printf("FAIL %s\r\n", label); failed++; }
}

static void i2c1_init(void) {
    __HAL_RCC_GPIOB_CLK_ENABLE();
    __HAL_RCC_I2C1_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_8 | GPIO_PIN_9;
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

static void uart2_init(void) {
    __HAL_RCC_GPIOA_CLK_ENABLE();
    __HAL_RCC_USART2_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_2 | GPIO_PIN_3;
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

int main(void) {
    HAL_Init();
    SystemClock_Config();
    uart2_init();
    i2c1_init();
    HAL_Delay(100);

    I2CConnectionSTM32Cube connection(&hi2c1, 0x34);
    AXP2101Minimal chip(connection);                       // Create AXP2101 driver, (connection)
    AXP2101Full full(connection);                          // Create AXP2101 Full driver, (connection)

    float v = chip.batteryVoltage();
    check_true(v >= 0.0f && v <= 5.0f, "battery_voltage_plausible");
    check_true(chip.batteryPercent() <= 100, "battery_percent_range");
    check_true(chip.chargeState() <= 5, "charge_state_range");
    check_true(chip.valid(), "bus_check_ok");
    check_true(full.valid(), "construct_full");
    check_true(full.vbusVoltage() >= 0.0f && full.vbusVoltage() <= 15.0f, "vbus_voltage_range");
    check_true(full.systemVoltage() >= 0.0f && full.systemVoltage() <= 6.0f, "system_voltage_range");
    check_true(full.tsVoltage() >= 0.0f && full.tsVoltage() <= 4.0f, "ts_voltage_range");
    float t = full.dieTemperature();
    check_true(t >= -40.0f && t <= 150.0f, "die_temperature_range");
    AXP2101Readings all;
    full.readAll(all);
    check_true(all.batteryPercent <= 100, "read_all");
    AXP2101Status st;
    full.status(st);
    check_true(st.batteryDirection != nullptr, "status_direction");
    bool en = false;
    float volts = 0.0f;
    check_true(full.getRail("dcdc1", en, volts) && volts >= 1.4f && volts <= 3.5f, "get_rail_dcdc1");
    check_true(full.setInputVoltageLimit(4.36f), "set_input_voltage_limit");
    uint8_t b = 0;
    check_true(full.writeDataBuffer(0, 0x5A) && full.readDataBuffer(0, b) && b == 0x5A, "data_buffer_roundtrip");
    check_true(full.enableInterrupt(AXP2101Full::SOURCE_VBUS_INSERT), "enable_interrupt");
    full.pollInterrupt();
    check_true(full.valid(), "poll_interrupt");

    check_true(connection.error_count() == 0, "i2c_no_hal_errors");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
