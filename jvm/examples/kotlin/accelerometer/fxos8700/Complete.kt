///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-kotlin:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.accelerometer.FXOS8700Full
import it.uhde.periph.chips.accelerometer.FXOS8700Minimal

fun main() {
    val bus = System.getenv("I2C_BUS")?.toInt() ?: 1
    I2CConnection(bus, FXOS8700Minimal.I2C_ADDRESS).use { conn ->
        val chip = FXOS8700Full(conn)                           // Create FXOS8700 Full driver, (connection)
                                                           // verifies WHO_AM_I, resets and starts hybrid mode

        val accel = chip.readAccel()                          // Read acceleration, () → [x, y, z] g
                                                              // burst-reads 0x01-0x06, 14-bit left-justified
        println("accel " + accel.contentToString())
        val mag = chip.readMag()                              // Read magnetic field, () → [x, y, z] µT
                                                              // burst-reads 0x33-0x38, 0.1 µT/LSB
        println("mag " + mag.contentToString())
        val hybrid = chip.readHybrid()                        // Read both sensors, () → [ax, ay, az, mx, my, mz]
                                                              // one 12-byte burst from 0x01 (hyb_autoinc_mode)
        println("hybrid " + hybrid.contentToString())
        val rawAccel = chip.readRawAccel()                    // Read raw accelerometer, () → [x, y, z] counts
                                                              // signed 14-bit counts
        println("rawAccel " + rawAccel.contentToString())
        val rawMag = chip.readRawMag()                        // Read raw magnetometer, () → [x, y, z] counts
                                                              // signed 16-bit counts, 0.1 µT/LSB
        println("rawMag " + rawMag.contentToString())
        val temperature = chip.readTemperature()              // Read die temperature, () → °C
                                                              // int8(TEMP) × 0.96, relative only
        println("temperature " + temperature)
        val newAccel = chip.newAccelData()                    // Check accelerometer data ready, () → boolean
                                                              // STATUS.zyxdr
        println("newAccel " + newAccel)
        val newMag = chip.newMagData()                        // Check magnetometer data ready, () → boolean
                                                              // M_DR_STATUS.zyxdr
        println("newMag " + newMag)
        val status = chip.readStatus()                        // Read STATUS, () → int
                                                              // raw STATUS byte
        println("status " + status)
        val magStatus = chip.readMagStatus()                  // Read M_DR_STATUS, () → int
                                                              // raw M_DR_STATUS byte
        println("magStatus " + magStatus)
        val sysmod = chip.readSystemMode()                    // Read SYSMOD, () → int 0 standby | 1 wake | 2 sleep
                                                              // SYSMOD bits 1:0
        println("sysmod " + sysmod)

        chip.setMode(FXOS8700Full.MODE_HYBRID)                // Select operating mode, (mode 0|1|2) → void
                                                              // 0 accel only, 1 mag only, 2 hybrid
        chip.setRange(4)                                      // Set accelerometer range, (range_g 2|4|8) → void
                                                              // rewrites XYZ_DATA_CFG.fs and the cached sensitivity
        chip.setDataRate(200.0)                               // Set system data rate, (rate_hz 800..1.5625) → void
                                                              // each sensor runs at half this rate in hybrid mode
        chip.setAccelOversampling(0, 0)                       // Set accelerometer oversampling, (mode 0..3, sleep_mode -1..3) → void
                                                              // MODS / SMODS in CTRL_REG2
        chip.setMagOversampling(7, 7)                         // Set magnetometer oversampling, (ratio 0..7, sleep_ratio -1..7) → void
                                                              // m_os / m_aslp_os
        chip.setLowNoise(false)                               // Set low-noise mode, (enabled) → void
                                                              // lnoise limits the range to ±4 g
        chip.setResolution(14)                                // Set accelerometer resolution, (bits 14|8) → void
                                                              // 8 selects fast read (MSB only)
        chip.setHighPassOutput(false, 0)                      // Set high-pass output, (enabled, cutoff 0..3) → void
                                                              // hpf_out and sel
        chip.standby()                                        // Enter STANDBY, () → void
                                                              // polls SYSMOD until 0 (up to 300 ms)
        chip.activate()                                       // Enter ACTIVE, () → void
                                                              // waits the 2/ODR + 2 ms turn-on time

        chip.setAccelOffset(0.0, 0.0, 0.0)                    // Set accelerometer offsets, (x_g, y_g, z_g) → void
                                                              // OFF_X/Y/Z, 2 mg/LSB
        val accelOffset = chip.getAccelOffset()               // Get accelerometer offsets, () → [x, y, z] g
                                                              // signed 8-bit registers
        println("accelOffset " + accelOffset.contentToString())
        chip.setMagOffset(0.0, 0.0, 0.0)                      // Set hard-iron offsets, (x_ut, y_ut, z_ut) → void
                                                              // 15-bit values subtracted from the output
        val magOffset = chip.getMagOffset()                   // Get hard-iron offsets, () → [x, y, z] µT
                                                              // M_OFF_X/Y/Z
        println("magOffset " + magOffset.contentToString())
        chip.setMagRaw(false)                                 // Select raw magnetometer output, (raw) → void
                                                              // false applies the hard-iron offsets
        chip.setMagAutoCalibration(true)                      // Hardware hard-iron calibration, (enabled) → void
                                                              // MAX/MIN midpoint → M_OFF_* every ODR cycle
        Thread.sleep(1000)                                    // Wait, (ms) → void
                                                              // lets the min/max tracker collect data
        val minMax = chip.readMagMinMax()                     // Read tracked min / max, () → [minx, miny, minz, maxx, maxy, maxz] µT
                                                              // MIN_* / MAX_* registers
        println("minMax " + minMax.contentToString())
        chip.setMagAutoCalibration(false)                     // Hardware hard-iron calibration, (enabled) → void
                                                              // the offsets stay in M_OFF_*
        chip.resetMagMinMax()                                 // Reset min / max tracker, () → void
                                                              // m_maxmin_rst
        chip.setMagReset(0)                                   // Set automatic magnetic reset, (mode 0..3) → void
                                                              // 0 every ODR cycle, 3 disabled
        chip.triggerMagReset()                                // Trigger one-shot degauss, () → void
                                                              // m_rst
        val once = chip.measureMagOnce()                      // Measure once in STANDBY, () → [x, y, z] µT
                                                              // m_ost, restores ACTIVE
        println("once " + once.contentToString())
        val heading = chip.heading(2.5)                       // Compute tilt-compensated heading, (declination_deg) → degrees
                                                              // software compensation from one accel + one mag sample
        println("heading " + heading)

        chip.setFifo(FXOS8700Full.FIFO_CIRCULAR, 16)          // Configure FIFO, (mode 0..3, watermark) → void
                                                              // circular mode with a 16-sample watermark
        chip.setFifoTrigger(false, false, false, true)        // Select FIFO trigger sources, (transient, orientation, pulse, motion) → void
                                                              // TRIG_CFG
        chip.setFifoGate(false)                               // Set FIFO gate, (enabled) → void
                                                              // fifo_gate
        Thread.sleep(200)                                     // Wait, (ms) → void
                                                              // lets samples accumulate
        val level = chip.fifoLevel()                          // Read FIFO level, () → int
                                                              // f_cnt, 0-32
        println("level " + level)
        val fifoStatus = chip.readFifoStatus()                // Read FIFO status, () → [overflow, watermark, count]
                                                              // decodes F_STATUS
        println("fifoStatus " + fifoStatus.contentToString())
        val samples = chip.readFifo(4)                        // Drain FIFO, (count) → [[x, y, z] g, ...]
                                                              // one 6-byte transaction per sample
        println("samples " + samples.contentDeepToString())
        chip.setFifo(FXOS8700Full.FIFO_DISABLED, 0)           // Disable FIFO, (mode 0, watermark 0) → void
                                                              // FIFO is flushed

        chip.setFreefallMotion(true, 7, 0.3, 60.0, true, false) // Configure freefall / motion, (motion, axes, threshold_g, debounce_ms, latch, debounce_clear) → void
                                                                // A_FFMT_CFG / THS / COUNT
        val motion = chip.readMotionSource()                  // Read A_FFMT_SRC, () → int
                                                              // clears the flags when latched
        println("motion " + motion)
        chip.setTransient(7, 0.4, 20.0, false, false)         // Configure transient detector, (axes, threshold_g, debounce_ms, bypass_hpf, latch) → void
                                                              // TRANSIENT_CFG / THS / COUNT
        val transientSrc = chip.readTransientSource()            // Read TRANSIENT_SRC, () → int
                                                              // clears the interrupt
        println("transientSrc " + transientSrc)
        chip.setPulse(0x04, 0x00)                             // Configure tap detection, (single, double_tap) → void
                                                              // single tap on Z with default thresholds
        val pulse = chip.readPulseSource()                    // Read PULSE_SRC, () → int
                                                              // clears the interrupt
        println("pulse " + pulse)
        chip.setOrientation(true, 100.0, 45, 4, 4, 2)         // Configure orientation detection, (enabled, debounce_ms, threshold_deg, hysteresis, z_lock, back_front) → void
                                                              // PL_CFG / COUNT / THS / BF_ZCOMP
        val orientation = chip.readOrientation()              // Read orientation, () → [changed, lockout, portrait_landscape, back]
                                                              // clears newlp
        println("orientation " + orientation.contentToString())
        chip.setAutoSleep(true, 12.5, 10.0, FXOS8700Full.SOURCE_FF_MT) // Configure Auto-SLEEP, (enabled, sleep_rate_hz, timeout_s, wake_sources) → void
                                                                       // sleeps after 10 s of inactivity, motion wakes

        chip.setInterruptPins(false, false)                   // Configure INT pins, (active_high, open_drain) → void
                                                              // ipol / pp_od
        chip.enableInterrupt(FXOS8700Full.SOURCE_FF_MT or FXOS8700Full.SOURCE_ORIENTATION, 1) // Enable and route interrupt, (source mask, pin 1|2) → void
                                                                                             // CTRL_REG4 enable, CTRL_REG5 routing
        val pending = chip.pollInterrupt()                    // Poll and clear interrupt sources, () → int
                                                              // reads INT_SOURCE and the matching source registers
        println("pending " + pending)
        chip.disableInterrupt(FXOS8700Full.SOURCE_FF_MT or FXOS8700Full.SOURCE_ORIENTATION) // Disable interrupt sources, (source mask) → void
                                                                                           // clears int_en_*

        val reg = chip.readRegister(0x5F, 1)                  // Read raw registers, (reg, length) → bytes
                                                              // A_VECM_CFG, not wrapped by the API
        println("reg " + reg.contentToString())
        chip.writeRegister(0x5F, reg)                         // Write raw registers, (reg, data) → void
                                                              // no STANDBY handling
        val selfTest = chip.selfTest()                        // Run accelerometer self-test, () → [ok, dx, dy, dz]
                                                              // electrostatic test, checks X ≥ 192, Y ≥ 270, Z ≥ 1275
        println("selfTest " + selfTest.contentToString())
        chip.reset()                                          // Software reset, () → void
                                                              // the NACK on the write is tolerated; call init() afterwards
        chip.init()                                           // Re-initialise, () → void
                                                              // verifies WHO_AM_I and restarts hybrid mode
    }
}
