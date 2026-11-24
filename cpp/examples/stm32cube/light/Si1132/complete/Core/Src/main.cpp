#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Si1132.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, Si1132Minimal::I2C_ADDRESS);
    Si1132Full sensor(connection);                                  // Create Si1132 driver, (connection)
    sensor.init();                                                  // Initialize the chip, () → bool
                                                                    // checks PART_ID, writes HW_KEY, UV coefficients and CHLIST
    float uv = 0; uint16_t vis = 0, ir = 0, aux = 0;
    sensor.read(uv, vis, ir);                                       // Read UV index and ALS counts, (uv&, visible&, infrared&) → bool
    printf("UV %.2f visible %lu infrared %lu\r\n", (double)(uv), (unsigned long)(vis), (unsigned long)(ir));
    printf("uv index %.2f\r\n", (double)(sensor.read_uv_index()));  // Read the UV index, () → float
                                                                    // forced measurement, AUX_DATA / 100
    sensor.read_visible(vis);                                       // Read visible counts, (visible&) → bool
    sensor.read_infrared(ir);                                       // Read infrared counts, (infrared&) → bool
    sensor.read_raw(vis, ir, aux);                                  // Read the data registers, (visible&, infrared&, aux&) → bool
                                                                    // latest values, no measurement is started
    printf("raw %lu %lu %lu\r\n", (unsigned long)(vis), (unsigned long)(ir), (unsigned long)(aux));
    sensor.read_temperature_raw(aux);                               // Read die temperature, (raw&) → bool
                                                                    // auxiliary channel, relative only
    printf("temperature raw %lu\r\n", (unsigned long)(aux));
    sensor.read_vdd_raw(aux);                                       // Read supply voltage, (raw&) → bool
                                                                    // auxiliary channel with AUX_ADCMUX = 0x75
    printf("vdd raw %lu\r\n", (unsigned long)(aux));
    bool ok = sensor.set_channels(true, true, true);                // Select channels, (uv, visible, infrared, aux=false) → bool
                                                                    // uv and aux cannot be combined
    ok = sensor.set_gain(2, 2);                                     // Set the ADC gain, (visible 0-7, infrared 0-7) → bool
                                                                    // integration time x 2^n plus recovery count
    ok = sensor.set_gain(0, 0);
    ok = sensor.set_high_range(false, false);                       // Set the high signal range, (visible, infrared) → bool
                                                                    // true divides the sensitivity by 14.5
    ok = sensor.set_alignment(true, true);                          // Set the data alignment, (visible_low, infrared_low) → bool
                                                                    // true reports the least significant 16 bits
    const uint8_t ucoef[4] = {0x7B, 0x6B, 0x01, 0x00};
    ok = sensor.set_uv_coefficients(ucoef);                         // Set the UV coefficients, (ucoef[4]) → bool
                                                                    // datasheet defaults for an open-air UV index
    uint8_t cal[12];
    ok = sensor.read_calibration(cal);                              // Read the factory calibration, (out[12]) → bool
                                                                    // GET_CAL then registers 0x22-0x2D
    ok = sensor.force();                                            // Start a forced measurement, () → bool
                                                                    // ALS_FORCE without waiting
    HAL_Delay(50);
    printf("ready %lu\r\n", (unsigned long)(sensor.measurement_ready()));// Check measurement done, () → bool
                                                                    // IRQ_STATUS.ALS_INT
    uint8_t counter = 0, error = 0;
    sensor.read_response(counter, error);                           // Read the command response, (counter&, error&) → bool
                                                                    // error 0 = none
    printf("response %lu error %lu\r\n", (unsigned long)(counter), (unsigned long)(error));
    printf("poll 0x%02X\r\n", (unsigned)(sensor.poll_interrupt())); // Poll interrupt status, () → uint8_t
                                                                    // reads IRQ_STATUS and clears it
    ok = sensor.start_autonomous(0.5f);                             // Start autonomous mode, (interval_s 0-2.05) → bool
                                                                    // MEAS_RATE in 31.25 us steps then ALS_AUTO
    ok = sensor.enable_interrupt();                                 // Enable the INT pin, () → bool
                                                                    // INT_OE and ALS_IE
    HAL_Delay(1000);
    ok = sensor.disable_interrupt();                                // Disable the INT pin, () → bool
    ok = sensor.pause();                                            // Pause autonomous mode, () → bool
                                                                    // ALS_PAUSE
    ok = sensor.set_i2c_address(0x60);                              // Set the I2C address, (address 7-bit) → bool
                                                                    // I2C_ADDR parameter then BUSADDR, volatile
    ok = sensor.reset();                                            // Reset the chip, () → bool
                                                                    // RESET, waits, writes HW_KEY again
    printf("ok %lu\r\n", (unsigned long)(ok));
    while (1) {}
}
