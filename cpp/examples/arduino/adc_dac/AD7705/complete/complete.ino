#include <SPI.h>
#include <Periph.h>

#ifndef TEST_CS_PIN
#define TEST_CS_PIN 10
#endif
#ifndef TEST_RESET_PIN
#define TEST_RESET_PIN 9
#endif

SPISettings settings(5000000, MSBFIRST, SPI_MODE3);
SPIConnection connection(SPI, TEST_CS_PIN, settings);

class ResetPinArduino : public OutputPin {
public:
    ResetPinArduino(uint8_t pin) : _pin(pin) { pinMode(_pin, OUTPUT); digitalWrite(_pin, HIGH); }
    void set(bool high) override { digitalWrite(_pin, high ? HIGH : LOW); }
private:
    uint8_t _pin;
};

ResetPinArduino reset_pin(TEST_RESET_PIN);                          // Construct hardware reset OutputPin, (pin=9) → ResetPinArduino

AD7705Full adc(connection, 2.5, AD7705Minimal::MCLK_2_4576MHZ, &reset_pin);
                                                                     // Construct and initialise the AD7705, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz, reset_pin=&reset_pin) → AD7705Full

void setup() {
    Serial.begin(115200);
    delay(2000);
    SPI.begin();

    adc.configure(2, AD7705Full::GAIN_8, true, true, 60);           // Configure channel 2, (channel=2, gain=GAIN_8, bipolar=true, buffered=true, output_rate_hz=60) → None
                                                                     // writes Setup and Clock Registers for channel 2; does not calibrate
    adc.self_calibrate(2);                                          // Self-calibrate channel 2, (channel=2) → None
                                                                     // runs internal self-calibration, blocks until DRDY

    uint32_t off2 = adc.get_offset_calibration(2);                   // Read offset calibration, (channel=2) → uint32_t 24-bit
    uint32_t gain2 = adc.get_gain_calibration(2);                    // Read gain calibration, (channel=2) → uint32_t 24-bit
    Serial.print("ch2 offset="); Serial.print(off2);
    Serial.print(" gain="); Serial.println(gain2);

    uint16_t raw1 = adc.read_raw(1);                                 // Read raw 16-bit code, (channel=1) → uint16_t
                                                                     // blocks until DRDY, returns raw Data Register code
    float v1 = adc.read_voltage(1);                                  // Read voltage, (channel=1) → float V
                                                                     // converts raw code to volts using channel's current gain/bipolar
    float v2 = adc.read_voltage(2);                                  // Read voltage, (channel=2) → float V
    Serial.print("ch1 raw="); Serial.print(raw1);
    Serial.print(" ch1 v="); Serial.print(v1);
    Serial.print(" ch2 v="); Serial.println(v2);

    adc.standby();                                                   // Enter standby, () → None
                                                                     // sets STBY=1 (~10 µA, registers retained)
    delay(100);
    adc.wakeup();                                                    // Exit standby, () → None
                                                                     // clears STBY; blocks until a fresh conversion is available

    adc.reset();                                                     // Hardware reset, () → None
                                                                     // pulses RESET low for >=100 ns; all registers return to power-on defaults
}

void loop() {
}
