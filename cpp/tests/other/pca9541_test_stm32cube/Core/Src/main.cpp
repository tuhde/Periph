#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "PCA9541.h"

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

    I2CConnectionSTM32Cube connection(&hi2c1, PCA9541Minimal::I2C_ADDRESS);
    PCA9541Minimal selector(connection);                    // Create PCA9541 driver, (connection)
    check_true(selector.present(), "present");
    bool has = selector.hasControl();                       // Report control, () → bool
    check_true(has == true || has == false, "has_control_is_bool");

    PCA9541Full full(connection);                           // Create PCA9541 Full driver, (connection)
    uint8_t control = full.getControl();                    // Read the CONTROL byte, () → uint8_t
    PCA9541Full::Status st = full.status();                 // Decode CONTROL, () → Status
    check_true(st.hasControl == (((control & 1) != 0) == ((control & 2) != 0)), "status_matches_control");
    uint8_t ie = 0, ctl = 0, istat = 0;
    full.readAll(ie, ctl, istat);                           // Read IE, CONTROL, ISTAT, (ie&, control&, istat&) → void
    check_true(ie <= 0x0F, "read_all_ie_range");
    full.setInterruptMask(false, true);                     // Write IE, (busLost, busNotIdle, busInit, intIn) → void
    check_true(full.getInterruptMask() == 0x04, "interrupt_mask_roundtrip"); // Read IE, () → uint8_t
    full.setInterruptMask();
    check_true(full.getInterruptMask() == 0x00, "interrupt_mask_cleared");
    full.testInterrupt(true);                               // Drive this master's INT low, (own=false, other=false) → void
    check_true(full.status().ownTest, "test_interrupt_sets_teston");
    full.testInterrupt();
    check_true(!full.status().ownTest, "test_interrupt_clears");
    check_true(full.acquire() && full.hasControl(), "acquire_takes_bus"); // Take the downstream bus, (busInit=false, timeoutMs=20) → bool
    full.disconnect();                                      // Isolate the downstream bus, () → void
    check_true(!full.isConnected(), "disconnect");
    full.connect();                                         // Reconnect, () → void
    check_true(full.isConnected(), "connect");
    full.release();                                         // Give the bus to the other master, () → void
    check_true(!full.hasControl(), "release_gives_bus_away");
    full.acquire();
    check_true(full.hasControl(), "reacquire");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
