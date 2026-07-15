///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.magnetometer.As5600Full

fun main() {
    I2CTransport(1, 0x36).use { transport ->                 // open I²C bus 1, device 0x36, (bus, address) → I2CTransport

        val as5600 = As5600Full(transport)                         // construct driver, (transport) → As5600Full

        val angle = as5600.angle()                           // read angle in degrees, () → Double deg
                                                             // scaled by ZPOS/MPOS range, 0.0–360.0
        val raw = as5600.angleRaw()                          // read raw angle count, () → Int 0–4095
                                                             // 12-bit value from ANGLE register
        println("angle=%.2f°  raw=%d".format(angle, raw))

        val rawAngle = as5600.rawAngle()                     // read unscaled raw angle, () → Int 0–4095
                                                             // from RAW_ANGLE register, unaffected by ZPOS/MPOS
        val rawDeg = as5600.rawAngleDegrees()                // read raw angle in degrees, () → Double deg
                                                             // rawAngle × 360 / 4096
        println("raw_angle=%d  raw_deg=%.2f°".format(rawAngle, rawDeg))

        val agc = as5600.agc()                               // read AGC value, () → Int 0–255 (5V) or 0–127 (3.3V)
                                                             // mid-range = optimal airgap
        val mag = as5600.magnitude()                         // read CORDIC magnitude, () → Int 0–4095
                                                             // 12-bit magnitude from CORDIC computation
        val status = as5600.statusByte()                     // read raw STATUS byte, () → Int
                                                             // MH(bit5), ML(bit4), MD(bit3)
        println("agc=%d  magnitude=%d  status=0x%02X".format(agc, mag, status))

        println("magnet_detected=%s  too_strong=%s  too_weak=%s".format(
            as5600.isMagnetDetected(),                       // check magnet detected, () → Boolean
            as5600.isMagnetTooStrong(),                      // check magnet too strong, () → Boolean
            as5600.isMagnetTooWeak()))                       // check magnet too weak, () → Boolean

        val zpos = as5600.zeroPosition()                     // read zero position, () → Int 0–4095
        val mpos = as5600.maxPosition()                      // read max position, () → Int 0–4095
        val mang = as5600.maxAngle()                         // read max angle span, () → Int 0–4095
        println("zpos=%d  mpos=%d  mang=%d".format(zpos, mpos, mang))

        as5600.setZeroPosition(0)                            // set zero position, (pos=0) → Unit
                                                             // writes ZPOS to volatile RAM; lost on power cycle
        as5600.setMaxPosition(2048)                          // set max position, (pos=2048) → Unit
                                                             // writes MPOS to volatile RAM; 180° range
        Thread.sleep(2)  // wait ≥1 ms for register to take effect

        val bc = as5600.burnCount()                          // read burn count, () → Int 0–3
                                                             // number of permanent ZPOS/MPOS writes done
        println("burn_count=%d (remaining=%d)".format(bc, 3 - bc))

        as5600.configure(                                    // configure chip, (pm=0, hyst=0, outs=0, pwmf=0, sf=0, fth=0, wd=false) → Unit
            As5600Full.PM_NOM, As5600Full.HYST_OFF,
            As5600Full.OUTS_ANALOG, As5600Full.PWMF_115HZ,
            As5600Full.SF_16X, 0, false)
                                                             // NOM mode, no hysteresis, analog output, 115 Hz PWM, 16× slow filter
    }
}
