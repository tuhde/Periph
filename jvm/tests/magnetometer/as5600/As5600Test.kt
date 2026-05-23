///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.magnetometer.As5600Full

var passed = 0
var failed = 0

fun checkTrue(label: String, condition: Boolean) {
    if (condition) { println("PASS $label"); passed++ }
    else           { println("FAIL $label"); failed++ }
}

fun main() {
    val bus  = System.getenv("I2C_BUS")?.toInt() ?: 1
    val addr = System.getenv("I2C_ADDR")?.removePrefix("0x")?.removePrefix("0X")?.toInt(16) ?: 0x36

    I2CTransport(bus, addr).use { transport ->

        val as5600 = As5600Full(transport)

        // --- Basic measurements ---
        val angle = as5600.angle()
        checkTrue("angle() in range 0–360", angle in 0.0..359.999)

        val raw = as5600.angleRaw()
        checkTrue("angleRaw() in range 0–4095", raw in 0..4095)

        // --- Magnet status ---
        val md = as5600.isMagnetDetected()
        checkTrue("isMagnetDetected() == true", md)

        as5600.isMagnetTooStrong()
        checkTrue("isMagnetTooStrong() accepted", true)

        as5600.isMagnetTooWeak()
        checkTrue("isMagnetTooWeak() accepted", true)

        // --- Full methods ---
        val rawAngle = as5600.rawAngle()
        checkTrue("rawAngle() in range 0–4095", rawAngle in 0..4095)

        val rawDeg = as5600.rawAngleDegrees()
        checkTrue("rawAngleDegrees() in range 0–360", rawDeg in 0.0..359.999)

        val agc = as5600.agc()
        checkTrue("agc() is finite", agc >= 0)

        val mag = as5600.magnitude()
        checkTrue("magnitude() in range 0–4095", mag in 0..4095)

        val status = as5600.statusByte()
        checkTrue("statusByte() accepted", status in 0..255)

        // --- Position registers ---
        val zpos = as5600.zeroPosition()
        checkTrue("zeroPosition() accepted", zpos in 0..4095)

        val mpos = as5600.maxPosition()
        checkTrue("maxPosition() accepted", mpos in 0..4095)

        val mang = as5600.maxAngle()
        checkTrue("maxAngle() accepted", mang in 0..4095)

        // --- Set position (volatile) ---
        as5600.setZeroPosition(100)
        Thread.sleep(2)
        val zpos2 = as5600.zeroPosition()
        checkTrue("setZeroPosition(100) wrote correctly", zpos2 == 100)

        as5600.setMaxPosition(2048)
        Thread.sleep(2)
        val mpos2 = as5600.maxPosition()
        checkTrue("setMaxPosition(2048) wrote correctly", mpos2 == 2048)

        as5600.setMaxAngle(1024)
        Thread.sleep(2)
        val mang2 = as5600.maxAngle()
        checkTrue("setMaxAngle(1024) wrote correctly", mang2 == 1024)

        // --- Burn count ---
        val bc = as5600.burnCount()
        checkTrue("burnCount() in range 0–3", bc in 0..3)

        // --- Configure ---
        as5600.configure(As5600Full.PM_NOM, As5600Full.HYST_OFF,
                As5600Full.OUTS_ANALOG, As5600Full.PWMF_115HZ,
                As5600Full.SF_16X, 0, false)
        checkTrue("configure() accepted", true)

        Thread.sleep(2)  // allow register to take effect

        // --- Verify angle still works after configure ---
        val angle2 = as5600.angle()
        checkTrue("angle() after configure() in range", angle2 in 0.0..359.999)
    }

    println("===DONE: $passed passed, $failed failed===")
    System.exit(if (failed == 0) 0 else 1)
}
