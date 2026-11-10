package it.uhde.periph.chips.power

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import java.util.function.IntConsumer

/**
 * AXP2101 — single-cell Li-ion NVDC power-management IC (X-Powers) — full interface.
 *
 * Extends [AXP2101Minimal] with the remaining ADC channels, charger, battery-temperature, power
 * rail, power-key, sleep, watchdog, fuel-gauge and interrupt control. Setters validate their
 * argument and throw [IllegalArgumentException] for a value the chip cannot represent.
 *
 * Dangerous operations: disabling or re-voltaging the rail that powers the host (commonly DCDC1
 * or DCDC3), [powerOff], the power-off thresholds, charge voltage / current beyond the cell's
 * limits and [setBatfetOffEnable] can power the board down or damage the battery.
 *
 * [onInterrupt] uses `connection.intPin()` if wired (falling edge), otherwise a 5 ms polling
 * thread.
 */
class AXP2101Full(connection: RegisterConnection) : AXP2101Minimal(connection) {

    companion object {
        /** Battery under-temperature while discharging. */
        const val SOURCE_BAT_UNDER_TEMP_WORK = 0x000001
        /** Battery over-temperature while discharging. */
        const val SOURCE_BAT_OVER_TEMP_WORK = 0x000002
        /** Battery under-temperature while charging. */
        const val SOURCE_BAT_UNDER_TEMP_CHG = 0x000004
        /** Battery over-temperature while charging. */
        const val SOURCE_BAT_OVER_TEMP_CHG = 0x000008
        /** Gauge new state of charge. */
        const val SOURCE_GAUGE_NEW_SOC = 0x000010
        /** Gauge watchdog timeout. */
        const val SOURCE_GAUGE_WDT = 0x000020
        /** State of charge at warning level 1 / shutdown level. */
        const val SOURCE_SOC_WARN_L1 = 0x000040
        /** State of charge at warning level 2. */
        const val SOURCE_SOC_WARN_L2 = 0x000080
        /** Power key released. */
        const val SOURCE_PWRON_POS_EDGE = 0x000100
        /** Power key pressed. */
        const val SOURCE_PWRON_NEG_EDGE = 0x000200
        /** Power key long press. */
        const val SOURCE_PWRON_LONG = 0x000400
        /** Power key short press. */
        const val SOURCE_PWRON_SHORT = 0x000800
        /** Battery removed. */
        const val SOURCE_BAT_REMOVE = 0x001000
        /** Battery inserted. */
        const val SOURCE_BAT_INSERT = 0x002000
        /** VBUS removed. */
        const val SOURCE_VBUS_REMOVE = 0x004000
        /** VBUS inserted. */
        const val SOURCE_VBUS_INSERT = 0x008000
        /** Battery over-voltage protection. */
        const val SOURCE_BAT_OVER_VOLTAGE = 0x010000
        /** Charger safety timer expired. */
        const val SOURCE_CHARGER_TIMER = 0x020000
        /** Die over-temperature level 1. */
        const val SOURCE_DIE_OVER_TEMP_L1 = 0x040000
        /** Charging started. */
        const val SOURCE_CHARGE_START = 0x080000
        /** Charging done. */
        const val SOURCE_CHARGE_DONE = 0x100000
        /** LDO over-current. */
        const val SOURCE_LDO_OVER_CURRENT = 0x400000
        /** Watchdog expired. */
        const val SOURCE_WATCHDOG_EXPIRE = 0x800000

        // name -> {enable register, enable bit, voltage register, kind, voltage mask}
        // kind: 0 dcdc1, 1 dcdc2/3, 2 dcdc4, 3 ldo 100 mV, 4 ldo 50 mV, 5 dldo1
        private val RAILS: Map<String, IntArray> = mapOf(
            "dcdc1" to intArrayOf(0x80, 0, 0x82, 0, 0x1F),
            "dcdc2" to intArrayOf(0x80, 1, 0x83, 1, 0x7F),
            "dcdc3" to intArrayOf(0x80, 2, 0x84, 1, 0x7F),
            "dcdc4" to intArrayOf(0x80, 3, 0x85, 2, 0x7F),
            "aldo1" to intArrayOf(0x90, 0, 0x92, 3, 0x1F),
            "aldo2" to intArrayOf(0x90, 1, 0x93, 3, 0x1F),
            "aldo3" to intArrayOf(0x90, 2, 0x94, 3, 0x1F),
            "aldo4" to intArrayOf(0x90, 3, 0x95, 3, 0x1F),
            "bldo1" to intArrayOf(0x90, 4, 0x96, 3, 0x1F),
            "bldo2" to intArrayOf(0x90, 5, 0x97, 3, 0x1F),
            "cpusldo" to intArrayOf(0x90, 6, 0x98, 4, 0x1F),
            "dldo1" to intArrayOf(0x90, 7, 0x99, 5, 0x1F),
            "dldo2" to intArrayOf(0x91, 0, 0x9A, 4, 0x1F))

        // rail -> {register offset from 0x28, shift}
        private val FAST_RAILS: Map<String, IntArray> = mapOf(
            "dcdc1" to intArrayOf(0, 0), "dcdc2" to intArrayOf(0, 2),
            "dcdc3" to intArrayOf(0, 4), "dcdc4" to intArrayOf(0, 6),
            "aldo1" to intArrayOf(1, 2), "aldo2" to intArrayOf(1, 4),
            "aldo3" to intArrayOf(1, 6), "aldo4" to intArrayOf(2, 0),
            "bldo1" to intArrayOf(2, 2), "bldo2" to intArrayOf(2, 4),
            "cpusldo" to intArrayOf(2, 6),
            "dldo1" to intArrayOf(3, 0), "dldo2" to intArrayOf(3, 2))

        private fun round(x: Double): Int = Math.floor(x + 0.5).toInt()

        private fun stepCode(value: Double, start: Double, step: Double, maxCode: Int, what: String): Int {
            val code = round((value - start) / step)
            require(code in 0..maxCode && Math.abs(value - (start + code * step)) <= step / 2 + 1e-9) {
                "$what out of range: $value"
            }
            return code
        }

        private fun indexOf(value: Double, keys: DoubleArray, what: String): Int {
            for (i in keys.indices) {
                if (Math.abs(keys[i] - value) < 1e-6) return i
            }
            throw IllegalArgumentException("$what unsupported value: $value")
        }

        private fun bit(on: Boolean, b: Int): Int = if (on) b else 0

        private fun railInfo(name: String): IntArray =
            RAILS[name] ?: throw IllegalArgumentException("unknown rail: $name")

        private fun railCode(kind: Int, volts: Double, name: String): Int {
            val what = "$name voltage"
            return when (kind) {
                0 -> stepCode(volts, 1.5, 0.1, 19, what)
                3 -> stepCode(volts, 0.5, 0.1, 30, what)
                4 -> stepCode(volts, 0.5, 0.05, 19, what)
                5 -> stepCode(volts, 0.5, 0.1, 28, what)
                else -> {
                    val top = if (kind == 1) 87 else 102
                    if (volts <= 1.21) stepCode(volts, 0.5, 0.01, 70, what)
                    else 71 + stepCode(volts, 1.22, 0.02, top - 71, what)
                }
            }
        }

        private fun railVolts(kind: Int, code: Int): Double = when (kind) {
            0 -> 1.5 + code * 0.1
            3, 5 -> 0.5 + code * 0.1
            4 -> 0.5 + code * 0.05
            else -> if (code <= 70) 0.5 + code * 0.01 else 1.22 + (code - 71) * 0.02
        }
    }

    /**
     * Every measurement returned by [readAll].
     *
     * @property batteryVoltage battery voltage in V
     * @property vbusVoltage VBUS voltage in V
     * @property systemVoltage system voltage in V
     * @property tsVoltage TS pin voltage in V
     * @property dieTemperature die temperature in °C
     * @property batteryPercent fuel-gauge state of charge, 0–100 %
     * @property chargeState one of the `CHARGE_*` constants
     * @property vbusPresent `true` when a good input source is connected
     */
    data class Readings(
        val batteryVoltage: Double, val vbusVoltage: Double, val systemVoltage: Double,
        val tsVoltage: Double, val dieTemperature: Double, val batteryPercent: Int,
        val chargeState: Int, val vbusPresent: Boolean
    )

    /**
     * Decoded STATUS1 / STATUS2 returned by [status].
     *
     * @property batteryDirection `"standby"`, `"charge"`, `"discharge"` or `"reserved"`
     */
    data class Status(
        val vbusGood: Boolean, val batfetOpen: Boolean, val batteryPresent: Boolean,
        val batteryActive: Boolean, val thermalRegulation: Boolean, val currentLimit: Boolean,
        val batteryDirection: String, val systemOn: Boolean, val vindpm: Boolean,
        val chargeState: Int
    )

    /**
     * State of one power rail returned by [getRail].
     *
     * @property enabled whether the rail is enabled
     * @property volts programmed voltage in V
     */
    data class Rail(val enabled: Boolean, val volts: Double)

    private var callback: IntConsumer? = null
    private var subscribedPin: InputPin? = null
    @Volatile private var polling = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { handleEdge() }

    // --- Measurements -----------------------------------------------------

    /**
     * Read the VBUS voltage.
     *
     * @return VBUS voltage in V
     */
    fun vbusVoltage(): Double = adc14(REG_VBUS_H) * 0.001

    /**
     * Read the system (VSYS) voltage.
     *
     * @return VSYS voltage in V
     */
    fun systemVoltage(): Double = adc14(REG_VSYS_H) * 0.001

    /**
     * Read the TS pin voltage.
     *
     * @return TS voltage in V (0.5 mV / LSB)
     */
    fun tsVoltage(): Double = adc14(REG_TS_H) * 0.0005

    /**
     * Read the die temperature using the vendor XPowersLib formula (not from the datasheet;
     * verify on hardware).
     *
     * @return die temperature in °C
     */
    fun dieTemperature(): Double = 22 + (7274 - adc14(REG_TDIE_H)) / 20.0

    /**
     * Read every measurement.
     *
     * @return all measurements
     */
    fun readAll(): Readings = Readings(batteryVoltage(), vbusVoltage(), systemVoltage(), tsVoltage(),
        dieTemperature(), batteryPercent(), chargeState(), vbusPresent())

    /**
     * Decode STATUS1 and STATUS2.
     *
     * @return the decoded flags
     */
    fun status(): Status {
        val s1 = readReg(REG_STATUS1)
        val s2 = readReg(REG_STATUS2)
        val directions = arrayOf("standby", "charge", "discharge", "reserved")
        return Status((s1 and 0x20) != 0, (s1 and 0x10) != 0, (s1 and 0x08) != 0, (s1 and 0x04) != 0,
            (s1 and 0x02) != 0, (s1 and 0x01) != 0, directions[(s2 shr 5) and 3],
            (s2 and 0x10) != 0, (s2 and 0x08) != 0, s2 and 0x07)
    }

    // --- ADC --------------------------------------------------------------

    /**
     * Select the enabled ADC channels (ADC_ENABLE).
     *
     * @param vbat battery voltage channel
     * @param ts TS pin channel
     * @param vbus VBUS voltage channel
     * @param vsys system voltage channel
     * @param tdie die temperature channel
     * @param gpadc general-purpose ADC channel
     */
    fun setAdcChannels(vbat: Boolean, ts: Boolean, vbus: Boolean, vsys: Boolean, tdie: Boolean, gpadc: Boolean) {
        rmw(REG_ADC_ENABLE, 0x3F, bit(vbat, 1) or bit(ts, 2) or bit(vbus, 4) or bit(vsys, 8) or
            bit(tdie, 16) or bit(gpadc, 32))
    }

    /**
     * Select slow (64 s) ADC sampling when powered off on battery only (TS_H bit 7).
     *
     * @param enabled `true` for low-frequency sampling
     */
    fun setAdcLowFrequency(enabled: Boolean) = rmw(REG_TS_H, 0x80, bit(enabled, 0x80))

    // --- Charger ----------------------------------------------------------

    /**
     * Enable or disable main-cell charging (MODULE_ENABLE bit 1).
     *
     * @param enabled `true` to charge
     */
    fun setCharging(enabled: Boolean) = rmw(REG_MODULE_ENABLE, 0x02, bit(enabled, 0x02))

    /**
     * Set the charge target voltage.
     *
     * @param volts 4.0, 4.1, 4.2, 4.35 or 4.4 V (the last two only for high-voltage cells)
     * @throws IllegalArgumentException for any other value
     */
    fun setChargeVoltage(volts: Double) {
        val i = indexOf(volts, doubleArrayOf(4.0, 4.1, 4.2, 4.35, 4.4), "charge voltage")
        rmw(REG_CV, 0x07, i + 1)
    }

    /**
     * Set the constant-current charge current: 0–0.2 A in 25 mA steps, 0.2–1.5 A in 100 mA
     * steps, capped by the limit of the currently programmed charge voltage.
     *
     * @param amps charge current in A
     * @throws IllegalArgumentException out of range or above the charge-voltage limit
     */
    fun setChargeCurrent(amps: Double) {
        require(amps >= 0 && amps <= 1.5 + 1e-9) { "charge current out of range: $amps" }
        val cv = readReg(REG_CV) and 0x07
        val limit = when (cv) { 3 -> 1.4; 4 -> 1.1; 5 -> 1.0; else -> 1.5 }
        require(amps <= limit + 1e-9) { "charge current $amps A exceeds $limit A limit for this charge voltage" }
        val code = if (amps <= 0.25) Math.min(8, round(amps / 0.025)) else 8 + round((amps - 0.2) / 0.1)
        rmw(REG_ICC, 0x1F, code)
    }

    /**
     * Set the pre-charge current.
     *
     * @param amps 0–0.2 A in 25 mA steps
     */
    fun setPrechargeCurrent(amps: Double) = rmw(REG_IPRECHG, 0x0F, stepCode(amps, 0.0, 0.025, 8, "precharge current"))

    /**
     * Set the charge termination current.
     *
     * @param amps 0–0.2 A in 25 mA steps
     * @param enabled termination enable
     */
    @JvmOverloads
    fun setTermination(amps: Double, enabled: Boolean = true) =
        rmw(REG_ITERM, 0x1F, stepCode(amps, 0.0, 0.025, 8, "termination current") or bit(enabled, 0x10))

    /**
     * Set the input current limit.
     *
     * @param amps 0.1, 0.5, 0.9, 1.0, 1.5 or 2.0 A
     */
    fun setInputCurrentLimit(amps: Double) =
        rmw(REG_IINLIM, 0x07, indexOf(amps, doubleArrayOf(0.1, 0.5, 0.9, 1.0, 1.5, 2.0), "input current limit"))

    /**
     * Set the input voltage limit (VINDPM).
     *
     * @param volts 3.88–5.08 V in 80 mV steps
     */
    fun setInputVoltageLimit(volts: Double) = rmw(REG_VINDPM, 0x0F, stepCode(volts, 3.88, 0.08, 15, "input voltage limit"))

    /**
     * Set the minimum system voltage.
     *
     * @param volts 3.2–3.9 V in 100 mV steps
     */
    fun setMinSystemVoltage(volts: Double) = rmw(REG_MIN_VSYS, 0x07, stepCode(volts, 3.2, 0.1, 7, "minimum system voltage"))

    /**
     * Configure the charger safety timers (CHG_TIMER).
     *
     * @param prechargeMin pre-charge timer, 40 / 50 / 60 / 70 min
     * @param doneHours charge-done timer, 5 / 8 / 12 / 20 h
     * @param prechargeEnabled pre-charge timer enable
     * @param doneEnabled charge-done timer enable
     * @param slowInDpm half-speed timers during input DPM / thermal regulation
     */
    @JvmOverloads
    fun setChargeTimers(prechargeMin: Int = 60, doneHours: Int = 12, prechargeEnabled: Boolean = true,
                        doneEnabled: Boolean = true, slowInDpm: Boolean = true) {
        val pre = indexOf(prechargeMin.toDouble(), doubleArrayOf(40.0, 50.0, 60.0, 70.0), "precharge timer")
        val done = indexOf(doneHours.toDouble(), doubleArrayOf(5.0, 8.0, 12.0, 20.0), "done timer")
        rmw(REG_CHG_TIMER, 0xF7, pre or bit(prechargeEnabled, 0x04) or (done shl 4) or
            bit(doneEnabled, 0x40) or bit(slowInDpm, 0x80))
    }

    /**
     * Set the charger thermal-regulation threshold.
     *
     * @param celsius 60, 80, 100 or 120 °C
     */
    fun setThermalRegulation(celsius: Int) =
        rmw(REG_THERMAL_REG, 0x03, indexOf(celsius.toDouble(), doubleArrayOf(60.0, 80.0, 100.0, 120.0), "thermal regulation"))

    /**
     * Configure die over-temperature level 1 (DIE_TEMP_CTRL).
     *
     * @param level1Celsius 115, 125 or 135 °C
     * @param detect die temperature detection enable
     */
    @JvmOverloads
    fun setDieProtection(level1Celsius: Int = 125, detect: Boolean = true) {
        val code = indexOf(level1Celsius.toDouble(), doubleArrayOf(115.0, 125.0, 135.0), "die protection")
        rmw(REG_DIE_TEMP_CTRL, 0x07, (code shl 1) or bit(detect, 1))
    }

    /**
     * Enable or disable battery detection (BAT_DETECT).
     *
     * @param enabled `true` to enable
     */
    fun setBatteryDetection(enabled: Boolean) = rmw(REG_BAT_DETECT, 0x01, bit(enabled, 1))

    /**
     * Configure charging of the backup (coin) cell.
     *
     * @param enabled `true` to charge the backup cell
     * @param volts termination voltage, 2.6–3.3 V in 100 mV steps
     */
    @JvmOverloads
    fun setBackupCharging(enabled: Boolean, volts: Double = 2.9) {
        val code = stepCode(volts, 2.6, 0.1, 7, "backup charge voltage")
        rmw(REG_BTN_BAT_VTERM, 0x07, code)
        rmw(REG_MODULE_ENABLE, 0x04, bit(enabled, 0x04))
    }

    /**
     * Configure the CHGLED pin.
     *
     * @param mode `"off"`, `"type_a"`, `"type_b"` or `"manual"`
     * @param output for `"manual"`: `"hiz"`, `"blink_1hz"`, `"blink_4hz"` or `"low"`
     */
    @JvmOverloads
    fun setChgled(mode: String = "type_a", output: String = "hiz") {
        var value = when (mode) {
            "off" -> 0x00
            "type_a" -> 0x01
            "type_b" -> 0x03
            "manual" -> 0x05
            else -> throw IllegalArgumentException("unknown CHGLED mode: $mode")
        }
        val out = when (output) {
            "hiz" -> 0
            "blink_1hz" -> 1
            "blink_4hz" -> 2
            "low" -> 3
            else -> throw IllegalArgumentException("unknown CHGLED output: $output")
        }
        if (mode == "manual") value = value or (out shl 4)
        rmw(REG_CHGLED_CTRL, 0x37, value)
    }

    // --- Battery temperature ----------------------------------------------

    /**
     * Configure the TS pin (TS_CTRL).
     *
     * @param batterySensor `true` for the battery thermistor, `false` for an external input
     * @param currentUa injected current, 20 / 40 / 50 / 60 µA
     * @param source current-source mode: `"off"`, `"when_adc"`, `"when_ts"` or `"always"`
     */
    @JvmOverloads
    fun configureTs(batterySensor: Boolean = true, currentUa: Int = 50, source: String = "when_adc") {
        val s = when (source) {
            "off" -> 0
            "when_adc" -> 1
            "when_ts" -> 2
            "always" -> 3
            else -> throw IllegalArgumentException("unknown TS source: $source")
        }
        val cur = indexOf(currentUa.toDouble(), doubleArrayOf(20.0, 40.0, 50.0, 60.0), "TS current")
        rmw(REG_TS_CTRL, 0x1F, cur or (s shl 2) or bit(!batterySensor, 0x10))
    }

    /**
     * Set the battery-temperature limits as TS pin voltages.
     *
     * @param chargeCold charge low-temperature limit in V (32 mV steps)
     * @param chargeHot charge high-temperature limit in V (2 mV steps)
     * @param workCold discharge low-temperature limit in V (32 mV steps)
     * @param workHot discharge high-temperature limit in V (2 mV steps)
     */
    fun setTsLimits(chargeCold: Double, chargeHot: Double, workCold: Double, workHot: Double) {
        val a = stepCode(chargeCold, 0.0, 0.032, 255, "charge cold limit")
        val b = stepCode(chargeHot, 0.0, 0.002, 255, "charge hot limit")
        val c = stepCode(workCold, 0.0, 0.032, 255, "work cold limit")
        val d = stepCode(workHot, 0.0, 0.002, 255, "work hot limit")
        writeReg(REG_VLTF_CHG, a)
        writeReg(REG_VHTF_CHG, b)
        writeReg(REG_VLTF_WORK, c)
        writeReg(REG_VHTF_WORK, d)
    }

    /**
     * Set the TS threshold hysteresis.
     *
     * @param lowToNormal low to normal hysteresis in V (16 mV steps)
     * @param highToNormal high to normal hysteresis in V (4 mV steps)
     */
    @JvmOverloads
    fun setTsHysteresis(lowToNormal: Double = 0.032, highToNormal: Double = 0.004) {
        val l2h = stepCode(lowToNormal, 0.0, 0.016, 255, "TS low hysteresis")
        val h2l = stepCode(highToNormal, 0.0, 0.004, 255, "TS high hysteresis")
        writeReg(REG_TS_HYS_L2H, l2h)
        writeReg(REG_TS_HYS_H2L, h2l)
    }

    /**
     * Configure the JEITA charging profile.
     *
     * @param enabled JEITA enable
     * @param coolVoltage cool threshold T2 as TS voltage in V (16 mV steps), or `null` to keep it
     * @param warmVoltage warm threshold T3 as TS voltage in V (8 mV steps), or `null` to keep it
     * @param coolHalfCurrent halve the charge current in the cool range
     * @param warmHalfCurrent halve the charge current in the warm range
     * @param coolVoltageDrop charge-voltage gears below the setting when cool, 0–2
     * @param warmVoltageDrop charge-voltage gears below the setting when warm, 0–2
     */
    @JvmOverloads
    fun configureJeita(enabled: Boolean, coolVoltage: Double? = null, warmVoltage: Double? = null,
                       coolHalfCurrent: Boolean = true, warmHalfCurrent: Boolean = false,
                       coolVoltageDrop: Int = 0, warmVoltageDrop: Int = 1) {
        require(coolVoltageDrop in 0..2 && warmVoltageDrop in 0..2) { "JEITA voltage drop must be 0-2" }
        val cool = if (coolVoltage == null) -1 else stepCode(coolVoltage, 0.0, 0.016, 255, "JEITA cool")
        val warm = if (warmVoltage == null) -1 else stepCode(warmVoltage, 0.0, 0.008, 255, "JEITA warm")
        rmw(REG_JEITA_CV, 0x5F, coolVoltageDrop or (warmVoltageDrop shl 2) or bit(coolHalfCurrent, 0x10) or
            bit(warmHalfCurrent, 0x40))
        if (cool >= 0) writeReg(REG_JEITA_COOL, cool)
        if (warm >= 0) writeReg(REG_JEITA_WARM, warm)
        rmw(REG_JEITA_EN, 0x01, bit(enabled, 1))
    }

    // --- Power rails ------------------------------------------------------

    /**
     * Read the state of a power rail.
     *
     * @param name `"dcdc1"`..`"dcdc4"`, `"aldo1"`..`"aldo4"`, `"bldo1"`, `"bldo2"`, `"cpusldo"`,
     *             `"dldo1"` or `"dldo2"`
     * @return enable state and programmed voltage
     */
    fun getRail(name: String): Rail {
        val r = railInfo(name)
        val enabled = (readReg(r[0]) and (1 shl r[1])) != 0
        return Rail(enabled, railVolts(r[3], readReg(r[2]) and r[4]))
    }

    /**
     * Set a rail voltage, rounded to the nearest step inside the rail's range.
     *
     * @param name rail name as for [getRail]
     * @param volts target voltage in V; outside the rail's range is rejected
     * @throws IllegalArgumentException for an unknown rail or an out-of-range voltage
     */
    fun setRailVoltage(name: String, volts: Double) {
        val r = railInfo(name)
        rmw(r[2], r[4], railCode(r[3], volts, name))
    }

    /**
     * Enable or disable a rail. Disabling the rail that powers the host cuts the host's power.
     *
     * @param name rail name as for [getRail]
     * @param enabled `true` to enable
     */
    fun setRailEnabled(name: String, enabled: Boolean) {
        val r = railInfo(name)
        rmw(r[0], 1 shl r[1], bit(enabled, 1 shl r[1]))
    }

    /**
     * Configure one DCDC converter.
     *
     * @param name `"dcdc1"`..`"dcdc4"`
     * @param alwaysPwm force PWM instead of automatic PWM/PFM
     * @param dvm dynamic voltage scaling for dcdc2 / dcdc3, or `null` to keep it
     */
    @JvmOverloads
    fun configureDcdc(name: String, alwaysPwm: Boolean = false, dvm: Boolean? = null) {
        val r = railInfo(name)
        require(r[0] == 0x80) { "not a DCDC: $name" }
        require(dvm == null || name == "dcdc2" || name == "dcdc3") { "DVM is only available on dcdc2 / dcdc3" }
        val b = 1 shl (r[1] + 2)
        rmw(REG_DCDC_PWM, b, bit(alwaysPwm, b))
        if (dvm != null) rmw(r[2], 0x80, bit(dvm, 0x80))
    }

    /**
     * Configure settings shared by all DCDC converters.
     *
     * @param forceCcm force continuous conduction mode
     * @param slowRamp 31.25 µs per DVM step instead of 15.625 µs
     * @param frequencySpread enable switching-frequency spread
     * @param spread100khz spread range 100 kHz instead of 50 kHz
     * @param uvpDebounceUs under-voltage debounce, 60 / 120 / 180 / 240 µs
     */
    @JvmOverloads
    fun configureDcdcGlobal(forceCcm: Boolean = false, slowRamp: Boolean = false, frequencySpread: Boolean = false,
                            spread100khz: Boolean = false, uvpDebounceUs: Int = 60) {
        val deb = indexOf(uvpDebounceUs.toDouble(), doubleArrayOf(60.0, 120.0, 180.0, 240.0), "UVP debounce")
        rmw(REG_DCDC_EN, 0x60, bit(forceCcm, 0x40) or bit(slowRamp, 0x20))
        rmw(REG_DCDC_PWM, 0xC3, bit(frequencySpread, 0x80) or bit(spread100khz, 0x40) or deb)
    }

    // --- System control ---------------------------------------------------

    /**
     * Enable output discharge when a rail is off (COMMON_CONFIG bit 5).
     *
     * @param enabled `true` to enable
     */
    fun setOffDischarge(enabled: Boolean) = rmw(REG_COMMON_CONFIG, 0x20, bit(enabled, 0x20))

    /** Soft power-off: every output except RTCLDO turns off. */
    fun powerOff() = command(REG_COMMON_CONFIG, 0x01)

    /** Restart the system (power-off / power-on). */
    fun restart() = command(REG_COMMON_CONFIG, 0x02)

    /** Enter sleep; the host disables / re-voltages rails first. */
    fun sleep() = command(REG_SLEEP_CTRL, 0x01)

    /** Wake up from sleep. */
    fun wakeup() = command(REG_SLEEP_CTRL, 0x02)

    /**
     * Configure sleep / wake-up behaviour (SLEEP_CTRL).
     *
     * @param wakeOnIrq IRQ pin held low for more than 16 ms wakes the PMU
     * @param pwrokLow PWROK low during wake-up
     * @param restoreVoltages wake with the voltages from before sleep
     */
    @JvmOverloads
    fun configureSleep(wakeOnIrq: Boolean = false, pwrokLow: Boolean = true, restoreVoltages: Boolean = false) =
        rmw(REG_SLEEP_CTRL, 0x1C, bit(wakeOnIrq, 0x10) or bit(pwrokLow, 0x08) or bit(restoreVoltages, 0x04))

    /**
     * Read the power-on source flags.
     *
     * @return PWRON_STATUS (bit 0 key, 1 IRQ pin, 2 VBUS, 3 battery > 3.3 V, 4 battery inserted, 5 EN mode)
     */
    fun powerOnSource(): Int = readReg(REG_PWRON_STATUS)

    /**
     * Read the power-off source flags.
     *
     * @return PWROFF_STATUS (bit 0 key, 1 software, 2 EN low, 3 VSYS UV, 4 VBUS OV, 5 DCDC UV, 6 DCDC OV, 7 die over-temperature)
     */
    fun powerOffSource(): Int = readReg(REG_PWROFF_STATUS)

    /**
     * Configure the power-key timing and actions.
     *
     * @param onMs hold time to power on, 128 / 512 / 1000 / 2000 ms
     * @param offS hold time to power off, 4 / 6 / 8 / 10 s
     * @param irqS hold time for the long-press IRQ, 1 / 1.5 / 2 / 2.5 s
     * @param longPressOff power off when the key is held for [offS]
     * @param restartInstead restart instead of powering off
     * @param forceOff16s holding the key for 16 s shuts the PMIC down
     */
    @JvmOverloads
    fun configurePowerKey(onMs: Int = 512, offS: Double = 6.0, irqS: Double = 1.5, longPressOff: Boolean = true,
                          restartInstead: Boolean = false, forceOff16s: Boolean = false) {
        val on = indexOf(onMs.toDouble(), doubleArrayOf(128.0, 512.0, 1000.0, 2000.0), "power key on time")
        val off = indexOf(offS, doubleArrayOf(4.0, 6.0, 8.0, 10.0), "power key off time")
        val irq = indexOf(irqS, doubleArrayOf(1.0, 1.5, 2.0, 2.5), "power key irq time")
        rmw(REG_PWRKEY_LEVEL, 0x3F, on or (off shl 2) or (irq shl 4))
        rmw(REG_PWROFF_EN, 0x03, bit(longPressOff, 0x02) or bit(restartInstead, 0x01))
        rmw(REG_COMMON_CONFIG, 0x04, bit(forceOff16s, 0x04))
    }

    /**
     * Configure the power-off sources and thresholds.
     *
     * @param dieOverTemp power off on die over-temperature level 2 (145 °C)
     * @param offVoltage system power-off threshold, 2.6–3.3 V in 100 mV steps
     * @param dcdcOvp DCDC over-voltage turns the PMIC off
     * @param dcdcUvp mask of DCDC1–4 whose under-voltage turns the PMIC off (bits 0–3)
     */
    @JvmOverloads
    fun configurePowerOff(dieOverTemp: Boolean = true, offVoltage: Double = 2.6, dcdcOvp: Boolean = true,
                          dcdcUvp: Int = 0x0F) {
        val code = stepCode(offVoltage, 2.6, 0.1, 7, "power-off voltage")
        rmw(REG_PWROFF_EN, 0x04, bit(dieOverTemp, 0x04))
        rmw(REG_VOFF, 0x07, code)
        rmw(REG_DCDC_PROT, 0x2F, bit(dcdcOvp, 0x20) or (dcdcUvp and 0x0F))
    }

    /**
     * Configure the PWROK output.
     *
     * @param check check PWROK after all outputs are valid
     * @param delayMs PWROK delay, 8 / 16 / 32 / 64 ms
     * @param offDelay 4 ms power-off delay after PWROK disable
     * @param reverseOffSequence power off in reverse start-up order
     * @param restartOnLow PWROK pulled low restarts the system
     */
    @JvmOverloads
    fun configurePwrok(check: Boolean = true, delayMs: Int = 8, offDelay: Boolean = true,
                       reverseOffSequence: Boolean = false, restartOnLow: Boolean = false) {
        val delay = indexOf(delayMs.toDouble(), doubleArrayOf(8.0, 16.0, 32.0, 64.0), "PWROK delay")
        rmw(REG_PWROK_CTRL, 0x1F, delay or bit(reverseOffSequence, 0x04) or bit(offDelay, 0x08) or bit(check, 0x10))
        rmw(REG_COMMON_CONFIG, 0x08, bit(restartOnLow, 0x08))
    }

    /**
     * Turn the BATFET on when powered off on battery only (BATFET_CTRL bit 3).
     *
     * @param enabled `true` to enable
     */
    fun setBatfetOffEnable(enabled: Boolean) = rmw(REG_BATFET_CTRL, 0x08, bit(enabled, 0x08))

    /**
     * Configure fast power-on / wake-up and, optionally, the per-rail start sequence.
     *
     * @param poweron fast power-on enable
     * @param wakeup fast wake-up enable
     * @param sequence rail name to start-sequence code 0–2 (a `null` value disables the rail),
     *                 or `null` to leave the sequence untouched
     */
    @JvmOverloads
    fun configureFastPoweron(poweron: Boolean, wakeup: Boolean, sequence: Map<String, Int?>? = null) {
        if (sequence != null) {
            for ((name, code) in sequence) {
                require(FAST_RAILS.containsKey(name)) { "unknown rail: $name" }
                require(code == null || code in 0..2) { "start-sequence code must be 0-2 or null" }
            }
            for ((name, code) in sequence) {
                val f = FAST_RAILS[name]!!
                val c = code ?: 3
                rmw(REG_FAST_PWRON0 + f[0], 3 shl f[1], c shl f[1])
            }
        }
        rmw(REG_FAST_PWRON0 + 3, 0xC0, bit(poweron, 0x80) or bit(wakeup, 0x40))
    }

    /**
     * Configure the watchdog.
     *
     * @param enabled watchdog enable
     * @param timeoutS 1, 2, 4, 8, 16, 32, 64 or 128 s
     * @param action `"irq"`, `"reset"`, `"reset_pwrok"` or `"power_cycle"`
     */
    @JvmOverloads
    fun configureWatchdog(enabled: Boolean, timeoutS: Int = 64, action: String = "irq") {
        val a = when (action) {
            "irq" -> 0
            "reset" -> 1
            "reset_pwrok" -> 2
            "power_cycle" -> 3
            else -> throw IllegalArgumentException("unknown watchdog action: $action")
        }
        val code = indexOf(timeoutS.toDouble(), doubleArrayOf(1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0, 128.0), "watchdog timeout")
        rmw(REG_WATCHDOG_CTRL, 0x37, code or (a shl 4))
        rmw(REG_MODULE_ENABLE, 0x01, bit(enabled, 1))
    }

    /** Clear (feed) the watchdog (WATCHDOG_CTRL bit 3). */
    fun feedWatchdog() = command(REG_WATCHDOG_CTRL, 0x08)

    // --- Fuel gauge -------------------------------------------------------

    /**
     * Set the low-battery warning levels (LOWBAT_WARN).
     *
     * @param level2 warning level 2, 5–20 %
     * @param level1 warning level 1, 0–15 %
     */
    @JvmOverloads
    fun configureBatteryWarning(level2: Int = 10, level1: Int = 1) {
        require(level2 in 5..20 && level1 in 0..15) { "battery warning levels out of range" }
        writeReg(REG_LOWBAT_WARN, ((level2 - 5) shl 4) or level1)
    }

    /**
     * Enable or disable the fuel gauge (MODULE_ENABLE bit 3).
     *
     * @param enabled `true` to enable
     */
    fun setGaugeEnabled(enabled: Boolean) = rmw(REG_MODULE_ENABLE, 0x08, bit(enabled, 0x08))

    /**
     * Reset the fuel gauge.
     *
     * @param full `true` for the deeper reset that also clears gauge data beyond the registers
     */
    @JvmOverloads
    fun resetGauge(full: Boolean = false) = command(REG_GAUGE_RESET, if (full) 0x04 else 0x08)

    // --- GPIO1 and data buffer --------------------------------------------

    /**
     * Drive the open-drain GPIO1 output.
     *
     * @param low `true` to drive low, `false` for Hi-Z
     */
    fun setGpio1(low: Boolean) = rmw(REG_GPIO1_CTRL, 0x0C, bit(low, 0x04))

    /**
     * Read one of the four general-purpose bytes.
     *
     * @param index 0–3
     * @return the stored byte
     */
    fun readDataBuffer(index: Int): Int {
        require(index in 0..3) { "data buffer index must be 0-3" }
        return readReg(REG_DATA_BUFFER0 + index)
    }

    /**
     * Write one of the four general-purpose bytes (survives soft restarts, not a power-on reset).
     *
     * @param index 0–3
     * @param value byte to store
     */
    fun writeDataBuffer(index: Int, value: Int) {
        require(index in 0..3) { "data buffer index must be 0-3" }
        writeReg(REG_DATA_BUFFER0 + index, value)
    }

    // --- Interrupts -------------------------------------------------------

    /**
     * Enable interrupt sources (IRQ_EN0..2 read-modify-write).
     *
     * @param source bitwise OR of `SOURCE_*` constants
     */
    fun enableInterrupt(source: Int) {
        for (k in 0 until 3) {
            val bits = (source shr (8 * k)) and 0xFF
            if (bits != 0) rmw(REG_IRQ_EN0 + k, bits, bits)
        }
    }

    /**
     * Disable interrupt sources.
     *
     * @param source bitwise OR of `SOURCE_*` constants
     */
    fun disableInterrupt(source: Int) {
        for (k in 0 until 3) {
            val bits = (source shr (8 * k)) and 0xFF
            if (bits != 0) rmw(REG_IRQ_EN0 + k, bits, 0)
        }
    }

    /**
     * Read and clear the interrupt status. Writes back exactly the bits it saw set, so an event
     * arriving between the read and the write is not lost.
     *
     * @return 24-bit status: IRQ_STATUS0 in bits 7:0, IRQ_STATUS1 in 15:8, IRQ_STATUS2 in 23:16
     */
    fun pollInterrupt(): Int {
        var status = 0
        for (k in 0 until 3) {
            val v = readReg(REG_IRQ_STATUS0 + k)
            if (v != 0) writeReg(REG_IRQ_STATUS0 + k, v)
            status = status or (v shl (8 * k))
        }
        return status
    }

    /**
     * Subscribe to IRQ pin assertions. Uses `connection.intPin()` (falling edge) when wired,
     * otherwise a 5 ms polling thread.
     *
     * @param callback called with the 24-bit status
     */
    @Synchronized
    fun onInterrupt(callback: (Int) -> Unit) {
        offInterrupt()
        this.callback = IntConsumer { callback(it) }
        val pin = connection.intPin()
        if (pin != null) {
            subscribedPin = pin
            pin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            val thread = Thread {
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(5)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }
            thread.isDaemon = true
            thread.name = "axp2101-poll"
            pollThread = thread
            thread.start()
        }
    }

    /** Unsubscribe from IRQ pin assertions. */
    @Synchronized
    fun offInterrupt() {
        callback = null
        subscribedPin?.offEdge(edgeHandler)
        subscribedPin = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun handleEdge() {
        val cb = callback ?: return
        try {
            val status = pollInterrupt()
            if (status != 0) cb.accept(status)
        } catch (e: IOException) {
            // keep delivering: a transient bus error must not kill the polling thread
        }
    }
}
