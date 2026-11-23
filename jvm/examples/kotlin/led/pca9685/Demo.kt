///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-kotlin:1.5.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.led.Pca9685Full

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt()  ?: 1
    val addr = System.getenv("I2C_ADDR")?.let { Integer.decode(it) } ?: 0x40
    I2CConnection(bus, addr).use { connection ->                       // open I²C bus, (bus, address=0x40) → I2CConnection
        // --- LED fader and servo sweep ---
        // Fade channel 0 at 1 kHz, show a phase-shifted pair, run a 16-channel chase,
        // switch to 50 Hz and sweep a servo on channel 15 from 1 ms to 2 ms, then show
        // all-channel writes, full ON/OFF, output modes, sleep/wake/restart with the
        // outputs preserved, the all-call and sub addresses, and finish with a reset.
        val pwm = Pca9685Full(connection)   // construct driver, (connection) → Pca9685Full
        pwm.setFrequency(1000.0)   // 1 kHz PWM for LEDs, (hz) → void
        for (i in 0..100) {
            pwm.setDuty(0, 0.5 - 0.5 * Math.cos(Math.PI * i / 50))   // fade up and down, (channel, duty) → void
            Thread.sleep(20)
        }
        pwm.setPwm(1, 0, 1024)   // 25 % duty, no delay, (channel, on, off) → void
        pwm.setPwm(2, 2048, 3072)   // same duty, shifted by half a period
        println(java.util.Arrays.toString(pwm.getPwm(2)))   // read back the counts, (channel) → int[] {on, off}
        Thread.sleep(1000)
        for (ch in 0 until 16) {
            pwm.setDuty(ch, 1.0)   // chase across all channels, (channel, duty) → void
            Thread.sleep(50)
            pwm.setDuty(ch, 0.0)
        }
        pwm.setAllDuty(0.2)   // all channels together, (duty) → void
        Thread.sleep(500)
        pwm.setAllPwm(0, 3072)   // 75 % on all channels, (on, off) → void
        Thread.sleep(500)
        pwm.setAllDuty(0.0)
        pwm.setFullOn(3)   // constant high, (channel) → void
        Thread.sleep(500)
        pwm.setFullOff(3)   // constant low, (channel) → void
        pwm.setFrequency(50.0)   // servo frequency, (hz) → void
        for (us in 1000..2000 step 50) {
            pwm.setPulseWidth(15, us / 1e6)   // servo pulse 1–2 ms, (channel, seconds) → void
            Thread.sleep(50)
        }
        println(pwm.getFrequency())   // () → double Hz
        pwm.setFrequency(1000.0)
        pwm.setDuty(4, 0.5)
        pwm.setOutputMode(false, true, Pca9685Full.OFF_LOW)   // inverted output, (openDrain, invert, offState) → void
        Thread.sleep(500)
        pwm.setOutputMode(true, false, Pca9685Full.OFF_HIGHZ)   // open-drain, high-Z when OE = 1
        Thread.sleep(500)
        pwm.setOutputMode(false, false, Pca9685Full.OFF_LOW)   // back to totem-pole, not inverted
        pwm.setUpdateOnAck(true)   // update on ACK, (enabled) → void
        pwm.setUpdateOnAck(false)
        pwm.sleep()   // outputs pause, PWM registers kept, () → void
        Thread.sleep(500)
        pwm.wake()   // outputs resume, () → void
        pwm.restart()   // restart sequence, () → void
        pwm.setAllcall(true)   // respond to all-call, (enabled [, address]) → void
        pwm.setAllcall(false)
        pwm.setSubaddress(1, 0x71, true)   // respond to sub address 1, (index, address, enabled) → void
        pwm.setSubaddress(1, 0x71, false)
        println(java.util.Arrays.toString(pwm.readMode()))   // () → int[] {mode1, mode2}
        pwm.reset()   // back to power-on state, () → void
    }
}
