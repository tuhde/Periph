package it.uhde.periph.chips.other

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * PCA9541 full interface — extends [PCA9541Minimal] with raw control access,
 * connect / disconnect, bus recovery, interrupt masks, the INT functional test
 * and interrupt delivery.
 *
 * @param connection I²C register connection bound to the device (0x70–0x77)
 */
class PCA9541Full(connection: RegisterConnection) : PCA9541Minimal(connection) {

    /** Decoded CONTROL register. */
    data class Status(
        val hasControl: Boolean,
        val connected: Boolean,
        val busInitRequested: Boolean,
        val ownTest: Boolean,
        val otherTest: Boolean,
    )

    companion object {
        /** ISTAT / interrupt source bits. */
        const val SOURCE_INT_IN = 0x01
        const val SOURCE_BUS_INIT = 0x02
        const val SOURCE_BUS_NOT_IDLE = 0x04
        const val SOURCE_BUS_LOST = 0x08
        const val SOURCE_TEST = 0x40
        const val SOURCE_OTHER_TEST = 0x80
    }

    @Volatile private var callback: ((Int) -> Unit)? = null
    private var intPinUsed: InputPin? = null
    private var pollThread: Thread? = null
    @Volatile private var polling = false

    /** Fixed edge handler so offEdge() can remove the exact instance that was registered. */
    private val edgeHandler = EdgeHandler { handleEdge() }

    /** Read the raw CONTROL byte. */
    fun getControl(): Int = readReg(REG_CONTROL)

    /** Whether the downstream bus is connected to some master (BUSON differs from NBUSON). */
    fun isConnected(): Boolean = connectedBits(readReg(REG_CONTROL))

    /** Decode the CONTROL register. */
    fun status(): Status {
        val c = readReg(REG_CONTROL)
        return Status(hasControlBits(c), connectedBits(c), c and C_BUSINIT != 0, c and C_TESTON != 0, c and C_NTESTON != 0)
    }

    /** Turn the connection on without changing ownership (BUSON = NOT NBUSON; MYBUS as read). */
    fun connect() {
        val c = readReg(REG_CONTROL)
        writeReg(REG_CONTROL, ((1 - nbuson(c)) shl 2) or (c and C_MYBUS))
    }

    /** Disconnect both masters from the downstream bus (BUSON = NBUSON; ownership bits left). */
    fun disconnect() {
        val c = readReg(REG_CONTROL)
        writeReg(REG_CONTROL, (nbuson(c) shl 2) or (c and C_MYBUS))
    }

    /**
     * Recover a hung downstream bus and take it (acquire with bus initialization).
     *
     * @param timeoutMs maximum time to wait for BUSINIT, in milliseconds
     * @return true if this master has control afterwards
     */
    fun recoverBus(timeoutMs: Int = 20): Boolean = acquire(true, timeoutMs)

    /**
     * Write the IE register (true = masked, no interrupt); all-false re-enables every interrupt.
     *
     * @param busLost mask the "other master took the bus" interrupt
     * @param busNotIdle mask the "bus non-idle at the switch" interrupt
     * @param busInit mask the "bus initialization done" interrupt
     * @param intIn mask the downstream INT_IN interrupt
     */
    fun setInterruptMask(busLost: Boolean = false, busNotIdle: Boolean = false, busInit: Boolean = false, intIn: Boolean = false) {
        writeReg(REG_IE, (if (busLost) 8 else 0) or (if (busNotIdle) 4 else 0) or (if (busInit) 2 else 0) or (if (intIn) 1 else 0))
    }

    /** Read the IE register (bit set = interrupt masked). */
    fun getInterruptMask(): Int = readReg(REG_IE)

    /**
     * Drive the INT lines for the functional test; the bus bits are written back unchanged.
     *
     * @param own true to drive this master's INT low (TESTON)
     * @param other true to drive the other master's INT low (NTESTON)
     */
    fun testInterrupt(own: Boolean = false, other: Boolean = false) {
        val c = readReg(REG_CONTROL)
        writeReg(REG_CONTROL, (c and (C_BUSON or C_MYBUS)) or (if (own) C_TESTON else 0) or (if (other) C_NTESTON else 0))
    }

    /**
     * Read IE, CONTROL and ISTAT in one auto-increment transaction (clears the self-clearing ISTAT flags).
     *
     * @return (ie, control, istat)
     */
    fun readAll(): Triple<Int, Int, Int> {
        val b = connection.read(AI or REG_IE, 3)
        return Triple(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF, b[2].toInt() and 0xFF)
    }

    /**
     * Enable an interrupt source (clear its mask bit in IE); the test sources need no enable.
     *
     * @param source one of [SOURCE_INT_IN], [SOURCE_BUS_INIT], [SOURCE_BUS_NOT_IDLE], [SOURCE_BUS_LOST]
     */
    fun enableInterrupt(source: Int) {
        val bit = source and 0x0F
        if (bit != 0) writeReg(REG_IE, readReg(REG_IE) and bit.inv())
    }

    /**
     * Disable an interrupt source (set its mask bit in IE).
     *
     * @param source one of [SOURCE_INT_IN], [SOURCE_BUS_INIT], [SOURCE_BUS_NOT_IDLE], [SOURCE_BUS_LOST]
     */
    fun disableInterrupt(source: Int) {
        val bit = source and 0x0F
        if (bit != 0) writeReg(REG_IE, readReg(REG_IE) or bit)
    }

    /**
     * Read ISTAT (clears BUSLOST / BUSOK / BUSINIT).
     *
     * @return ISTAT byte; bits as the `SOURCE_*` constants
     */
    fun pollInterrupt(): Int = readReg(REG_ISTAT)

    /**
     * Subscribe to this master's INT pin (falling edge). Falls back to a 100 ms
     * polling thread if no [InputPin] is available.
     *
     * @param intPin overrides `connection.intPin()`, or null
     * @param callback called with the ISTAT byte that was read
     */
    @Synchronized
    fun onInterrupt(intPin: InputPin? = null, callback: (Int) -> Unit) {
        this.callback = callback
        startDelivery(intPin ?: connection.intPin())
    }

    private fun startDelivery(pin: InputPin?) {
        stopDelivery()
        intPinUsed = pin
        if (pin != null) {
            pin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = Thread({
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(100)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }, "pca9541-poll").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    private fun stopDelivery() {
        intPinUsed?.offEdge(edgeHandler)
        intPinUsed = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    /** Unsubscribe and stop delivery. */
    @Synchronized
    fun offInterrupt() {
        stopDelivery()
        callback = null
    }

    private fun handleEdge() {
        try {
            val status = pollInterrupt()
            if (status != 0) callback?.invoke(status)
        } catch (ignored: IOException) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }
}
