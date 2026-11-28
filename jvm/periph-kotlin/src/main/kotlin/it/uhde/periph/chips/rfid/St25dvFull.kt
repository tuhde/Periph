package it.uhde.periph.chips.rfid

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.function.IntConsumer

/**
 * ST25DV full driver — extends [St25dvMinimal] with identification, system area, password
 * session, memory areas, GPO interrupts, energy harvesting, RF state, fast-transfer-mode mailbox
 * and a minimal NDEF writer.
 *
 * System-area writes need an open I²C security session ([presentPassword]). Bits 0-6 of
 * `GPO_CTRL_Dyn` are read-only over I²C, so interrupt sources are configured in the static `GPO`
 * register (session required); only `GPO_EN` switches at runtime without a session.
 *
 * @param user connection at 0x53 (`regBytes` = 2)
 * @param system connection at 0x57 (`regBytes` = 2)
 * @param gpoActiveHigh true for -JF variants (rising GPO edge)
 */
class St25dvFull @JvmOverloads constructor(
    user: RegisterConnection,
    system: RegisterConnection,
    gpoActiveHigh: Boolean = false
) : St25dvMinimal(user, system, gpoActiveHigh) {

    companion object {
        /** Interrupt source (IT_STS_Dyn bit 0): RF "Manage GPO" set/reset. */
        const val SOURCE_RF_USER = 0x01
        /** Interrupt source (bit 1): RF access. */
        const val SOURCE_RF_ACTIVITY = 0x02
        /** Interrupt source (bit 2): RF "Manage GPO" interrupt request. */
        const val SOURCE_RF_INTERRUPT = 0x04
        /** Interrupt source (bit 3): RF field disappeared. */
        const val SOURCE_FIELD_FALLING = 0x08
        /** Interrupt source (bit 4): RF field appeared. */
        const val SOURCE_FIELD_RISING = 0x10
        /** Interrupt source (bit 5): RF reader wrote a mailbox message. */
        const val SOURCE_RF_PUT_MSG = 0x20
        /** Interrupt source (bit 6): RF reader read the mailbox message. */
        const val SOURCE_RF_GET_MSG = 0x40
        /** Interrupt source (bit 7): RF reader wrote to EEPROM. */
        const val SOURCE_RF_WRITE = 0x80

        /** GPO bit 0: GPO level by RF "Manage GPO" set/reset. */
        const val GPO_RF_USER = 0x01
        /** GPO bit 1: GPO follows RF activity. */
        const val GPO_RF_ACTIVITY = 0x02
        /** GPO bit 2: GPO pulse by RF "Manage GPO". */
        const val GPO_RF_INTERRUPT = 0x04
        /** GPO bit 3: pulse on RF field change. */
        const val GPO_FIELD_CHANGE = 0x08
        /** GPO bit 4: pulse when the reader put a mailbox message. */
        const val GPO_RF_PUT_MSG = 0x10
        /** GPO bit 5: pulse when the reader got the mailbox message. */
        const val GPO_RF_GET_MSG = 0x20
        /** GPO bit 6: pulse after an RF EEPROM write. */
        const val GPO_RF_WRITE = 0x40
        /** GPO bit 7: GPO output enable. */
        const val GPO_EN = 0x80

        private const val SYS_GPO = 0x0000
        private const val SYS_IT_TIME = 0x0001
        private const val SYS_EH_MODE = 0x0002
        private const val SYS_RFA1SS = 0x0004
        private const val SYS_ENDA1 = 0x0005
        private const val SYS_I2CSS = 0x000B
        private const val SYS_LOCK_CCFILE = 0x000C
        private const val SYS_MB_MODE = 0x000D
        private const val SYS_MB_WDG = 0x000E
        private const val SYS_UID = 0x0018
        private const val SYS_IC_REV = 0x0020
        private const val SYS_I2C_PWD = 0x0900
        private const val DYN_GPO_CTRL = 0x2000
        private const val DYN_EH_CTRL = 0x2002
        private const val DYN_RF_MNGT = 0x2003
        private const val DYN_I2C_SSO = 0x2004
        private const val DYN_IT_STS = 0x2005
        private const val DYN_MB_CTRL = 0x2006
        private const val DYN_MB_LEN = 0x2007
        private const val MAILBOX = 0x2008
        private const val MAILBOX_SIZE = 256

        private fun sourceToGpo(source: Int): Int {
            var gpo = source and 0x07
            if (source and (SOURCE_FIELD_RISING or SOURCE_FIELD_FALLING) != 0) gpo = gpo or GPO_FIELD_CHANGE
            return gpo or ((source shr 1) and 0x70)
        }

        private fun passwordFrame(password: ByteArray, code: Int): ByteArray {
            require(password.size == 8) { "password must be 8 bytes" }
            return password + byteArrayOf(code.toByte()) + password
        }
    }

    /**
     * Decoded `MB_CTRL_Dyn`.
     *
     * @property enabled MB_EN: fast transfer mode active
     * @property hostPut HOST_PUT_MSG: the host wrote the current message
     * @property rfPut RF_PUT_MSG: the RF reader wrote the current message
     * @property hostMiss HOST_MISS_MSG: host message not read before the watchdog
     * @property rfMiss RF_MISS_MSG: RF message not read before the watchdog
     * @property hostCurrent HOST_CURRENT_MSG: the host is reading the message
     * @property rfCurrent RF_CURRENT_MSG: the RF reader is reading the message
     */
    data class MailboxStatus(
        val enabled: Boolean, val hostPut: Boolean, val rfPut: Boolean, val hostMiss: Boolean,
        val rfMiss: Boolean, val hostCurrent: Boolean, val rfCurrent: Boolean
    )

    /**
     * Protection of one memory area.
     *
     * @property rf RFAnSS value 0-15
     * @property i2c I2CSS level 0-3
     */
    data class AreaProtection(val rf: Int, val i2c: Int)

    @Volatile private var callback: IntConsumer? = null
    private var subscribedPin: InputPin? = null
    @Volatile private var polling = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { handleEdge() }

    /** User memory size in bytes ((MEM_SIZE + 1) × (BLK_SIZE + 1)). */
    fun memorySize(): Int = size

    /** IC_REF: 0x24 (ST25DV04K) or 0x26 (ST25DV16K/64K). */
    fun icReference(): Int = icRef

    /** Read the 64-bit unique ID, most significant byte first (E0 02 …). */
    fun uid(): ByteArray = readReg(system, SYS_UID, 8).reversedArray()

    /** Read IC_REV. */
    fun icRevision(): Int = sysByte(SYS_IC_REV)

    /**
     * Raw read of the system configuration area (device 0x57).
     *
     * @param address system register address
     * @param length number of bytes
     */
    fun readSystem(address: Int, length: Int): ByteArray = readReg(system, address, length)

    /**
     * Raw write to the system area, then ACK poll (session required).
     *
     * @param address system register address
     * @param data bytes to write
     */
    fun writeSystem(address: Int, data: ByteArray) {
        system.write(address, data)
        commit(system)
    }

    /**
     * Write one system register byte (session required).
     *
     * @param address system register address
     * @param value byte value
     */
    fun writeSystem(address: Int, value: Int) = writeSystem(address, byteArrayOf(value.toByte()))

    private fun sysByte(reg: Int): Int = readReg(system, reg, 1)[0].toInt() and 0xFF

    private fun dynByte(reg: Int): Int = readReg(user, reg, 1)[0].toInt() and 0xFF

    private fun writeDyn(reg: Int, value: Int) = user.write(reg, byteArrayOf(value.toByte()))

    /**
     * Present the I²C password (opens the security session).
     *
     * @param password 8 bytes, most significant first (factory 00 × 8)
     * @return true if the session is open afterwards
     */
    fun presentPassword(password: ByteArray): Boolean {
        system.write(SYS_I2C_PWD, passwordFrame(password, 0x09))
        ackPoll(system, WRITE_TIMEOUT_MS)
        return sessionOpen()
    }

    /**
     * Change the I²C password (session must be open, otherwise [IOException]).
     *
     * @param password new 8-byte password, most significant first
     */
    fun writePassword(password: ByteArray) {
        val frame = passwordFrame(password, 0x07)
        if (!sessionOpen()) throw IOException("ST25DV security session is closed")
        system.write(SYS_I2C_PWD, frame)
        commit(system)
    }

    /** I2C_SSO_Dyn bit 0: the I²C security session is open. */
    fun sessionOpen(): Boolean = dynByte(DYN_I2C_SSO) and 0x01 != 0

    /**
     * Set ENDA1..3 (session required); area n ends at byte endBlock × 32 + 31.
     *
     * @param area 1-3
     * @param endBlock ENDAn value 0-255
     */
    fun setAreaEnd(area: Int, endBlock: Int) {
        require(area in 1..3) { "area must be 1-3" }
        require(endBlock in 0..0xFF) { "endBlock must be 0-255" }
        writeSystem(SYS_ENDA1 + 2 * (area - 1), endBlock)
    }

    /**
     * Set the RF and I²C protection of an area (session required).
     *
     * @param area 1-4
     * @param rf RFAnSS 0-15 (bits 3:2 RW protection, bits 1:0 RF password)
     * @param i2c I2CSS level 0-3 (bit 0 write, bit 1 read protection)
     */
    fun setAreaProtection(area: Int, rf: Int, i2c: Int) {
        require(area in 1..4) { "area must be 1-4" }
        require(rf in 0..0x0F && i2c in 0..3) { "rf must be 0-15 and i2c 0-3" }
        writeSystem(SYS_RFA1SS + 2 * (area - 1), rf)
        val shift = 2 * (area - 1)
        val i2css = (sysByte(SYS_I2CSS) and (0x03 shl shift).inv() and 0xFF) or (i2c shl shift)
        writeSystem(SYS_I2CSS, i2css)
    }

    /**
     * Read the protection of an area.
     *
     * @param area 1-4
     * @return RFAnSS & 0x0F and the area's I2CSS level
     */
    fun getAreaProtection(area: Int): AreaProtection {
        require(area in 1..4) { "area must be 1-4" }
        val rf = sysByte(SYS_RFA1SS + 2 * (area - 1)) and 0x0F
        val i2c = (sysByte(SYS_I2CSS) shr (2 * (area - 1))) and 0x03
        return AreaProtection(rf, i2c)
    }

    /**
     * Write the static GPO register (session required); the chip copies it into GPO_CTRL_Dyn.
     *
     * @param sources mask of GPO_* enable bits 0-6
     * @param enable sets GPO_EN
     */
    @JvmOverloads
    fun setGpo(sources: Int, enable: Boolean = true) =
        writeSystem(SYS_GPO, (sources and 0x7F) or (if (enable) GPO_EN else 0))

    /** Read the static GPO register (GPO_* mask). */
    fun getGpo(): Int = sysByte(SYS_GPO)

    /**
     * Switch the GPO output at runtime (GPO_CTRL_Dyn.GPO_EN, no session; bits 0-6 are read-only).
     *
     * @param enable true enables the output
     */
    fun setGpoRuntime(enable: Boolean) = writeDyn(DYN_GPO_CTRL, if (enable) GPO_EN else 0)

    /**
     * Set IT_TIME (session required); pulse = 301 µs − value × 37.65 µs.
     *
     * @param value 0-7
     */
    fun setInterruptPulse(value: Int) {
        require(value in 0..7) { "value must be 0-7" }
        writeSystem(SYS_IT_TIME, value)
    }

    /** EH_CTRL_Dyn.FIELD_ON: an RF field is present. */
    fun fieldPresent(): Boolean = dynByte(DYN_EH_CTRL) and 0x04 != 0

    /**
     * Set EH_CTRL_Dyn.EH_EN; write EH_MODE (session required) only when [forced] changes it.
     *
     * @param enabled energy harvesting on now
     * @param forced forced after power-up (EH_MODE = 0) instead of on demand
     */
    @JvmOverloads
    fun setEnergyHarvesting(enabled: Boolean, forced: Boolean = false) {
        val mode = if (forced) 0 else 1
        if (sysByte(SYS_EH_MODE) and 0x01 != mode) writeSystem(SYS_EH_MODE, mode)
        writeDyn(DYN_EH_CTRL, if (enabled) 0x01 else 0x00)
    }

    /**
     * Set the runtime RF state RF_MNGT_Dyn (the static RF_MNGT is unchanged).
     *
     * @param disable RF_DISABLE
     * @param sleep RF_SLEEP
     */
    @JvmOverloads
    fun setRfState(disable: Boolean = false, sleep: Boolean = false) =
        writeDyn(DYN_RF_MNGT, (if (disable) 0x01 else 0) or (if (sleep) 0x02 else 0))

    /** Set MB_MODE if needed (session required), then MB_CTRL_Dyn.MB_EN. */
    fun enableMailbox() {
        if (sysByte(SYS_MB_MODE) and 0x01 == 0) writeSystem(SYS_MB_MODE, 0x01)
        writeDyn(DYN_MB_CTRL, 0x01)
    }

    /** Clear MB_CTRL_Dyn.MB_EN (MB_MODE stays). */
    fun disableMailbox() = writeDyn(DYN_MB_CTRL, 0x00)

    /** Decode MB_CTRL_Dyn. */
    fun mailboxStatus(): MailboxStatus {
        val v = dynByte(DYN_MB_CTRL)
        return MailboxStatus(
            v and 0x01 != 0, v and 0x02 != 0, v and 0x04 != 0, v and 0x10 != 0,
            v and 0x20 != 0, v and 0x40 != 0, v and 0x80 != 0
        )
    }

    /**
     * Put a message into the mailbox (one write starting at 0x2008); [IOException] if the
     * mailbox is disabled or busy.
     *
     * @param data 1-256 bytes
     */
    fun mailboxWrite(data: ByteArray) {
        require(data.size in 1..MAILBOX_SIZE) { "mailbox message must be 1-256 bytes" }
        val ctrl = dynByte(DYN_MB_CTRL)
        if (ctrl and 0x01 == 0) throw IOException("ST25DV mailbox is disabled")
        if (ctrl and 0x06 != 0) throw IOException("ST25DV mailbox is busy")
        user.write(MAILBOX, data)
    }

    /** Read the mailbox message: MB_LEN_Dyn + 1 bytes, or empty when no message is present. */
    fun mailboxRead(): ByteArray {
        val ctrl = dynByte(DYN_MB_CTRL)
        if (ctrl and 0x06 == 0) return ByteArray(0)
        val length = dynByte(DYN_MB_LEN) + 1
        val out = ByteArrayOutputStream(length)
        var off = 0
        while (off < length) {
            out.writeBytes(readReg(user, MAILBOX + off, minOf(CHUNK, length - off)))
            off += CHUNK
        }
        return out.toByteArray()
    }

    /**
     * Set MB_WDG (session required); watchdog = 2^(value − 1) × 30 ms, 0 disables it.
     *
     * @param value 0-7
     */
    fun setMailboxWatchdog(value: Int) {
        require(value in 0..7) { "value must be 0-7" }
        writeSystem(SYS_MB_WDG, value)
    }

    /**
     * Write LOCK_CCFILE (session required): RF write protection of blocks 0 and 1.
     *
     * @param lock true locks both blocks
     */
    @JvmOverloads
    fun lockCcfile(lock: Boolean = true) = writeSystem(SYS_LOCK_CCFILE, if (lock) 0x03 else 0x00)

    private fun capabilityContainer(): ByteArray {
        if (size <= 2048) return byteArrayOf(0xE1.toByte(), 0x40, minOf(size / 8, 0xFF).toByte(), 0x01)
        val mlen = size / 8 - 1
        return byteArrayOf(0xE2.toByte(), 0x40, 0x00, 0x01, 0x00, 0x00, (mlen shr 8).toByte(), mlen.toByte())
    }

    /**
     * Write a Type 5 capability container and one NDEF TLV (03 len … FE; 03 FF hi lo … for ≥ 255
     * bytes); [IllegalArgumentException] if it does not fit.
     *
     * @param message encoded NDEF message
     */
    fun writeNdef(message: ByteArray) {
        val tlv = if (message.size < 0xFF) byteArrayOf(0x03, message.size.toByte())
        else byteArrayOf(0x03, 0xFF.toByte(), (message.size shr 8).toByte(), message.size.toByte())
        val image = capabilityContainer() + tlv + message + byteArrayOf(0xFE.toByte())
        require(image.size <= size) { "NDEF message too large" }
        write(0, image)
    }

    /** Read the message of the first NDEF TLV; empty when no CC or NDEF TLV is found. */
    fun readNdef(): ByteArray {
        val cc = read(0, 4)
        var pos = when (cc[0].toInt() and 0xFF) {
            0xE1 -> 4
            0xE2 -> 8
            else -> return ByteArray(0)
        }
        while (pos < size) {
            val t = read(pos, 1)[0].toInt() and 0xFF
            if (t == 0x00) { pos++; continue }
            if (t == 0xFE || pos + 2 > size) return ByteArray(0)
            var length = read(pos + 1, 1)[0].toInt() and 0xFF
            pos += 2
            if (length == 0xFF) {
                if (pos + 2 > size) return ByteArray(0)
                val lb = read(pos, 2)
                length = ((lb[0].toInt() and 0xFF) shl 8) or (lb[1].toInt() and 0xFF)
                pos += 2
            }
            if (t == 0x03) {
                if (pos + length > size) return ByteArray(0)
                return read(pos, length)
            }
            pos += length
        }
        return ByteArray(0)
    }

    /** ACK poll on 0x53 until the write cycle has finished ([IOException] on timeout). */
    fun waitWriteComplete() = commit(user)

    /**
     * Enable SOURCE_* sources in the static GPO register plus GPO_EN (session required).
     * SOURCE_FIELD_RISING and SOURCE_FIELD_FALLING share FIELD_CHANGE_EN.
     *
     * @param source SOURCE_* mask
     */
    fun enableInterrupt(source: Int) {
        writeSystem(SYS_GPO, getGpo() or sourceToGpo(source) or GPO_EN)
        setGpoRuntime(true)
    }

    /**
     * Clear SOURCE_* sources in the static GPO register (session required).
     *
     * @param source SOURCE_* mask
     */
    fun disableInterrupt(source: Int) = writeSystem(SYS_GPO, getGpo() and sourceToGpo(source).inv() and 0xFF)

    /** Read IT_STS_Dyn (cleared on read): the SOURCE_* mask, 0 = none. */
    fun pollInterrupt(): Int = dynByte(DYN_IT_STS)

    /**
     * Subscribe to GPO events: falling edge (-IE) or rising edge (-JF) on [intPin], or a 10 ms
     * polling thread when no pin is wired.
     *
     * @param callback receives the SOURCE_* mask
     * @param intPin pin wired to GPO (default: the user connection's pin)
     */
    @JvmOverloads
    fun onInterrupt(callback: IntConsumer, intPin: InputPin? = user.intPin()) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            subscribedPin = intPin
            intPin.onEdge(edgeHandler, if (gpoActiveHigh) EdgeTrigger.RISING else EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = Thread({
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(10)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }, "st25dv-poll").apply {
                isDaemon = true
                start()
            }
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
            // a failed poll is retried on the next edge / tick
        }
    }
}
