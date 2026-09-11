package it.uhde.periph.chips.comms

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.OutputPin
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * RFM9x (RFM95/96/97/98W) LoRa transceiver — minimal driver (Kotlin).
 *
 * All four modules share identical pins, register maps, SPI protocol, and
 * LoRa modem logic. They differ only in supported frequency bands and the
 * maximum spreading factor for RFM97W.
 *
 * Only the Minimal-stage public API ([send], [receive]) is `public` here;
 * Full-stage functionality is `protected open` and re-exposed publicly by
 * [_Rfm9xFull], so `RfmXXMinimal` instances never see it.
 *
 * An optional NRESET [OutputPin] may be supplied (Full only, via
 * [_Rfm9xFull]); when present it drives a real hardware reset both at
 * construction and on [_Rfm9xFull.reset]. Without it, construction falls
 * back to waiting out the POR delay.
 */
abstract class _Rfm9xBase protected constructor(
    protected val transport: Connection,
    frequencyHz: Long,
    protected val resetPin: OutputPin? = null,
) {
    /** Variant-specific minimum carrier frequency in Hz. */
    protected abstract fun freqMinHz(): Long

    /** Variant-specific maximum carrier frequency in Hz. */
    protected abstract fun freqMaxHz(): Long

    /** Variant-specific maximum spreading factor. */
    protected abstract fun maxSf(): Int

    /** True for LF-band variants (RFM96W, RFM98W). */
    protected abstract fun lfBand(): Boolean

    /** Current carrier frequency in Hz. */
    var frequencyHz: Long = 0
        protected set

    init {
        if (frequencyHz < freqMinHz() || frequencyHz > freqMaxHz()) {
            throw IllegalArgumentException(
                "frequencyHz $frequencyHz out of range [${freqMinHz()}, ${freqMaxHz()}]")
        }
        this.frequencyHz = frequencyHz

        if (resetPin != null) {
            resetViaPin()
        } else {
            sleepMs(10)
        }
        initRegisters()
    }

    /**
     * Run the register-level init sequence: FSK-sleep → LoRa-sleep, LNA/AGC
     * setup, FIFO split, carrier frequency, default modem parameters, TX
     * power, then STDBY. Shared by the constructor and [_Rfm9xFull.reset].
     */
    protected fun initRegisters() {
        writeReg(REG_OP_MODE, 0x00)
        sleepMs(1)
        writeReg(REG_OP_MODE, MODE_LONG_RANGE or MODE_SLEEP)
        sleepMs(1)

        if (lfBand()) {
            val lna = readReg(REG_LNA)
            writeReg(REG_LNA, lna and 0x3F)
        } else {
            writeReg(REG_LNA, 0x23)
        }
        writeReg(REG_MODEM_CONFIG_3, readReg(REG_MODEM_CONFIG_3) or 0x04)

        writeReg(REG_FIFO_TX_BASE, 0x80)
        writeReg(REG_FIFO_RX_BASE, 0x00)

        setFrequency(frequencyHz)

        writeReg(REG_MODEM_CONFIG_1, (0x07 shl 4) or (0x01 shl 1) or 0x00)
        writeReg(REG_MODEM_CONFIG_2, (0x07 shl 4) or (0x01 shl 2) or 0x03)
        writeReg(REG_PREAMBLE_LSB, 0x08)

        setTxPower(17, true)
        standby()
    }

    /** Assert NRESET low, then release it (active-low hardware reset). */
    protected fun resetViaPin() {
        val pin = resetPin ?: return
        pin.set(false)
        sleepMs(1)
        pin.set(true)
        sleepMs(5)
    }

    /** Sleep, swallowing [InterruptedException] by re-asserting the interrupt flag. */
    protected fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    protected fun writeReg(reg: Int, value: Int) {
        transport.write(byteArrayOf((reg or 0x80).toByte(), (value and 0xFF).toByte()))
    }

    protected fun readReg(reg: Int): Int =
        (transport.writeRead(byteArrayOf((reg and 0x7F).toByte()), 1)[0].toInt() and 0xFF)

    protected fun burstWrite(reg: Int, data: ByteArray) {
        val buf = ByteArray(data.size + 1)
        buf[0] = (reg or 0x80).toByte()
        System.arraycopy(data, 0, buf, 1, data.size)
        transport.write(buf)
    }

    protected fun burstRead(reg: Int, n: Int): ByteArray =
        transport.writeRead(byteArrayOf((reg and 0x7F).toByte()), n)

    /**
     * Set the carrier frequency.
     *
     * @throws IOException on SPI error
     */
    protected open fun setFrequency(frequencyHz: Long) {
        if (frequencyHz < freqMinHz() || frequencyHz > freqMaxHz()) {
            throw IllegalArgumentException(
                "frequencyHz $frequencyHz out of range [${freqMinHz()}, ${freqMaxHz()}]")
        }
        val frf = (frequencyHz shl 19) / FXOSC
        writeReg(REG_FRF_MSB, ((frf shr 16) and 0xFF).toInt())
        writeReg(REG_FRF_MID, ((frf shr  8) and 0xFF).toInt())
        writeReg(REG_FRF_LSB, ( frf        and 0xFF).toInt())
        this.frequencyHz = frequencyHz
    }

    /**
     * Set TX output power.
     *
     * @throws IOException on SPI error
     */
    protected open fun setTxPower(powerDbm: Int, usePaBoost: Boolean) {
        var p = powerDbm
        if (usePaBoost) {
            if (p > 17) {
                if (p > 20) p = 20
                writeReg(REG_PA_DAC, PA_DAC_HIGH_POWER)
                writeReg(REG_OCP, OCP_240MA)
                writeReg(REG_PA_CONFIG, PA_BOOST or 0x0F)
            } else {
                if (p < 2) p = 2
                writeReg(REG_PA_DAC, PA_DAC_DEFAULT)
                writeReg(REG_OCP, OCP_DEFAULT)
                writeReg(REG_PA_CONFIG, PA_BOOST or (p - 2))
            }
        } else {
            writeReg(REG_PA_DAC, PA_DAC_DEFAULT)
            writeReg(REG_OCP, OCP_DEFAULT)
            val maxPower = 7
            val pmax = 10.8 + 0.6 * maxPower
            var op = (p - pmax + 15).toInt()
            if (op < 0) op = 0
            if (op > 15) op = 15
            writeReg(REG_PA_CONFIG, (maxPower shl 4) or op)
        }
    }

    /**
     * Configure LoRa modulation parameters.
     *
     * @throws IOException on SPI error
     */
    protected open fun configure(sf: Int, bandwidthKhz: Float, codingRate: Int, crc: Boolean = true) {
        val bwTable = floatArrayOf(7.8f, 10.4f, 15.6f, 20.8f, 31.25f, 41.7f, 62.5f, 125.0f, 250.0f, 500.0f)
        var bwCode = 0x07
        for (i in 0..9) {
            if (bandwidthKhz == bwTable[i]) { bwCode = i; break }
        }
        var s = sf
        if (s < 6 || s > maxSf()) s = s.coerceIn(6, maxSf())

        if (s == 6) {
            writeReg(REG_DETECTION_OPT, 0x05)
            writeReg(REG_DETECTION_THR, 0x0C)
        } else {
            writeReg(REG_DETECTION_OPT, 0x03)
            writeReg(REG_DETECTION_THR, 0x0A)
        }

        val implicitHeader = (s == 6)
        val cr = if (codingRate in 5..8) codingRate - 4 else 0x01
        writeReg(REG_MODEM_CONFIG_1, (bwCode shl 4) or (cr shl 1) or (if (implicitHeader) 1 else 0))
        writeReg(REG_MODEM_CONFIG_2, (s shl 4) or ((if (crc) 1 else 0) shl 2) or 0x03)
    }

    /** Enter STDBY mode. */
    protected open fun standby() { writeReg(REG_OP_MODE, MODE_LONG_RANGE or bandFlag() or MODE_STANDBY) }

    /** Enter SLEEP mode. */
    protected open fun sleep()   { writeReg(REG_OP_MODE, MODE_LONG_RANGE or bandFlag() or MODE_SLEEP) }

    /** Read RegVersion. Expect 0x12 (SX1276). */
    protected open fun version(): Int = readReg(REG_VERSION)

    /** Send a packet. */
    fun send(data: ByteArray) {
        val len = minOf(data.size, 255)
        standby()
        writeReg(REG_FIFO_ADDR_PTR, 0x80)
        val payload = ByteArray(len)
        System.arraycopy(data, 0, payload, 0, len)
        burstWrite(REG_FIFO, payload)
        writeReg(REG_PAYLOAD_LENGTH, len)
        writeReg(REG_DIO_MAPPING_1, DIO0_TX_DONE)
        writeReg(REG_OP_MODE, MODE_LONG_RANGE or bandFlag() or MODE_TX)
        while (true) {
            val irq = readReg(REG_IRQ_FLAGS)
            if ((irq and IRQ_TX_DONE) != 0) break
            sleepMs(2)
        }
        writeReg(REG_IRQ_FLAGS, IRQ_TX_DONE)
        standby()
    }

    /**
     * Receive a single packet.
     *
     * @return received payload bytes, or null on timeout
     */
    fun receive(timeoutMs: Int = 2000): ByteArray? {
        val t = if (timeoutMs <= 0) 2000 else timeoutMs
        standby()
        writeReg(REG_DIO_MAPPING_1, DIO0_RX_DONE)
        writeReg(REG_OP_MODE, MODE_LONG_RANGE or bandFlag() or MODE_RX_SINGLE)
        var elapsed = 0
        while (elapsed < t) {
            val irq = readReg(REG_IRQ_FLAGS)
            if ((irq and IRQ_RX_DONE) != 0) {
                writeReg(REG_IRQ_FLAGS, IRQ_RX_DONE)
                return readPayload()
            }
            if ((irq and IRQ_RX_TIMEOUT) != 0) {
                writeReg(REG_IRQ_FLAGS, IRQ_RX_TIMEOUT)
                return null
            }
            sleepMs(5)
            elapsed += 5
        }
        writeReg(REG_OP_MODE, MODE_LONG_RANGE or bandFlag() or MODE_STANDBY)
        return null
    }

    protected fun readPayload(): ByteArray {
        val current = readReg(REG_FIFO_RX_CURRENT)
        writeReg(REG_FIFO_ADDR_PTR, current)
        val n = readReg(REG_RX_NB_BYTES) and 0xFF
        return burstRead(REG_FIFO, n)
    }

    /** Enter continuous receive mode. */
    protected open fun receiveContinuous() {
        standby()
        writeReg(REG_DIO_MAPPING_1, DIO0_RX_DONE)
        writeReg(REG_OP_MODE, MODE_LONG_RANGE or bandFlag() or MODE_RX_CONT)
    }

    /** Read one packet from the FIFO in continuous receive mode. */
    protected open fun readPacket(): ByteArray? {
        val irq = readReg(REG_IRQ_FLAGS)
        if ((irq and IRQ_RX_DONE) == 0) return null
        writeReg(REG_IRQ_FLAGS, IRQ_RX_DONE)
        return readPayload()
    }

    /** Return to STDBY from continuous receive mode. */
    protected open fun stopReceive() { standby() }

    /** Current channel RSSI in dBm. */
    protected open fun rssi(): Float = -137.0f + readReg(REG_RSSI)

    /** RSSI of last received packet in dBm. */
    protected open fun lastPacketRssi(): Float = -137.0f + readReg(REG_PKT_RSSI)

    /** SNR of last received packet in dB. */
    protected open fun lastPacketSnr(): Float {
        var raw = readReg(REG_PKT_SNR)
        if ((raw and 0x80) != 0) raw -= 0x100
        return raw / 4.0f
    }

    protected fun bandFlag(): Int = if (lfBand()) 0x08 else 0x00

    companion object {
        const val REG_FIFO            = 0x00
        const val REG_OP_MODE         = 0x01
        const val REG_FRF_MSB         = 0x06
        const val REG_FRF_MID         = 0x07
        const val REG_FRF_LSB         = 0x08
        const val REG_PA_CONFIG       = 0x09
        const val REG_OCP             = 0x0B
        const val REG_LNA             = 0x0C
        const val REG_FIFO_ADDR_PTR   = 0x0D
        const val REG_FIFO_TX_BASE    = 0x0E
        const val REG_FIFO_RX_BASE    = 0x0F
        const val REG_FIFO_RX_CURRENT = 0x10
        const val REG_IRQ_FLAGS       = 0x12
        const val REG_RX_NB_BYTES     = 0x13
        const val REG_PKT_SNR         = 0x19
        const val REG_PKT_RSSI        = 0x1A
        const val REG_RSSI            = 0x1B
        const val REG_MODEM_CONFIG_1  = 0x1D
        const val REG_MODEM_CONFIG_2  = 0x1E
        const val REG_PREAMBLE_LSB    = 0x21
        const val REG_PAYLOAD_LENGTH  = 0x22
        const val REG_MODEM_CONFIG_3  = 0x26
        const val REG_DETECTION_OPT   = 0x31
        const val REG_DETECTION_THR   = 0x37
        const val REG_DIO_MAPPING_1   = 0x40
        const val REG_VERSION         = 0x42
        const val REG_PA_DAC          = 0x4D

        const val MODE_LONG_RANGE = 0x80
        const val MODE_SLEEP      = 0x00
        const val MODE_STANDBY    = 0x01
        const val MODE_TX         = 0x03
        const val MODE_RX_CONT    = 0x05
        const val MODE_RX_SINGLE  = 0x06

        const val IRQ_TX_DONE    = 0x08
        const val IRQ_RX_DONE    = 0x40
        const val IRQ_RX_TIMEOUT = 0x80

        const val PA_BOOST          = 0x80
        const val PA_DAC_HIGH_POWER = 0x87
        const val PA_DAC_DEFAULT    = 0x84
        const val OCP_240MA         = 0x3B
        const val OCP_DEFAULT       = 0x2B

        const val DIO0_RX_DONE = 0x00
        const val DIO0_TX_DONE = 0x40

        const val FXOSC            = 32_000_000L
        const val EXPECTED_VERSION = 0x12
    }
}

/**
 * RFM95W minimal driver — 868/915 MHz HF band, max SF=12.
 */
class Rfm95Minimal(transport: Connection, frequencyHz: Long) : _Rfm9xBase(transport, frequencyHz) {
    override fun freqMinHz(): Long = 862_000_000L
    override fun freqMaxHz(): Long = 1_020_000_000L
    override fun maxSf(): Int     = 12
    override fun lfBand(): Boolean = false
}

/**
 * RFM96W minimal driver — 433/470 MHz LF band, max SF=12.
 */
class Rfm96Minimal(transport: Connection, frequencyHz: Long) : _Rfm9xBase(transport, frequencyHz) {
    override fun freqMinHz(): Long = 410_000_000L
    override fun freqMaxHz(): Long =  525_000_000L
    override fun maxSf(): Int     = 12
    override fun lfBand(): Boolean = true
}

/**
 * RFM97W minimal driver — 868/915 MHz HF band, max SF=9.
 */
class Rfm97Minimal(transport: Connection, frequencyHz: Long) : _Rfm9xBase(transport, frequencyHz) {
    override fun freqMinHz(): Long = 862_000_000L
    override fun freqMaxHz(): Long = 1_020_000_000L
    override fun maxSf(): Int     = 9
    override fun lfBand(): Boolean = false
}

/**
 * RFM98W minimal driver — 433/470 MHz LF band, max SF=12.
 */
class Rfm98Minimal(transport: Connection, frequencyHz: Long) : _Rfm9xBase(transport, frequencyHz) {
    override fun freqMinHz(): Long = 410_000_000L
    override fun freqMaxHz(): Long =  525_000_000L
    override fun maxSf(): Int     = 12
    override fun lfBand(): Boolean = true
}

/**
 * RFM9x full driver — extends _Rfm9xBase with reset(), interrupt-driven
 * receive, and re-exposes the full API.
 */
abstract class _Rfm9xFull protected constructor(
    transport: Connection,
    frequencyHz: Long,
    resetPin: OutputPin? = null,
    protected val dio0Pin: InputPin? = null,
) : _Rfm9xBase(transport, frequencyHz, resetPin) {

    /**
     * Hardware reset. Drives NRESET low/high when a reset pin was configured,
     * otherwise waits out the POR delay; then re-runs the full init sequence.
     */
    fun reset() {
        if (resetPin != null) {
            resetViaPin()
        } else {
            sleepMs(5)
        }
        initRegisters()
    }

    public override fun configure(sf: Int, bandwidthKhz: Float, codingRate: Int, crc: Boolean) = super.configure(sf, bandwidthKhz, codingRate, crc)
    public override fun setFrequency(frequencyHz: Long) = super.setFrequency(frequencyHz)
    public override fun setTxPower(powerDbm: Int, usePaBoost: Boolean) = super.setTxPower(powerDbm, usePaBoost)
    public override fun standby() = super.standby()
    public override fun sleep() = super.sleep()
    public override fun version(): Int = super.version()
    public override fun receiveContinuous() = super.receiveContinuous()
    public override fun readPacket(): ByteArray? = super.readPacket()
    public override fun stopReceive() = super.stopReceive()
    public override fun rssi(): Float = super.rssi()
    public override fun lastPacketRssi(): Float = super.lastPacketRssi()
    public override fun lastPacketSnr(): Float = super.lastPacketSnr()

    /**
     * Receive a single packet, optionally waiting on the DIO0 interrupt line
     * instead of polling the IRQ status register.
     *
     * @param useInterrupt true to wait on the DIO0 edge instead of polling
     *                      (requires a `dio0Pin` passed to the constructor)
     * @return received payload bytes, or null on timeout
     * @throws IllegalStateException if [useInterrupt] is true but no `dio0Pin` was configured
     */
    fun receive(timeoutMs: Int = 2000, useInterrupt: Boolean = false): ByteArray? {
        if (!useInterrupt) {
            return super.receive(timeoutMs)
        }
        val pin = dio0Pin ?: throw IllegalStateException("useInterrupt=true requires dio0Pin")
        return receiveInterrupt(timeoutMs, pin)
    }

    private fun receiveInterrupt(timeoutMs: Int, pin: InputPin): ByteArray? {
        val t = if (timeoutMs <= 0) 2000 else timeoutMs
        standby()
        writeReg(REG_DIO_MAPPING_1, DIO0_RX_DONE)

        val latch = CountDownLatch(1)
        val handler = EdgeHandler { latch.countDown() }
        pin.onEdge(handler, EdgeTrigger.RISING)
        try {
            writeReg(REG_OP_MODE, MODE_LONG_RANGE or bandFlag() or MODE_RX_SINGLE)

            val fired = try {
                latch.await(t.toLong(), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!fired) {
                writeReg(REG_OP_MODE, MODE_LONG_RANGE or bandFlag() or MODE_STANDBY)
                return null
            }

            val irq = readReg(REG_IRQ_FLAGS)
            writeReg(REG_IRQ_FLAGS, irq)
            return if ((irq and IRQ_RX_DONE) != 0) readPayload() else null
        } finally {
            pin.offEdge(handler)
        }
    }
}

/** RFM95W full driver. */
class Rfm95Full(
    transport: Connection,
    frequencyHz: Long,
    resetPin: OutputPin? = null,
    dio0Pin: InputPin? = null,
) : _Rfm9xFull(transport, frequencyHz, resetPin, dio0Pin) {
    override fun freqMinHz(): Long = 862_000_000L
    override fun freqMaxHz(): Long = 1_020_000_000L
    override fun maxSf(): Int     = 12
    override fun lfBand(): Boolean = false
}

/** RFM96W full driver. */
class Rfm96Full(
    transport: Connection,
    frequencyHz: Long,
    resetPin: OutputPin? = null,
    dio0Pin: InputPin? = null,
) : _Rfm9xFull(transport, frequencyHz, resetPin, dio0Pin) {
    override fun freqMinHz(): Long = 410_000_000L
    override fun freqMaxHz(): Long =  525_000_000L
    override fun maxSf(): Int     = 12
    override fun lfBand(): Boolean = true
}

/** RFM97W full driver. */
class Rfm97Full(
    transport: Connection,
    frequencyHz: Long,
    resetPin: OutputPin? = null,
    dio0Pin: InputPin? = null,
) : _Rfm9xFull(transport, frequencyHz, resetPin, dio0Pin) {
    override fun freqMinHz(): Long = 862_000_000L
    override fun freqMaxHz(): Long = 1_020_000_000L
    override fun maxSf(): Int     = 9
    override fun lfBand(): Boolean = false
}

/** RFM98W full driver. */
class Rfm98Full(
    transport: Connection,
    frequencyHz: Long,
    resetPin: OutputPin? = null,
    dio0Pin: InputPin? = null,
) : _Rfm9xFull(transport, frequencyHz, resetPin, dio0Pin) {
    override fun freqMinHz(): Long = 410_000_000L
    override fun freqMaxHz(): Long =  525_000_000L
    override fun maxSf(): Int     = 12
    override fun lfBand(): Boolean = true
}
