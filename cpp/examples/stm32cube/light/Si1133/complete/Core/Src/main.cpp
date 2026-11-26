#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Si1133.h"

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

static void on_event(uint8_t status) {
    printf("interrupt 0x%02X\r\n", (unsigned)(status));
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x55);
    Si1133Full sensor(connection);                                 // Create Si1133 driver, (connection) → void
    // Constructor stores the connection only; no bus traffic yet.
    sensor.init();                                                 // Check PART_ID, reset, UV + white channels, () → bool
    // Waits 25 ms, verifies PART_ID 0x33, RESET_SW, channel 0 = UV, channel 1 = white, 24-bit outputs.
    uint8_t part = 0, hw = 0, rev = 0;
    sensor.read_id(part, hw, rev);                                 // Identity registers, (part_id&, hw_id&, rev_id&) → bool
    // PART_ID is 0x33; HW_ID bits 4:0 are 0x03 for the Si1133-AA00.
    printf("PART_ID 0x%02X HW_ID 0x%02X REV_ID 0x%02X\r\n", (unsigned)(part), (unsigned)(hw), (unsigned)(rev));
    uint8_t state = 0, counter = 0;
    bool err = false;
    sensor.read_response(state, err, counter);                     // RESPONSE0 decoded, (state&, error&, counter&) → bool
    // State bits RUNNING/SUSPEND/SLEEP, the CMD_ERR flag and the 4-bit command counter.
    printf("state 0x%02X error %u counter %u\r\n", (unsigned)(state), (unsigned)(err), (unsigned)(counter));

    int32_t uv = 0, white = 0, x = 0;
    sensor.read(uv, white);                                        // Forced measurement, (uv&, white&) → bool
    // FORCE, wait for both channels in IRQ_STATUS, decode two signed 24-bit counts.
    printf("UV %ld white %ld\r\n", (long)(uv), (long)(white));
    sensor.read_uv(x);                                             // UV photodiode once, (value&) → bool
    // Uses channel 5 as a scratch channel and restores CHAN_LIST afterwards.
    printf("read_uv %ld\r\n", (long)(x));
    sensor.read_white(x);                                          // White photodiode once, (value&) → bool
    // Same scratch channel, HW_GAIN 7.
    printf("read_white %ld\r\n", (long)(x));
    sensor.read_ir(x, Si1133Full::MUX_LARGE_IR);                   // IR photodiode once, (value&, photodiode) → bool
    // Small, medium or large IR photodiode; the small one is the default.
    printf("read_ir %ld\r\n", (long)(x));

    sensor.configure_channel(2, Si1133Full::MUX_LARGE_WHITE, 4, 1, 0, false, false, 0);// Channel setup, (channel, adcmux, hw_gain, sw_gain, decim_rate, high_signal, bits24, postshift) → bool
    // Writes ADCCONFIG2, ADCSENS2 and ADCPOST2; this channel reports 16-bit unsigned counts.
    sensor.enable_channels(0x07);                                  // CHAN_LIST, (mask) → bool
    // Channels 0, 1 and 2 now run on every FORCE.
    int32_t v[6] = {0, 0, 0, 0, 0, 0};
    size_t n = 0;
    sensor.read_channels(v, n);                                    // Forced read of all channels, (values*, count&, force=true) → bool
    // Results in channel order: 24-bit signed for channels 0/1, 16-bit for channel 2.
    printf("channels %ld %ld %ld\r\n", (long)(v[0]), (long)(v[1]), (long)(v[2]));
    sensor.force();                                                // FORCE without waiting, () → bool
    // Starts a measurement; the results arrive in HOSTOUT when IRQ_STATUS flags the channels.
    HAL_Delay(100);
    uint8_t mask = 0;
    sensor.data_ready(mask);                                       // IRQ_STATUS mask, (mask&) → bool
    // Bit c set when channel c finished; this read clears the flags.
    printf("data ready 0x%02X\r\n", (unsigned)(mask));
    sensor.read_channels(v, n, false);                             // Decode current results, (values*, count&, force=false) → bool
    // No new measurement, only HOSTOUT decoded.

    sensor.set_threshold(0, 2000);                                 // Threshold value, (index, value) → bool
    // THRESHOLD0 = 2000 counts (16-bit).
    sensor.set_channel_threshold(0, 1);                            // Threshold select, (channel, index) → bool
    // Channel 0 interrupts only when crossing THRESHOLD0 (index 1); 0 turns it off.
    sensor.set_channel_threshold(0, 0);
    sensor.set_channel_counter(2, 2);                              // Counter for a channel, (channel, index) → bool
    // Channel 2 uses MEASCOUNT1 in autonomous mode; 0 keeps a channel forced-only.
    sensor.start_autonomous(0.1f, 10, 5, 1);                       // Autonomous mode, (rate_s, count0, count1, count2) → bool
    // MEASRATE = 0.1 s; channels 0/1 run every 1 s (MEASCOUNT0 = 10), channel 2 every 0.5 s.
    HAL_Delay(1500);
    printf("poll 0x%02X\r\n", (unsigned)(sensor.poll_interrupt())); // IRQ_STATUS mask, () → uint8_t
    // Same as data_ready; also releases the INT pin.
    sensor.pause();                                                // PAUSE, () → bool
    // Stops the autonomous measurements.
    sensor.set_burst(4);                                           // Burst mode, (count) → bool
    // Four readings per burst at MEASRATE; 0 disables burst mode.
    sensor.set_burst(0);

    sensor.write_parameter(0x1E, 3);                               // PARAM_SET, (address, value) → bool
    // Direct parameter-table access: MEASCOUNT2 = 3.
    uint8_t p = 0;
    sensor.read_parameter(0x1E, p);                                // PARAM_QUERY, (address, value&) → bool
    // Reads it back through RESPONSE1.
    printf("MEASCOUNT2 %u\r\n", (unsigned)(p));

    sensor.disable_interrupt(Si1133Full::SOURCE_CHANNEL2);         // IRQ_ENABLE clear, (source) → bool
    // Channel 2 no longer drives INT.
    sensor.enable_interrupt(Si1133Full::SOURCE_CHANNEL2);          // IRQ_ENABLE set, (source) → bool
    // Channel 2 drives INT again.
    sensor.on_interrupt(on_event);                                 // Subscribe, (callback, intPin=nullptr) → void
    // INT falling edge via the connection's pin; without a pin, poll with poll_interrupt().
    sensor.read_channels(v, n);
    HAL_Delay(100);
    sensor.off_interrupt();                                        // Unsubscribe, () → void
    // Removes the edge handler.

    sensor.reset();                                                // RESET_SW + defaults, () → bool
    // Parameter table back to power-up values, then channel 0 = UV, channel 1 = white again.
    // sensor.set_i2c_address(0x56);                               // Change address, (address) → bool
    // Volatile; the chip answers on the new address at once, so a new connection is needed.
    while (1) {}
}
