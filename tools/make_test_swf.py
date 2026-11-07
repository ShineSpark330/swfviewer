#!/usr/bin/env python3
"""Builds a synthetic SWF that exercises every feature the asset manager's parser/previewer
supports: lossless + JPEG images, shapes (solid / gradient / bitmap fill, holes, curves,
shared edges, lines), sprites with animation and a mask, sounds (PCM, ADPCM, MP3),
fonts, edit text, binary data, scripts, symbol names.

usage: make_test_swf.py OUTDIR
Writes OUTDIR/test_cws.swf (zlib) and OUTDIR/test_fws.swf (uncompressed).
"""
import io
import math
import os
import shutil
import struct
import subprocess
import sys
import zlib

from PIL import Image


# ---------------------------------------------------------------- bit writer
class BW:
    def __init__(self):
        self.b = bytearray()
        self.cur = 0
        self.n = 0

    def _bit(self, v):
        self.cur = (self.cur << 1) | (v & 1)
        self.n += 1
        if self.n == 8:
            self.b.append(self.cur)
            self.cur = 0
            self.n = 0

    def ub(self, v, n):
        for i in range(n - 1, -1, -1):
            self._bit((v >> i) & 1)

    def sb(self, v, n):
        self.ub(v & ((1 << n) - 1), n)

    def align(self):
        while self.n:
            self._bit(0)

    def raw(self, data):
        self.align()
        self.b.extend(data)

    def u8(self, v):
        self.raw(bytes([v & 0xFF]))

    def u16(self, v):
        self.raw(struct.pack('<H', v & 0xFFFF))

    def s16(self, v):
        self.raw(struct.pack('<h', v))

    def u32(self, v):
        self.raw(struct.pack('<I', v & 0xFFFFFFFF))

    def cstr(self, s):
        self.raw(s.encode() + b'\0')

    def get(self):
        self.align()
        return bytes(self.b)


def nbits(*vals):
    m = 1
    for v in vals:
        m = max(m, (v.bit_length() if v >= 0 else (-v - 1).bit_length()) + 1)
    return m


def rect(bw, x0, x1, y0, y1):
    n = nbits(x0, x1, y0, y1)
    bw.ub(n, 5)
    for v in (x0, x1, y0, y1):
        bw.sb(v, n)
    bw.align()


def matrix(bw, scale=None, rot=None, tx=0, ty=0):
    if scale:
        sx, sy = int(round(scale[0] * 65536)), int(round(scale[1] * 65536))
        n = nbits(sx, sy)
        bw.ub(1, 1)
        bw.ub(n, 5)
        bw.sb(sx, n)
        bw.sb(sy, n)
    else:
        bw.ub(0, 1)
    if rot:
        r0, r1 = int(round(rot[0] * 65536)), int(round(rot[1] * 65536))
        n = nbits(r0, r1)
        bw.ub(1, 1)
        bw.ub(n, 5)
        bw.sb(r0, n)
        bw.sb(r1, n)
    else:
        bw.ub(0, 1)
    n = nbits(tx, ty)
    bw.ub(n, 5)
    bw.sb(tx, n)
    bw.sb(ty, n)
    bw.align()


def tag(code, data=b'', long_form=False):
    if len(data) >= 63 or long_form:
        return struct.pack('<HI', (code << 6) | 0x3F, len(data)) + data
    return struct.pack('<H', (code << 6) | len(data)) + data


PX = 20  # twips per pixel


# ---------------------------------------------------------------- shapes
class ShapeRecords:
    def __init__(self, bw, nf, nl):
        self.bw, self.nf, self.nl = bw, nf, nl

    def style(self, move=None, fill0=None, fill1=None, line=None):
        bw = self.bw
        bw.ub(0, 1)
        flags = (1 if move else 0) | (2 if fill0 is not None else 0) | \
                (4 if fill1 is not None else 0) | (8 if line is not None else 0)
        bw.ub(flags, 5)
        if move:
            n = nbits(*move)
            bw.ub(n, 5)
            bw.sb(move[0], n)
            bw.sb(move[1], n)
        if fill0 is not None:
            bw.ub(fill0, self.nf)
        if fill1 is not None:
            bw.ub(fill1, self.nf)
        if line is not None:
            bw.ub(line, self.nl)

    def line(self, dx, dy):
        bw = self.bw
        n = max(nbits(dx, dy), 2)
        bw.ub(1, 1)
        bw.ub(1, 1)
        bw.ub(n - 2, 4)
        if dx and dy:
            bw.ub(1, 1)
            bw.sb(dx, n)
            bw.sb(dy, n)
        else:
            bw.ub(0, 1)
            if dx == 0:
                bw.ub(1, 1)
                bw.sb(dy, n)
            else:
                bw.ub(0, 1)
                bw.sb(dx, n)

    def curve(self, cdx, cdy, adx, ady):
        bw = self.bw
        n = max(nbits(cdx, cdy, adx, ady), 2)
        bw.ub(1, 1)
        bw.ub(0, 1)
        bw.ub(n - 2, 4)
        for v in (cdx, cdy, adx, ady):
            bw.sb(v, n)

    def end(self):
        self.bw.ub(0, 1)
        self.bw.ub(0, 5)


def rgba(c):
    return bytes(c)


def solid(c):
    return b'\x00' + rgba(c)


def gradient_fill(kind, stops, size_px):
    bw = BW()
    bw.u8(kind)
    s = size_px * PX / 32768.0
    matrix(bw, scale=(s, s), tx=size_px * PX // 2, ty=size_px * PX // 2)
    bw.u8(len(stops))
    for ratio, c in stops:
        bw.u8(ratio)
        bw.raw(rgba(c))
    return bw.get()


def bitmap_fill(kind, bitmap_id, scale):
    bw = BW()
    bw.u8(kind)
    bw.u16(bitmap_id)
    matrix(bw, scale=(scale, scale))
    return bw.get()


def define_shape(code, char_id, bounds, fills, lines, draw, line2=False):
    bw = BW()
    bw.u16(char_id)
    rect(bw, *bounds)
    if code == 83:
        rect(bw, *bounds)
        bw.u8(0)
    bw.u8(len(fills))
    for f in fills:
        bw.raw(f)
    bw.u8(len(lines))
    for l in lines:
        bw.raw(l)
    nf, nl = max(len(fills).bit_length(), 1), max(len(lines).bit_length(), 1)
    bw.ub(nf, 4)
    bw.ub(nl, 4)
    sr = ShapeRecords(bw, nf, nl)
    draw(sr)
    sr.end()
    return tag(code, bw.get())


def line_style(width_px, c):
    return struct.pack('<H', int(width_px * PX)) + rgba(c)


def line_style2(width_px, c):
    bw = BW()
    bw.u16(int(width_px * PX))
    bw.ub(0, 2)  # start cap round
    bw.ub(0, 2)  # join round
    bw.ub(0, 1)  # hasFill
    bw.ub(0, 3)
    bw.ub(0, 5)
    bw.ub(0, 1)
    bw.ub(0, 2)
    bw.raw(rgba(c))
    return bw.get()


def rect_path(sr, w, h, fill_attr='fill1', style=1, line=None, ccw=False):
    kw = {'move': (0, 0), fill_attr: style}
    if line is not None:
        kw['line'] = line
    sr.style(**kw)
    if not ccw:
        sr.line(w, 0); sr.line(0, h); sr.line(-w, 0); sr.line(0, -h)
    else:
        sr.line(0, h); sr.line(w, 0); sr.line(0, -h); sr.line(-w, 0)


def make_shapes():
    out = []
    S = 100 * PX
    # 10: red box with curved top edge and blue outline (shape3)
    def draw10(sr):
        sr.style(move=(0, 0), fill1=1, line=1)
        sr.curve(S // 2, -30 * PX, S // 2, 30 * PX)  # control (50,-30) -> anchor (100,0)
        sr.line(0, S); sr.line(-S, 0); sr.line(0, -S)
    out.append(define_shape(32, 10, (0, S, -15 * PX, S), [solid((220, 40, 40, 255))],
                            [line_style(3, (20, 40, 200, 255))], draw10))
    # 11: green ring (outer + inner opposite winding)
    def draw11(sr):
        sr.style(move=(0, 0), fill1=1)
        sr.line(S, 0); sr.line(0, S); sr.line(-S, 0); sr.line(0, -S)
        sr.style(move=(25 * PX, 25 * PX), fill1=1)
        sr.line(0, 50 * PX); sr.line(50 * PX, 0); sr.line(0, -50 * PX); sr.line(-50 * PX, 0)
    out.append(define_shape(32, 11, (0, S, 0, S), [solid((30, 160, 60, 255))], [], draw11))
    # 12: linear gradient
    grad = gradient_fill(0x10, [(0, (255, 0, 0, 255)), (128, (255, 220, 0, 255)), (255, (0, 0, 255, 255))], 100)
    out.append(define_shape(32, 12, (0, S, 0, S), [grad], [], lambda sr: rect_path(sr, S, S)))
    # 13: radial gradient circle (8 curves)
    rad = gradient_fill(0x12, [(0, (255, 255, 255, 255)), (255, (20, 60, 200, 255))], 100)
    def draw13(sr):
        r = 50 * PX
        cx = cy = r
        pts = []
        for k in range(9):
            a = k * math.pi / 4
            pts.append((int(round(cx + r * math.cos(a))), int(round(cy + r * math.sin(a)))))
        ctrl_r = r / math.cos(math.pi / 8)
        sr.style(move=pts[0], fill1=1)
        for k in range(8):
            a = (k + 0.5) * math.pi / 4
            c = (int(round(cx + ctrl_r * math.cos(a))), int(round(cy + ctrl_r * math.sin(a))))
            sr.curve(c[0] - pts[k][0], c[1] - pts[k][1], pts[k + 1][0] - c[0], pts[k + 1][1] - c[1])
    out.append(define_shape(32, 13, (0, S, 0, S), [rad], [], draw13))
    # 14: repeating bitmap fill of image 1 (32px tile at 1:1)
    out.append(define_shape(32, 14, (0, S, 0, S), [bitmap_fill(0x40, 1, 20)], [],
                            lambda sr: rect_path(sr, S, S)))
    # 15: DefineShape4 yellow triangle with LineStyle2
    def draw15(sr):
        sr.style(move=(0, S), fill0=1, line=1)
        sr.line(S, 0); sr.line(-S // 2, -S); sr.line(-S // 2, S)
    out.append(define_shape(83, 15, (0, S, 0, S), [solid((250, 220, 20, 255))],
                            [line_style2(2, (0, 0, 0, 255))], draw15))
    # 16: two rectangles sharing an edge (fill0 / fill1 reversal)
    H = 100 * PX
    W = 100 * PX
    def draw16(sr):
        sr.style(move=(0, 0), fill1=1)
        sr.line(W, 0)
        sr.style(fill0=2)           # shared edge B->E: right rect is on the left side
        sr.line(0, H)
        sr.style(fill0=0)
        sr.line(-W, 0); sr.line(0, -H)
        sr.style(move=(W, 0), fill1=2)
        sr.line(W, 0); sr.line(0, H); sr.line(-W, 0)
    out.append(define_shape(32, 16, (0, 2 * W, 0, H),
                            [solid((250, 140, 20, 255)), solid((20, 170, 170, 255))], [], draw16))
    return out


# ---------------------------------------------------------------- placement / sprites
def place2(depth, char_id=None, tx=0, ty=0, move=False, name=None, clip=None, cx_mult=None):
    bw = BW()
    flags = 0
    if move: flags |= 0x01
    if char_id is not None: flags |= 0x02
    flags |= 0x04  # matrix
    if cx_mult is not None: flags |= 0x08
    if name: flags |= 0x20
    if clip: flags |= 0x40
    bw.u8(flags)
    bw.u16(depth)
    if char_id is not None: bw.u16(char_id)
    matrix(bw, tx=tx, ty=ty)
    if cx_mult is not None:
        # CXFORMWITHALPHA: hasMult only
        n = 10
        bw.ub(0, 1); bw.ub(1, 1); bw.ub(n, 4)
        for m in cx_mult:
            bw.sb(int(m * 256), n)
        bw.align()
    if name: bw.cstr(name)
    if clip: bw.u16(clip)
    return tag(26, bw.get())


def define_sprite(char_id, frames, body):
    bw = BW()
    bw.u16(char_id)
    bw.u16(frames)
    return tag(39, bw.get() + body + tag(0))


SHOW = tag(1)


def make_sprites():
    out = []
    # 20: shape 10 sliding right over 4 frames
    body = b''
    for f in range(4):
        body += place2(1, 10, tx=f * 30 * PX) if f == 0 else place2(1, tx=f * 30 * PX, move=True)
        body += SHOW
    out.append(define_sprite(20, 4, body))
    # 21: gradient shape 12 masked by shape 10 shifted (mask depth 1 clips depth 2)
    body = place2(1, 10, tx=50 * PX, ty=20 * PX, clip=2) + place2(2, 12) + SHOW
    out.append(define_sprite(21, 1, body))
    return out


# ---------------------------------------------------------------- images
def img_lossless2_alpha():
    w = h = 32
    raw = bytearray()
    for y in range(h):
        for x in range(w):
            dx, dy = x - 15.5, y - 15.5
            inside = dx * dx + dy * dy <= 15 * 15
            a = 255 if inside else 0
            r, g, b = int(255 * x / (w - 1)), int(255 * y / (h - 1)), 128
            # premultiplied ARGB
            raw += bytes([a, r * a // 255, g * a // 255, b * a // 255])
    bw = BW()
    bw.u16(1)
    bw.u8(5)
    bw.u16(w)
    bw.u16(h)
    bw.raw(zlib.compress(bytes(raw)))
    return tag(36, bw.get())


def img_lossless_palette():
    w = h = 10  # not a multiple of 4: tests row padding
    pal = [(255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0)]
    raw = bytearray()
    for c in pal:
        raw += bytes(c)
    stride = (w + 3) & ~3
    for y in range(h):
        row = bytearray(stride)
        for x in range(w):
            row[x] = (x // 5) + 2 * (y // 5)
        raw += row
    bw = BW()
    bw.u16(2)
    bw.u8(3)
    bw.u16(w)
    bw.u16(h)
    bw.u8(len(pal) - 1)
    bw.raw(zlib.compress(bytes(raw)))
    return tag(20, bw.get())


def jpeg_bytes(w=64, h=48):
    im = Image.new('RGB', (w, h))
    px = im.load()
    for y in range(h):
        for x in range(w):
            px[x, y] = (int(255 * x / w), int(255 * y / h), 200 if (x // 8 + y // 8) % 2 else 60)
    buf = io.BytesIO()
    im.save(buf, 'JPEG', quality=90)
    return buf.getvalue()


def img_jpeg2():
    bw = BW()
    bw.u16(3)
    bw.raw(jpeg_bytes())
    return tag(21, bw.get())


def img_jpeg3_alpha():
    j = jpeg_bytes()
    w, h = 64, 48
    alpha = bytes(255 if x >= w // 2 else 64 for y in range(h) for x in range(w))
    bw = BW()
    bw.u16(4)
    bw.u32(len(j))
    bw.raw(j)
    bw.raw(zlib.compress(alpha))
    return tag(35, bw.get())


def img_lossless_15bit():
    w = h = 6
    raw = bytearray()
    stride = ((w * 2) + 3) & ~3
    for y in range(h):
        row = bytearray(stride)
        for x in range(w):
            r5 = x * 31 // (w - 1); g5 = y * 31 // (h - 1); b5 = 16
            v = (r5 << 10) | (g5 << 5) | b5
            row[x * 2] = v >> 8
            row[x * 2 + 1] = v & 0xFF
        raw += row
    bw = BW()
    bw.u16(5)
    bw.u8(4)
    bw.u16(w)
    bw.u16(h)
    bw.raw(zlib.compress(bytes(raw)))
    return tag(20, bw.get())


def jpeg_split_tables():
    """Splits a JPEG into JPEGTables (DQT/DHT) and a DefineBits image (everything else)."""
    j = jpeg_bytes(40, 40)
    assert j[:2] == b'\xff\xd8'
    tables, rest = b'', b''
    i = 2
    while i < len(j):
        m = j[i + 1]
        if m == 0xDA:  # start of scan: rest of file is entropy data + EOI
            rest += j[i:]
            break
        ln = struct.unpack('>H', j[i + 2:i + 4])[0]
        seg = j[i:i + 2 + ln]
        if m in (0xDB, 0xC4):
            tables += seg
        else:
            rest += seg
        i += 2 + ln
    assert tables
    jt = tag(8, b'\xff\xd8' + tables + b'\xff\xd9')
    bw = BW()
    bw.u16(6)
    bw.raw(b'\xff\xd8' + rest)
    return jt + tag(6, bw.get())


# ---------------------------------------------------------------- sounds
STEP = [7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80,
        88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598,
        658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
        3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289,
        16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767]
IDX = {2: [-1, 2], 3: [-1, -1, 2, 4], 4: [-1] * 4 + [2, 4, 6, 8], 5: [-1] * 8 + [1, 2, 4, 6, 8, 10, 13, 16]}


def sine(rate, secs, freq, amp=12000):
    return [int(amp * math.sin(2 * math.pi * freq * i / rate)) for i in range(int(rate * secs))]


def adpcm_encode(chans, bits):
    """Encodes per-channel sample lists as SWF ADPCM; returns (bytes, decoded_channels)."""
    bw = BW()
    bw.ub(bits - 2, 2)
    n = len(chans[0])
    sign = 1 << (bits - 1)
    recon = [[] for _ in chans]
    pred = [0] * len(chans)
    idx = [0] * len(chans)
    for start in range(0, n, 4096):
        end = min(start + 4096, n)
        for c, s in enumerate(chans):
            pred[c] = s[start]
            idx[c] = 0
            bw.ub(pred[c] & 0xFFFF, 16)
            bw.ub(idx[c], 6)
            recon[c].append(pred[c])
        for i in range(start + 1, end):
            for c, s in enumerate(chans):
                diff = s[i] - pred[c]
                code = 0
                if diff < 0:
                    code = sign
                    diff = -diff
                step = STEP[idx[c]]
                delta = step >> (bits - 2)
                for k in range(bits - 2, -1, -1):
                    add = step >> (bits - 2 - k)
                    if diff >= delta + add:
                        code |= 1 << k
                        delta += add
                p = pred[c] + (-delta if code & sign else delta)
                pred[c] = max(-32768, min(32767, p))
                idx[c] = max(0, min(88, idx[c] + IDX[bits][code & (sign - 1)]))
                bw.ub(code, bits)
                recon[c].append(pred[c])
    return bw.get(), recon


def define_sound(char_id, fmt, rate_idx, sixteen, stereo, count, data):
    bw = BW()
    bw.u16(char_id)
    bw.u8((fmt << 4) | (rate_idx << 2) | (2 if sixteen else 0) | (1 if stereo else 0))
    bw.u32(count)
    bw.raw(data)
    return tag(14, bw.get())


def make_mp3():
    """Real MP3 if ffmpeg/lame exist, otherwise silent-frame stub (still a valid stream)."""
    wavsamples = sine(44100, 0.5, 440)
    if shutil.which('ffmpeg'):
        raw = b''.join(struct.pack('<h', s) for s in wavsamples)
        p = subprocess.run(['ffmpeg', '-v', 'quiet', '-f', 's16le', '-ar', '44100', '-ac', '1', '-i', '-',
                            '-f', 'mp3', '-'], input=raw, capture_output=True)
        if p.returncode == 0 and p.stdout:
            return p.stdout, len(wavsamples), 'ffmpeg'
    # MPEG1 Layer III, 128 kbps, 44.1 kHz, mono, no padding: frame = 417 bytes
    frame = bytes([0xFF, 0xFB, 0x90, 0xC4]) + bytes(413)
    frames = 20
    return frame * frames, 1152 * frames, 'stub'


def make_sounds():
    out = []
    pcm = sine(11025, 0.5, 440)
    out.append(define_sound(30, 3, 1, True, False, len(pcm), b''.join(struct.pack('<h', s) for s in pcm)))
    ad4, _ = adpcm_encode([sine(22050, 0.3, 523)], 4)
    out.append(define_sound(31, 1, 2, True, False, int(22050 * 0.3), ad4))
    left, right = sine(11025, 0.4, 330), sine(11025, 0.4, 660)
    ad3, _ = adpcm_encode([left, right], 3)
    out.append(define_sound(32, 1, 1, True, True, len(left), ad3))
    mp3, count, how = make_mp3()
    out.append(define_sound(33, 2, 3, True, False, count, struct.pack('<h', 0) + mp3))
    return out, how


# ---------------------------------------------------------------- misc tags
def make_misc():
    out = []
    # font2 with a name and zero glyphs
    bw = BW()
    bw.u16(40)
    bw.u8(0x01)  # bold
    bw.u8(0)
    name = b'TestFont'
    bw.u8(len(name))
    bw.raw(name)
    bw.u16(0)
    bw.u16(0)
    out.append(tag(48, bw.get()))
    # edit text
    bw = BW()
    bw.u16(41)
    rect(bw, 0, 100 * PX, 0, 24 * PX)
    bw.u8(0x80 | 0x01)  # HasText | HasFont
    bw.u8(0x00)
    bw.u16(40)
    bw.u16(12 * PX)
    bw.cstr('txt')
    bw.cstr('Hello SWF world')
    out.append(tag(37, bw.get()))
    # binary data (zip-like)
    bw = BW()
    bw.u16(42)
    bw.u32(0)
    bw.raw(b'PK\x03\x04' + b'payload' * 10)
    out.append(tag(87, bw.get()))
    # DoABC stub
    bw = BW()
    bw.u32(1)
    bw.cstr('frame1')
    bw.raw(bytes(range(32)))
    out.append(tag(82, bw.get()))
    return out


def symbol_class(pairs):
    bw = BW()
    bw.u16(len(pairs))
    for i, n in pairs:
        bw.u16(i)
        bw.cstr(n)
    return tag(76, bw.get())


def export_assets(pairs):
    bw = BW()
    bw.u16(len(pairs))
    for i, n in pairs:
        bw.u16(i)
        bw.cstr(n)
    return tag(56, bw.get())


# ---------------------------------------------------------------- assemble
def build():
    body = b''
    body += tag(69, struct.pack('<I', 0x08))  # FileAttributes
    body += tag(9, bytes([0xEE, 0xEE, 0xEE]))  # background
    body += img_lossless2_alpha() + img_lossless_palette() + img_jpeg2() + img_jpeg3_alpha()
    body += img_lossless_15bit() + jpeg_split_tables()
    body += b''.join(make_shapes())
    body += b''.join(make_sprites())
    sounds, how = make_sounds()
    body += b''.join(sounds)
    body += b''.join(make_misc())
    body += symbol_class([(0, 'Main'), (10, 'RedBox'), (20, 'Walker')])
    body += export_assets([(11, 'RingShape'), (30, 'beep')])
    # main timeline: 4 frames
    layout = [(10, 20, 20), (11, 140, 20), (12, 260, 20), (13, 380, 20),
              (14, 20, 140), (15, 140, 140), (16, 260, 140), (20, 20, 270), (21, 260, 270)]
    for depth, (cid, x, y) in enumerate(layout, start=1):
        body += place2(depth, cid, tx=x * PX, ty=y * PX)
    body += SHOW
    body += SHOW * 3
    body += tag(0)

    hdr = BW()
    rect(hdr, 0, 550 * PX, 0, 420 * PX)
    hdr.u16(12 * 256)  # 12 fps (8.8 fixed)
    hdr.u16(4)
    payload = hdr.get() + body
    fws = b'FWS' + bytes([10]) + struct.pack('<I', 8 + len(payload)) + payload
    cws = b'CWS' + bytes([10]) + struct.pack('<I', 8 + len(payload)) + zlib.compress(payload, 9)
    return fws, cws, how


if __name__ == '__main__':
    outdir = sys.argv[1] if len(sys.argv) > 1 else '.'
    os.makedirs(outdir, exist_ok=True)
    fws, cws, how = build()
    open(os.path.join(outdir, 'test_fws.swf'), 'wb').write(fws)
    open(os.path.join(outdir, 'test_cws.swf'), 'wb').write(cws)
    print('wrote test_fws.swf (%d B) test_cws.swf (%d B), mp3 via %s' % (len(fws), len(cws), how))
