#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Is31fl3731.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x74);
    Is31fl3731Full matrix(connection);                    // Create IS31FL3731 driver, (connection)
    HAL_Delay(2000);

    matrix.clear();                                       // Switch all LEDs of the drawing frame off, () → void
    matrix.set_pixel(2, 5, 200);                          // Set one LED's PWM brightness, (row 0–8, col 0–15, brightness 0–255) → bool
    printf("%d\r\n", (int)(matrix.get_pixel(2, 5)));      // Read a pixel from the shadow copy, (row, col) → uint8_t 0–255
    uint8_t ramp[144];
    for (int i = 0; i < 144; i++) ramp[i] = (uint8_t)i;
    matrix.set_pixels(ramp, 144);                         // Write a whole frame, (data, len must be 144) → bool
    matrix.fill(40);                                      // Set every LED's PWM brightness, (brightness 0–255) → void
    matrix.set_led_enabled(0, 0, false);                  // LED-control (on/off) bit, (row, col, on) → bool
    matrix.set_led_enabled(0, 0, true);
    matrix.set_blink(1, 1, true);                         // Blink-enable bit of one LED, (row, col, on) → bool
    matrix.set_blink_all(false);                          // Blink-enable bit of every LED, (on) → void
    matrix.enable_blink(true, 2);                         // Blink on, (enabled, period 0–7 × 0.27 s) → bool
                                                          // 0.54 s period, 50 % duty
    HAL_Delay(2000);
    matrix.enable_blink(false);
    matrix.select_frame(1);                               // Frame subsequent drawing writes to, (frame 0–7) → bool
    matrix.fill(100);
    matrix.copy_frame(1, 2);                              // Copy PWM values between frames, (src, dst) → bool
    matrix.clear_frame(2);                                // Re-initialise one frame, (frame 0–7) → bool
    matrix.clear_all_frames();                            // Re-initialise all eight frames, () → void
    matrix.select_frame(0);
    matrix.show_frame(0);                                 // Display a frame in Picture mode, (frame 0–7) → bool
    matrix.set_picture_mode();                            // Picture mode, () → void
    matrix.start_auto_play(2, 1, 110, 0);                 // Auto Frame Play, (frames 0–7, loops 0–7, delay_ms, start_frame) → bool
                                                          // frames/loops 0 = all/endless; plays 2 frames once
    HAL_Delay(1000);
    printf("%d\r\n", (int)(matrix.frame_state()));        // Read FRAME_STATE, () → uint8_t
    printf("%d\r\n", (int)(matrix.current_frame()));      // Frame being displayed, () → uint8_t 0–7
    printf("%d\r\n", (int)(matrix.movie_finished()));     // Movie-finished flag (clears it), () → bool
    matrix.stop_auto_play();                              // Back to Picture mode on the current frame, () → void
    matrix.set_breath(true, 3, 3, 2);                     // Breath function, (enabled, fade_in, fade_out, extinguish 0–7) → bool
    HAL_Delay(3000);
    matrix.set_breath(false);
    matrix.set_intensity_control(false);                  // Share frame 1's PWM with all frames, (use_frame1) → void
    matrix.set_audio_gain(6, true, false);                // Audio gain, (gain_db 0–21 step 3, agc, fast) → bool
    matrix.set_audio_rate(10);                            // Audio ADC interval, (a 0–255, × 46 µs) → void
    matrix.set_audio_sync(false);                         // Audio intensity modulation, (enabled) → void
    matrix.set_audio_mode(false);                         // Audio Frame Play on/off, (enabled) → void
    printf("%d\r\n", (int)(matrix.read_function_register(0x03)));// Raw function-page read, (reg 0–0x0C) → uint8_t
    matrix.write_function_register(0x03, 0x0B);           // Raw function-page write, (reg, value) → bool
    printf("%d\r\n", (int)(matrix.poll_interrupt()));     // FRAME_STATE, clears INT, () → uint8_t
    matrix.on_interrupt([](uint8_t status) { (void)status; });// Callback when a movie ends, (callback, intPin) → void
    matrix.service();                                     // Poll once and dispatch, () → void
    matrix.off_interrupt();                               // Unsubscribe, () → void
    matrix.shutdown();                                    // Software shutdown, (on = true) → void
    matrix.shutdown(false);                               // Back to normal operation

    printf("===DONE: 0 passed, 0 failed===\r\n");
    while (true) {
    HAL_Delay(1000); 
        HAL_Delay(10);
    }

}
