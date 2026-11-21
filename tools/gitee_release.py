# -*- coding: utf-8 -*-
"""在 Gitee 上建发行版并上传 jar（OAuth2 token，绝不落盘/打印）。

用法:
    python tools/gitee_release.py <tag> <jar> <title> <bodyfile>
    GITEE_TOKEN 环境变量优先，其次读 .workbuddy/memory/gitee_token.txt

★ Gitee API v5 的坑：GET /releases/tags/<tag> 在该 tag 没有发行版时也回 200，
  body 是字面量 null —— 判断"已存在"必须 isinstance(d, dict) and "id" in d。
"""
import json
import io
import os
import sys
import urllib.request
import urllib.error
import uuid

OWNER, REPO = "yi-sichen-jiezhi", "DFC-Map-Creation-Tool"
API = "https://gitee.com/api/v5/repos/%s/%s" % (OWNER, REPO)


def get_token():
    t = os.environ.get("GITEE_TOKEN", "").strip()
    if t:
        return t
    p = os.path.join(".workbuddy", "memory", "gitee_token.txt")
    if os.path.isfile(p):
        return io.open(p, encoding="utf-8").read().strip()
    raise SystemExit("no GITEE_TOKEN: set env or create .workbuddy/memory/gitee_token.txt")


def call(path, tok, data=None, raw=None, method=None, ctype=None):
    if raw is not None:
        body = raw
    elif data is not None:
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
    else:
        body = None
    url = API + path + ("&" if "?" in path else "?") + "access_token=" + tok
    req = urllib.request.Request(url, data=body, method=method or ("POST" if body else "GET"))
    req.add_header("User-Agent", "jiezhi-gitee-release")
    if ctype:
        req.add_header("Content-Type", ctype)
    elif data is not None:
        req.add_header("Content-Type", "application/json;charset=UTF-8")
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            txt = r.read().decode("utf-8", "replace")
            return r.status, (json.loads(txt) if txt.strip().startswith(("{", "[")) else txt)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def main():
    tag, jar, title, bodyfile = sys.argv[1:5]
    tok = get_token()
    body = io.open(bodyfile, encoding="utf-8").read()

    # 1) 查已发行版（★ GET 命中不存在时也回 200 + body=null）
    st, d = call("/releases/tags/" + tag, tok)
    if isinstance(d, dict) and "id" in d:
        print("release exists:", d["id"], d.get("html_url", ""))
        rel = d
    else:
        payload = {
            "tag_name": tag,
            "name": title,
            "body": body,
            "target_commitish": "main",   # Gitee v5 必填，缺了 400
            "prerelease": False,   # 默认正式版，测试版另设 GITEE_TAG_SUFFIX 后缀
        }
        st, rel = call("/releases", tok, data=payload)
        print("release:", st, rel.get("html_url", "") if isinstance(rel, dict) else rel)
        if not isinstance(rel, dict) or "id" not in rel:
            print("错误:", json.dumps(rel, ensure_ascii=False)[:600])
            raise SystemExit(1)

    # 2) 上传附件（multipart/form-data，字段名固定 "file"）
    name = os.path.basename(jar)
    assets = rel.get("assets", []) or []
    if any(isinstance(a, dict) and a.get("name") == name for a in assets):
        print("asset exists:", name)
        return
    boundary = "----jiezhi" + uuid.uuid4().hex
    blob = open(jar, "rb").read()
    part = (
        ("--" + boundary + "\r\n"
         + 'Content-Disposition: form-data; name="file"; filename="' + name + '"\r\n'
         + "Content-Type: application/java-archive\r\n\r\n").encode("utf-8")
        + blob + ("\r\n--" + boundary + "--\r\n").encode("utf-8"))
    st, a = call("/releases/%s/attach_files" % rel["id"], tok, raw=part,
                 ctype="multipart/form-data; boundary=" + boundary)
    print("asset:", st, a.get("browser_download_url", "") if isinstance(a, dict) else a)


if __name__ == "__main__":
    main()
