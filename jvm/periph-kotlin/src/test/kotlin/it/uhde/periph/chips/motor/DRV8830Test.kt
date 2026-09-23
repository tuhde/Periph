package it.uhde.periph.chips.motor

import it.uhde.periph.connection.MockConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DRV8830Test {

    private fun lastWrite(conn: MockConnection, reg: Int): Int =
        conn.writes().lastOrNull { it.size == 2 && (it[0].toInt() and 0xFF) == reg }?.let { it[1].toInt() and 0xFF } ?: -1

    @Test
    fun initMakesNoRegisterWrites() {
        val conn = MockConnection()
        DRV8830Minimal(conn)
        assertEquals(1, conn.writes().size)
        assertEquals(1, conn.writes()[0].size)
    }

    @Test
    fun driveConvertsVoltageAndDirection() {
        val conn = MockConnection()
        val motor = DRV8830Minimal(conn)
        motor.drive(3.0)
        assertEquals((37 shl 2) or 0x01, lastWrite(conn, 0x00))
        motor.drive(-2.0)
        assertEquals((25 shl 2) or 0x02, lastWrite(conn, 0x00))
        motor.drive(0.4)
        assertEquals(0x00, lastWrite(conn, 0x00))
        motor.drive(0.48)
        assertEquals((6 shl 2) or 0x01, lastWrite(conn, 0x00))
        motor.drive(9.0)
        assertEquals((63 shl 2) or 0x01, lastWrite(conn, 0x00))
        motor.brake()
        assertEquals(0x03, lastWrite(conn, 0x00))
        motor.stop()
        assertEquals(0x00, lastWrite(conn, 0x00))
    }

    @Test
    fun setOutputWritesRawFieldsAndRejectsReserved() {
        val conn = MockConnection()
        val motor = DRV8830Full(conn)
        motor.setOutput(20, false, true)
        assertEquals((20 shl 2) or 0x02, lastWrite(conn, 0x00))
        val before = conn.writes().size
        assertThrows(IllegalArgumentException::class.java) { motor.setOutput(5, true, false) }
        assertEquals(before, conn.writes().size)
    }

    @Test
    fun readOutputDecodesControl() {
        val conn = MockConnection()
        val motor = DRV8830Full(conn)
        conn.setRegister(0x00, (63 shl 2) or 0x01)
        val out = motor.readOutput()
        assertEquals(DRV8830Full.Direction.FORWARD, out.direction)
        assertEquals(5.06, out.voltage, 0.01)
        conn.setRegister(0x00, (16 shl 2) or 0x02)
        assertEquals(1.285, motor.readOutput().voltage, 0.001)
        conn.setRegister(0x00, 0x03)
        assertEquals(DRV8830Full.Output(0.0, DRV8830Full.Direction.BRAKE), motor.readOutput())
    }

    @Test
    fun faultsAreReportedNotClearedUntilAsked() {
        val conn = MockConnection()
        conn.setRegister(0x01, 0x11)
        val motor = DRV8830Full(conn)
        assertEquals(DRV8830Full.Fault(true, false, false, false, true), motor.readFault())
        conn.setRegister(0x01, 0x0F)
        val f = motor.pollInterrupt()
        assertTrue(f.fault && f.ocp && f.uvlo && f.ots)
        assertFalse(f.ilimit)
        assertEquals(-1, lastWrite(conn, 0x01))
        motor.clearFault()
        assertEquals(0x80, lastWrite(conn, 0x01))
    }
}
