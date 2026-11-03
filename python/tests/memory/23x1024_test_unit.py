import sys

from periph.chips.memory._23x1024 import SRAM23X1024Minimal, SRAM23X1024Full

passed = 0
failed = 0


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


class SramModel:
    """Behavioural 23X1024: parses the instruction frames the driver sends and
    models the array, the MODE register and the Byte/Page/Sequential
    address-increment rules. Every frame is recorded on `frames`."""

    def __init__(self):
        self.mem = bytearray(131072)
        self.frames = []
        self.mode = 0x40

    def _next(self, addr):
        if (self.mode & 0xC0) == 0x80:
            return (addr & ~31) | ((addr + 1) & 31)
        return (addr + 1) & 0x1FFFF

    def write(self, data):
        data = bytes(data)
        self.frames.append(data)
        if data[0] == 0x01 and len(data) == 2:
            self.mode = data[1]
        elif data[0] == 0x02 and len(data) >= 4:
            addr = (data[1] << 16) | (data[2] << 8) | data[3]
            for b in data[4:]:
                self.mem[addr] = b
                addr = self._next(addr)
                if (self.mode & 0xC0) == 0x00:
                    break

    def write_read(self, data, n):
        data = bytes(data)
        self.frames.append(data)
        out = bytearray(n)
        if data[0] == 0x05:
            out[0] = self.mode
        elif data[0] == 0x03 and len(data) == 4:
            addr = (data[1] << 16) | (data[2] << 8) | data[3]
            for i in range(n):
                out[i] = self.mem[addr]
                addr = self._next(addr)
                if (self.mode & 0xC0) == 0x00:
                    break
        return bytes(out)


# --- Construction: WRMR 0x40 then RDMR ---
conn = SramModel()
chip = SRAM23X1024Minimal(conn)
check_true('init_two_frames', len(conn.frames) == 2)
check_true('init_wrmr_sequential', conn.frames[0] == bytes([0x01, 0x40]))
check_true('init_rdmr', conn.frames[1] == bytes([0x05]))

# --- Construction fails on a MODE mismatch (floating MISO) ---
class DeadBus(SramModel):
    def write_read(self, data, n):
        return bytes(n)

try:
    SRAM23X1024Minimal(DeadBus())
    check_true('init_raises_on_bad_mode', False)
except OSError:
    check_true('init_raises_on_bad_mode', True)

# --- Minimal byte access: frame layout + round trip ---
chip.write_byte(0x1ABCD, 0x5A)
check_true('write_byte_frame', conn.frames[-1] == bytes([0x02, 0x01, 0xAB, 0xCD, 0x5A]))
check_true('write_byte_stored', conn.mem[0x1ABCD] == 0x5A)
check_true('read_byte_roundtrip', chip.read_byte(0x1ABCD) == 0x5A)
check_true('read_byte_frame', conn.frames[-1] == bytes([0x03, 0x01, 0xAB, 0xCD]))
chip.write_byte(0x1FFFF, 0x77)
check_true('last_address', chip.read_byte(0x1FFFF) == 0x77)
try:
    chip.read_byte(0x20000)
    check_true('read_byte_out_of_range', False)
except ValueError:
    check_true('read_byte_out_of_range', True)

# --- Full: block access ---
fc = SramModel()
full = SRAM23X1024Full(fc)
check_true('size', full.size() == 131072)
pattern = bytes((i * 7 + 3) & 0xFF for i in range(600))
full.write(0x100, pattern)
check_true('write_block', bytes(fc.mem[0x100:0x100 + 600]) == pattern)
check_true('read_block', full.read(0x100, 600) == pattern)
check_true('write_chunked_256', sum(1 for f in fc.frames if f[0] == 0x02) == 3)

full.max_chunk = 100
check_true('set_max_chunk', full.max_chunk == 100)
fc.frames.clear()
full.write(0, pattern[:250])
check_true('write_chunked_custom', [len(f) for f in fc.frames] == [104, 104, 54])
full.max_chunk = 256
try:
    full.max_chunk = 257
    check_true('max_chunk_rejects_257', False)
except ValueError:
    check_true('max_chunk_rejects_257', True)

# --- fill ---
full.fill(0x2000, 100, 0xA5)
check_true('fill_range_only', all(b == 0xA5 for b in fc.mem[0x2000:0x2064]) and fc.mem[0x2064] == 0 and fc.mem[0x1FFF] == 0)
full.fill(0x3000, 5)
check_true('fill_default_zero', fc.mem[0x3000] == 0 and fc.mem[0x3004] == 0)

# --- Range checks ---
for label, call in (('read_past_end', lambda: full.read(0x1FFFF, 2)),
                    ('write_past_end', lambda: full.write(0x1FFFF, b'ab')),
                    ('fill_past_end', lambda: full.fill(0x1FFFF, 2))):
    try:
        call()
        check_true(label, False)
    except ValueError:
        check_true(label, True)

# --- Page mode: transfers split at 32-byte boundaries, linear semantics preserved ---
full.set_mode(SRAM23X1024Full.MODE_PAGE)
check_true('set_mode_page', fc.mode == 0x80 and full.get_mode() == SRAM23X1024Full.MODE_PAGE)
fc.frames.clear()
data64 = bytes(0xC0 + i for i in range(64))
full.write(0x4010, data64)  # crosses the pages at 0x4020 and 0x4040
check_true('page_write_split_frames', len(fc.frames) == 3)
check_true('page_write_linear', bytes(fc.mem[0x4010:0x4050]) == data64 and fc.mem[0x4050] == 0)
check_true('page_read_linear', full.read(0x4010, 64) == data64)

# --- Byte mode: one byte per transaction ---
full.set_mode(SRAM23X1024Full.MODE_BYTE)
fc.frames.clear()
full.write(0x500, data64[:4])
check_true('byte_mode_frame_per_byte', len(fc.frames) == 4)
check_true('byte_mode_read', full.read(0x500, 4) == data64[:4])
full.set_mode(SRAM23X1024Full.MODE_SEQUENTIAL)
check_true('set_mode_sequential', full.get_mode() == SRAM23X1024Full.MODE_SEQUENTIAL)

try:
    full.set_mode(0xC0)
    check_true('set_mode_rejects_reserved', False)
except ValueError:
    check_true('set_mode_rejects_reserved', True)

# --- get_mode masks reserved bits ---
fc.mode = 0x4F
check_true('get_mode_masks_reserved', full.get_mode() == SRAM23X1024Full.MODE_SEQUENTIAL)
fc.mode = 0x40

# --- reset_io sends 0xFF and resyncs the cached mode ---
fc.frames.clear()
full.reset_io()
check_true('reset_io_frame', fc.frames[0] == bytes([0xFF]))

print('{} passed, {} failed'.format(passed, failed))
sys.exit(1 if failed else 0)
