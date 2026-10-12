# CHANGELOG.md から指定バージョンの部分を取り出す
import re, sys
ver = sys.argv[1].lstrip("v")
text = open("CHANGELOG.md", encoding="utf-8").read()
m = re.search(r"^## v?" + re.escape(ver) + r"\b.*?$(.*?)(?=^## |\Z)", text, re.S | re.M)
print(m.group(1).strip() if m else "変更内容は CHANGELOG.md を見てください。")
