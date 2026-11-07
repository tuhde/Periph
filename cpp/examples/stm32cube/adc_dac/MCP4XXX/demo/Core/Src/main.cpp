#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "MCP4XXX.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x58);
    MCP4XXXFull pot(connection, MCP4XXXPart::MCP4532, 10000.0f, 75.0f); // Create MCP4XXX driver, (connection, part, r_ab=10000 Ω, r_wiper=75 Ω, hvc=nullptr, hvc_settle_ms=1)
    pot.init();                                              // Bus check, () → void


    // --- Identify the family member ---
    // One driver covers sixteen parts; the descriptor tells resolution, networks and memory type.
    printf("network(s)=%d steps=%d pot=%s eeprom=%s\r\n", (int)(pot.channels()), (int)(pot.steps()), (pot.is_potentiometer()) ? "true" : "false", (pot.is_nonvolatile()) ? "true" : "false"); // Part properties, () → uint8_t / uint16_t / bool
    printf("power-up wiper %d %.1f ohm\r\n", (int)(pot.get_wiper(0)), (double)(pot.get_resistance(0))); // Read wiper, (channel=0) → uint16_t

    // --- Set 2.2 kOhm and report the quantisation error ---
    // The wiper snaps to the nearest step of RAB / max code.
    pot.set_resistance(2200.0f, 0);
    float actual = pot.get_resistance(0);                    // Read W-B resistance, (channel=0) → float Ω
    printf("target=2200 ohm code=%d actual=%.1f ohm error=%.1f ohm\r\n", (int)(pot.get_wiper(0)), (double)(actual), (double)(actual - 2200.0f));

    // --- Step the wiper up and down ---
    // increment / decrement send one command byte per step in one transaction and saturate at full scale and zero scale.
    const int moves[] = {8, 8, -4, 10};
    for (int i = 0; i < 4; i++) {
        int m = moves[i];
        uint16_t code = m > 0 ? pot.increment(m, 0) : pot.decrement(-m, 0); // Step wiper, (steps=1, channel=0) → uint16_t
    printf("    step %d -> code=%d R_WB=%.1f ohm\r\n", (int)(m), (int)(code), (double)(pot.get_resistance(0)));
    }

    // --- Ratios ---
    // The ratio is the fraction of RAB between W and B (the unloaded divider ratio of a potentiometer).
    const float ratios[] = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};
    for (int i = 0; i < 5; i++) {
        pot.set_ratio(ratios[i], 0);                         // Set wiper ratio, (ratio 0.0-1.0, channel=0) → void
    printf("    ratio=%.2f code=%d R_WB=%.1f R_WA=%.1f\r\n", (double)(ratios[i]), (int)(pot.get_wiper(0)), (double)(pot.get_resistance(0)), (double)(pot.get_resistance_wa(0))); // Read W-A resistance (-1 on rheostats), (channel=0) → float Ω
    }

    // --- Dual parts: drive both networks ---
    if (pot.channels() == 2) {
        pot.set_both(32, 96);                                // Set both wipers, (code0, code1) → bool
    printf("    both: %d %d -> %d %d\r\n", (int)(pot.get_wiper(0)), (int)(pot.get_wiper(1)), (int)(pot.increment(5, 1)), (int)(pot.decrement(2, 1)));
    }

    // --- Shutdown and terminal disconnect ---
    // Shutdown ties W to B and opens A without losing the wiper; set_terminals(w = false) floats W.
    pot.shutdown(0);                                         // Shut network down, (channel=0) → void
    printf("shutdown=%s code=%d\r\n", (pot.is_shutdown(0)) ? "true" : "false", (int)(pot.get_wiper(0))); // Read state, (channel=0) → bool
    HAL_Delay(1000);
    pot.resume(0);                                           // Leave shutdown, (channel=0) → void
    pot.set_terminals(true, false, true, 0);                 // Disconnect the wiper, (a, w, b, channel=0) → void
    pot.set_terminals(true, true, true, 0);                  // Reconnect all terminals, (a, w, b, channel=0) → void

    // --- Non-volatile parts: EEPROM wiper and data ---
    // store_wiper makes the setting survive power cycles; every EEPROM write uses one of the 1 000 000 write cycles, so do not do this in a loop.
    if (pot.is_nonvolatile()) {
        MCP4XXXStatus status;
        pot.read_status(status);                             // Read STATUS, (&status) → bool
    printf("    busy=%s\r\n", (status.eeprom_busy) ? "true" : "false");
    printf("    stored %d\r\n", (int)(pot.store_wiper(0)));  // Store the wiper in EEPROM, (channel=0) → int
        pot.write_data(0, 0x155);                            // Write calibration marker, (index 0-9, value 0-0x1FF) → bool
    printf("    data0=%d nv wiper=%d\r\n", (int)(pot.read_data(0)), (int)(pot.get_nv_wiper(0))); // Read back, (index) → int
    }

    // --- Back to the power-up state ---
    pot.reset();                                             // Emulate power-on reset, () → void
    printf("after reset: %d\r\n", (int)(pot.get_wiper(0)));

    while (true) HAL_Delay(1000);
}
