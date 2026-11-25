import math
import time
from periph.connection.i2c_auto import I2CConnection
from periph.chips.led.pca9685 import PCA9685Full

connection = I2CConnection(0x40)

# --- LED fader and servo sweep ---
# Fade channel 0 at 1 kHz, show a phase-shifted pair, run a 16-channel chase,
# switch to 50 Hz and sweep a servo on channel 15 from 1 ms to 2 ms, then show
# all-channel writes, full ON/OFF, output modes, sleep/wake/restart with the
# outputs preserved, the all-call and sub addresses, and finish with a reset.
pwm = PCA9685Full(connection)                             # Create PCA9685 driver, (connection)
pwm.set_frequency(1000)                                   # 1 kHz PWM for LEDs, (hz) → None

for i in range(0, 101):
    pwm.set_duty(0, 0.5 - 0.5 * math.cos(math.pi * i / 50))   # Fade up and down, (channel, duty) → None
    time.sleep(0.02)

pwm.set_pwm(1, 0, 1024)                                   # 25 % duty, no delay, (channel, on, off) → None
pwm.set_pwm(2, 2048, 3072)                                # same duty, shifted by half a period
print(pwm.get_pwm(2))                                     # Read back the counts, (channel) → (on, off)
time.sleep(1)

for ch in range(16):
    pwm.set_duty(ch, 1.0)                                 # Chase across all channels, (channel, duty) → None
    time.sleep(0.05)
    pwm.set_duty(ch, 0.0)

pwm.set_all_duty(0.2)                                     # All channels together, (duty) → None
time.sleep(0.5)
pwm.set_all_pwm(0, 3072)                                  # 75 % on all channels, (on, off) → None
time.sleep(0.5)
pwm.set_all_duty(0.0)
pwm.set_full_on(3)                                        # Constant high, (channel) → None
time.sleep(0.5)
pwm.set_full_off(3)                                       # Constant low, (channel) → None

pwm.set_frequency(50)                                     # Servo frequency, (hz) → None
for us in range(1000, 2001, 50):
    pwm.set_pulse_width(15, us / 1e6)                     # Servo pulse 1–2 ms, (channel, seconds) → None
    time.sleep(0.05)
print(pwm.get_frequency())                                # () → float Hz

pwm.set_frequency(1000)
pwm.set_duty(4, 0.5)
pwm.set_output_mode(False, True, PCA9685Full.OFF_LOW)     # Inverted output, (open_drain, invert, off_state) → None
time.sleep(0.5)
pwm.set_output_mode(True, False, PCA9685Full.OFF_HIGHZ)   # Open-drain, high-Z when OE = 1
time.sleep(0.5)
pwm.set_output_mode()                                     # Back to totem-pole, not inverted
pwm.set_update_on_ack(True)                               # Update on ACK, (enabled) → None
pwm.set_update_on_ack(False)

pwm.sleep()                                               # Outputs pause, (), PWM registers kept → None
time.sleep(0.5)
pwm.wake()                                                # Outputs resume, () → None
pwm.restart()                                             # Restart sequence, () → None
pwm.set_allcall(True, 0x70)                               # Respond to all-call, (enabled, address) → None
pwm.set_allcall(False)
pwm.set_subaddress(1, 0x71, True)                         # Respond to sub address 1, (index, address, enabled) → None
pwm.set_subaddress(1, 0x71, False)
print(pwm.read_mode())                                    # (mode1, mode2) → tuple
pwm.reset()                                               # Back to power-on state, () → None
print('===DONE: 0 passed, 0 failed===')
