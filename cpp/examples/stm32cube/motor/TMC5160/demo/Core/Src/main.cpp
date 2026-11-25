// TMC5160 Demo example: pick-and-place axis with the internal ramp generator and StallGuard2.
// Wiring: SPI Mode 3 (SCK idles high), 1 MHz; SD_MODE strapped low (ramp generator); DRV_ENN (and DIAG0) on plain GPIO.
#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "SPIConnectionSTM32Cube.h"
#include "OutputPinSTM32Cube.h"
#include "TMC5160.h"

#define LOG(...) printf(__VA_ARGS__)
#define SLEEP_MS(ms) HAL_Delay(ms)

static UART_HandleTypeDef huart2;
static SPI_HandleTypeDef hspi1;

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

// SPI1 on the Arduino header: PA5=SCK (D13), PA6=MISO (D12), PA7=MOSI (D11).
// SPI1 is on APB2 (100 MHz), so the baud rate is 100 MHz / prescaler.
static void spi1_init(uint32_t prescaler, uint32_t polarity, uint32_t phase) {
    __HAL_RCC_SPI1_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_5 | GPIO_PIN_6 | GPIO_PIN_7;
    gpioInit.Mode      = GPIO_MODE_AF_PP;
    gpioInit.Pull      = GPIO_NOPULL;
    gpioInit.Speed     = GPIO_SPEED_FREQ_HIGH;
    gpioInit.Alternate = GPIO_AF5_SPI1;
    HAL_GPIO_Init(GPIOA, &gpioInit);

    hspi1.Instance               = SPI1;
    hspi1.Init.Mode              = SPI_MODE_MASTER;
    hspi1.Init.Direction         = SPI_DIRECTION_2LINES;
    hspi1.Init.DataSize          = SPI_DATASIZE_8BIT;
    hspi1.Init.CLKPolarity       = polarity;
    hspi1.Init.CLKPhase          = phase;
    hspi1.Init.NSS               = SPI_NSS_SOFT;
    hspi1.Init.BaudRatePrescaler = prescaler;
    hspi1.Init.FirstBit          = SPI_FIRSTBIT_MSB;
    hspi1.Init.TIMode            = SPI_TIMODE_DISABLE;
    hspi1.Init.CRCCalculation    = SPI_CRCCALCULATION_DISABLE;
    HAL_SPI_Init(&hspi1);
}

static void example(Connection& connection) {
    OutputPinSTM32Cube en(GPIOB, GPIO_PIN_4);
TMC5160Full motor(connection, 0.075f, &en);           // Create TMC5160 Full driver, (connection, r_sense_ohm=0.075 Ω, en_pin, diag0_pin, diag1_pin)
    if (!motor.ok()) { LOG("TMC5160 not found\n"); return; }

    // --- Pick-and-place axis: smooth trapezoid between two stations ---
    // The internal ramp generator accelerates at 400 full steps/s² up to 600 full steps/s;
    // StealthChop keeps the axis quiet and StallGuard2 reports a crash against an end stop.
    motor.setCurrent(1.2f);                           // Set run current, (rms_a A RMS) → bool
    motor.setSpeed(600.0f);                           // Set VMAX, (full_steps_per_s full steps/s) → bool
    motor.setAcceleration(400.0f);                    // Set acceleration, (full_steps_per_s2 full steps/s²) → bool
    motor.setStallThreshold(2);                       // Set StallGuard2 threshold, (sgt -64..63) → bool
    motor.enable();                                   // Power stage on, () → void
    motor.setPosition(0.0f);                          // Define position, (position full steps) → bool

    // --- Shuttle between the stations and report each stop ---
    // Each stop prints the position and the StallGuard2 reading; a driver fault
    // shuts everything down and ends the run.
    const float stations[4] = {1000.0f, 0.0f, 1000.0f, 0.0f};
    for (int i = 0; i < 4; i++) {
        motor.moveTo(stations[i]);                    // Start positioning move, (position full steps) → bool
        motor.waitUntilReached();                     // Wait for the ramp generator, (timeout_s=0 s) → bool
        float pos = motor.position();                 // Read XACTUAL, () → float full steps
        uint16_t sg = motor.readStallguard();         // Read StallGuard2 result, () → uint16_t 0–1023
        LOG("at %d (SG %d)\n", (int)pos, (int)sg);
        uint8_t fault = motor.readFault();            // Read fault bitmask, () → uint8_t
        if (fault) {
            motor.disable();                          // Power stage off, () → void
            LOG("fault 0x%02X\n", (unsigned)fault);
            return;
        }
    }
    motor.disable();                                  // Power stage off, () → void
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    spi1_init(SPI_BAUDRATEPRESCALER_64, SPI_POLARITY_HIGH, SPI_PHASE_2EDGE);  // Mode 3, 100 MHz / 64
    SPIConnectionSTM32Cube connection(&hspi1, GPIOB, GPIO_PIN_6);  // Create SPI connection, (&hspi1, GPIOB, GPIO_PIN_6 = CS) → SPIConnectionSTM32Cube
    example(connection);
    while (true) HAL_Delay(1000);
}
