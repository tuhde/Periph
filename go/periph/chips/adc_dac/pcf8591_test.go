package adcdac

import "testing"

func TestPCF8591FullAPI(t *testing.T) {
	conn := newMockConnection()
	adc, err := NewPCF8591Full(conn)
	if err != nil {
		t.Fatalf("NewPCF8591Full: %v", err)
	}

	// ReadChannel(2): writes control byte CHN=2, reads 2 bytes; byte0 stale, byte1 fresh.
	conn.queueRead([]byte{0x11, 0x7F})
	v, err := adc.ReadChannel(2)
	if err != nil || v != 0x7F {
		t.Errorf("ReadChannel(2) = %d, %v, want 0x7F, nil", v, err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x02}) {
		t.Errorf("ReadChannel(2) write = %v, want [0x02]", lastWrite(conn.writes))
	}

	// ReadChannel clamps an out-of-range channel to 0.
	conn.queueRead([]byte{0x00, 0x55})
	v, err = adc.ReadChannel(9)
	if err != nil || v != 0x55 {
		t.Errorf("ReadChannel(9) = %d, %v, want 0x55, nil", v, err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x00}) {
		t.Errorf("ReadChannel(9) write = %v, want [0x00]", lastWrite(conn.writes))
	}

	// ReadAll(): writes control with AI=1 (0x04), reads 5 bytes, discards stale byte.
	conn.queueRead([]byte{0x00, 0x10, 0x20, 0x30, 0x40})
	all, err := adc.ReadAll()
	if err != nil {
		t.Fatalf("ReadAll: %v", err)
	}
	if all != ([4]uint8{0x10, 0x20, 0x30, 0x40}) {
		t.Errorf("ReadAll() = %v, want [0x10 0x20 0x30 0x40]", all)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x04}) {
		t.Errorf("ReadAll write = %v, want [0x04]", lastWrite(conn.writes))
	}

	// Configure(3, true, true) -> control 0x74.
	if err := adc.Configure(3, true, true); err != nil {
		t.Fatalf("Configure: %v", err)
	}
	if !bytesEqual(lastWrite(conn.writes), []byte{0x74}) {
		t.Errorf("Configure write = %v, want [0x74]", lastWrite(conn.writes))
	}

	// ReadChannelVoltage(0, 3.3, 0.0): raw=128.
	conn.queueRead([]byte{0x00, 128})
	fv, err := adc.ReadChannelVoltage(0, 3.3, 0.0)
	if err != nil {
		t.Fatalf("ReadChannelVoltage: %v", err)
	}
	want := float32(128) * 3.3 / 256.0
	if fv < want-1e-4 || fv > want+1e-4 {
		t.Errorf("ReadChannelVoltage(0,3.3,0.0) = %v, want %v", fv, want)
	}

	// ReadAllVoltage(3.3, 0.0): raws [0, 64, 128, 255].
	conn.queueRead([]byte{0x00, 0, 64, 128, 255})
	voltages, err := adc.ReadAllVoltage(3.3, 0.0)
	if err != nil {
		t.Fatalf("ReadAllVoltage: %v", err)
	}
	raws := [4]float32{0, 64, 128, 255}
	for i := 0; i < 4; i++ {
		exp := raws[i] * 3.3 / 256.0
		if voltages[i] < exp-1e-4 || voltages[i] > exp+1e-4 {
			t.Errorf("ReadAllVoltage()[%d] = %v, want %v", i, voltages[i], exp)
		}
	}

	// ReadDifferential(1): raw byte 200 -> signed two's complement = -56.
	conn.queueRead([]byte{0x00, 200})
	dv, err := adc.ReadDifferential(1)
	if err != nil || dv != -56 {
		t.Errorf("ReadDifferential(1) = %d, %v, want -56, nil", dv, err)
	}

	// raw byte 100 (< 128) stays positive.
	conn.queueRead([]byte{0x00, 100})
	dv, err = adc.ReadDifferential(1)
	if err != nil || dv != 100 {
		t.Errorf("ReadDifferential(1) = %d, %v, want 100, nil", dv, err)
	}

	// SetDAC(200): sets AOE=1, AI=0, writes [ctrl, value].
	if err := adc.SetDAC(200); err != nil {
		t.Fatalf("SetDAC: %v", err)
	}
	last := lastWrite(conn.writes)
	if last[1] != 200 {
		t.Errorf("SetDAC(200) value = %d, want 200", last[1])
	}
	if last[0]&0x40 == 0 {
		t.Errorf("SetDAC(200) ctrl = 0x%02X, want AOE bit set", last[0])
	}
	if last[0]&0x04 != 0 {
		t.Errorf("SetDAC(200) ctrl = 0x%02X, want AI bit clear", last[0])
	}

	// SetDACVoltage(0.5): uint8(0.5*255.0) truncates to 127, not 128.
	if err := adc.SetDACVoltage(0.5); err != nil {
		t.Fatalf("SetDACVoltage: %v", err)
	}
	if lastWrite(conn.writes)[1] != 127 {
		t.Errorf("SetDACVoltage(0.5) value = %d, want 127", lastWrite(conn.writes)[1])
	}

	// DisableDAC(): clears AOE bit.
	if err := adc.DisableDAC(); err != nil {
		t.Fatalf("DisableDAC: %v", err)
	}
	if lastWrite(conn.writes)[0]&0x40 != 0 {
		t.Errorf("DisableDAC ctrl = 0x%02X, want AOE bit clear", lastWrite(conn.writes)[0])
	}
}
