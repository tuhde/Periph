///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-groovy:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.led.Pca9685Full

int bus  = System.getenv("I2C_BUS")  ? System.getenv("I2C_BUS").toInteger()  : 1
String addrStr = System.getenv("I2C_ADDR") ?: "0x40"
int addr = Integer.decode(addrStr)

I2CConnection connection = new I2CConnection(bus, addr)            // open I²C bus, (bus, address=0x40) → I2CConnection
try {
    def pwm = new Pca9685Full(connection)   // construct driver, (connection) → Pca9685Full
    pwm.setDuty(0, 0.25)   // set one channel's duty cycle, (channel 0–15, duty 0.0–1.0) → void
    pwm.setPwm(1, 409, 1228)   // phase-shifted PWM, (channel, on 0–4095, off 0–4095) → void
    // 10 % delay, 20 % duty
    println(java.util.Arrays.toString(pwm.getPwm(1)))   // read the ON/OFF counts, (channel) → int[] {on, off}
    pwm.setFullOn(2)   // hold a channel high, (channel) → void
    pwm.setFullOff(2)   // hold a channel low, (channel) → void
    pwm.setAllDuty(0.1)   // same duty on all 16 channels, (duty 0.0–1.0) → void
    pwm.setAllPwm(0, 2048)   // same ON/OFF counts on all channels, (on, off) → void
    pwm.setAllDuty(0.0)
    pwm.setFrequency(50)   // PWM frequency, (hz 24–1526 [, oscHz]) → void
    println(pwm.getFrequency())   // programmed PWM frequency, ([oscHz]) → double Hz
    pwm.setPulseWidth(15, 0.0015)   // servo pulse width, (channel, seconds) → void
    // 1.5 ms at 50 Hz = servo centre
    Thread.sleep(1000)
    pwm.sleep()   // low-power mode, outputs stop, () → void
    pwm.wake()   // wake and restart running channels, () → void
    pwm.restart()   // datasheet restart sequence, () → void
    pwm.setOutputMode(false, false, Pca9685Full.OFF_LOW)   // MODE2 output driver, (openDrain, invert, offState) → void
    pwm.setUpdateOnAck(false)   // outputs change on STOP (false) or ACK (true), (enabled) → void
    pwm.setAllcall(false)   // LED All Call address, (enabled [, address 0x70]) → void
    pwm.setSubaddress(1, 0x71, false)   // LED Sub Call address, (index 1–3, address, enabled) → void
    println(java.util.Arrays.toString(pwm.readMode()))   // raw MODE1/MODE2, () → int[] {mode1, mode2}
    // pwm.useExternalClock()  sticky EXTCLK switch, () → void (needs a clock on EXTCLK)
    // Pca9685Full.softwareReset(generalCall)  reset every PCA9685 on the bus, (Connection at address 0x00) → void
    pwm.reset()   // write power-on defaults, () → void
} finally {
    connection.close()
}
