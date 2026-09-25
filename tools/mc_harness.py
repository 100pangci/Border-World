#!/usr/bin/env python3
"""
世界生成测试驱动器（可复用）。

功能：
  1. 启动一个 Minecraft 服务端（modded dev server 或 vanilla server）；
  2. 等待 "Done"；
  3. 通过 RCON forceload 指定区块范围（或自动围绕出生区块），等待全部生成/落盘；
  4. save-all flush 后 stop；退出时确保进程组被清理。

用法示例：
  # 围绕出生区块生成 ±10 区块，再在最远 600 格处生成 8x8 区块
  python3 tools/mc_harness.py \
      --start "./gradlew runServer --console=plain" \
      --server-dir run/server --log /tmp/opencode/bw-modded.log \
      --forceload-around-spawn 10 10 \
      --forceload 20 20 27 27

  # vanilla 基线（同样的种子与区块范围）
  python3 tools/mc_harness.py \
      --start "java -Xmx2G -jar ~/.gradle/caches/fabric-loom/1.21.1/minecraft-server.jar --nogui" \
      --server-dir run/vanilla --log /tmp/opencode/bw-vanilla.log \
      --forceload 20 20 27 27
"""
import argparse
import os
import re
import signal
import struct
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rcon import run as rcon_run  # noqa: E402

RCON_HOST = '127.0.0.1'
RCON_PORT = 25575
RCON_PASSWORD = 'bwtest'


def log(msg):
    print(f'[harness {time.strftime("%H:%M:%S")}] {msg}', flush=True)


def port_free(port):
    import socket
    with socket.socket() as sock:
        try:
            sock.bind(('127.0.0.1', port))
            return True
        except OSError:
            return False


def _ancestor_pids():
    pids = set()
    pid = os.getpid()
    while pid and pid not in pids:
        pids.add(pid)
        try:
            with open(f'/proc/{pid}/stat') as fh:
                pid = int(fh.read().rsplit(')', 1)[1].split()[1])
        except Exception:
            break
    return pids


def kill_leftover_servers():
    """清理上一次中断留下的服务端进程（否则会占端口/世界会话锁）。

    注意：不能直接 `pkill -f`，因为本 harness 的启动命令里也可能包含同样的模式
    （例如 vanilla 启动命令里的 minecraft-server.jar），会把自己杀掉。
    """
    protected = _ancestor_pids()
    killed = False
    for pattern in ('devlaunchinjector', 'minecraft-server.jar'):
        result = subprocess.run(['pgrep', '-f', pattern], capture_output=True, text=True)
        for line in result.stdout.split():
            try:
                pid = int(line)
            except ValueError:
                continue
            if pid in protected:
                continue
            subprocess.run(['kill', '-9', str(pid)], capture_output=True)
            killed = True
    if killed:
        log('检测到残留服务端进程，已强制清理')
    for _ in range(30):
        if port_free(25565) and port_free(25575):
            return
        time.sleep(1)
    log('警告：25565/25575 仍被占用，启动可能失败')


def read_log(path):
    if not os.path.exists(path):
        return ''
    with open(path, 'r', errors='replace') as fh:
        return fh.read()


def wait_for(log_path, needle, timeout, proc):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if needle in read_log(log_path):
            return True
        if proc.poll() is not None:
            return False
        time.sleep(1)
    return False


def parse_spawn_chunk(log_path):
    text = read_log(log_path)
    matches = re.findall(r'spawnChunk=\((-?\d+), (-?\d+)\)', text)
    if not matches:
        return None
    return int(matches[-1][0]), int(matches[-1][1])


def region_chunk_present(server_dir, world, cx, cz):
    rx, rz = cx >> 5, cz >> 5
    path = os.path.join(server_dir, world, 'region', f'r.{rx}.{rz}.mca')
    if not os.path.exists(path):
        return False
    index = (cx & 31) + (cz & 31) * 32
    with open(path, 'rb') as fh:
        fh.seek(index * 4)
        entry = fh.read(4)
    if len(entry) < 4:
        return False
    return struct.unpack('>I', b'\x00' + entry[:3])[0] != 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--start', required=True, help='启动命令（shell 字符串）')
    parser.add_argument('--server-dir', required=True)
    parser.add_argument('--cwd', default=None, help='启动命令的工作目录（默认当前目录）')
    parser.add_argument('--world', default='world')
    parser.add_argument('--log', required=True)
    parser.add_argument('--forceload', nargs='+', type=int, action='append', default=None,
                        help='chunk 坐标组 x1 z1 x2 z2（可多次出现；实测 /forceload 上限 256 区块/维度，脚本内自动分批）')
    parser.add_argument('--forceload-around-spawn', nargs=2, type=int, default=None,
                        metavar=('RX', 'RZ'), help='围绕出生区块生成 ±RX/±RZ 区块')
    parser.add_argument('--startup-timeout', type=int, default=600)
    parser.add_argument('--gen-timeout', type=int, default=1800)
    parser.add_argument('--gen-grace', type=int, default=20,
                        help='forceload 后等待生成的秒数（避免把"空壳区块"当成已生成）')
    parser.add_argument('--keep-running', action='store_true')
    args = parser.parse_args()

    log_path = args.log
    os.makedirs(os.path.dirname(log_path), exist_ok=True)

    kill_leftover_servers()

    log_file = open(log_path, 'w')
    proc = subprocess.Popen(args.start, shell=True, cwd=args.cwd or os.getcwd(), stdout=log_file,
                            stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                            start_new_session=True)
    log(f'server pid={proc.pid}, log={log_path}')

    def cleanup(signum=signal.SIGTERM):
        if proc.poll() is None and not args.keep_running:
            try:
                os.killpg(os.getpgid(proc.pid), signum)
            except ProcessLookupError:
                pass
            try:
                proc.wait(timeout=90)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
                except ProcessLookupError:
                    pass
                proc.wait(timeout=30)

    try:
        if not wait_for(log_path, 'Done (', args.startup_timeout, proc):
            log('服务端启动失败或超时，日志尾部：')
            print(read_log(log_path)[-4000:], flush=True)
            cleanup()
            raise SystemExit(1)
        log('服务端已就绪')

        rects = []
        if args.forceload_around_spawn:
            spawn = parse_spawn_chunk(log_path)
            if spawn is None:
                log('日志里没有 spawnChunk，无法围绕出生点生成')
                cleanup()
                raise SystemExit(1)
            rx, rz = args.forceload_around_spawn
            log(f'出生区块 = {spawn}')
            rects.append((spawn[0] - rx, spawn[1] - rz, spawn[0] + rx, spawn[1] + rz))
        if args.forceload:
            flat = [value for group in args.forceload for value in group]
            if len(flat) % 4 != 0:
                raise SystemExit('--forceload 需要 4 的倍数个整数')
            rects += [tuple(flat[i:i + 4]) for i in range(0, len(flat), 4)]

        expected_all = []
        for x1, z1, x2, z2 in rects:
            # forceload 每个维度上限 256 区块，分批处理
            try:
                rcon_run(RCON_HOST, RCON_PORT, RCON_PASSWORD, 'forceload remove all')
            except Exception:
                pass
            # 注意：/forceload 的参数是"方块坐标"，需要把区块范围换算成方块范围
            bx1, bz1 = x1 * 16, z1 * 16
            bx2, bz2 = x2 * 16 + 15, z2 * 16 + 15
            response = rcon_run(RCON_HOST, RCON_PORT, RCON_PASSWORD,
                                f'forceload add {bx1} {bz1} {bx2} {bz2}')
            log(f'forceload ({x1},{z1}) .. ({x2},{z2}) -> {response.strip()[:160]}')
            expected = []
            for cx in range(min(x1, x2), max(x1, x2) + 1):
                for cz in range(min(z1, z2), max(z1, z2) + 1):
                    expected.append((cx, cz))
            expected_all.extend(expected)

            log(f'等待 {len(expected)} 个区块生成（宽限 {args.gen_grace}s）...')
            time.sleep(args.gen_grace)
            deadline = time.time() + args.gen_timeout
            last_report = 0.0
            while time.time() < deadline:
                try:
                    rcon_run(RCON_HOST, RCON_PORT, RCON_PASSWORD, 'save-all flush')
                except Exception as exc:
                    log(f'save-all 失败（忽略）: {exc}')
                missing = [c for c in expected
                           if not region_chunk_present(args.server_dir, args.world, *c)]
                if not missing:
                    log('本批区块已全部落盘')
                    break
                if time.time() - last_report > 30:
                    log(f'仍缺 {len(missing)} 个区块，例如 {missing[:5]}')
                    last_report = time.time()
                time.sleep(3)
            else:
                missing = [c for c in expected
                           if not region_chunk_present(args.server_dir, args.world, *c)]
                log(f'超时：本批仍缺 {len(missing)} 个区块: {missing[:20]}')

        try:
            rcon_run(RCON_HOST, RCON_PORT, RCON_PASSWORD, 'save-all flush')
        except Exception:
            pass

        if not args.keep_running:
            log('停止服务端')
            try:
                rcon_run(RCON_HOST, RCON_PORT, RCON_PASSWORD, 'stop')
            except Exception:
                pass
            try:
                proc.wait(timeout=180)
            except subprocess.TimeoutExpired:
                log('stop 超时，强制结束')
        log('完成')
    finally:
        cleanup()
        log_file.close()


if __name__ == '__main__':
    main()
