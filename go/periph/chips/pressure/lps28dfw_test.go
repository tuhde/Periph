package pressure

import (
	"testing"
)

func newLPS28DFWConnection() *mockConnection {
	c := newMockConnection()
	c.setRegister(0x0F, 0xB4) // WHO_AM_I
	return c
}

func lastWriteToReg(conn *mockConnection, reg byte) (byte, bool) {
	for i := len(conn.writes) - 1; i >= 0; i-- {
		w := conn.writes[i]
		if len(w) == 2 && w[0] == reg {
			return w[1], true
		}
	}
	return 0, false
}

func TestLPS28DFWMinimalConstructionAndRead(t *testing.T) {
	conn := newLPS28DFWConnection()
	chip, err := NewLPS28DFWMinimal(conn)
	if err != nil {
		t.Fatalf("NewLPS28DFWMinimal: %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0x10); v != 0x22 {
		t.Errorf("init CTRL_REG1 = %#x, want 0x22", v)
	}
	if v, _ := lastWriteToReg(conn, 0x11); v != 0x18 {
		t.Errorf("init CTRL_REG2 = %#x, want 0x18", v)
	}

	conn.setRegister(0x28, 0x00, 0x54, 0x3F) // 1013.25 hPa
	p, err := chip.ReadPressure()
	if err != nil || abs32(p-1013.25) > 0.001 {
		t.Errorf("ReadPressure() = %v, %v, want ~1013.25", p, err)
	}

	conn.setRegister(0x2B, 0x2E, 0x09) // 23.5 C
	temp, err := chip.ReadTemperature()
	if err != nil || abs32(temp-23.5) > 0.001 {
		t.Errorf("ReadTemperature() = %v, %v, want ~23.5", temp, err)
	}
}

func abs32(v float32) float32 {
	if v < 0 {
		return -v
	}
	return v
}

func TestLPS28DFWMinimalConstructionBadChipID(t *testing.T) {
	conn := newMockConnection()
	conn.setRegister(0x0F, 0x00)
	if _, err := NewLPS28DFWMinimal(conn); err == nil {
		t.Error("NewLPS28DFWMinimal with bad WHO_AM_I: expected error, got nil")
	}
}

func TestLPS28DFWMinimalNegativeValues(t *testing.T) {
	conn := newLPS28DFWConnection()
	chip, _ := NewLPS28DFWMinimal(conn)

	conn.setRegister(0x28, 0x00, 0xE0, 0xFC) // -50.0 hPa
	p, err := chip.ReadPressure()
	if err != nil || abs32(p-(-50.0)) > 0.001 {
		t.Errorf("ReadPressure() negative = %v, %v, want ~-50.0", p, err)
	}

	conn.setRegister(0x2B, 0x18, 0xFC) // -10.0 C
	temp, err := chip.ReadTemperature()
	if err != nil || abs32(temp-(-10.0)) > 0.001 {
		t.Errorf("ReadTemperature() negative = %v, %v, want ~-10.0", temp, err)
	}
}

func TestLPS28DFWFullConfigureAndRead(t *testing.T) {
	conn := newLPS28DFWConnection()
	full, err := NewLPS28DFWFull(conn)
	if err != nil {
		t.Fatalf("NewLPS28DFWFull: %v", err)
	}

	if err := full.Configure(LPS28DFWODR50Hz, LPS28DFWAVG64, 1, 1, true); err != nil {
		t.Fatalf("Configure: %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0x11); v != 0x78 {
		t.Errorf("Configure CTRL_REG2 = %#x, want 0x78", v)
	}
	if v, _ := lastWriteToReg(conn, 0x10); v != 0x2C {
		t.Errorf("Configure CTRL_REG1 = %#x, want 0x2C", v)
	}

	conn.setRegister(0x28, 0x00, 0x80, 0x3E, 0x21, 0x07) // 2000 hPa mode2, 18.25 C
	p, temp, err := full.Read()
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if abs32(p-2000.0) > 0.001 || abs32(temp-18.25) > 0.001 {
		t.Errorf("Read() = (%v, %v), want (2000.0, 18.25)", p, temp)
	}
}

func TestLPS28DFWFullIsDataReady(t *testing.T) {
	conn := newLPS28DFWConnection()
	full, _ := NewLPS28DFWFull(conn)

	conn.setRegister(0x27, 0x01)
	ready, err := full.IsDataReady()
	if err != nil || !ready {
		t.Errorf("IsDataReady() = %v, %v, want true, nil", ready, err)
	}
	conn.setRegister(0x27, 0x00)
	ready, err = full.IsDataReady()
	if err != nil || ready {
		t.Errorf("IsDataReady() = %v, %v, want false, nil", ready, err)
	}
}

func TestLPS28DFWFullReadOneshot(t *testing.T) {
	conn := newLPS28DFWConnection()
	full, _ := NewLPS28DFWFull(conn)

	conn.setRegister(0x10, 0x22) // saved CTRL_REG1 (ODR=4)
	conn.setRegister(0x11, 0x18) // saved CTRL_REG2
	conn.setRegister(0x27, 0x01) // P_DA already set
	conn.setRegister(0x28, 0x00, 0x80, 0x3E)
	conn.setRegister(0x2B, 0xD0, 0x07)

	p, temp, err := full.ReadOneshot()
	if err != nil {
		t.Fatalf("ReadOneshot: %v", err)
	}
	if abs32(p-1000.0) > 0.001 || abs32(temp-20.0) > 0.001 {
		t.Errorf("ReadOneshot() = (%v, %v), want (1000.0, 20.0)", p, temp)
	}
	if v, _ := lastWriteToReg(conn, 0x10); v != 0x22 {
		t.Errorf("ReadOneshot did not restore ODR: CTRL_REG1 = %#x, want 0x22", v)
	}
}

func TestLPS28DFWFullSetOffsetAndSoftreset(t *testing.T) {
	conn := newLPS28DFWConnection()
	full, _ := NewLPS28DFWFull(conn)
	full.fsMode = 1 // Mode 2

	if err := full.SetOffset(-0.5); err != nil { // -0.5*2048 = -1024 = 0xFC00
		t.Fatalf("SetOffset: %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0x1A); v != 0x00 {
		t.Errorf("SetOffset RPDS_L = %#x, want 0x00", v)
	}
	if v, _ := lastWriteToReg(conn, 0x1B); v != 0xFC {
		t.Errorf("SetOffset RPDS_H = %#x, want 0xFC", v)
	}

	// CTRL_REG2 is currently 0x18 from construction (SetOffset above never
	// touches it) -> Softreset ORs in SWRESET=0x02.
	if err := full.Softreset(); err != nil {
		t.Fatalf("Softreset: %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0x11); v != 0x1A {
		t.Errorf("Softreset CTRL_REG2 = %#x, want 0x1A", v)
	}
}

func TestLPS28DFWFullFIFOConfigureBypassPassThroughRegression(t *testing.T) {
	// Regression: switching directly to a non-bypass mode must still write
	// FIFO_CTRL=0x00 (Bypass) first, per the spec's FIFO reset procedure --
	// the buggy version only did this when the target mode itself was Bypass.
	conn := newLPS28DFWConnection()
	full, _ := NewLPS28DFWFull(conn)

	if err := full.FIFOConfigure(LPS28DFWFIFOContinuous, 50, true); err != nil {
		t.Fatalf("FIFOConfigure: %v", err)
	}
	var fifoCtrlWrites []byte
	for _, w := range conn.writes {
		if len(w) == 2 && w[0] == 0x14 {
			fifoCtrlWrites = append(fifoCtrlWrites, w[1])
		}
	}
	if len(fifoCtrlWrites) < 2 || fifoCtrlWrites[0] != 0x00 {
		t.Errorf("FIFO_CTRL writes = %v, want a 0x00 bypass write before the final mode write", fifoCtrlWrites)
	}
	if got := fifoCtrlWrites[len(fifoCtrlWrites)-1]; got != 0x0A {
		t.Errorf("final FIFO_CTRL = %#x, want 0x0A (TRIG=0,STOP=1,F_MODE=10)", got)
	}
	if v, _ := lastWriteToReg(conn, 0x15); v != 50 {
		t.Errorf("FIFO_WTM = %d, want 50", v)
	}
}

func TestLPS28DFWFullFIFOReadAndLevel(t *testing.T) {
	conn := newLPS28DFWConnection()
	full, _ := NewLPS28DFWFull(conn)

	conn.setRegister(0x78,
		0x00, 0x80, 0x3E, // 1000.0 hPa
		0x00, 0x20, 0x3F, // 1010.0 hPa
		0x00, 0xC0, 0x3F, // 1020.0 hPa
	)
	samples, err := full.FIFORead(3)
	if err != nil {
		t.Fatalf("FIFORead: %v", err)
	}
	if len(samples) != 3 || abs32(samples[0]-1000.0) > 0.01 || abs32(samples[1]-1010.0) > 0.01 || abs32(samples[2]-1020.0) > 0.01 {
		t.Errorf("FIFORead() = %v", samples)
	}

	conn.setRegister(0x25, 42)
	level, err := full.FIFOLevel()
	if err != nil || level != 42 {
		t.Errorf("FIFOLevel() = %v, %v, want 42, nil", level, err)
	}
}

func TestLPS28DFWFullSetThreshold(t *testing.T) {
	conn := newLPS28DFWConnection()
	full, _ := NewLPS28DFWFull(conn)
	conn.setRegister(0x0B, 0x00)

	if err := full.SetThreshold(1020.0, true, true); err != nil { // Mode 1: 1020*16=16320=0x3FC0
		t.Fatalf("SetThreshold: %v", err)
	}
	if v, _ := lastWriteToReg(conn, 0x0C); v != 0xC0 {
		t.Errorf("THS_P_L = %#x, want 0xC0", v)
	}
	if v, _ := lastWriteToReg(conn, 0x0D); v != 0x3F {
		t.Errorf("THS_P_H = %#x, want 0x3F", v)
	}
	if v, _ := lastWriteToReg(conn, 0x0B); v != 0x03 {
		t.Errorf("INTERRUPT_CFG = %#x, want 0x03", v)
	}
}

func TestLPS28DFWFullChipID(t *testing.T) {
	conn := newLPS28DFWConnection()
	full, _ := NewLPS28DFWFull(conn)
	id, err := full.ChipID()
	if err != nil || id != 0xB4 {
		t.Errorf("ChipID() = %#x, %v, want 0xB4, nil", id, err)
	}
}
