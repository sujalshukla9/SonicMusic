"""Validate SonicMusic's release APK and produce the updater's integrity manifest."""

import argparse
import hashlib
import json
import re
import struct
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile

APP_ID = "com.sonicmusic.app"
MIN_SDK = 26
REQUIRED_ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
MAX_VERSION_CODE = 2_100_000_000


def command(*args):
    result = subprocess.run([str(arg) for arg in args], capture_output=True, text=True)
    if result.returncode:
        raise ValueError(f"{Path(str(args[0])).name} failed: {result.stderr.strip() or result.stdout.strip()}")
    return result.stdout


def version(value):
    if not re.fullmatch(r"[vV]?\.?\d+\.\d+\.\d+", value):
        raise ValueError(f"Invalid version name {value!r}. Use a numeric version such as 2.0.1.")
    return tuple(int(part) for part in re.findall(r"\d+", value))


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_16k_native_libraries(path):
    page_size = 16 * 1024
    with ZipFile(path) as archive, path.open("rb") as container:
        for entry in archive.infolist():
            if not entry.filename.startswith(("lib/arm64-v8a/", "lib/x86_64/")) or not entry.filename.endswith(".so"):
                continue
            if entry.compress_type == ZIP_STORED:
                container.seek(entry.header_offset + 26)
                name_size, extra_size = struct.unpack("<HH", container.read(4))
                payload_offset = entry.header_offset + 30 + name_size + extra_size
                if payload_offset % page_size:
                    raise ValueError(f"{entry.filename} is uncompressed and lacks 16 KB ZIP alignment.")

            library = archive.read(entry)
            if library[:6] != b"\x7fELF\x02\x01":
                raise ValueError(f"{entry.filename} is not a little-endian 64-bit ELF library.")
            headers_offset, = struct.unpack_from("<Q", library, 32)
            header_size, header_count = struct.unpack_from("<HH", library, 54)
            alignments = []
            for index in range(header_count):
                offset = headers_offset + index * header_size
                kind, = struct.unpack_from("<I", library, offset)
                if kind == 1:  # PT_LOAD
                    alignments.append(struct.unpack_from("<Q", library, offset + 48)[0])
            if not alignments or any(alignment < page_size for alignment in alignments):
                raise ValueError(f"{entry.filename} has ELF segments incompatible with 16 KB devices.")


@dataclass(frozen=True)
class Apk:
    code: int
    version: str
    min_sdk: int
    target_sdk: int
    abis: frozenset


def inspect(args, path):
    signer = Path(args.apksigner)
    signing_tool = ["java", "-jar", signer.parent / "lib" / "apksigner.jar"] if signer.suffix == ".bat" else [signer]
    certificates = command(*signing_tool, "verify", "--print-certs", path)
    digests = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]+)", certificates)
    if [value.lower() for value in digests] != [args.cert]:
        raise ValueError(f"{path.name} does not match the pinned original release certificate.")

    badging = command(args.aapt, "dump", "badging", path)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'([^\n]*)", badging, re.M)
    minimum = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    target = re.search(r"^targetSdkVersion:'(\d+)'", badging, re.M)
    if not all((package, minimum, target)):
        raise ValueError(f"{path.name} is missing required APK identity or SDK metadata.")
    if package[1] != APP_ID or re.search(r"\bsplit=", package[4]):
        raise ValueError(f"{path.name} must be a standalone APK for {APP_ID}.")

    with ZipFile(path) as archive:
        abis = frozenset(
            name.split("/")[1]
            for name in archive.namelist()
            if name.startswith("lib/") and name.count("/") == 2 and name.endswith(".so")
        )
    return Apk(int(package[2]), package[3], int(minimum[1]), int(target[1]), abis)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("baseline", "candidate"))
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--previous", required=True, type=Path)
    parser.add_argument("--cert", required=True, help="SHA-256 fingerprint from the original signing certificate")
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--metadata", type=Path)
    args = parser.parse_args()
    args.cert = args.cert.replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", args.cert):
        raise ValueError("RELEASE_CERT_SHA256 must be the 64-digit SHA-256 of the original release certificate.")
    if not re.fullmatch(r"\d+\.\d+\.\d+", args.version_name):
        raise ValueError("RELEASE_VERSION_NAME must use MAJOR.MINOR.PATCH.")

    previous = inspect(args, args.previous)
    if version(args.version_name) <= version(previous.version):
        raise ValueError(f"The release version name must be higher than {previous.version} for older updaters.")
    if not 0 < previous.code < MAX_VERSION_CODE:
        raise ValueError("The previous APK version code cannot be incremented.")
    if args.mode == "baseline":
        print(f"Verified previous release {previous.version} ({previous.code})")
        return

    if not args.apk or not args.metadata:
        parser.error("candidate requires --apk and --metadata")
    candidate = inspect(args, args.apk)
    if candidate.code != previous.code + 1 or candidate.version != args.version_name:
        raise ValueError("The APK version must match the selected version name and increment the previous version code by one.")
    if candidate.min_sdk != MIN_SDK or candidate.min_sdk > previous.min_sdk:
        raise ValueError(f"The release must retain minSdk {MIN_SDK} and may not raise the previous minimum.")
    if candidate.target_sdk < previous.target_sdk:
        raise ValueError("The release targetSdk cannot regress.")
    missing = (REQUIRED_ABIS | previous.abis) - candidate.abis
    if missing:
        raise ValueError(f"APK is missing native libraries for: {', '.join(sorted(missing))}.")
    require_16k_native_libraries(args.apk)
    if "application-debuggable" in command(args.aapt, "dump", "badging", args.apk):
        raise ValueError("Debuggable APKs cannot be published as releases.")

    manifest = {
        "packageName": APP_ID,
        "versionCode": candidate.code,
        "versionName": candidate.version,
        "minSdk": candidate.min_sdk,
        "signingCertificateSha256": args.cert,
        "abis": sorted(candidate.abis),
        "apk": args.apk.name,
        "sha256": sha256(args.apk),
    }
    args.metadata.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Verified candidate {candidate.version} ({candidate.code}) and wrote {args.metadata.name}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, struct.error) as error:
        sys.exit(str(error))
