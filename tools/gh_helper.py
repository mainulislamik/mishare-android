import urllib.request
import urllib.error
import json
import os
import sys

def get_token():
    cred_path = os.path.expanduser("~/.git-credentials")
    if os.path.exists(cred_path):
        with open(cred_path) as f:
            for line in f:
                if "github.com" in line:
                    # https://user:token@github.com
                    parts = line.strip().split("@github.com")[0].split("://")[-1]
                    if ":" in parts:
                        return parts.split(":", 1)[1]
    return ""

def get_job_log(job_id):
    token = get_token()
    url = f"https://api.github.com/repos/mainulislamik/mishare-android/actions/jobs/{job_id}/logs"
    
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    opener = urllib.request.build_opener(NoRedirect)
    req = urllib.request.Request(url)
    req.add_header("Authorization", f"token {token}")
    try:
        opener.open(req)
    except urllib.error.HTTPError as e:
        if e.code in (301, 302):
            loc = e.headers["Location"]
            with urllib.request.urlopen(loc) as resp:
                text = resp.read().decode("utf-8", errors="ignore")
                for line in text.splitlines():
                    if "e: " in line or "error" in line.lower() or "what went wrong" in line.lower():
                        print(line)

def get_latest_run():
    token = get_token()
    url = "https://api.github.com/repos/mainulislamik/mishare-android/actions/runs"
    req = urllib.request.Request(url)
    req.add_header("Authorization", f"token {token}")
    req.add_header("Accept", "application/vnd.github+json")
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode())
            runs = data.get("workflow_runs", [])
            if runs:
                r = runs[0]
                print(f"Run #{r['run_number']}: id={r['id']} - status={r['status']} - conclusion={r['conclusion']}")
                return r
    except Exception as e:
        print("Error fetching runs:", e)
    return None

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "latest":
        get_latest_run()
    elif len(sys.argv) > 1:
        get_job_log(sys.argv[1])
    else:
        print("Usage: python3 gh_helper.py [latest | <job_id>]")
