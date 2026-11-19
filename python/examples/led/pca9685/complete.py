import time
from periph.connection.i2c_auto import I2CConnection
from periph.chips.led.pca9685 import PCA9685Full

connection = I2CConnection(0x40)
pwm = PCA9685Full(connection)                             # Create PCA9685 driver, (connection)
pwm.set_duty(0, 0.25)                                     # Set one channel's duty cycle, (channel 0–15, duty 0.0–1.0) → None
pwm.set_pwm(1, 409, 1228)                                 # Phase-shifted PWM, (channel, on 0–4095, off 0–4095) → None
                                                          # 10 % delay, 20 % duty
print(pwm.get_pwm(1))                                     # Read the ON/OFF counts, (channel) → (on, off)
pwm.set_full_on(2)                                        # Hold a channel high, (channel) → None
pwm.set_full_off(2)                                       # Hold a channel low, (channel) → None
pwm.set_all_duty(0.1)                                     # Same duty on all 16 channels, (duty 0.0–1.0) → None
pwm.set_all_pwm(0, 2048)                                  # Same ON/OFF counts on all channels, (on, off) → None
pwm.set_all_duty(0.0)
pwm.set_frequency(50)                                     # PWM frequency, (hz 24–1526, osc_hz = 25 MHz) → None
print(pwm.get_frequency())                                # Programmed PWM frequency, (osc_hz = 25 MHz) → float Hz
pwm.set_pulse_width(15, 0.0015)                           # Servo pulse width, (channel, seconds) → None
                                                          # 1.5 ms at 50 Hz = servo centre
time.sleep(1)
pwm.sleep()                                               # Low-power mode, outputs stop, () → None
pwm.wake()                                                # Wake and restart running channels, () → None
pwm.restart()                                             # Datasheet restart sequence, () → None
pwm.set_output_mode(False, False, PCA9685Full.OFF_LOW)    # MODE2 output driver, (open_drain, invert, off_state OFF_LOW/OFF_HIGH_OR_HIGHZ/OFF_HIGHZ) → None
pwm.set_update_on_ack(False)                              # Outputs change on STOP (False) or ACK (True), (enabled) → None
pwm.set_allcall(False, 0x70)                              # LED All Call address, (enabled, address) → None
pwm.set_subaddress(1, 0x71, False)                        # LED Sub Call address, (index 1–3, address, enabled) → None
print(pwm.read_mode())                                    # Raw MODE1/MODE2, () → (mode1, mode2)
# pwm.use_external_clock()                                # Sticky EXTCLK switch, () → None  (needs a clock on EXTCLK)
# PCA9685Full.software_reset(general_call)                # Reset every PCA9685 on the bus, (Connection at address 0x00) → None
pwm.reset()                                               # Write power-on defaults, () → None
print('===DONE: 0 passed, 0 failed===')
