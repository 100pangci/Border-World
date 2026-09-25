#!/usr/bin/env python3
"""
Region 文件对比与统计工具（阶段 4/6/9 验证用）。

功能：
  compare  —— 逐区块比对两个世界（block_states 调色板+数据、biomes），输出差异统计
  stats    —— 统计一个世界的区域特征：地表高度分布、陡坡/平台比例、矿石/洞穴/水、biome 集合

用法：
  python3 tools/world_compare.py compare --a run/server --b run/vanilla \
      --chunks 0 0 15 15 [--chunks ...]
  python3 tools/world_compare.py stats --dir run/server --chunks 10 10 17 17 --label modded
"""
import argparse
import io
import os
import struct
import sys
import zlib
import gzip

import nbtlib


def read_chunk(server_dir, world, cx, cz):
    """读取一个区块的 NBT；不存在返回 None。"""
    rx, rz = cx >> 5, cz >> 5
    path = os.path.join(server_dir, world, 'region', f'r.{rx}.{rz}.mca')
    if not os.path.exists(path):
        return None
    index = (cx & 31) + (cz & 31) * 32
    with open(path, 'rb') as fh:
        fh.seek(index * 4)
        entry = fh.read(4)
        if len(entry) < 4:
            return None
        offset = struct.unpack('>I', b'\x00' + entry[:3])[0]
        if offset == 0:
            return None
        fh.seek(offset * 4096)
        length = struct.unpack('>I', fh.read(4))[0]
        compression = fh.read(1)[0]
        payload = fh.read(length - 1)
    if compression == 1:
        payload = gzip.decompress(payload)
    elif compression == 2:
        payload = zlib.decompress(payload)
    elif compression == 3:
        pass
    else:
        return None
    return nbtlib.File.parse(io.BytesIO(payload))


def to_bytes(tag):
    if tag is None:
        return b''
    try:
        import numpy as np
        return np.asarray(tag, dtype='>i8').tobytes()
    except Exception:
        return struct.pack('>' + 'q' * len(tag), *[int(v) for v in tag])


def canonical_section(section):
    """(Y, 归一化方块摘要, 归一化 biome 摘要)——调色板按内容排序后重新编码，消除顺序差异。"""
    y = int(section['Y'])
    bs = section.get('block_states')
    bio = section.get('biomes')
    return (y, _canonical_states(bs, min_bits=4), _canonical_states(bio, min_bits=1))


def _canonical_states(states, min_bits):
    """用 (调色板内容, 每个位置 2 字节的规范索引流) 表示一个 bit-packed 状态数组。"""
    if states is None:
        return ((), b'')
    palette = []
    for entry in states.get('palette', []):
        if isinstance(entry, dict) and 'Name' in entry:
            props = tuple(sorted((str(k), str(v)) for k, v in entry.get('Properties', {}).items()))
            palette.append((str(entry['Name']), props))
        else:
            palette.append((str(entry), ()))
    if len(palette) <= 1:
        return (tuple(palette), b'')
    order = sorted(range(len(palette)), key=lambda i: palette[i])
    mapping = {old: new for new, old in enumerate(order)}
    sorted_palette = tuple(palette[i] for i in order)
    bits = max(min_bits, (len(palette) - 1).bit_length())
    per_long = 64 // bits
    mask = (1 << bits) - 1
    data = [int(v) & 0xFFFFFFFFFFFFFFFF for v in states.get('data', [])]
    out = bytearray(4096 * 2)
    for i in range(4096):
        long_index = i // per_long
        value = 0
        if long_index < len(data):
            value = (data[long_index] >> ((i % per_long) * bits)) & mask
            if value >= len(palette):
                value = 0
        canon = mapping.get(value, 0)
        out[i * 2] = canon & 0xFF
        out[i * 2 + 1] = (canon >> 8) & 0xFF
    return (sorted_palette, bytes(out))


def section_fingerprint(section):
    return canonical_section(section)


def chunk_fingerprint(chunk):
    sections = chunk.get('sections', [])
    return tuple(section_fingerprint(s) for s in sections)


AIR = {'minecraft:air', 'minecraft:cave_air', 'minecraft:void_air'}


def palette_blocks(chunk):
    """产出 (x, y, z, block_name) 的迭代器（解码 bit-packed 数据）。"""
    sections = chunk.get('sections', [])
    for section in sections:
        y_base = int(section['Y']) * 16
        bs = section.get('block_states')
        if bs is None:
            continue
        palette = [str(e['Name']) for e in bs.get('palette', [])]
        if len(palette) == 1:
            for i in range(4096):
                yield (i & 15, y_base + (i >> 8), (i >> 4) & 15, palette[0])
            continue
        bits = max(4, (len(palette) - 1).bit_length())
        per_long = 64 // bits
        mask = (1 << bits) - 1
        data = [int(v) & 0xFFFFFFFFFFFFFFFF for v in bs.get('data', [])]
        for i in range(4096):
            long_index = i // per_long
            if long_index >= len(data):
                break
            offset = (i % per_long) * bits
            value = (data[long_index] >> offset) & mask
            if value >= len(palette):
                value = 0
            yield (i & 15, y_base + (i >> 8), (i >> 4) & 15, palette[value])


def collect_stats(chunks):
    """对一批区块做地形统计。"""
    heights = {}
    ore_counts = {}
    fluid_counts = {'minecraft:water': 0, 'minecraft:lava': 0}
    air_below_60 = 0
    solid_below_60 = 0
    biomes = set()
    structures = {}
    sections_per_chunk = []
    for (cx, cz), chunk in chunks:
        sections_per_chunk.append(len(chunk.get('sections', [])))
        starts = chunk.get('structures', {}).get('starts', {})
        for name, start in starts.items():
            structures[str(name)] = structures.get(str(name), 0) + 1
        for section in chunk.get('sections', []):
            bio = section.get('biomes')
            if bio is not None:
                for e in bio.get('palette', []):
                    biomes.add(str(e))
        for x, y, z, name in palette_blocks(chunk):
            code = terrain_code(name)
            if code in (CODE_SOLID, CODE_LAVA):
                key = (cx * 16 + x, cz * 16 + z)
                if y > heights.get(key, -999):
                    heights[key] = y
            if 'ore' in name:
                ore_counts[name] = ore_counts.get(name, 0) + 1
            if name in fluid_counts:
                fluid_counts[name] += 1
            if y < 60:
                if name in AIR:
                    air_below_60 += 1
                else:
                    solid_below_60 += 1

    values = sorted(heights.values())
    n = len(values)
    if n == 0:
        return None
    mean = sum(values) / n
    steep = 0
    flat = 0
    pairs = 0
    for (x, z), h in heights.items():
        for nb in ((x + 1, z), (x, z + 1)):
            if nb in heights:
                pairs += 1
                d = abs(heights[nb] - h)
                if d >= 8:
                    steep += 1
                elif d == 0:
                    flat += 1
    return {
        'columns': n,
        'height_min': values[0],
        'height_max': values[-1],
        'height_mean': round(mean, 2),
        'height_p05': values[int(n * 0.05)],
        'height_p95': values[int(n * 0.95)],
        'steep_pair_ratio': round(steep / max(1, pairs), 4),
        'flat_pair_ratio': round(flat / max(1, pairs), 4),
        'ores': dict(sorted(ore_counts.items(), key=lambda kv: -kv[1])[:12]),
        'fluids': fluid_counts,
        'air_below_60': air_below_60,
        'solid_below_60': solid_below_60,
        'cave_ratio_below_60': round(air_below_60 / max(1, air_below_60 + solid_below_60), 4),
        'biomes': sorted(biomes),
        'biome_count': len(biomes),
        'structures': dict(sorted(structures.items(), key=lambda kv: -kv[1])),
        'structure_starts': sum(structures.values()),
    }


def parse_rects(args_chunks):
    if len(args_chunks) % 4 != 0:
        raise SystemExit('--chunks 需要 4 的倍数个整数')
    return [tuple(args_chunks[i:i + 4]) for i in range(0, len(args_chunks), 4)]


def iter_chunks(server_dir, world, rects):
    for x1, z1, x2, z2 in rects:
        for cx in range(min(x1, x2), max(x1, x2) + 1):
            for cz in range(min(z1, z2), max(z1, z2) + 1):
                chunk = read_chunk(server_dir, world, cx, cz)
                if chunk is None:
                    print(f'  ! 缺少区块 ({cx}, {cz})', file=sys.stderr)
                    continue
                yield (cx, cz), chunk


def cmd_compare(args):
    rects = parse_rects(args.chunks)
    total = 0
    same = 0
    different = []
    for (cx, cz), chunk_a in iter_chunks(args.a, args.world_a, rects):
        chunk_b = read_chunk(args.b, args.world_b, cx, cz)
        total += 1
        if chunk_b is None:
            different.append((cx, cz, 'missing in B'))
            continue
        fa = chunk_fingerprint(chunk_a)
        fb = chunk_fingerprint(chunk_b)
        if fa == fb:
            same += 1
        else:
            diff_sections = [sa[0] for sa, sb in zip(fa, fb) if sa != sb]
            different.append((cx, cz, f'sections {diff_sections[:6]}'))
    print(f'区块总数: {total}, 完全一致: {same}, 不同: {len(different)}')
    for cx, cz, why in different[:20]:
        print(f'  差异 ({cx}, {cz}): {why}')
    if len(different) > 20:
        print(f'  ... 其余 {len(different) - 20} 个')


def cmd_stats(args):
    rects = parse_rects(args.chunks)
    chunks = list(iter_chunks(args.dir, args.world, rects))
    if not chunks:
        raise SystemExit('没有可统计的区块')
    stats = collect_stats(chunks)
    print(f'== {args.label} ({args.dir}) 区块数={len(chunks)} ==')
    for key, value in stats.items():
        print(f'  {key}: {value}')


def surface_heights(server_dir, world, rects):
    """返回 {(x, z): 地形顶面 y}——忽略水与植被（只取地形实心/岩浆），自顶向下扫描。"""
    columns = {}
    for (cx, cz), chunk in iter_chunks(server_dir, world, rects):
        remaining = set(range(256))
        sections = sorted(chunk.get('sections', []), key=lambda s: -int(s['Y']))
        for section in sections:
            if not remaining:
                break
            y_base = int(section['Y']) * 16
            bs = section.get('block_states')
            if bs is None:
                continue
            palette = []
            for entry in bs.get('palette', []):
                palette.append(str(entry['Name']) if isinstance(entry, dict) and 'Name' in entry else str(entry))
            if len(palette) == 1:
                name = palette[0]
                if terrain_code(name) not in (CODE_SOLID, CODE_LAVA):
                    continue
                for column in remaining:
                    columns[(cx * 16 + column % 16, cz * 16 + column // 16)] = y_base + 15
                remaining.clear()
                continue
            bits = max(4, (len(palette) - 1).bit_length())
            per_long = 64 // bits
            mask = (1 << bits) - 1
            data = [int(v) & 0xFFFFFFFFFFFFFFFF for v in bs.get('data', [])]
            for i in range(4095, -1, -1):
                if not remaining:
                    break
                column = (i & 15) + ((i >> 4) & 15) * 16
                if column not in remaining:
                    continue
                long_index = i // per_long
                if long_index >= len(data):
                    continue
                value = (data[long_index] >> ((i % per_long) * bits)) & mask
                if value >= len(palette):
                    value = 0
                if terrain_code(palette[value]) in (CODE_SOLID, CODE_LAVA):
                    columns[(cx * 16 + (column & 15), cz * 16 + (column >> 4))] = y_base + (i >> 8)
                    remaining.discard(column)
    return columns


def percentile(values, q):
    if not values:
        return None
    values = sorted(values)
    return values[min(len(values) - 1, int(len(values) * q))]


def cmd_seams(args):
    """阶段 8：检查跨 chunk 接缝是否比区块内部出现额外断层。"""
    rects = parse_rects(args.chunks)
    heights = surface_heights(args.dir, args.world, rects)
    if not heights:
        raise SystemExit('没有数据')
    cross = []
    inner = []
    for (x, z), h in heights.items():
        for nb in ((x + 1, z), (x, z + 1)):
            if nb not in heights:
                continue
            d = abs(heights[nb] - h)
            crosses = (x >> 4) != (nb[0] >> 4) or (z >> 4) != (nb[1] >> 4)
            (cross if crosses else inner).append(d)

    def report(label, values):
        if not values:
            print(f'  {label}: 无数据')
            return
        big = sum(1 for v in values if v >= 8)
        print(f'  {label}: n={len(values)} mean={sum(values) / len(values):.2f} '
              f'p50={percentile(values, 0.5)} p90={percentile(values, 0.9)} '
              f'p99={percentile(values, 0.99)} max={max(values)} '
              f'>=8格比例={big / len(values):.4f}')

    print(f'== {args.label} 接缝检查（地表高度差，{len(heights)} 列）==')
    report('区块内部相邻列', inner)
    report('跨区块边界相邻列', cross)
    if inner and cross:
        ratio = (sum(1 for v in cross if v >= 8) / len(cross)) / max(1e-9, (sum(1 for v in inner if v >= 8) / len(inner)))
        print(f'  陡坡比例（跨边界/内部）= {ratio:.3f}（接近 1 表示没有接缝异常）')


def cmd_rings(args):
    """阶段 7：按到出生区块中心的切比雪夫距离分环统计地形特征，观察畸变是否连续增强。"""
    rects = parse_rects(args.chunks)
    heights = surface_heights(args.dir, args.world, rects)
    if not heights:
        raise SystemExit('没有数据')
    center_x = args.center_chunk[0] * 16 + 8.0
    center_z = args.center_chunk[1] * 16 + 8.0
    bands = {}
    for (x, z), h in heights.items():
        d = max(abs(x - center_x), abs(z - center_z))
        band = int(d // args.step) * args.step
        bands.setdefault(band, {'heights': [], 'jumps': [], 'flat': 0, 'pairs': 0})
        entry = bands[band]
        entry['heights'].append(h)
        for nb in ((x + 1, z), (x, z + 1)):
            if nb in heights:
                entry['pairs'] += 1
                delta = abs(heights[nb] - h)
                entry['jumps'].append(delta)
                if delta == 0:
                    entry['flat'] += 1
    print(f'== {args.label} 距离环带统计（中心=出生区块中心 {center_x:.0f},{center_z:.0f}，环宽 {args.step} 格）==')
    print('  距离带      列数  高度范围     高度均值  |Δh|均值  p90   p99   ≥8格比例  完全等高处比例')
    for band in sorted(bands):
        e = bands[band]
        hs = e['heights']
        js = e['jumps'] or [0]
        steep = sum(1 for v in js if v >= 8) / len(js)
        flat = e['flat'] / max(1, e['pairs'])
        print(f'  {band:4d}-{band + args.step - 1:4d}  {len(hs):6d}  '
              f'{min(hs):3d}..{max(hs):3d}   {sum(hs) / len(hs):7.2f}  '
              f'{sum(js) / len(js):7.2f}  {percentile(js, 0.9):4d}  {percentile(js, 0.99):4d}  '
              f'{steep:8.4f}  {flat:10.4f}')


def cmd_render(args):
    """把区域高度场渲染成 PNG（高度渐变 + 浮雕阴影），用于直观检查墙体/平台/畸变。"""
    from PIL import Image, ImageDraw
    rects = parse_rects(args.chunks)
    heights = surface_heights(args.dir, args.world, rects)
    if not heights:
        raise SystemExit('没有数据')
    # 用请求范围确定边界（缺失列渲染成深色），保证不同世界渲染图尺寸一致、可对比
    min_x = min(r[0] for r in rects) * 16
    max_x = max(r[2] for r in rects) * 16 + 15
    min_z = min(r[1] for r in rects) * 16
    max_z = max(r[3] for r in rects) * 16 + 15
    width = (max_x - min_x + 1) // args.scale
    height = (max_z - min_z + 1) // args.scale
    present = [h for h in heights.values()]
    lo, hi = min(present), max(present)
    hs = [heights.get((min_x + x * args.scale, min_z + z * args.scale)) for z in range(height) for x in range(width)]
    span = max(1, hi - lo)
    img = Image.new('RGB', (width, height))
    px = img.load()
    for z in range(height):
        for x in range(width):
            h = hs[z * width + x]
            if h is None:
                px[x, z] = (20, 20, 30)
                continue
            t = (h - lo) / span
            # 高度渐变：低=深绿/水色，中=绿，高=黄褐，顶=白
            if t < 0.25:
                color = (int(40 + t * 400), int(90 + t * 400), int(160 - t * 100))
            elif t < 0.7:
                u = (t - 0.25) / 0.45
                color = (int(140 + u * 60), int(190 - u * 40), int(60 + u * 20))
            else:
                u = (t - 0.7) / 0.3
                color = (int(200 + u * 55), int(150 + u * 90), int(80 + u * 150))
            # 浮雕：与左/上邻居的高度差
            hl = hs[z * width + x - 1] if x > 0 else h
            hu = hs[(z - 1) * width + x] if z > 0 else h
            if hl is None:
                hl = h
            if hu is None:
                hu = h
            slope = (h - hl) + (h - hu)
            shade = max(-0.75, min(0.75, slope / 12.0))
            factor = 1.0 + shade
            px[x, z] = tuple(max(0, min(255, int(c * factor))) for c in color)

    draw = ImageDraw.Draw(img)
    if args.center_chunk:
        cx = (args.center_chunk[0] * 16 + 8 - min_x) // args.scale
        cz = (args.center_chunk[1] * 16 + 8 - min_z) // args.scale
        for radius, color in ((args.normal_radius // args.scale, (255, 255, 255)),
                              (args.outer_radius // args.scale, (255, 120, 120))):
            draw.rectangle([cx - radius, cz - radius, cx + radius, cz + radius], outline=color)
    img = img.resize((width * args.scale, height * args.scale), Image.NEAREST)
    img.save(args.out)
    print(f'已写出 {args.out}: {img.width}x{img.height}, 高度范围 {lo}..{hi}, 区域 {rects}')


TERRAIN_BLOCKS = {
    'minecraft:stone', 'minecraft:deepslate', 'minecraft:tuff', 'minecraft:andesite', 'minecraft:diorite',
    'minecraft:granite', 'minecraft:bedrock', 'minecraft:dirt', 'minecraft:grass_block', 'minecraft:podzol',
    'minecraft:mycelium', 'minecraft:coarse_dirt', 'minecraft:rooted_dirt', 'minecraft:mud', 'minecraft:sand',
    'minecraft:red_sand', 'minecraft:sandstone', 'minecraft:red_sandstone', 'minecraft:gravel', 'minecraft:clay',
    'minecraft:water', 'minecraft:lava', 'minecraft:ice', 'minecraft:packed_ice', 'minecraft:blue_ice',
    'minecraft:snow_block', 'minecraft:snow', 'minecraft:moss_block', 'minecraft:muddy_mangrove_roots',
    'minecraft:terracotta', 'minecraft:white_terracotta', 'minecraft:orange_terracotta', 'minecraft:yellow_terracotta',
    'minecraft:brown_terracotta', 'minecraft:red_terracotta', 'minecraft:light_gray_terracotta',
    'minecraft:calcite', 'minecraft:basalt', 'minecraft:blackstone', 'minecraft:soul_sand', 'minecraft:soul_soil',
    'minecraft:magma_block', 'minecraft:netherrack', 'minecraft:end_stone', 'minecraft:obsidian', 'minecraft:bedrock',
    'minecraft:sculk', 'minecraft:dripstone_block', 'minecraft:pointed_dripstone', 'minecraft:mangrove_roots',
}

# 每个 y 的编码：0=空气/植被 1=地形实心 2=水 3=岩浆
CODE_AIR = 0
CODE_SOLID = 1
CODE_WATER = 2
CODE_LAVA = 3


def is_terrain_solid(name):
    """地形实心方块：岩石/土/沙/冰等 + 矿石；植被、结构方块、洞穴装饰一律不算。"""
    return name in TERRAIN_BLOCKS or name.endswith('_ore') or name in (
        'minecraft:ancient_debris', 'minecraft:obsidian', 'minecraft:crying_obsidian',
    )


def classify(name):
    if name in AIR:
        return CODE_AIR
    if name == 'minecraft:water':
        return CODE_WATER
    if name == 'minecraft:lava':
        return CODE_LAVA
    return CODE_SOLID if is_terrain_solid(name) else CODE_AIR


def column_mask(chunk):
    """每个 (x,z) 列的地形骨架：从世界底部到"地形顶部"的逐格分类码。

    只保留密度驱动的地形（岩石/空气/水/岩浆），把植被、结构等"特征方块"排除在外，
    因此不受 MC 特征生成跨运行不确定性的影响。
    """
    sections = sorted(chunk.get('sections', []), key=lambda s: int(s['Y']))
    min_y = int(sections[0]['Y']) * 16 if sections else -64
    max_y = (int(sections[-1]['Y']) * 16 + 15) if sections else 319
    tops = {}
    codes = {}
    for section in sections:
        y_base = int(section['Y']) * 16
        bs = section.get('block_states')
        if bs is None:
            continue
        palette = []
        for entry in bs.get('palette', []):
            palette.append(str(entry['Name']) if isinstance(entry, dict) and 'Name' in entry else str(entry))
        if len(palette) == 1:
            name = palette[0]
            code = classify(name)
            solid = code == CODE_SOLID
            for i in range(4096):
                column = (i & 15) + ((i >> 4) & 15) * 16
                y = y_base + (i >> 8)
                if solid:
                    tops[column] = y if column not in tops else max(tops[column], y)
                if code != CODE_AIR:
                    codes[(column, y)] = code
            continue
        bits = max(4, (len(palette) - 1).bit_length())
        per_long = 64 // bits
        mask = (1 << bits) - 1
        data = [int(v) & 0xFFFFFFFFFFFFFFFF for v in bs.get('data', [])]
        for i in range(4096):
            long_index = i // per_long
            value = 0
            if long_index < len(data):
                value = (data[long_index] >> ((i % per_long) * bits)) & mask
                if value >= len(palette):
                    value = 0
            name = palette[value]
            code = classify(name)
            column = (i & 15) + ((i >> 4) & 15) * 16
            y = y_base + (i >> 8)
            if code == CODE_SOLID:
                tops[column] = y if column not in tops else max(tops[column], y)
            if code != CODE_AIR:
                codes[(column, y)] = code

    out = {}
    for column, top in tops.items():
        mask_bytes = bytearray(max_y - min_y + 1)
        for y in range(min_y, top + 1):
            mask_bytes[y - min_y] = codes.get((column, y), CODE_AIR)
        out[column] = (top, bytes(mask_bytes))
    return out


def terrain_code(name):
    """地形分类：0=空气 1=实心 2=水 3=岩浆；非地形方块（植被/结构/洞穴装饰）返回 None（跳过）。"""
    if name in AIR:
        return CODE_AIR
    if name == 'minecraft:water':
        return CODE_WATER
    if name == 'minecraft:lava':
        return CODE_LAVA
    if is_terrain_solid(name):
        return CODE_SOLID
    return None


def chunk_terrain_codes(chunk):
    """{(x, y, z): code}——只包含可比位置（地形方块/空气/水/岩浆）。"""
    out = {}
    for x, y, z, name in palette_blocks(chunk):
        code = terrain_code(name)
        if code is not None:
            out[(x, y, z)] = code
    return out


def cmd_terrain(args):
    """阶段 4 主判据：比较密度驱动的地形（特征方块跳过，不受 feature 跨运行不确定性影响）。"""
    rects = parse_rects(args.chunks)
    total = 0
    clean = 0
    reports = []
    for (cx, cz), chunk_a in iter_chunks(args.a, args.world_a, rects):
        chunk_b = read_chunk(args.b, args.world_b, cx, cz)
        total += 1
        if chunk_b is None:
            reports.append((cx, cz, 'B 缺失', 0))
            continue
        ca = chunk_terrain_codes(chunk_a)
        cb = chunk_terrain_codes(chunk_b)
        keys = set(ca) | set(cb)
        mismatches = [k for k in keys if ca.get(k) != cb.get(k)]
        if not mismatches:
            clean += 1
        else:
            reports.append((cx, cz, f'{len(mismatches)} 处不同', len(mismatches)))
    print(f'地形（密度驱动）比较: 区块 {total}, 完全一致 {clean}, 有差异 {len(reports)}')
    for cx, cz, why, n in reports[:15]:
        print(f'  ({cx}, {cz}): {why}')
    if reports:
        print(f'  差异位置合计: {sum(r[3] for r in reports)}（应远小于每区块 98304 个位置）')


def cmd_heightmap(args):
    """把区域地表高度画成 ASCII 图（每 cell 取该列最高非空气方块），用于目视检查墙体/平台。"""
    rects = parse_rects(args.chunks)
    columns = surface_heights(args.dir, args.world, rects)
    if not columns:
        raise SystemExit('没有数据')
    xs = sorted({k[0] for k in columns})
    zs = sorted({k[1] for k in columns})
    scale = args.scale
    print(f'== {args.label} 地表高度图  范围 X[{xs[0]},{xs[-1]}] Z[{zs[0]},{zs[-1]}]  采样步长={scale} ==')
    shades = ' .:-=+*#%@'
    values = [columns[k] for k in columns]
    lo, hi = min(values), max(values)
    print(f'   高度范围 {lo}..{hi}')
    header = '     ' + ''.join(
        ('|' if (x % (16 * 4) < scale) else ' ') for x in xs[::scale])
    print(header)
    for z in zs[::scale]:
        row = []
        for x in xs[::scale]:
            h = columns.get((x, z))
            if h is None:
                row.append(' ')
            else:
                idx = int((h - lo) / max(1, hi - lo) * (len(shades) - 1))
                row.append(shades[idx])
        print(f'{z:4d} ' + ''.join(row))
    print(f'   图例: {shades} = 低 → 高（每字符 {scale} 格）')


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='cmd', required=True)

    p = sub.add_parser('compare')
    p.add_argument('--a', required=True)
    p.add_argument('--b', required=True)
    p.add_argument('--world-a', default='world')
    p.add_argument('--world-b', default=None, help='默认与 --world-a 相同')
    p.add_argument('--chunks', nargs='+', type=int, required=True)
    p.set_defaults(func=cmd_compare)

    p = sub.add_parser('stats')
    p.add_argument('--dir', required=True)
    p.add_argument('--world', default='world')
    p.add_argument('--label', default='world')
    p.add_argument('--chunks', nargs='+', type=int, required=True)
    p.set_defaults(func=cmd_stats)

    p = sub.add_parser('heightmap')
    p.add_argument('--dir', required=True)
    p.add_argument('--world', default='world')
    p.add_argument('--label', default='world')
    p.add_argument('--chunks', nargs='+', type=int, required=True)
    p.add_argument('--scale', type=int, default=2, help='采样步长（格/字符）')
    p.set_defaults(func=cmd_heightmap)

    p = sub.add_parser('seams')
    p.add_argument('--dir', required=True)
    p.add_argument('--world', default='world')
    p.add_argument('--label', default='world')
    p.add_argument('--chunks', nargs='+', type=int, required=True)
    p.set_defaults(func=cmd_seams)

    p = sub.add_parser('rings')
    p.add_argument('--dir', required=True)
    p.add_argument('--world', default='world')
    p.add_argument('--label', default='world')
    p.add_argument('--chunks', nargs='+', type=int, required=True)
    p.add_argument('--center-chunk', nargs=2, type=int, required=True,
                   help='出生区块坐标（环带中心）')
    p.add_argument('--step', type=int, default=16)
    p.set_defaults(func=cmd_rings)

    p = sub.add_parser('terrain')
    p.add_argument('--a', required=True)
    p.add_argument('--b', required=True)
    p.add_argument('--world-a', default='world')
    p.add_argument('--world-b', default=None)
    p.add_argument('--chunks', nargs='+', type=int, required=True)
    p.set_defaults(func=cmd_terrain)

    p = sub.add_parser('render')
    p.add_argument('--dir', required=True)
    p.add_argument('--world', default='world')
    p.add_argument('--label', default='world')
    p.add_argument('--chunks', nargs='+', type=int, required=True)
    p.add_argument('--out', required=True)
    p.add_argument('--scale', type=int, default=1)
    p.add_argument('--center-chunk', nargs=2, type=int, default=None)
    p.add_argument('--normal-radius', type=int, default=80)
    p.add_argument('--outer-radius', type=int, default=112)
    p.set_defaults(func=cmd_render)

    args = parser.parse_args()
    if getattr(args, 'world_b', None) is None:
        args.world_b = getattr(args, 'world_a', getattr(args, 'world', 'world'))
    args.func(args)


if __name__ == '__main__':
    main()
