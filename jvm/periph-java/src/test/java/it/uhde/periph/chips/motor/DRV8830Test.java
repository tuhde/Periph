package it.uhde.periph.chips.motor;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DRV8830Test {

    private static int lastWrite(MockConnection connection, int reg) {
        int value = -1;
        for (byte[] w : connection.writes()) {
            if (w.length == 2 && (w[0] & 0xFF) == reg) value = w[1] & 0xFF;
        }
        return value;
    }

    @Test
    void initMakesNoRegisterWrites() throws Exception {
        MockConnection connection = new MockConnection();
        new DRV8830Minimal(connection);
        List<byte[]> writes = connection.writes();
        assertEquals(1, writes.size());
        assertEquals(1, writes.get(0).length);
        assertEquals(0x00, writes.get(0)[0]);
    }

    @Test
    void driveConvertsVoltageAndDirection() throws Exception {
        MockConnection connection = new MockConnection();
        DRV8830Minimal motor = new DRV8830Minimal(connection);
        motor.drive(3.0);
        assertEquals((37 << 2) | 0x01, lastWrite(connection, 0x00));
        motor.drive(-2.0);
        assertEquals((25 << 2) | 0x02, lastWrite(connection, 0x00));
        motor.drive(0.0);
        assertEquals(0x00, lastWrite(connection, 0x00));
        motor.drive(0.4);
        assertEquals(0x00, lastWrite(connection, 0x00));
        motor.drive(0.48);
        assertEquals((6 << 2) | 0x01, lastWrite(connection, 0x00));
        motor.drive(9.0);
        assertEquals((63 << 2) | 0x01, lastWrite(connection, 0x00));
        motor.brake();
        assertEquals(0x03, lastWrite(connection, 0x00));
        motor.stop();
        assertEquals(0x00, lastWrite(connection, 0x00));
    }

    @Test
    void setOutputWritesRawFieldsAndRejectsReserved() throws Exception {
        MockConnection connection = new MockConnection();
        DRV8830Full motor = new DRV8830Full(connection);
        motor.setOutput(20, false, true);
        assertEquals((20 << 2) | 0x02, lastWrite(connection, 0x00));
        int before = connection.writes().size();
        assertThrows(IllegalArgumentException.class, () -> motor.setOutput(5, true, false));
        assertEquals(before, connection.writes().size());
    }

    @Test
    void readOutputDecodesControl() throws Exception {
        MockConnection connection = new MockConnection();
        DRV8830Full motor = new DRV8830Full(connection);
        connection.setRegister(0x00, (63 << 2) | 0x01);
        DRV8830Full.Output out = motor.readOutput();
        assertEquals(DRV8830Full.Direction.FORWARD, out.direction());
        assertEquals(5.06, out.voltage(), 0.01);
        connection.setRegister(0x00, (16 << 2) | 0x02);
        out = motor.readOutput();
        assertEquals(DRV8830Full.Direction.REVERSE, out.direction());
        assertEquals(1.285, out.voltage(), 0.001);
        connection.setRegister(0x00, 0x03);
        assertEquals(new DRV8830Full.Output(0.0, DRV8830Full.Direction.BRAKE), motor.readOutput());
        connection.setRegister(0x00, 0x00);
        assertEquals(new DRV8830Full.Output(0.0, DRV8830Full.Direction.COAST), motor.readOutput());
    }

    @Test
    void faultsAreReportedNotClearedUntilAsked() throws Exception {
        MockConnection connection = new MockConnection();
        connection.setRegister(0x01, 0x11);
        DRV8830Full motor = new DRV8830Full(connection);
        assertEquals(new DRV8830Full.Fault(true, false, false, false, true), motor.readFault());
        connection.setRegister(0x01, 0x0F);
        DRV8830Full.Fault f = motor.pollInterrupt();
        assertTrue(f.fault() && f.ocp() && f.uvlo() && f.ots());
        assertFalse(f.ilimit());
        assertEquals(-1, lastWrite(connection, 0x01));
        motor.clearFault();
        assertEquals(0x80, lastWrite(connection, 0x01));
    }
}
