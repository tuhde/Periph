#include <math.h>
#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "ADS7828.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x48);
    ADS7828Full adc(connection);

    uint16_t raw = adc.readRaw(0);
    check_true(raw <= 4095, "readRaw(0) in [0, 4095]");
    check_true(adc.readRaw(99) <= 4095, "readRaw(99) clamped");
    float v = adc.readVoltage(1);
    check_true(v >= 0.0f && v <= 2.5f, "readVoltage in [0, 2.5]");
    float all[8];
    adc.readAll(all);
    bool allOk = true;
    for (int i = 0; i < 8; i++) { if (all[i] < 0.0f || all[i] > 2.5f) allOk = false; }
    check_true(allOk, "readAll values in [0, 2.5]");
    check_true(adc.readDifferentialRaw(0) <= 4095, "readDifferentialRaw in range");
    check_true(adc.readDifferential(1, true) >= 0.0f, "readDifferential inverted >= 0");
    float sv = adc.readSignedDifferential(2);
    check_true(sv >= -2.5f && sv <= 2.5f, "readSignedDifferential in [-2.5, 2.5]");
    check_true(fabsf(adc.lsb() - 2.5f / 4096.0f) < 1e-6f, "lsb is vref/4096");
    check_true(adc.usesInternalReference() && adc.getReference() == 2.5f, "internal reference selected");
    const uint8_t chans[3] = {0, 3, 7};
    uint16_t scan[3];
    adc.readScan(chans, 3, scan);
    check_true(scan[0] <= 4095 && scan[1] <= 4095 && scan[2] <= 4095, "readScan codes in range");
    check_true(adc.setPowerMode(ADS7828Full::REF_ON_ADC_ON), "setPowerMode accepted");
    check_true(!adc.setPowerMode(ADS7828Full::REF_OFF_ADC_ON), "internal reference rejects PD1 = 0");
    adc.setReference(5.0f);
    check_true(!adc.usesInternalReference() && adc.getReference() == 5.0f, "external reference selected");
    adc.setReference(0.0f);
    check_true(adc.usesInternalReference(), "internal reference restored");
    adc.powerDown();
    check_true(adc.readRaw(0) <= 4095, "read after powerDown works");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
