#include <stdio.h>
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

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, PCA9541Minimal::I2C_ADDRESS);
    PCA9541Full selector(connection);                   // Create PCA9541 Full driver, (connection)
    uint8_t control = selector.getControl();            // Read the CONTROL byte, () → uint8_t
    PCA9541Full::Status st = selector.status();         // Decode CONTROL, () → Status
    // {hasControl, connected, busInitRequested, ownTest, otherTest}
    printf("CONTROL 0x%d hasControl %d connected %d\n", (int)control, (int)st.hasControl, (int)st.connected);
    uint8_t ie = 0, ctl = 0, istat = 0;
    selector.readAll(ie, ctl, istat);                   // Read IE, CONTROL, ISTAT, (ie&, control&, istat&) → void
    // one auto-increment read; clears the ISTAT flags
    printf("IE %d CONTROL %d ISTAT %d\n", (int)ie, (int)ctl, (int)istat);

    bool ok = selector.acquire();                       // Take the downstream bus, (busInit=false, timeoutMs=20) → bool
    // reads CONTROL, writes MYBUS/BUSON per the datasheet rule
    bool rec = selector.recoverBus();                   // Recover and take the bus, (timeoutMs=20) → bool
    // nine clocks, NACK and STOP on the downstream bus, then connect
    bool has = selector.hasControl();                   // Report control, () → bool
    bool conn = selector.isConnected();                 // Report the connection, () → bool
    // BUSON != NBUSON: some master is connected
    printf("acquire %d recover %d control %d connected %d\n", (int)ok, (int)rec, (int)has, (int)conn);
    selector.disconnect();                              // Isolate the downstream bus, () → void
    // BUSON = NBUSON; ownership bits are left
    selector.connect();                                 // Reconnect without changing ownership, () → void

    selector.setInterruptMask(false, true);             // Write IE, (busLost, busNotIdle, busInit, intIn) → void
    // true = masked, no interrupt
    uint8_t mask = selector.getInterruptMask();         // Read IE, () → uint8_t
    selector.disableInterrupt(PCA9541Full::SOURCE_INT_IN);  // Mask one source, (source) → void
    selector.enableInterrupt(PCA9541Full::SOURCE_INT_IN);  // Unmask one source, (source) → void
    printf("mask %d\n", (int)mask);
    selector.onInterrupt([](uint8_t status) { (void)status; });  // Subscribe, (callback(status), intPin=nullptr) → void
    // falling edge on this master's INT; call service() periodically without a pin
    selector.testInterrupt(true);                       // Drive this master's INT low, (own=false, other=false) → void
    // the bus bits are written back unchanged
    HAL_Delay(200);
    selector.testInterrupt();                           // Clear the test, () → void
    selector.service();                                 // Poll ISTAT once and dispatch the callback, () → void
    uint8_t status = selector.pollInterrupt();          // Read and clear ISTAT, () → uint8_t
    printf("ISTAT %d\n", (int)status);
    selector.offInterrupt();                            // Unsubscribe, () → void
    selector.release();                                 // Give the bus to the other master, () → void
    while (1) {}
}
