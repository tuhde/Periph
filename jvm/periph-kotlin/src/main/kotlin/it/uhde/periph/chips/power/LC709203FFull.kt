package it.uhde.periph.chips.power

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import java.util.function.IntConsumer

/**
 * LC709203F — full driver. Extends [LC709203FMinimal] with RSOC control, thermistor mode,
 * APA/APT, current direction, battery profiles, alarms and power mode.
 *
 * Constants: [DIRECTION_AUTO], [DIRECTION_CHARGE], [DIRECTION_DISCHARGE]; [ALARM_RSOC], [ALARM_VOLTAGE]
 * (bitmask returned by [pollInterrupt]).
 *
 * The chip has no status register; [pollInterrupt] evaluates RSOC and voltage against the cached
 * thresholds. [onInterrupt] uses `connection.intPin()` if wired (falling edge on ALARMB), otherwise a
 * 1 s polling thread.
 *
 * @param connection I²C connection bound to address 0x0B
 * @param capacityMah design capacity in mAh
 * @param profile battery profile (0 or 1)
 */
class LC709203FFull(
    connection: RegisterConnection,
    capacityMah: Int = 1000,
    profile: Int = 1
) : LC709203FMinimal(connection, capacityMah, profile) {

    companion object {
        /** Current direction: automatic. */
        const val DIRECTION_AUTO = 0x0000
        /** Current direction: charge. */
        const val DIRECTION_CHARGE = 0x0001
        /** Current direction: discharge. */
        const val DIRECTION_DISCHARGE = 0xFFFF
        /** Alarm bitmask bit: RSOC below the Alarm Low RSOC threshold. */
        const val ALARM_RSOC = 0x01
        /** Alarm bitmask bit: cell voltage below the Alarm Low Cell Voltage threshold. */
        const val ALARM_VOLTAGE = 0x02
    }

    private var alarmRsocRaw = readWord(REG_ALARM_RSOC)
    private var alarmVoltageRaw = readWord(REG_ALARM_VOLTAGE)
    @Volatile private var callback: IntConsumer? = null
    private var subscribedPin: InputPin? = null
    @Volatile private var polling = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { handleEdge() }

    /** @return state of charge in % (0–100) */
    fun rsoc(): Int = readWord(REG_RSOC)

    /**
     * Force the RSOC register (use sleep mode to keep the value).
     *
     * @param percent state of charge in % (0–100)
     */
    fun setRsoc(percent: Int) = writeWord(REG_RSOC, percent.coerceIn(0, 100))

    /** Initialize RSOC from the present voltage (0x07 ← 0xAA55); waits 2 ms. */
    fun initRsoc() {
        writeWord(REG_INITIAL_RSOC, 0xAA55)
        pause(2)
    }

    /** Initialize RSOC from the maximum voltage since reset (0x04 ← 0xAA55); waits 2 ms. */
    fun beforeRsoc() {
        writeWord(REG_BEFORE_RSOC, 0xAA55)
        pause(2)
    }

    /**
     * Switch to thermistor temperature mode.
     *
     * @param bConstant thermistor B constant in K
     */
    fun useThermistor(bConstant: Int = 3380) {
        writeWord(REG_THERMISTOR_B, bConstant)
        writeWord(REG_STATUS, 0x0001)
    }

    /**
     * Switch to I²C temperature mode and write a temperature.
     *
     * @param celsius cell temperature in °C
     */
    fun useI2cTemperature(celsius: Double = 25.0) {
        writeWord(REG_STATUS, 0x0000)
        setTemperature(celsius)
    }

    /** @return true if the temperature source is the thermistor */
    fun isThermistorMode(): Boolean = readWord(REG_STATUS) == 0x0001

    /** @return thermistor B constant in K */
    fun getBConstant(): Int = readWord(REG_THERMISTOR_B)

    /**
     * Write the Adjustment Pack Application register.
     *
     * @param value APA value (0–255)
     */
    fun setApa(value: Int) = writeWord(REG_APA, value and 0xFF)

    /** @return Adjustment Pack Application register */
    fun getApa(): Int = readWord(REG_APA)

    /**
     * Write the Adjustment Pack Thermistor register.
     *
     * @param value APT value (0–65535)
     */
    fun setApt(value: Int) = writeWord(REG_APT, value and 0xFFFF)

    /** @return Adjustment Pack Thermistor register */
    fun getApt(): Int = readWord(REG_APT)

    /**
     * Set the current direction.
     *
     * @param mode [DIRECTION_AUTO], [DIRECTION_CHARGE] or [DIRECTION_DISCHARGE]
     * @throws IllegalArgumentException for any other value
     */
    fun setCurrentDirection(mode: Int) {
        require(mode == DIRECTION_AUTO || mode == DIRECTION_CHARGE || mode == DIRECTION_DISCHARGE) {
            "invalid current direction: $mode"
        }
        writeWord(REG_DIRECTION, mode)
    }

    /** @return current direction register */
    fun getCurrentDirection(): Int = readWord(REG_DIRECTION)

    /**
     * Select battery profile 0 or 1: re-initializes the chip (waits 90 ms), then re-enters operational mode.
     *
     * @param profile 0 or 1
     */
    fun setProfile(profile: Int) {
        writeWord(REG_PROFILE, profile and 1)
        pause(100)
        wakeAndOperational()
    }

    /** @return selected battery profile (0 or 1) */
    fun getProfile(): Int = readWord(REG_PROFILE)

    /** @return Number of the Parameter register (e.g. 0x0301) */
    fun profileId(): Int = readWord(REG_PROFILE_ID)

    /** @return IC Version register */
    fun icVersion(): Int = readWord(REG_IC_VERSION)

    /**
     * Set the low-RSOC alarm threshold.
     *
     * @param percent 0 disables, 1–100 %
     */
    fun setAlarmRsoc(percent: Int) {
        val p = percent.coerceIn(0, 100)
        writeWord(REG_ALARM_RSOC, p)
        alarmRsocRaw = p
    }

    /** @return low-RSOC alarm threshold in % */
    fun getAlarmRsoc(): Int = readWord(REG_ALARM_RSOC)

    /**
     * Set the low-voltage alarm threshold.
     *
     * @param volts 0 disables; otherwise volts (resolution 1 mV)
     */
    fun setAlarmVoltage(volts: Double) {
        val raw = Math.round(volts * 1000.0).coerceIn(0L, 0xFFFFL).toInt()
        writeWord(REG_ALARM_VOLTAGE, raw)
        alarmVoltageRaw = raw
    }

    /** @return low-voltage alarm threshold in V */
    fun getAlarmVoltage(): Double = readWord(REG_ALARM_VOLTAGE) / 1000.0

    /** Enter sleep mode (only I²C stays alive; charge counting stops). */
    fun sleep() = writeWord(REG_POWER_MODE, 0x0002)

    /** Wake from sleep: dummy transaction (SDA pulse), wait 400 µs, enter operational mode. */
    fun wake() = wakeAndOperational()

    /** @return true if the chip is in sleep mode (the bus is woken first) */
    fun isSleeping(): Boolean {
        try {
            connection.write(REG_POWER_MODE, ByteArray(0)) // command code only: ignored by the chip
        } catch (e: IOException) {
            // ignored: wake-up pulse
        }
        pause(1)
        return readWord(REG_POWER_MODE) == 0x0002
    }

    /**
     * Evaluate RSOC and voltage against the cached alarm thresholds.
     *
     * @return bitmask of [ALARM_RSOC] / [ALARM_VOLTAGE] (0 = no alarm)
     */
    @Synchronized
    fun pollInterrupt(): Int {
        var status = 0
        if (alarmRsocRaw != 0 && readWord(REG_RSOC) < alarmRsocRaw) status = status or ALARM_RSOC
        if (alarmVoltageRaw != 0 && readWord(REG_VOLTAGE) < alarmVoltageRaw) status = status or ALARM_VOLTAGE
        return status
    }

    /**
     * Subscribe to ALARMB on [intPin] (falling edge), or a 1 s polling thread if it is null.
     *
     * @param callback receives the [ALARM_RSOC] / [ALARM_VOLTAGE] bitmask
     * @param intPin pin to arm; defaults to `connection.intPin()`
     */
    fun onInterrupt(callback: IntConsumer, intPin: InputPin? = connection.intPin()) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            subscribedPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = Thread({
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(1000)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }, "lc709203f-poll").also { it.isDaemon = true; it.start() }
        }
    }

    /** Unsubscribe and stop delivery. */
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
            // transient bus error: the next edge or tick retries
        }
    }
}
