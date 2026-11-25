#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "ES8311.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x18);
    ES8311Full codec(connection);  // Create ES8311 driver, (connection, mclk_hz=12288000 Hz, sample_rate_hz=48000 Hz)
    codec.set_volume(-30.0f);                                   // Set DAC volume, (db −95.5–32 dB) → void
                                                                // writes 0x32 = round(db / 0.5) + 191
    float v = codec.get_volume();                               // Read back DAC volume, () → float dB
                                                                // (0x32 − 191) × 0.5
    printf("volume dB %.1f\r\n", (double)v);
    codec.set_mute(false);                                      // Unmute the DAC, (muted=false) → void
                                                                // clears DSM and DEM mute bits of 0x31
    codec.set_mic_gain(27.0f);                                  // Set microphone PGA gain, (db 0–30 dB) → void
                                                                // 0x14 bits 3:0 = round(db / 3)
    float g = codec.get_mic_gain();                             // Read back PGA gain, () → float dB
                                                                // 3 dB per register step
    printf("mic gain dB %.1f\r\n", (double)g);
    codec.configure_clock(12288000, 16000);                     // Program the clock tree, (mclk_hz Hz, sample_rate_hz Hz, mclk_from_bclk=false, mclk_invert=false) → bool
                                                                // preset table first, then computed pre-divider / multiplier / OSR
    codec.configure_serial(ES8311Full::FORMAT_I2S, 16);         // Configure the digital audio port, (format, word_length=16, master=false, bclk_invert=false, lrck_invert=false, dac_right=false, bclk_div=0, lrck_div=0, continuous_bclk=true) → bool
                                                                // 0x09 / 0x0A format and word length, slave mode
    codec.set_tristate(false, false);                           // Tri-state BCLK/LRCK and ADCDAT, (bclk_lrck=false, adcdat=false) → void
                                                                // 0x07 bits 5:4
    codec.set_input(ES8311Full::INPUT_ANALOG);                  // Select microphone input, (source INPUT_ANALOG|INPUT_DIGITAL|INPUT_NONE) → bool
                                                                // MIC1P / MIC1N differential, PGA powered
    codec.configure_dmic(false);                                // Select PDM latch edge, (latch_falling_edge=false) → void
                                                                // 0x15 bit 0
    codec.set_adc_volume(0.0f);                                 // Set ADC digital volume, (db −95.5–32 dB) → void
                                                                // 0x17; ALC maximum gain when the ALC is on
    codec.configure_adc(24, 64, false, true);                   // Configure ADC path, (scale_db=24 dB, osr=64, double_speed=false, hpf=true, hpf_coeff1=0x0C, hpf_coeff2=0x0C) → bool
                                                                // 0x16 scale, 0x03 speed / OSR, 0x1B / 0x1C high-pass filter
    codec.set_adc_ramp(0);                                      // Set ADC soft-ramp rate, (rate 0–15) → void
                                                                // 0 = off; n = 0.25 dB per 2^(n+1) LRCK
    codec.configure_alc(true, -12.0f, -18.1f, 0, true, 0, -60.0f); // Configure ALC, (enabled, max_level_db, min_level_db, window, automute, automute_window, noise_gate_db) → void
                                                                // 0x18–0x1A, levels rounded to the nearest listed value
    codec.configure_alc(false);                                 // Disable ALC, (enabled=false) → void
                                                                // restores defaults
    codec.bypass_adc_eq();                                      // Bypass ADC equalizer, () → void
                                                                // 0x1C bit 6; set_adc_eq() loads five 30-bit coefficients b0,a1,a2,b1,b2
    codec.set_dac_ramp(8);                                      // Set DAC soft-ramp rate, (rate 0–15) → void
                                                                // 0x37 bits 7:4
    codec.configure_drc(true, -12.0f, -24.1f, 0);               // Configure DRC, (enabled, max_level_db, min_level_db, window) → void
                                                                // 0x34 / 0x35; DAC volume becomes the maximum gain
    codec.configure_drc(false);                                 // Disable DRC, (enabled=false) → void
                                                                // clears the DRC enable bit
    codec.bypass_dac_eq();                                      // Bypass DAC equalizer, () → void
                                                                // 0x37 bit 3; set_dac_eq() loads three 30-bit coefficients b0,b1,a1
    codec.set_dac_mute_options(true, true, false);              // Set DAC mute bits, (dsm=true, dem=true, dsm_target=false) → void
                                                                // 0x31 bits 7:5 individually
    codec.set_loopback(true);                                   // Route ADC output into the DAC, (enabled) → void
                                                                // 0x44 bit 7
    codec.set_loopback(false);                                  // Disable loopback, (enabled=false) → void
                                                                // 0x44 bit 7 cleared
    codec.set_adcdat_mode(ES8311Full::ADCDAT_DAC_ADC);          // Select ADCDAT content, (mode ADCDAT_*) → bool
                                                                // DAC + ADC: digital feedback for echo cancellation
    codec.set_adcdat_mode(ES8311Full::ADCDAT_ADC_ADC);          // Restore ADCDAT content, (mode ADCDAT_*) → bool
                                                                // ADC data on both slots
    codec.set_sdp_mute(false, false);                           // Mute serial input / output, (input=false, output=false) → void
                                                                // 0x09 / 0x0A bit 6
    codec.set_low_power();                                      // Clear all low-power bits, (dac, pga, pga_out, adc, adc_ref, dac_ref, flash, integrator = false) → void
                                                                // 0x0F; all off = best THD+N
    uint8_t r = codec.read_register(0x32);                      // Read raw register, (reg 0x00–0xFF) → uint8_t
                                                                // returns the byte as stored in the chip
    printf("reg 0x32 %.1f\r\n", (double)r);
    codec.write_register(0x32, 0xBF);                           // Write raw register, (reg, value) → void
                                                                // 0xBF = 0 dB DAC volume
    HAL_Delay(1000);
    codec.set_mute(true);                                       // Mute the DAC, (muted=true) → void
                                                                // sets DSM and DEM mute
    codec.power_down();                                         // Enter standby, () → void
                                                                // powers down analog blocks, resets digital, clears CSM_ON
    codec.power_up();                                           // Leave standby, () → void
                                                                // re-enables analog blocks, clocks and CSM_ON
    codec.reset();                                              // Software reset, () → void
                                                                // registers return to reset values; reconfigure afterwards

    while (true) {
        HAL_Delay(1000);
    }
}
