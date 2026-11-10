#include <Wire.h>
#include <Periph.h>

#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_I2C_FREQ
#define TEST_I2C_FREQ 400000
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x18
#endif

I2CConnection connection(Wire, TEST_ADDR);
static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) {
        Serial.print("PASS "); Serial.println(label);
        passed++;
    } else {
        Serial.print("FAIL "); Serial.println(label);
        failed++;
    }
}

void setup() {
    Serial.begin(115200);
    delay(2000);

    Wire.begin(TEST_SDA, TEST_SCL, TEST_I2C_FREQ);
    ES8311Full codec(connection);

    check_true("init accepted", true);
    codec.set_volume(-20.0f);
    check_true("set_volume/get_volume round trip", codec.get_volume() == -20.0f);
    codec.set_mic_gain(24.0f);
    check_true("set_mic_gain/get_mic_gain round trip", codec.get_mic_gain() == 24.0f);
    codec.set_mute(true);
    check_true("DAC mute bits set", (codec.read_register(0x31) & 0x60) == 0x60);
    codec.set_mute(false);
    check_true("DAC mute bits cleared", (codec.read_register(0x31) & 0x60) == 0x00);
    check_true("configure_clock accepted", codec.configure_clock(12288000, 16000));
    check_true("configure_serial accepted", codec.configure_serial(ES8311Full::FORMAT_I2S, 16));
    check_true("serial format / word length read back", (codec.read_register(0x09) & 0x1F) == 0x0C);
    codec.set_adc_volume(0.0f);
    check_true("ADC volume 0 dB (0xBF)", codec.read_register(0x17) == 0xBF);
    codec.configure_alc(true, -12.0f, -18.1f, 0, true, 0, -60.0f);
    check_true("ALC enabled bit", (codec.read_register(0x18) & 0x80) == 0x80);
    codec.configure_alc(false);
    codec.configure_drc(true, -12.0f, -24.1f, 0);
    check_true("DRC enabled bit", (codec.read_register(0x34) & 0x80) == 0x80);
    codec.configure_drc(false);
    codec.set_loopback(true);
    check_true("loopback bit set", (codec.read_register(0x44) & 0x80) == 0x80);
    codec.set_loopback(false);
    check_true("ADCDAT mode dac_adc (4)", codec.set_adcdat_mode(ES8311Full::ADCDAT_DAC_ADC) && ((codec.read_register(0x44) >> 4) & 7) == 4);
    codec.set_adcdat_mode(ES8311Full::ADCDAT_ADC_ADC);
    codec.set_dac_ramp(8);
    check_true("DAC ramp rate 8", (codec.read_register(0x37) >> 4) == 8);
    codec.power_down();
    check_true("power_down clears CSM_ON", (codec.read_register(0x00) & 0x80) == 0x00);
    codec.reset();
    check_true("reset accepted", true);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
