# Jaravel 文档站

MkDocs Material。内容在 `docs/`,配置在 `mkdocs.yml`。

```bash
python -m venv .venv
# Windows: .venv\Scripts\activate
# macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt
mkdocs serve          # http://127.0.0.1:8000
mkdocs build --strict # 静态站点输出到 site/
```

> 模块级设计文档仍在仓库根目录的 [`docs/`](../docs/)。这个站点是面向
> **使用者**的入口:安装、快速开始、指南、迁移与 API 参考;
> 设计取舍和 ADR 链接到根目录文档。
