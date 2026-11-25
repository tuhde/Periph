///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-kotlin:1.2.1

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.motor.DRV2605Full
import it.uhde.periph.chips.motor.DRV2605Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1

    I2CConnection(bus, DRV2605Minimal.DEFAULT_ADDRESS).use { connection ->  // open I²C bus, device address, (bus, address=0x5A) → I2CConnection
        val LRA = false                                                                             // set to true for a linear resonant actuator
        val haptic = DRV2605Full(connection, LRA)                                                   // construct driver and run init, (connection, lra=false) → DRV2605Full

        // --- Verify and calibrate the actuator ---
        // diagnose() reports a missing or shorted actuator; calibrate() measures the
        // back-EMF so the closed-loop engine matches this particular motor.
        println("actuator ok: " + haptic.diagnose())                                                // Run actuator diagnostics, () → boolean
        var lraFreq = 0.0
        if (LRA) {
            lraFreq = 175.0
        }
        val cal = haptic.calibrate(LRA, 3.0, 3.0, lraFreq)                                          // Run auto-calibration, (lra, ratedVoltage V, overdriveVoltage V, lraFreqHz Hz) → Calibration(ok, comp, bemf, bemfGain)
        println("calibration: " + cal)

        // --- Play the three click effects ---
        for (effect in intArrayOf(DRV2605Minimal.EFFECT_STRONG_CLICK, DRV2605Minimal.EFFECT_DOUBLE_CLICK, DRV2605Minimal.EFFECT_TRIPLE_CLICK)) {
            haptic.playEffect(effect)                                                               // Play a ROM effect, (effectId 1–123) → Unit
            haptic.waitDone(5000)                                                                   // Wait for playback, (timeoutMs) → boolean
            Thread.sleep(500)
        }

        // --- Heartbeat: two strong clicks 100 ms apart ---
        // The sequencer plays both effects and the delay without host involvement.
        haptic.playSequence(DRV2605Full.SequenceStep.effect(DRV2605Minimal.EFFECT_STRONG_CLICK), DRV2605Full.SequenceStep.delay(100), DRV2605Full.SequenceStep.effect(DRV2605Minimal.EFFECT_STRONG_CLICK))  // Play a sequence, (steps ≤ 8) → Unit
        haptic.waitDone(5000)                                                                       // Wait for playback, (timeoutMs) → boolean
        Thread.sleep(500)

        // --- Two-second amplitude ramp ---
        // Real-time playback streams the amplitude; VBAT and LRA_PERIOD are valid
        // while the actuator is driven.
        for (step in 0..20) {
            haptic.rtpLevel(step / 20.0)                                                            // Stream an RTP amplitude, (level 0.0–1.0) → Unit
            Thread.sleep(100)
            println("vdd " + haptic.readSupplyVoltage() + " V")                                     // Read supply voltage, () → double V
            if (LRA) {
                println("lra " + haptic.readLraFrequency())                                         // Read LRA frequency, () → Double? Hz (null if LRA_PERIOD is 0)
            }
        }
        haptic.stop()                                                                               // Cancel playback, () → Unit

        // --- Report latched faults ---
        println("status: " + haptic.readStatus())                                                   // Read status flags, () → Status(diagFailed, fbTimeout, overTemp, overCurrent)
    }
}
