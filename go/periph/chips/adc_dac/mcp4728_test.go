package adcdac

import "testing"

func TestMCP4728FullAPI(t *testing.T) {
	conn := newMockConnection()
	dac, err := NewMCP4728Full(conn)
	if err != nil {
		t.Fatalf("NewMCP4728Full: %v", err)
	}

	// SetVoltage(1, 0.5): code = uint16(0.5*4095.0) truncates to 2047 (0x7FF).
	// Multi-Write byte1=0x42, byte2=0x07, byte3=0xFF.
	if err := dac.SetVoltage(1, 0.5); err != nil {
		t.Fatalf("SetVoltage: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x42, 0x07, 0xFF}) {
		t.Errorf("SetVoltage(1, 0.5) write = %v, want [0x42 0x07 0xFF]", lastWrite(conn.writes))
	}

	if err := dac.SetVoltage(1, 2.0); err != nil {
		t.Fatalf("SetVoltage: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x42, 0x0F, 0xFF}) {
		t.Errorf("SetVoltage(1, 2.0) clamp write = %v, want [0x42 0x0F 0xFF]", lastWrite(conn.writes))
	}

	// SetRaw(3, 4095) -> byte1=0x46, byte2=0x0F, byte3=0xFF.
	if err := dac.SetRaw(3, 4095); err != nil {
		t.Fatalf("SetRaw: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x46, 0x0F, 0xFF}) {
		t.Errorf("SetRaw(3, 4095) write = %v, want [0x46 0x0F 0xFF]", lastWrite(conn.writes))
	}

	if err := dac.SetRaw(9, 9000); err != nil {
		t.Fatalf("SetRaw: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x46, 0x0F, 0xFF}) {
		t.Errorf("SetRaw(9, 9000) clamp write = %v, want [0x46 0x0F 0xFF]", lastWrite(conn.writes))
	}

	// SetAll([0.0, 1.0, 0.5, 0.25]): truncation gives codes 0, 4095, 2047, 1023.
	if err := dac.SetAll([4]float32{0.0, 1.0, 0.5, 0.25}); err != nil {
		t.Fatalf("SetAll: %v", err)
	}
	want := []byte{0x00, 0x00, 0x0F, 0xFF, 0x07, 0xFF, 0x03, 0xFF}
	if !bytesEqual(lastWrite(conn.writes), want) {
		t.Errorf("SetAll write = %v, want %v", lastWrite(conn.writes), want)
	}

	// SetVoltageEEPROM(2, 0.5, vref=1, gain=2): code truncates to 2047.
	if err := dac.SetVoltageEEPROM(2, 0.5, 1, 2); err != nil {
		t.Fatalf("SetVoltageEEPROM: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x5C, 0x97, 0xFF}) {
		t.Errorf("SetVoltageEEPROM write = %v, want [0x5C 0x97 0xFF]", lastWrite(conn.writes))
	}

	// SetRawEEPROM(0, 4095, vref=0, gain=1).
	if err := dac.SetRawEEPROM(0, 4095, 0, 1); err != nil {
		t.Fatalf("SetRawEEPROM: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x58, 0x0F, 0xFF}) {
		t.Errorf("SetRawEEPROM write = %v, want [0x58 0x0F 0xFF]", lastWrite(conn.writes))
	}

	// SetAllEEPROM: fractions=[0.0,1.0,0.5,0.25], vrefs=[0,1,0,1], gains=[1,2,1,2].
	if err := dac.SetAllEEPROM([4]float32{0.0, 1.0, 0.5, 0.25}, [4]uint8{0, 1, 0, 1}, [4]uint8{1, 2, 1, 2}); err != nil {
		t.Fatalf("SetAllEEPROM: %v", err)
	}
	wantEE := []byte{0x50, 0x00, 0x00, 0x9F, 0xFF, 0x07, 0xFF, 0x93, 0xFF}
	if !bytesEqual(lastWrite(conn.writes), wantEE) {
		t.Errorf("SetAllEEPROM write = %v, want %v", lastWrite(conn.writes), wantEE)
	}

	// SetVREF(1, 0, 1, 0) -> byte1 = 0x8A.
	if err := dac.SetVREF(1, 0, 1, 0); err != nil {
		t.Fatalf("SetVREF: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x8A}) {
		t.Errorf("SetVREF write = %v, want [0x8A]", lastWrite(conn.writes))
	}

	// SetGain(1, 2, 1, 2) -> byte1 = 0xC5.
	if err := dac.SetGain(1, 2, 1, 2); err != nil {
		t.Fatalf("SetGain: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0xC5}) {
		t.Errorf("SetGain write = %v, want [0xC5]", lastWrite(conn.writes))
	}

	// SetPowerDown(0, 1, 2, 3) -> byte1=0xA2, byte2=0x58.
	if err := dac.SetPowerDown(0, 1, 2, 3); err != nil {
		t.Fatalf("SetPowerDown: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0xA2, 0x58}) {
		t.Errorf("SetPowerDown write = %v, want [0xA2 0x58]", lastWrite(conn.writes))
	}

	// Read(): 24-byte response, no register-select write.
	buf := make([]byte, 24)
	buf[0] = 0x80
	buf[1] = 0x01
	buf[2] = 0x23
	buf[13] = 0x90
	buf[14] = 0xAB
	conn.queueRead(buf)
	result, err := dac.Read()
	if err != nil {
		t.Fatalf("Read: %v", err)
	}
	if !result.EEPROMReady {
		t.Errorf("Read().EEPROMReady = false, want true")
	}
	if result.Channel[0].Code != 0x123 {
		t.Errorf("Read().Channel[0].Code = 0x%X, want 0x123", result.Channel[0].Code)
	}
	if result.Channel[0].VREF != 0 {
		t.Errorf("Read().Channel[0].VREF = %d, want 0", result.Channel[0].VREF)
	}
	if result.Channel[0].Gain != 1 {
		t.Errorf("Read().Channel[0].Gain = %d, want 1", result.Channel[0].Gain)
	}
	if result.Channel[0].PowerDown != 0 {
		t.Errorf("Read().Channel[0].PowerDown = %d, want 0", result.Channel[0].PowerDown)
	}
	if result.Channel[0].EEPROMCode != 0xAB {
		t.Errorf("Read().Channel[0].EEPROMCode = 0x%X, want 0xAB", result.Channel[0].EEPROMCode)
	}
	if result.Channel[0].EEPROMVREF != 1 {
		t.Errorf("Read().Channel[0].EEPROMVREF = %d, want 1", result.Channel[0].EEPROMVREF)
	}
	if result.Channel[0].EEPROMGain != 2 {
		t.Errorf("Read().Channel[0].EEPROMGain = %d, want 2", result.Channel[0].EEPROMGain)
	}

	conn.queueRead([]byte{0x80})
	if ready, err := dac.IsEEPROMReady(); err != nil || !ready {
		t.Errorf("IsEEPROMReady() = %v, %v, want true, nil", ready, err)
	}
	conn.queueRead([]byte{0x00})
	if ready, err := dac.IsEEPROMReady(); err != nil || ready {
		t.Errorf("IsEEPROMReady() = %v, %v, want false, nil", ready, err)
	}

	// SoftwareUpdate()/WakeUp()/Reset(): General Call commands.
	if err := dac.SoftwareUpdate(); err != nil {
		t.Fatalf("SoftwareUpdate: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x00, 0x08}) {
		t.Errorf("SoftwareUpdate write = %v, want [0x00 0x08]", lastWrite(conn.writes))
	}
	if err := dac.WakeUp(); err != nil {
		t.Fatalf("WakeUp: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x00, 0x09}) {
		t.Errorf("WakeUp write = %v, want [0x00 0x09]", lastWrite(conn.writes))
	}
	if err := dac.Reset(); err != nil {
		t.Fatalf("Reset: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x00, 0x06}) {
		t.Errorf("Reset write = %v, want [0x00 0x06]", lastWrite(conn.writes))
	}
}
