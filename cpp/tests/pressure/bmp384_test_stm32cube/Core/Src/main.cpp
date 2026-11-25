#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "BMP384.h"

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

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\r\n", label); passed++; }
    else       { printf("FAIL %s\r\n", label); failed++; }
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x76);
    BMP384Minimal bmp(connection);

    HAL_Delay(2000);

    bmp._par_t1 = 1.0e6;
    bmp._par_t2 = 1.0e-3;
    bmp._par_t3 = 1.0e-6;
    bmp._par_p1 = 0.5;
    bmp._par_p2 = -0.1;
    bmp._par_p3 = 1.0e-6;
    bmp._par_p4 = 1.0e-7;
    bmp._par_p5 = 1000.0;
    bmp._par_p6 = 1.0e-3;
    bmp._par_p7 = 1.0e-5;
    bmp._par_p8 = 1.0e-7;
    bmp._par_p9 = 1.0e-12;
    bmp._par_p10 = 1.0e-12;
    bmp._par_p11 = 1.0e-20;

    bmp._t_lin = 25.0;
    double comp_p = bmp._compensate_pressure(415148);
    check_true(comp_p > 0.0, "pressure_compensation_runs");

    BMP384Full bmp_full(connection);
    bmp_full.configure(2, 1, 1, 0x04);                     // Configure ADC and IIR filter, (osr_p 0–5, osr_t 0–5, iir_filter 0–7, odr_sel 0x00–0x11) → None
    check_true(bmp_full._osr_p == 2 && bmp_full._iir == 1 && bmp_full._odr == 0x04, "configure_writes_through");

    bmp_full.set_mode(BMP384Full::MODE_FORCED);             // Set power mode, (mode 0/1/3) → None
    check_true(bmp_full._mode == BMP384Full::MODE_FORCED, "set_mode_forced");

    bmp_full.fifo_configure(true, true, 10);               // Configure FIFO, (press_en bool, temp_en bool, wtm 0–511, stop_on_full=false) → None
    const char* types[16];
    double values[16];
    size_t n = bmp_full.fifo_read(types, values, 16);      // Read and parse FIFO frames, (types, values, max_frames) → size_t
    check_true(n <= 16, "fifo_read_no_overflow");

    bmp_full.fifo_flush();                                 // Flush FIFO contents, () → None
    float alt = bmp_full.altitude(1013.25f);               // Compute altitude, (sea_level_hpa=1013.25) → float m
    check_true(alt > -500.0f && alt < 9000.0f, "altitude_in_range");
    bmp_full.softreset();                                  // Soft reset chip, () → None

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
