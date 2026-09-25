#!/usr/bin/env python3
"""
Far Lands 参数调参器（数值代理）。

原理：地形高度场在水平方向上的采样位置被坐标映射 F/G 改写，因此可以用
    h_warped(x, z) = h_vanilla(F(x), G(z))
近似评估不同参数带来的地形形态变化。比反复起服生成世界快几个数量级。

用法：
  python3 tools/tune_warp.py --dir run/vanilla --chunks -12 -12 24 24 --center-chunk 0 0
"""
import argparse
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from world_compare import surface_heights  # noqa: E402


def far_axis(u, strength, period, phase=0.0):
    if strength == 0.0 or period == 0.0:
        return u
    omega = 2.0 * math.pi / period
    return u + strength * math.sin(omega * u + phase) / omega


def warp_map(heights, center_x, center_z, normal_radius, transition, params):
    """参数: (primary_strength, primary_period, secondary_strength, secondary_period, phases)"""
    pa, pp, sa, sp, phx, phz = params
    out = {}
    for (x, z), h in heights.items():
        d = max(abs(x - center_x), abs(z - center_z))
        if d <= normal_radius:
            alpha = 0.0
        elif d >= normal_radius + transition:
            alpha = 1.0
        else:
            t = (d - normal_radius) / transition
            alpha = t * t * (3.0 - 2.0 * t)
        ux = x - center_x
        uz = z - center_z
        fx = far_axis(ux, pa, pp, phx)
        fz = far_axis(uz, pa, pp, phz)
        fx2 = far_axis(fx, sa, sp, 0.0)
        fz2 = far_axis(fz, sa, sp, 1.7)
        wx = x + alpha * (fx2 - ux)
        wz = z + alpha * (fz2 - uz)
        # 最近邻取样
        out[(x, z)] = heights.get((int(round(wx)), int(round(wz))))
    return out


def metrics(heights, normal_radius, center_x, center_z):
    """只统计远区（畸变完整区）内的相邻列差异。"""
    far = {k: v for k, v in heights.items() if v is not None
           and max(abs(k[0] - center_x), abs(k[1] - center_z)) > normal_radius + 32}
    jumps = []
    flat = 0
    pairs = 0
    for (x, z), h in far.items():
        for nb in ((x + 1, z), (x, z + 1)):
            if nb in far and far[nb] is not None:
                pairs += 1
                d = abs(far[nb] - h)
                jumps.append(d)
                if d == 0:
                    flat += 1
    if not jumps:
        return None
    jumps_sorted = sorted(jumps)
    n = len(jumps_sorted)
    vals = sorted(far.values())
    return {
        'columns': len(far),
        'mean_jump': sum(jumps) / n,
        'p90': jumps_sorted[int(n * 0.9)],
        'p99': jumps_sorted[min(n - 1, int(n * 0.99))],
        'max_jump': jumps_sorted[-1],
        'steep_ratio': sum(1 for v in jumps if v >= 8) / n,
        'flat_ratio': flat / pairs,
        'height_range': (vals[0], vals[-1]),
        'height_p05_p95': (vals[int(len(vals) * 0.05)], vals[int(len(vals) * 0.95)]),
    }


CANDIDATES = {
    'vanilla(无畸变)': None,
    '当前 1.05@96 + 0.35@37': (1.05, 96.0, 0.35, 37.0, 0.0, 0.0),
    'G 大位移 1.05@96 + 0.9@320': (1.05, 96.0, 0.9, 320.0, 0.0, 0.0),
    'H 大位移+折叠 1.2@72 + 1.0@256': (1.2, 72.0, 1.0, 256.0, 0.0, 0.0),
    'I 双大位移 1.0@128 + 1.0@384': (1.0, 128.0, 1.0, 384.0, 0.0, 0.0),
    'J 极强 1.3@64 + 1.2@200': (1.3, 64.0, 1.2, 200.0, 0.0, 0.0),
    'K 墙体优先 1.0@48 + 0.8@160': (1.0, 48.0, 0.8, 160.0, 0.0, 0.0),
    'L 均衡 1.1@80 + 0.9@240': (1.1, 80.0, 0.9, 240.0, 0.0, 0.0),
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--dir', default='run/vanilla')
    parser.add_argument('--world', default='world')
    parser.add_argument('--chunks', nargs=4, type=int, required=True)
    parser.add_argument('--center-chunk', nargs=2, type=int, default=[0, 0])
    parser.add_argument('--normal-radius', type=float, default=80.0)
    parser.add_argument('--transition', type=float, default=32.0)
    args = parser.parse_args()

    rect = tuple(args.chunks)
    heights = surface_heights(args.dir, args.world, [rect])
    center_x = args.center_chunk[0] * 16 + 8.0
    center_z = args.center_chunk[1] * 16 + 8.0
    print(f'原版高度场: {len(heights)} 列, 高度范围 {min(heights.values())}..{max(heights.values())}')
    print(f'中心=({center_x},{center_z}) 正常区={args.normal_radius} 过渡={args.transition}')
    print()
    header = f'{"方案":28s} {"列数":>6s} {"|Δh|均值":>8s} {"p90":>4s} {"p99":>4s} {"max":>4s} {"≥8格比例":>9s} {"完全平坦":>8s} {"高度范围":>10s}'
    print(header)
    print('-' * len(header))
    for name, params in CANDIDATES.items():
        if params is None:
            m = metrics(heights, args.normal_radius, center_x, center_z)
        else:
            warped = warp_map(heights, center_x, center_z, args.normal_radius, args.transition, params)
            m = metrics(warped, args.normal_radius, center_x, center_z)
        if m is None:
            print(f'{name:28s} 无数据')
            continue
        print(f'{name:28s} {m["columns"]:6d} {m["mean_jump"]:8.2f} {m["p90"]:4d} {m["p99"]:4d} {m["max_jump"]:4d} '
              f'{m["steep_ratio"]:9.4f} {m["flat_ratio"]:8.4f} '
              f'{str(m["height_range"][0]) + ".." + str(m["height_range"][1]):>10s}')


if __name__ == '__main__':
    main()
