package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.OutputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * MCP4XXX family — full driver: extends [MCP4XXXMinimal] with ratio helpers, increment /
 * decrement, shutdown and terminal control, General Call, reset, raw access and — on the
 * non-volatile parts only (marked **NV**; they throw [UnsupportedOperationException] on the
 * volatile parts) — the EEPROM wiper, ten general-purpose EEPROM registers, STATUS, WiperLock and
 * write protect.
 *
 * @param connection I²C connection bound to an address in the part's range
 * @param part part name
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 * @param hvc optional pin switching the external circuit that drives HVC/A0 to 8.5–12.5 V (needed only
 *            by [setWiperLock] / [setWriteProtect])
 * @param hvcSettleMs delay in ms between asserting `hvc` and the first SCL edge
 */
open class MCP4XXXFull(
    connection: RegisterConnection,
    part: String = "MCP4532",
    rAb: Double = 10000.0,
    rWiper: Double = 75.0,
    hvc: OutputPin? = null,
    private val hvcSettleMs: Long = 1
) : MCP4XXXMinimal(connection, part, rAb, rWiper) {

    /**
     * Decoded STATUS register of the non-volatile parts.
     *
     * @property eepromBusy an EEPROM write cycle is in progress (EEWA)
     * @property wiperLock WiperLock of network 0 and 1 (index 1 is false on single-network parts)
     * @property writeProtect hardware WP pin OR the nonvolatile write-protect bit
     */
    class Status(val eepromBusy: Boolean, val wiperLock: BooleanArray, val writeProtect: Boolean)

    private val hvc: OutputPin? = if (desc.nonvolatile) hvc else null

    private fun requireNv() {
        if (!desc.nonvolatile) throw UnsupportedOperationException("MCP4XXX: ${desc.name} has no EEPROM")
    }

    private fun requirePot() {
        if (!desc.potentiometer) throw UnsupportedOperationException("MCP4XXX: ${desc.name} is a rheostat (terminal A not available)")
    }

    /** Number of resistor networks (1 or 2). */
    fun channels(): Int = desc.channels

    /** Number of wiper positions (129 or 257). */
    fun steps(): Int = desc.maxCode + 1

    /** True for the X1 parts (terminal A on a pin). */
    fun isPotentiometer(): Boolean = desc.potentiometer

    /** True for the 454X / 456X / 464X / 466X parts. */
    fun isNonvolatile(): Boolean = desc.nonvolatile

    /**
     * Set the wiper to a fraction of the full scale.
     *
     * @param ratio 0.0–1.0 (clamped)
     * @param channel network index
     */
    fun setRatio(ratio: Double, channel: Int = 0) {
        setWiper(Math.round(ratio.coerceIn(0.0, 1.0) * desc.maxCode).toInt(), channel)
    }

    /**
     * Read the wiper position as a ratio.
     *
     * @param channel network index
     * @return code / maxCode
     */
    fun getRatio(channel: Int = 0): Double = getWiper(channel).toDouble() / desc.maxCode

    /**
     * Read the W–A resistance (potentiometer parts only).
     *
     * @param channel network index
     * @return (maxCode − N) / maxCode × RAB + RW in ohms
     */
    fun getResistanceWa(channel: Int = 0): Double {
        requirePot()
        return (desc.maxCode - getWiper(channel)).toDouble() / desc.maxCode * rAb + rWiper
    }

    /**
     * Set the W–A resistance to the nearest reachable value (potentiometer parts only).
     *
     * @param ohms target wiper-to-A resistance in ohms
     * @param channel network index
     */
    fun setResistanceWa(ohms: Double, channel: Int = 0) {
        requirePot()
        checkChannel(channel)
        setWiper(desc.maxCode - Math.round((ohms - rWiper) / rAb * desc.maxCode).toInt(), channel)
    }

    /** Resistance of one step, RAB / maxCode, in ohms. */
    fun getResistanceStep(): Double = rAb / desc.maxCode

    /**
     * Write both wipers of a dual part (two transactions).
     *
     * @param code0 network 0 wiper position
     * @param code1 network 1 wiper position
     */
    fun setBoth(code0: Int, code1: Int) {
        if (desc.channels < 2) throw UnsupportedOperationException("MCP4XXX: ${desc.name} has a single network")
        setWiper(code0, 0)
        setWiper(code1, 1)
    }

    private fun step(op: Int, steps: Int, channel: Int): Int {
        checkChannel(channel)
        if (steps > 0) {
            val command = ((ADDR_WIPER0 + channel) shl 4) or op
            // Continuous mode: one command byte per step, no data bytes, one transaction.
            connection.write(command, ByteArray(steps - 1) { command.toByte() })
        }
        return readWiper(channel)
    }

    /**
     * Increment the wiper by [steps] in one continuous transaction (saturating at the max code).
     *
     * @param steps number of increment commands
     * @param channel network index
     * @return new wiper code (read back)
     */
    fun increment(steps: Int = 1, channel: Int = 0): Int = step(OP_INC, steps, channel)

    /**
     * Decrement the wiper by [steps] in one continuous transaction (saturating at 0).
     *
     * @param steps number of decrement commands
     * @param channel network index
     * @return new wiper code (read back)
     */
    fun decrement(steps: Int = 1, channel: Int = 0): Int = step(OP_DEC, steps, channel)

    private fun modifyTcon(changes: List<Pair<Int, Boolean>>) {
        var value = read9(ADDR_TCON)
        for ((bit, on) in changes) value = if (on) value or (1 shl bit) else value and (1 shl bit).inv()
        write9(ADDR_TCON, value and 0x1FF)
    }

    /**
     * Shut a network down (TCON.RxHW = 0): A disconnected, W connected to B; the wiper is kept.
     *
     * @param channel network index
     */
    fun shutdown(channel: Int = 0) {
        checkChannel(channel)
        modifyTcon(listOf(TCON_BITS[channel][0] to false))
    }

    /**
     * Leave shutdown (TCON.RxHW = 1).
     *
     * @param channel network index
     */
    fun resume(channel: Int = 0) {
        checkChannel(channel)
        modifyTcon(listOf(TCON_BITS[channel][0] to true))
    }

    /**
     * Whether the network is in shutdown (read from TCON).
     *
     * @param channel network index
     */
    fun isShutdown(channel: Int = 0): Boolean {
        checkChannel(channel)
        return ((read9(ADDR_TCON) shr TCON_BITS[channel][0]) and 1) == 0
    }

    /**
     * Connect or disconnect the A, W and B terminals of a network (TCON.RxA / RxW / RxB).
     *
     * @param a connect terminal A
     * @param w connect the wiper terminal
     * @param b connect terminal B
     * @param channel network index
     */
    fun setTerminals(a: Boolean = true, w: Boolean = true, b: Boolean = true, channel: Int = 0) {
        checkChannel(channel)
        val bits = TCON_BITS[channel]
        modifyTcon(listOf(bits[1] to a, bits[2] to w, bits[3] to b))
    }

    /**
     * Read the terminal connection state of a network from TCON.
     *
     * @param channel network index
     * @return `Triple(a, w, b)`
     */
    fun getTerminals(channel: Int = 0): Triple<Boolean, Boolean, Boolean> {
        checkChannel(channel)
        val tcon = read9(ADDR_TCON)
        val bits = TCON_BITS[channel]
        return Triple(((tcon shr bits[1]) and 1) == 1, ((tcon shr bits[2]) and 1) == 1, ((tcon shr bits[3]) and 1) == 1)
    }

    /** Read the raw 9-bit TCON register. */
    fun getTcon(): Int = read9(ADDR_TCON)

    /**
     * Enable or disable the General Call address (TCON.GCEN).
     *
     * @param enabled true to answer General Call (address 0x00) commands
     */
    fun setGeneralCall(enabled: Boolean) {
        modifyTcon(listOf(8 to enabled))
    }

    /**
     * Emulate a power-on reset: write every volatile wiper with its power-up value (mid-scale, or the
     * EEPROM wiper on non-volatile parts) and TCON 0x1FF; networks locked by WiperLock are skipped.
     */
    fun reset() {
        val locked = if (desc.nonvolatile) readStatus().wiperLock else BooleanArray(2)
        var any = false
        for (c in 0 until desc.channels) {
            if (locked[c]) {
                any = true
                continue
            }
            setWiper(if (desc.nonvolatile) getNvWiper(c) else desc.maxCode / 2, c)
        }
        if (!any) write9(ADDR_TCON, TCON_DEFAULT)
    }

    private fun checkAddress(address: Int, writable: Boolean) {
        var ok = address == ADDR_TCON || address < desc.channels
        if (desc.nonvolatile) {
            ok = ok || ((address == 2 || address == 3) && address - 2 < desc.channels) || (address in ADDR_DATA0..0x0F)
            if (address == ADDR_STATUS) ok = !writable
        }
        require(address in 0..0x0F && ok) { "MCP4XXX: address 0x%02X not available on %s".format(address, desc.name) }
    }

    /**
     * Raw 9-bit read of a memory address the part has.
     *
     * @param address memory address 0–15
     * @return 9-bit value
     */
    fun readRegister(address: Int): Int {
        checkAddress(address, false)
        return read9(address)
    }

    /**
     * Raw write of a writable memory address the part has.
     *
     * @param address memory address
     * @param value value (0–0x3FF for wipers, 0–0x1FF otherwise)
     */
    fun writeRegister(address: Int, value: Int) {
        checkAddress(address, true)
        write9(address, value.coerceIn(0, if (address < 2) 0x3FF else 0x1FF))
    }

    /**
     * Change the resistance model used by the conversions (no I²C traffic).
     *
     * @param rAb end-to-end resistance RAB in ohms
     * @param rWiper wiper resistance RW in ohms; null keeps the current RW
     */
    fun setRab(rAb: Double, rWiper: Double? = null) {
        this.rAb = rAb
        if (rWiper != null) this.rWiper = rWiper
    }

    /** **NV.** Read and decode STATUS. */
    fun readStatus(): Status {
        requireNv()
        val v = read9(ADDR_STATUS)
        val wl = BooleanArray(2) { it < desc.channels && (v and intArrayOf(0x02, 0x04)[it]) != 0 }
        return Status((v and 0x08) != 0, wl, (v and 0x01) != 0)
    }

    /**
     * **NV.** Poll STATUS.EEWA until the EEPROM write cycle has finished (tWC is 5 ms typical, 10 ms maximum).
     *
     * @param timeoutMs maximum wait in milliseconds
     * @throws IOException if the write cycle does not finish in time
     */
    fun waitReady(timeoutMs: Int = 20) {
        requireNv()
        for (i in 0..timeoutMs) {
            if ((read9(ADDR_STATUS) and 0x08) == 0) return
            Thread.sleep(1)
        }
        throw IOException("MCP4XXX: EEPROM write did not finish within $timeoutMs ms")
    }

    /**
     * **NV.** Read the EEPROM wiper (power-up value).
     *
     * @param channel network index
     * @return stored code, clamped to the max code
     */
    fun getNvWiper(channel: Int = 0): Int {
        requireNv()
        checkChannel(channel)
        return minOf(read9(ADDR_NVWIPER0 + channel), desc.maxCode)
    }

    /**
     * **NV.** Write the EEPROM wiper (the volatile wiper is not changed) and wait for the write cycle.
     *
     * @param code power-up code (clamped)
     * @param channel network index
     */
    fun setNvWiper(code: Int, channel: Int = 0) {
        requireNv()
        checkChannel(channel)
        write9(ADDR_NVWIPER0 + channel, clamp(code.toLong()))
        waitReady()
    }

    /**
     * **NV.** Write the current volatile wiper to the EEPROM wiper.
     *
     * @param channel network index
     * @return the stored code
     */
    fun storeWiper(channel: Int = 0): Int {
        requireNv()
        val code = getWiper(channel)
        setNvWiper(code, channel)
        return code
    }

    /**
     * **NV.** Read general-purpose EEPROM register [index].
     *
     * @param index register index 0–9
     * @return 9-bit value
     */
    fun readData(index: Int): Int {
        requireNv()
        require(index in 0..9) { "MCP4XXX: data index must be 0-9" }
        return read9(ADDR_DATA0 + index)
    }

    /**
     * **NV.** Write general-purpose EEPROM register [index] (uses one write cycle).
     *
     * @param index register index 0–9
     * @param value 9-bit value 0–0x1FF
     */
    fun writeData(index: Int, value: Int) {
        requireNv()
        require(index in 0..9) { "MCP4XXX: data index must be 0-9" }
        write9(ADDR_DATA0 + index, value.coerceIn(0, 0x1FF))
        waitReady()
    }

    /**
     * **NV.** Whether WiperLock is active for a network (STATUS.WLx).
     *
     * @param channel network index
     */
    fun isWiperLocked(channel: Int = 0): Boolean {
        checkChannel(channel)
        return readStatus().wiperLock[channel]
    }

    /** **NV.** Whether the EEPROM is write protected (hardware WP pin OR the nonvolatile bit). */
    fun isWriteProtected(): Boolean = readStatus().writeProtect

    private fun hvCommand(address: Int, decrement: Boolean) {
        val pin = hvc ?: throw UnsupportedOperationException("MCP4XXX: a high-voltage command needs the hvc pin")
        val command = (address shl 4) or (if (decrement) OP_DEC else OP_INC)
        pin.set(true)
        try {
            Thread.sleep(hvcSettleMs)
            connection.write(command, ByteArray(0))
        } finally {
            pin.set(false)
        }
        waitReady()
    }

    /**
     * **NV, needs the hvc pin.** Enable (decrement command) or disable (increment) WiperLock of a
     * network with a high-voltage command; waits for the EEPROM write cycle that stores the bit.
     *
     * @param locked true to lock, false to unlock
     * @param channel network index
     */
    fun setWiperLock(locked: Boolean, channel: Int = 0) {
        requireNv()
        checkChannel(channel)
        hvCommand(ADDR_NVWIPER0 + channel, locked)
    }

    /**
     * **NV, needs the hvc pin.** Set or clear the nonvolatile EEPROM write-protect bit with a
     * high-voltage command; the hardware WP pin (MCP4641 / MCP4661) can still block writes.
     *
     * @param enabled true to enable write protect, false to disable
     */
    fun setWriteProtect(enabled: Boolean) {
        requireNv()
        hvCommand(ADDR_WP, enabled)
    }
}
