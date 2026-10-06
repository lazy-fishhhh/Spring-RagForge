#!/usr/bin/env python3
"""扫本地目录，把 .adoc 批量传到 MinIO，只传变了的。

用途：本地改完语料后快速推上去，比 POST /files/upload 一个个传快得多。
传完记得打一次 POST /admin/reindex，向量库才会跟着更新。

依赖：pip install minio

对象 key 就是文件相对 <local-root> 的路径，正斜杠分隔，例如
  spring-boot/reference/pages/web/servlet.adoc
这和灌库扫描时的口径一致（MinioDocumentSource 拿对象 key 当路径算 chunk_id）。

跳过策略：先拉一次远端对象清单，本地文件的 md5 和远端 etag、大小都一致就跳过。
MinIO 单段上传的 etag 就是内容的 md5，所以这个比对是可靠的；
分片上传的 etag 形如 "abc-3"，没法直接比，这种一律重传。

用法：
  python scripts/upload_corpus.py D:/corpus
  python scripts/upload_corpus.py D:/corpus --dry-run
  python scripts/upload_corpus.py D:/corpus --workers 16 --force
"""

from __future__ import annotations

import argparse
import hashlib
import os
import sys
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

try:
    from minio import Minio
except ImportError:
    sys.exit("缺少 minio 库，先装：pip install minio")

# 默认值对着 application.yml 的 minio 段，可用参数或环境变量覆盖
DEFAULTS = {
    "endpoint": os.environ.get("MINIO_ENDPOINT", "192.168.16.128:9000"),
    "access_key": os.environ.get("MINIO_ACCESS_KEY", "admin"),
    "secret_key": os.environ.get("MINIO_SECRET_KEY", "12345678"),
    "bucket": os.environ.get("MINIO_BUCKET", "rag-docs"),
}

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="扫本地目录，把 .adoc 批量传到 MinIO（只传变了的）")
    parser.add_argument("local_root", type=Path,
                        help="本地语料根目录，里面的相对路径决定对象 key")
    parser.add_argument("--endpoint", default=DEFAULTS["endpoint"],
                        help=f"S3 API 端点，host:port（默认 {DEFAULTS['endpoint']}）")
    parser.add_argument("--access-key", default=DEFAULTS["access_key"])
    parser.add_argument("--secret-key", default=DEFAULTS["secret_key"])
    parser.add_argument("--bucket", default=DEFAULTS["bucket"])
    parser.add_argument("--ext", default=".adoc",
                        help="只传这个后缀的文件（默认 .adoc）")
    parser.add_argument("--workers", type=int, default=8,
                        help="并发上传线程数（默认 8）")
    parser.add_argument("--dry-run", action="store_true",
                        help="只打印要传哪些，不真传")
    parser.add_argument("--force", action="store_true",
                        help="不看远端 etag，全部重传")
    parser.add_argument("--secure", action="store_true",
                        help="用 HTTPS 连 endpoint")
    return parser.parse_args()


def md5_of(path: Path) -> str:
    digest = hashlib.md5()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def list_remote(client: Minio, bucket: str) -> dict[str, tuple[str, int]]:
    """远端对象清单：key -> (etag, size)。一次拉完，后面全部本地比对。"""
    remote: dict[str, tuple[str, int]] = {}
    for item in client.list_objects(bucket, recursive=True):
        if item.is_dir:
            continue
        etag = (item.etag or "").strip('"')
        remote[item.object_name] = (etag, item.size)
    return remote


def collect_local(root: Path, ext: str) -> list[tuple[str, Path, int]]:
    """本地待传文件：[(对象 key, 路径, 大小)]，按 key 排序方便对账。"""
    files: list[tuple[str, Path, int]] = []
    for path in root.rglob("*"):
        if not path.is_file() or path.suffix.lower() != ext:
            continue
        key = path.relative_to(root).as_posix()
        files.append((key, path, path.stat().st_size))
    files.sort(key=lambda entry: entry[0])
    return files


def main() -> int:
    args = parse_args()
    root: Path = args.local_root
    if not root.is_dir():
        return fail(f"本地目录不存在: {root}")

    client = Minio(
        args.endpoint,
        access_key=args.access_key,
        secret_key=args.secret_key,
        secure=args.secure,
    )

    if not client.bucket_exists(args.bucket):
        return fail(f"bucket 不存在: {args.bucket}（确认 endpoint 是 S3 API 端口，不是控制台端口）")

    local = collect_local(root, args.ext.lower())
    if not local:
        print(f"没有可传的文件（后缀 {args.ext}）：{root}")
        return 0

    remote = {} if args.force else list_remote(client, args.bucket)

    todo: list[tuple[str, Path, int]] = []
    skipped = 0
    for key, path, size in local:
        known = remote.get(key)
        if known is None:
            todo.append((key, path, size))
            continue
        etag, remote_size = known
        # 分片上传的 etag 带 "-N" 后缀，不是纯 md5，只能重传
        if "-" in etag or remote_size != size:
            todo.append((key, path, size))
            continue
        if md5_of(path) != etag:
            todo.append((key, path, size))
            continue
        skipped += 1

    print(f"bucket={args.bucket} 本地={len(local)} 待传={len(todo)} 跳过={skipped}")
    if not todo:
        print("远端已是最新，无需上传")
        return 0
    if args.dry_run:
        for key, _, size in todo:
            print(f"  待传 {key} ({size} bytes)")
        print("dry-run，未实际上传")
        return 0

    uploaded = 0
    failed: list[tuple[str, str]] = []
    lock = threading.Lock()

    def upload(key: str, path: Path) -> None:
        client.fput_object(args.bucket, key, str(path),
                           content_type="text/plain; charset=utf-8")

    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        futures = {pool.submit(upload, key, path): key for key, path, _ in todo}
        for future in as_completed(futures):
            key = futures[future]
            try:
                future.result()
                with lock:
                    uploaded += 1
                    print(f"  [{uploaded}/{len(todo)}] {key}")
            except Exception as exc:
                with lock:
                    failed.append((key, str(exc)))

    print(f"上传完成: 成功={uploaded} 跳过={skipped} 失败={len(failed)}")
    for key, reason in failed:
        print(f"  失败 {key}: {reason}", file=sys.stderr)
    if failed:
        return 1
    print("下一步：POST /admin/reindex 让向量库跟着更新")
    return 0


def fail(message: str) -> int:
    print(message, file=sys.stderr)
    return 1


if __name__ == "__main__":
    # Windows 控制台可能是 GBK，中文日志会炸，强制 UTF-8
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except AttributeError:
        pass
    raise SystemExit(main())
