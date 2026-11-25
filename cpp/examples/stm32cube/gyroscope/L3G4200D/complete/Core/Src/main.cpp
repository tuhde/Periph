#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "L3G4200D.h"

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
    L3G4200DFull chip(connection);         // Create L3G4200D driver, (connection)

    HAL_Delay(2000);

    uint8_t cid = chip.who_am_i();                         // Read WHO_AM_I, () → int
                                                            // returns 0xD3 for L3G4200D
    chip.configure(1, 0, 500);                             // Configure chip, (odr=1 [200 Hz], bandwidth=0, full_scale=500) → None
                                                            // sets CTRL_REG1 DR/BW and CTRL_REG4 FS
    chip.enable_axes(true, true, true);                   // Enable axes, (x, y, z) → None
                                                            // sets Xen/Yen/Zen in CTRL_REG1
    chip.set_full_scale(2000);                            // Set full scale, (full_scale 250/500/2000) → None
                                                            // updates FS[1:0] in CTRL_REG4
    bool ready = chip.data_ready();                       // Check data ready, () → bool
                                                            // returns STATUS_REG.ZYXDA
    uint8_t status = chip.status();                       // Read STATUS, () → int
                                                            // raw status byte (ZYXOR, ZOR, YOR, XOR, ZYXDA, ZDA, YDA, XDA)
    int8_t temp = chip.temperature();                     // Read temperature, () → int
                                                            // 8-bit signed relative count (−1 °C/digit)
    chip.enable_highpass(0, 4);                           // Enable high-pass, (mode 0–3, cutoff 0–9) → None
                                                            // sets HPen and HPM/HPCF; cutoff depends on ODR
    chip.disable_highpass();                              // Disable high-pass, () → None
                                                            // clears HPen in CTRL_REG5
    chip.set_interrupt(true, false, true, false, true, false, false, true);  // Configure INT1, (x_high, x_low, y_high, y_low, z_high, z_low, and_mode, latch) → None
                                                            // enable high events on x/y/z; latch until INT1_SRC read
    chip.set_threshold('x', 87.5);                        // Set X threshold, (axis 'x'/'y'/'z', threshold_dps) → None
                                                            // converts dps to raw 15-bit value via sensitivity
    chip.set_duration(4, false);                          // Set INT1 duration, (samples 0–127, wait=false) → None
                                                            // INT1 must be true for `samples` ODR cycles before firing
    chip.set_data_ready_pin(true);                        // Route DRDY to INT2, (enable=true) → None
                                                            // sets I2_DRDY in CTRL_REG3
    chip.enable_fifo(2, 10);                              // Enable FIFO, (mode 2=stream, watermark=10) → None
    chip.disable_fifo();                                  // Disable FIFO, () → None
                                                            // bypass mode and clear FIFO_EN
    uint8_t samples = chip.fifo_samples();                // Read FIFO count, () → int
                                                            // FSS[4:0] from FIFO_SRC_REG
    chip.power_down();                                    // Enter power-down, () → None
                                                            // clears PD in CTRL_REG1
    chip.wake_up();                                       // Wake from power-down, () → None
                                                            // sets PD; previously enabled axes restored
    chip.sleep();                                         // Enter sleep mode, () → None
                                                            // PD=1, all axes off
    uint8_t int_src = chip.read_int_source();             // Read & clear INT1_SRC, () → int
                                                            // reading clears the interrupt-active bit
    float x, y, z;
    chip.angular_rate(x, y, z);                           // Read X/Y/Z angular rate, () → (float, float, float) rad/s
    printf("X=%.2f Y=%.2f Z=%.2f rad/s, T=%d, ready=%d, status=0x%02X, fifo=%u, src=0x%02X, cid=0x%02X\r\n",
           x, y, z, temp, ready, status, samples, int_src, cid);
    printf("===DONE: 0 passed, 0 failed===\r\n");
    while (true) HAL_Delay(1000);
}
