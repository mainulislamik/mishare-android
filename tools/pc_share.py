#!/usr/bin/env python3
"""
MiShare PC Companion Utility
Easily send and receive files from PC command line or web interface.
"""

import os
import sys
import argparse
import urllib.request
import urllib.parse
import json

def list_mobile_files(server_url):
    url = f"{server_url.rstrip('/')}/api/files"
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=5) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            print("\n=== Shared Files on Mobile ===")
            if not data:
                print("No files currently staged on mobile.")
                return
            for idx, item in enumerate(data, 1):
                size_mb = item['size'] / (1024 * 1024)
                print(f"[{idx}] {item['name']} ({size_mb:.2f} MB)")
            print("===============================\n")
    except Exception as e:
        print(f"Error connecting to mobile server: {e}")

def send_file_to_mobile(server_url, file_path):
    if not os.path.exists(file_path):
        print(f"File not found: {file_path}")
        return

    file_name = os.path.basename(file_path)
    url = f"{server_url.rstrip('/')}/api/upload"
    boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW"
    
    with open(file_path, "rb") as f:
        file_bytes = f.read()

    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{file_name}"\r\n'
        f"Content-Type: application/octet-stream\r\n\r\n"
    ).encode("utf-8") + file_bytes + f"\r\n--{boundary}--\r\n".encode("utf-8")

    req = urllib.request.Request(url, data=body)
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")

    try:
        print(f"Sending '{file_name}' to {server_url}...")
        with urllib.request.urlopen(req, timeout=60) as resp:
            print("Success! File uploaded to mobile phone.")
    except Exception as e:
        print(f"Upload failed: {e}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="MiShare PC Companion CLI")
    parser.add_argument("url", help="Mobile Server URL (e.g. http://192.168.1.5:8888)")
    parser.add_argument("--send", help="Path to file to send to mobile")
    parser.add_argument("--list", action="store_true", help="List staged files on mobile")

    args = parser.parse_args()

    if args.send:
        send_file_to_mobile(args.url, args.send)
    elif args.list:
        list_mobile_files(args.url)
    else:
        list_mobile_files(args.url)
