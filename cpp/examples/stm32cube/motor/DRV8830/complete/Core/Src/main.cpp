#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "DRV8830.h"

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

static const char* directionName(DRV8830Full::Direction d) {
    switch (d) {
        case DRV8830Full::Direction::Forward: return "forward";
        case DRV8830Full::Direction::Reverse: return "reverse";
        case DRV8830Full::Direction::Brake:   return "brake";
        default:                              return "coast";
    }
}

static void onFault(const DRV8830Full::Fault& f) {
    printf("fault interrupt ocp=%d uvlo=%d ots=%d ilimit=%d\r\n", f.ocp, f.uvlo, f.ots, f.ilimit);
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, DRV8830Minimal::I2C_ADDRESS);
    DRV8830Full motor(connection);                          // Create DRV8830 Full driver, (connection)

    motor.drive(2.5f);                                      // Drive at regulated voltage, (voltage V, + = forward) → void
                                                            // maps 2.5 V to the nearest VSET code and sets IN1=1, IN2=0
    HAL_Delay(1000);
    DRV8830Full::Output out = motor.readOutput();           // Read back CONTROL, () → Output {float V, Direction}
                                                            // decodes VSET to volts and IN1/IN2 to a Direction
    printf("commanded %.2f V %s\r\n", (double)out.voltage, directionName(out.direction));

    motor.drive(-1.5f);                                     // Drive at regulated voltage, (voltage V, - = reverse) → void
                                                            // a negative voltage sets IN1=0, IN2=1
    HAL_Delay(1000);

    bool ok = motor.setOutput(37, true, false);             // Write raw CONTROL fields, (vset 6–63, in1, in2) → bool
                                                            // VSET 37 is ~2.97 V forward; codes 0–5 are rejected (false)
    HAL_Delay(1000);

    motor.brake();                                          // Short-brake, () → void
                                                            // IN1=IN2=1 drives both outputs high
    HAL_Delay(500);
    motor.stop();                                           // Coast to standby, () → void
                                                            // IN1=IN2=0 leaves both outputs high-impedance

    DRV8830Full::Fault f = motor.readFault();               // Read fault status, () → Fault {fault, ocp, uvlo, ots, ilimit}
                                                            // does not clear — latched OCP/ILIMIT keep the bridge off
    printf("setOutput=%d fault=%d ocp=%d uvlo=%d ots=%d ilimit=%d\r\n", ok, f.fault, f.ocp, f.uvlo, f.ots, f.ilimit);
    motor.clearFault();                                     // Clear fault bits, () → void
                                                            // writes CLEAR=1; re-enables a latched-off bridge

    motor.onInterrupt(onFault);                             // Subscribe to FAULTn, (callback, intPin=nullptr) → void
                                                            // callback receives the readFault() result
    DRV8830Full::Fault p = motor.pollInterrupt();           // Poll fault status, () → Fault
                                                            // same as readFault(); never clears implicitly
    motor.offInterrupt();                                   // Unsubscribe, () → void
    printf("poll fault=%d\r\n", p.fault);

    while (1) {
        HAL_Delay(1000);
    }
}
