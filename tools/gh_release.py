# -*- coding: utf-8 -*-
"""在当前版本上建 GitHub Release 并上传 jar。

令牌从 git 凭据管理器取（`git credential fill`），**不落盘、不打印**。
用法：python tools/seal_tmp/gh_release.py <tag> <jar> <title> <bodyfile>
"""
import json
import os
import subprocess
import sys
import urllib.request

OWNER, REPO = "jiezhiyisichen", "DFC-Map-Creation-Tool"
API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"


def token():
    p = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        capture_output=True, text=True, env=dict(os.environ, GIT_TERMINAL_PROMPT="0"))
    for line in p.stdout.splitlines():
        if line.startswith("password="):
            return line[len("password="):].strip()
    raise SystemExit("no cached credential for github.com")


def call(url, tok, data=None, method=None, raw=None, ctype="application/json"):
    body = raw if raw is not None else (json.dumps(data).encode("utf-8") if data is not None else None)
    req = urllib.request.Request(url, data=body, method=method or ("POST" if body else "GET"))
    req.add_header("Authorization", "token " + tok)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "jiezhi-release")
    if body is not None:
        req.add_header("Content-Type", ctype)
    with urllib.request.urlopen(req, timeout=120) as r:
        txt = r.read().decode("utf-8")
        return r.status, (json.loads(txt) if txt.strip().startswith(("{", "[")) else txt)


def main():
    tag, jar, title, bodyfile = sys.argv[1:5]
    tok = token()

    # 令牌是否有效（顺带确认 repo 权限）
    st, me = call(API + "/user", tok)
    print("token ok:", st, me.get("login"))

    body = open(bodyfile, encoding="utf-8").read()
    st, rel = call(API + "/repos/%s/%s/releases" % (OWNER, REPO), tok, data={
        "tag_name": tag, "name": title, "body": body,
        "draft": False, "prerelease": "beta" in tag,
    })
    print("release:", st, rel.get("html_url"))

    name = os.path.basename(jar)
    with open(jar, "rb") as fh:
        blob = fh.read()
    st, asset = call(rel["upload_url"].split("{")[0] + "?name=" + name, tok,
                     raw=blob, method="POST", ctype="application/java-archive")
    print("asset:", st, asset.get("browser_download_url"), asset.get("size"))


if __name__ == "__main__":
    main()
